<?php
declare(strict_types=1);

// Configuracion y utilidades comunes. Los secretos NO estan aqui: vienen de las
// directivas SetEnv de Apache (/etc/apache2/conf-available/mi-app-env.conf).

date_default_timezone_set('Europe/Madrid');

function env(string $clave, ?string $porDefecto = null): string
{
    $v = getenv($clave);
    if ($v === false || $v === '') {
        $v = $_SERVER[$clave] ?? $_ENV[$clave] ?? false;
    }
    if ($v === false || $v === '') {
        if ($porDefecto !== null) {
            return $porDefecto;
        }
        throw new RuntimeException("Falta la variable de entorno $clave");
    }
    return (string) $v;
}

function responder_json(array $datos, int $codigo = 200): void
{
    http_response_code($codigo);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($datos, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** Exige la cabecera X-Token igual al valor de la variable de entorno indicada. */
function exigir_token(string $variableEsperada): void
{
    $esperado = env($variableEsperada);
    $recibido = $_SERVER['HTTP_X_TOKEN'] ?? '';
    if (!is_string($recibido) || $recibido === '' || !hash_equals($esperado, $recibido)) {
        responder_json(['error' => 'no autorizado'], 401);
    }
}

/** Valida una fecha YYYY-MM-DD real. */
function fecha_valida(?string $f): ?string
{
    if ($f === null || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $f)) {
        return null;
    }
    [$a, $m, $d] = array_map('intval', explode('-', $f));
    return checkdate($m, $d, $a) ? $f : null;
}
