<?php
declare(strict_types=1);

require_once __DIR__ . '/db.php';

/**
 * Recalcula el resumen de un dia a partir de los puntos guardados.
 * Integra las potencias con la regla del trapecio; los huecos de mas de 30 min no se
 * integran. La produccion usa el contador diario del inversor si existe.
 */
function recalcular_resumen(PDO $db, string $fecha): array
{
    $siguiente = date('Y-m-d', strtotime("$fecha +1 day"));
    $st = $db->prepare(
        'SELECT hora, pv_w, casa_w, bateria_w, energia_pv_dia FROM inversor_puntos
         WHERE hora >= ? AND hora < ? ORDER BY hora'
    );
    $st->execute(["$fecha 00:00:00", "$siguiente 00:00:00"]);
    $p = $st->fetchAll();

    $pv = $casa = $carga = $descarga = 0.0;
    for ($i = 1; $i < count($p); $i++) {
        $horas = (strtotime($p[$i]['hora']) - strtotime($p[$i - 1]['hora'])) / 3600.0;
        if ($horas <= 0.0 || $horas > 0.5) {
            continue;
        }
        $pv += ($p[$i - 1]['pv_w'] + $p[$i]['pv_w']) / 2.0 * $horas / 1000.0;
        $casa += ($p[$i - 1]['casa_w'] + $p[$i]['casa_w']) / 2.0 * $horas / 1000.0;
        $b = ($p[$i - 1]['bateria_w'] + $p[$i]['bateria_w']) / 2.0 * $horas / 1000.0;
        if ($b > 0) {
            $carga += $b;
        } else {
            $descarga += -$b;
        }
    }

    $contador = null;
    $picoW = 0.0;
    $picoHora = null;
    foreach ($p as $f) {
        if ($f['energia_pv_dia'] !== null && ($contador === null || $f['energia_pv_dia'] > $contador)) {
            $contador = (float) $f['energia_pv_dia'];
        }
        if ($f['pv_w'] > $picoW) {
            $picoW = (float) $f['pv_w'];
            $picoHora = substr($f['hora'], 11, 5);
        }
    }

    $st = $db->prepare('SELECT MIN(soc) AS mn, MAX(soc) AS mx FROM bms_muestras WHERE hora >= ? AND hora < ?');
    $st->execute(["$fecha 00:00:00", "$siguiente 00:00:00"]);
    $soc = $st->fetch();

    $r = [
        'fecha' => $fecha,
        'pv_kwh' => round($contador ?? $pv, 3),
        'casa_kwh' => round($casa, 3),
        'bateria_carga_kwh' => round($carga, 3),
        'bateria_descarga_kwh' => round($descarga, 3),
        'pico_pv_w' => round($picoW, 1),
        'pico_pv_hora' => $picoHora,
        'soc_min' => $soc['mn'] !== null ? round((float) $soc['mn'], 1) : null,
        'soc_max' => $soc['mx'] !== null ? round((float) $soc['mx'], 1) : null,
    ];

    $db->prepare(
        'INSERT INTO resumen_dia (fecha, pv_kwh, casa_kwh, bateria_carga_kwh, bateria_descarga_kwh,
                                  pico_pv_w, pico_pv_hora, soc_min, soc_max, actualizado)
         VALUES (:fecha, :pv_kwh, :casa_kwh, :bateria_carga_kwh, :bateria_descarga_kwh,
                 :pico_pv_w, :pico_pv_hora, :soc_min, :soc_max, NOW())
         ON DUPLICATE KEY UPDATE pv_kwh = VALUES(pv_kwh), casa_kwh = VALUES(casa_kwh),
             bateria_carga_kwh = VALUES(bateria_carga_kwh), bateria_descarga_kwh = VALUES(bateria_descarga_kwh),
             pico_pv_w = VALUES(pico_pv_w), pico_pv_hora = VALUES(pico_pv_hora),
             soc_min = VALUES(soc_min), soc_max = VALUES(soc_max), actualizado = NOW()'
    )->execute($r);

    return $r;
}
