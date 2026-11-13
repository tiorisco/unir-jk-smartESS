<?php
declare(strict_types=1);

/**
 * Lector MQTT 3.1.1 minimo (solo lectura) sobre TLS.
 * Se conecta, se suscribe a un filtro, recoge los ultimos mensajes retenidos que publica
 * el broker (ESPHome publica con retencion) y se desconecta. No publica nada.
 */

function mqtt_cadena(string $s): string
{
    return pack('n', strlen($s)) . $s;
}

function mqtt_longitud(int $n): string
{
    $out = '';
    do {
        $byte = $n % 128;
        $n = intdiv($n, 128);
        if ($n > 0) {
            $byte |= 128;
        }
        $out .= chr($byte);
    } while ($n > 0);
    return $out;
}

/** Lee exactamente $n bytes (o devuelve null si se agota el tiempo). */
function mqtt_leer_bytes($s, int $n, float $limite): ?string
{
    $buf = '';
    while (strlen($buf) < $n) {
        if (microtime(true) > $limite) {
            return null;
        }
        $trozo = fread($s, $n - strlen($buf));
        if ($trozo === false || $trozo === '') {
            if (feof($s)) {
                return null;
            }
            usleep(20000);
            continue;
        }
        $buf .= $trozo;
    }
    return $buf;
}

/** Lee un paquete MQTT. Devuelve ['tipo','flags','cuerpo'] o null si no llega nada a tiempo. */
function mqtt_leer_paquete($s, float $limite): ?array
{
    $b = mqtt_leer_bytes($s, 1, $limite);
    if ($b === null) {
        return null;
    }
    $cab = ord($b);
    $mult = 1;
    $len = 0;
    do {
        $c = mqtt_leer_bytes($s, 1, $limite + 2);
        if ($c === null) {
            return null;
        }
        $x = ord($c);
        $len += ($x & 127) * $mult;
        $mult *= 128;
    } while (($x & 128) !== 0 && $mult <= 128 * 128 * 128 * 128);
    $cuerpo = $len > 0 ? mqtt_leer_bytes($s, $len, $limite + 5) : '';
    if ($cuerpo === null) {
        return null;
    }
    return ['tipo' => $cab >> 4, 'flags' => $cab & 15, 'cuerpo' => $cuerpo];
}

/**
 * @param string|string[] $filtro un filtro o una lista de filtros a los que suscribirse
 * @param string[]|null $esperados si se indica, se corta en cuanto han llegado todos estos topics
 *                      (sin esperar el silencio de 1,5 s): mucho mas rapido
 * @return array<string,string> topic => payload (ultimo valor de cada topic)
 */
function mqtt_leer_ultimos(string $host, string $usuario, string $clave, $filtro, int $puerto = 8883, float $maxSegundos = 10.0, ?array $esperados = null): array
{
    $ctx = stream_context_create(['ssl' => [
        'verify_peer' => true,
        'verify_peer_name' => true,
        'SNI_enabled' => true,
        'peer_name' => $host,
    ]]);
    $s = @stream_socket_client("ssl://$host:$puerto", $errno, $errstr, 10, STREAM_CLIENT_CONNECT, $ctx);
    if (!$s) {
        throw new RuntimeException("MQTT: no se pudo conectar ($errstr)");
    }
    stream_set_blocking($s, false);

    try {
        $clientId = 'colector-' . bin2hex(random_bytes(4));
        $cabVar = mqtt_cadena('MQTT') . chr(4) . chr(0xC2) . pack('n', 30); // v3.1.1, usuario+clave, sesion limpia
        $carga = mqtt_cadena($clientId) . mqtt_cadena($usuario) . mqtt_cadena($clave);
        fwrite($s, chr(0x10) . mqtt_longitud(strlen($cabVar . $carga)) . $cabVar . $carga);

        $limite = microtime(true) + $maxSegundos;
        $p = mqtt_leer_paquete($s, $limite);
        if ($p === null || $p['tipo'] !== 2) {
            throw new RuntimeException('MQTT: el broker no respondio al CONNECT');
        }
        $codigo = ord($p['cuerpo'][1] ?? "\xff");
        if ($codigo !== 0) {
            throw new RuntimeException("MQTT: conexion rechazada (codigo $codigo)");
        }

        $cuerpo = pack('n', 1); // identificador de paquete
        foreach ((array) $filtro as $f) {
            $cuerpo .= mqtt_cadena($f) . chr(0); // QoS 0
        }
        fwrite($s, chr(0x82) . mqtt_longitud(strlen($cuerpo)) . $cuerpo);

        $res = [];
        $ultimo = microtime(true);
        while (microtime(true) < $limite) {
            // Cuando ya hay mensajes y pasa 1,5 s en silencio, se da por recibido todo lo retenido
            $espera = $res ? min($limite, $ultimo + 1.5) : $limite;
            $p = mqtt_leer_paquete($s, $espera);
            if ($p === null) {
                if ($res || feof($s)) {
                    break;
                }
                continue;
            }
            if ($p['tipo'] === 3) { // PUBLISH
                $c = $p['cuerpo'];
                $lt = unpack('n', substr($c, 0, 2))[1];
                $topic = substr($c, 2, $lt);
                $pos = 2 + $lt;
                if ((($p['flags'] >> 1) & 3) > 0) {
                    $pos += 2; // identificador de paquete (QoS > 0)
                }
                $res[$topic] = substr($c, $pos);
                $ultimo = microtime(true);
                if ($esperados !== null && !array_diff($esperados, array_keys($res))) {
                    break; // ya estan todos los que hacian falta
                }
            }
        }
        @fwrite($s, chr(0xE0) . chr(0)); // DISCONNECT
        return $res;
    } finally {
        @fclose($s);
    }
}

/**
 * Ultimo valor de cada sensor del BMS, o null si no llego nada.
 * $rapido = true: solo los 7 temas que necesita Alexa (SOC, V, A, W, capacidad y estado) y se
 * corta en cuanto llegan; no trae celdas ni temperatura del MOSFET.
 */
function leer_bms(bool $rapido = false): ?array
{
    $host = env('SOLAR_MQTT_HOST');
    $usuario = env('SOLAR_MQTT_USER');
    $clave = env('SOLAR_MQTT_PASS');
    if ($rapido) {
        $temas = ['solar/bateria/status', 'solar/bateria/binary_sensor/online/state'];
        foreach (['soc', 'voltaje', 'corriente', 'potencia', 'capacidad_restante'] as $s) {
            $temas[] = "solar/bateria/sensor/$s/state";
        }
        $m = mqtt_leer_ultimos($host, $usuario, $clave, $temas, 8883, 6.0, $temas);
    } else {
        $m = mqtt_leer_ultimos($host, $usuario, $clave, 'solar/bateria/#');
    }
    $n = static function (string $sensor) use ($m): ?float {
        $v = $m["solar/bateria/sensor/$sensor/state"] ?? null;
        return ($v !== null && is_numeric($v)) ? (float) $v : null;
    };
    if (!isset($m['solar/bateria/sensor/soc/state'])) {
        return null;
    }
    // Los valores estan retenidos en el broker: si el ESP32 (status) o el BMS (online)
    // no estan conectados ahora, son valores viejos y NO se deben guardar como nuevos.
    if (($m['solar/bateria/status'] ?? '') !== 'online'
        || ($m['solar/bateria/binary_sensor/online/state'] ?? '') !== 'ON') {
        return null;
    }
    return [
        'soc' => $n('soc'),
        'voltaje' => $n('voltaje'),
        'corriente' => $n('corriente'),
        'potencia' => $n('potencia'),
        'celda_min' => $n('celda_min'),
        'celda_max' => $n('celda_max'),
        'temp_mosfet' => $n('temp_mosfet'),
        'capacidad' => $n('capacidad_restante'), // Ah restantes (para estimar cuanto durara)
        'puente_online' => ($m['solar/bateria/status'] ?? '') === 'online',
    ];
}
