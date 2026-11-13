<?php
declare(strict_types=1);

// GET /api/ahora.php   (cabecera X-Token = SOLAR_APP_TOKEN, solo lectura)
// Estado actual para la skill de Alexa: SOC del BMS en directo (lee el broker MQTT, ~2 s)
// y, si no se puede, la ultima muestra guardada. Mas la ultima lectura del inversor.

require_once __DIR__ . '/../core/db.php';
require_once __DIR__ . '/../core/mqtt.php';

exigir_token('SOLAR_APP_TOKEN');

$db = db();
$ahora = time();

/** Estima cuanto tarda en llenarse o vaciarse. Devuelve ['tipo' => 'llena'|'vacia', 'horas' => float] o null. */
function estimar(?float $soc, ?float $capacidad, ?float $corriente): ?array
{
    if ($soc === null || $capacidad === null || $corriente === null || $soc < 3.0 || $capacidad <= 0) {
        return null;
    }
    $total = $capacidad / ($soc / 100.0);
    if ($corriente > 0.5) {
        return ['tipo' => 'llena', 'horas' => round(($total - $capacidad) / $corriente, 1)];
    }
    if ($corriente < -0.5) {
        return ['tipo' => 'vacia', 'horas' => round($capacidad / -$corriente, 1)];
    }
    return null;
}

// ---- Bateria ----
$bateria = null;
try {
    $b = leer_bms(true); // modo rapido; null si el ESP32 o el BMS estan desconectados
    if ($b !== null && $b['soc'] !== null) {
        $bateria = [
            'soc' => $b['soc'], 'voltaje' => $b['voltaje'], 'corriente' => $b['corriente'],
            'potencia' => $b['potencia'], 'en_vivo' => true, 'hace_minutos' => 0,
            'estimacion' => estimar($b['soc'], $b['capacidad'] ?? null, $b['corriente']),
        ];
    }
} catch (Throwable $e) {
    // si MQTT falla se usa la ultima muestra guardada
}
if ($bateria === null) {
    $f = $db->query('SELECT hora, soc, voltaje, corriente, potencia FROM bms_muestras ORDER BY hora DESC LIMIT 1')->fetch();
    if ($f) {
        $bateria = [
            'soc' => $f['soc'] === null ? null : (float) $f['soc'],
            'voltaje' => $f['voltaje'] === null ? null : (float) $f['voltaje'],
            'corriente' => $f['corriente'] === null ? null : (float) $f['corriente'],
            'potencia' => $f['potencia'] === null ? null : (float) $f['potencia'],
            'en_vivo' => false,
            'hace_minutos' => (int) floor(($ahora - strtotime($f['hora'])) / 60),
            'estimacion' => null,
        ];
    }
}

// ---- Inversor (ultimo punto guardado, con unos minutos de retraso) ----
$inversor = null;
$f = $db->query('SELECT hora, pv_w, casa_w, bateria_w FROM inversor_puntos ORDER BY hora DESC LIMIT 1')->fetch();
if ($f) {
    $inversor = [
        'pv_w' => (float) $f['pv_w'],
        'casa_w' => (float) $f['casa_w'],
        'bateria_w' => (float) $f['bateria_w'],
        'hace_minutos' => (int) floor(($ahora - strtotime($f['hora'])) / 60),
    ];
}

responder_json(['hora' => date('Y-m-d H:i:s'), 'bateria' => $bateria, 'inversor' => $inversor]);
