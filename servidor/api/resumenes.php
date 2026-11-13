<?php
declare(strict_types=1);

// GET /api/resumenes.php?desde=YYYY-MM-DD&hasta=YYYY-MM-DD   (cabecera X-Token = SOLAR_APP_TOKEN)
// Resumen diario de energia + estado del colector. Por defecto, el ultimo ano.

require_once __DIR__ . '/../core/db.php';

exigir_token('SOLAR_APP_TOKEN');

$hasta = fecha_valida($_GET['hasta'] ?? null) ?? date('Y-m-d');
$desde = fecha_valida($_GET['desde'] ?? null) ?? date('Y-m-d', strtotime("$hasta -365 days"));

$db = db();
$st = $db->prepare(
    'SELECT fecha, pv_kwh, casa_kwh, bateria_carga_kwh, bateria_descarga_kwh,
            pico_pv_w, pico_pv_hora, soc_min, soc_max
     FROM resumen_dia WHERE fecha BETWEEN ? AND ? ORDER BY fecha'
);
$st->execute([$desde, $hasta]);
$dias = $st->fetchAll();
foreach ($dias as &$d) {
    foreach (['pv_kwh', 'casa_kwh', 'bateria_carga_kwh', 'bateria_descarga_kwh', 'pico_pv_w', 'soc_min', 'soc_max'] as $k) {
        $d[$k] = $d[$k] === null ? null : (float) $d[$k];
    }
}
unset($d);

$estado = [];
foreach ($db->query('SELECT fuente, ultima_ok, ultimo_error FROM estado_recogida') as $f) {
    $estado[$f['fuente']] = ['ultima_ok' => $f['ultima_ok'], 'ultimo_error' => $f['ultimo_error']];
}

$total = $db->query('SELECT MAX(energia_pv_total) AS t FROM inversor_puntos')->fetch();

responder_json([
    'desde' => $desde,
    'hasta' => $hasta,
    'dias' => $dias,
    'estado' => $estado,
    'pv_total_kwh' => $total['t'] !== null ? (float) $total['t'] : null,
]);
