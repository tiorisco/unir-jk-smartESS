<?php
declare(strict_types=1);

// GET /api/dia.php?fecha=YYYY-MM-DD   (cabecera X-Token = SOLAR_APP_TOKEN)
// Serie completa del dia: puntos del inversor y muestras del BMS (SOC real).

require_once __DIR__ . '/../core/db.php';

exigir_token('SOLAR_APP_TOKEN');

$fecha = fecha_valida($_GET['fecha'] ?? null) ?? date('Y-m-d');
$siguiente = date('Y-m-d', strtotime("$fecha +1 day"));
$db = db();

$st = $db->prepare(
    'SELECT hora, pv_w, casa_w, bateria_w, red_w, energia_pv_dia, energia_pv_total
     FROM inversor_puntos WHERE hora >= ? AND hora < ? ORDER BY hora'
);
$st->execute(["$fecha 00:00:00", "$siguiente 00:00:00"]);
$inversor = array_map(static fn($r) => [
    'hora' => $r['hora'],
    'pv_w' => (float) $r['pv_w'],
    'casa_w' => (float) $r['casa_w'],
    'bateria_w' => (float) $r['bateria_w'],
    'red_w' => (float) $r['red_w'],
    'energia_pv_dia' => $r['energia_pv_dia'] === null ? null : (float) $r['energia_pv_dia'],
    'energia_pv_total' => $r['energia_pv_total'] === null ? null : (float) $r['energia_pv_total'],
], $st->fetchAll());

$st = $db->prepare(
    'SELECT hora, soc, voltaje, corriente, potencia FROM bms_muestras
     WHERE hora >= ? AND hora < ? ORDER BY hora'
);
$st->execute(["$fecha 00:00:00", "$siguiente 00:00:00"]);
$bms = array_map(static fn($r) => [
    'hora' => $r['hora'],
    'soc' => $r['soc'] === null ? null : (float) $r['soc'],
    'voltaje' => $r['voltaje'] === null ? null : (float) $r['voltaje'],
    'corriente' => $r['corriente'] === null ? null : (float) $r['corriente'],
    'potencia' => $r['potencia'] === null ? null : (float) $r['potencia'],
], $st->fetchAll());

responder_json(['fecha' => $fecha, 'inversor' => $inversor, 'bms' => $bms]);
