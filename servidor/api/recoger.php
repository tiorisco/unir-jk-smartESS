<?php
declare(strict_types=1);

// Lo llama cron cada 5 minutos con la cabecera X-Token (SOLAR_CRON_TOKEN).
// Lee SmartESS (serie del dia) y el BMS (MQTT), lo guarda y recalcula el resumen de hoy.
// Cada fuente es independiente: si una falla, la otra se guarda igualmente.

require_once __DIR__ . '/../core/db.php';
require_once __DIR__ . '/../core/smartess.php';
require_once __DIR__ . '/../core/mqtt.php';
require_once __DIR__ . '/../core/resumen.php';
require_once __DIR__ . '/../core/alertas.php';

exigir_token('SOLAR_CRON_TOKEN');

$db = db();

// Una sola recogida a la vez
$bloqueo = $db->query("SELECT GET_LOCK('solar_recoger', 0) AS l")->fetch();
if ((int) $bloqueo['l'] !== 1) {
    responder_json(['ok' => false, 'error' => 'otra recogida en curso'], 409);
}

$hoy = date('Y-m-d');
$informe = ['fecha' => $hoy, 'inversor' => null, 'bms' => null];

function marcar(PDO $db, string $fuente, ?string $error): void
{
    $db->prepare(
        'INSERT INTO estado_recogida (fuente, ultima_ok, ultimo_error) VALUES (?, ?, ?)
         ON DUPLICATE KEY UPDATE ultima_ok = IF(? IS NULL, VALUES(ultima_ok), ultima_ok), ultimo_error = VALUES(ultimo_error)'
    )->execute([$fuente, $error === null ? date('Y-m-d H:i:s') : null, $error, $error]);
}

// ---- Inversor (SmartESS) ----
try {
    $cliente = new Smartess(env('SOLAR_SMARTESS_USER'), env('SOLAR_SMARTESS_PASS'));
    $puntos = $cliente->serieDelDia($hoy);

    // La serie ya no trae los contadores de produccion: se leen aparte y se anotan en el
    // ultimo punto. Si esta consulta falla, la recogida sigue (la produccion se integra).
    try {
        $c = $cliente->contadores();
        $n = count($puntos);
        if ($n > 0) {
            if ($puntos[$n - 1]['energia_pv_dia'] === null) {
                $puntos[$n - 1]['energia_pv_dia'] = $c['dia'];
            }
            if ($puntos[$n - 1]['energia_pv_total'] === null) {
                $puntos[$n - 1]['energia_pv_total'] = $c['total'];
            }
        }
    } catch (Throwable $e) {
        // sin contadores
    }

    // COALESCE: un dato nuevo sin contador no borra uno ya guardado
    $st = $db->prepare(
        'INSERT INTO inversor_puntos (hora, pv_w, casa_w, bateria_w, red_w, energia_pv_dia, energia_pv_total)
         VALUES (:hora, :pv_w, :casa_w, :bateria_w, :red_w, :energia_pv_dia, :energia_pv_total)
         ON DUPLICATE KEY UPDATE pv_w = VALUES(pv_w), casa_w = VALUES(casa_w), bateria_w = VALUES(bateria_w),
             red_w = VALUES(red_w),
             energia_pv_dia = COALESCE(VALUES(energia_pv_dia), energia_pv_dia),
             energia_pv_total = COALESCE(VALUES(energia_pv_total), energia_pv_total)'
    );
    $db->beginTransaction();
    foreach ($puntos as $p) {
        $st->execute($p);
    }
    $db->commit();
    marcar($db, 'inversor', null);
    $informe['inversor'] = ['puntos' => count($puntos)];
} catch (Throwable $e) {
    if ($db->inTransaction()) {
        $db->rollBack();
    }
    $msg = substr($e->getMessage(), 0, 250);
    marcar($db, 'inversor', $msg);
    $informe['inversor'] = ['error' => $msg];
}

// ---- Bateria (BMS por MQTT) ----
try {
    $b = leer_bms();
    if ($b === null) {
        $informe['bms'] = ['omitido' => 'ESP32/BMS desconectados o sin datos'];
        marcar($db, 'bms', 'ESP32/BMS desconectados o sin datos');
    } else {
        $db->prepare(
            'INSERT INTO bms_muestras (hora, soc, voltaje, corriente, potencia, celda_min, celda_max, temp_mosfet)
             VALUES (:hora, :soc, :voltaje, :corriente, :potencia, :celda_min, :celda_max, :temp_mosfet)
             ON DUPLICATE KEY UPDATE soc = VALUES(soc), voltaje = VALUES(voltaje), corriente = VALUES(corriente),
                 potencia = VALUES(potencia), celda_min = VALUES(celda_min), celda_max = VALUES(celda_max),
                 temp_mosfet = VALUES(temp_mosfet)'
        )->execute([
            'hora' => date('Y-m-d H:i:00'),
            'soc' => $b['soc'], 'voltaje' => $b['voltaje'], 'corriente' => $b['corriente'],
            'potencia' => $b['potencia'], 'celda_min' => $b['celda_min'], 'celda_max' => $b['celda_max'],
            'temp_mosfet' => $b['temp_mosfet'],
        ]);
        marcar($db, 'bms', null);
        $informe['bms'] = ['soc' => $b['soc']];
    }
} catch (Throwable $e) {
    $msg = substr($e->getMessage(), 0, 250);
    marcar($db, 'bms', $msg);
    $informe['bms'] = ['error' => $msg];
    $b = null;
}

// ---- Avisos al movil (Telegram). Un fallo aqui no afecta a la recogida ----
try {
    $avisos = [];
    if (!empty($b)) {
        $avisos = array_merge($avisos, evaluar_alertas($db, $b));
    }
    $avisos = array_merge($avisos, vigilar_bms($db));
    $informe['avisos'] = $avisos;
} catch (Throwable $e) {
    $informe['avisos'] = ['error' => substr($e->getMessage(), 0, 250)];
}

// ---- Resumen de hoy ----
try {
    $informe['resumen'] = recalcular_resumen($db, $hoy);
} catch (Throwable $e) {
    $informe['resumen'] = ['error' => substr($e->getMessage(), 0, 250)];
}

$db->query("SELECT RELEASE_LOCK('solar_recoger')");
$informe['ok'] = !isset($informe['inversor']['error']) && !isset($informe['bms']['error']);
responder_json($informe);
