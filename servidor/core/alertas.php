<?php
declare(strict_types=1);

require_once __DIR__ . '/db.php';

/**
 * Avisos al movil por un bot de Telegram segun el SOC del BMS. Cada aviso se envia UNA vez y
 * no se repite hasta que el SOC se aleja del umbral (histeresis), para no llenar el chat.
 *
 *   baja     <= 20 %   (se rearma al subir a >= 25 %)
 *   critica  <= 10 %   (se rearma al subir a >= 15 %)
 *   llena    >= 100 %  (se rearma al bajar a <= 95 %)
 *   sin_datos: el BMS lleva mas de 30 min sin dar datos (y aviso cuando vuelve)
 *
 * Variables de entorno:
 *   SOLAR_TELEGRAM_TOKEN  token del bot (lo da @BotFather). SECRETO.
 *   SOLAR_TELEGRAM_CHAT   ids de chat autorizados, separados por comas (tu movil y, si quieres,
 *                         los de la familia). Solo estos reciben avisos.
 *   SOLAR_TELEGRAM_API    (opcional) por defecto https://api.telegram.org
 */

const UMBRAL_BAJA = 20.0;
const REARME_BAJA = 25.0;
const UMBRAL_CRITICA = 10.0;
const REARME_CRITICA = 15.0;
const UMBRAL_LLENA = 100.0;
const REARME_LLENA = 95.0;
const MINUTOS_SIN_DATOS = 30;

/** Llama a un metodo de la API de Telegram. Devuelve el JSON. NUNCA incluye el token en errores. */
function telegram_api(string $metodo, array $datos = []): array
{
    $base = rtrim(env('SOLAR_TELEGRAM_API', 'https://api.telegram.org'), '/');
    $url = $base . '/bot' . env('SOLAR_TELEGRAM_TOKEN') . '/' . $metodo;
    $cuerpo = json_encode($datos, JSON_UNESCAPED_UNICODE);

    if (function_exists('curl_init')) {
        $c = curl_init($url);
        curl_setopt_array($c, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $cuerpo,
            CURLOPT_HTTPHEADER => ['Content-Type: application/json; charset=utf-8'],
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 15,
            CURLOPT_CONNECTTIMEOUT => 8,
        ]);
        $texto = curl_exec($c);
        curl_close($c);
    } else {
        $ctx = stream_context_create(['http' => [
            'method' => 'POST',
            'header' => 'Content-Type: application/json; charset=utf-8',
            'content' => $cuerpo,
            'timeout' => 15,
            'ignore_errors' => true,
        ]]);
        $texto = @file_get_contents($url, false, $ctx);
    }
    if ($texto === false || $texto === '') {
        throw new RuntimeException("Telegram: sin respuesta en $metodo");
    }
    $json = json_decode($texto, true);
    if (!is_array($json)) {
        throw new RuntimeException("Telegram: respuesta no valida en $metodo");
    }
    return $json;
}

/** @return string[] ids de chat autorizados */
function telegram_chats(): array
{
    return array_values(array_filter(array_map('trim', explode(',', env('SOLAR_TELEGRAM_CHAT')))));
}

/**
 * Envia un aviso a todos los chats autorizados. Falla solo si no llega a NINGUNO.
 * Prioridad <= 2 se envia sin sonido.
 */
function avisar(string $titulo, string $mensaje, int $prioridad = 3, string $emoji = ''): void
{
    $chats = telegram_chats();
    if (!$chats) {
        throw new RuntimeException('Telegram: SOLAR_TELEGRAM_CHAT esta vacio');
    }
    $texto = trim("$emoji $titulo") . "\n" . $mensaje;
    $entregados = 0;
    $errores = [];
    foreach ($chats as $chat) {
        try {
            $r = telegram_api('sendMessage', [
                'chat_id' => $chat,
                'text' => $texto,
                'disable_notification' => $prioridad <= 2,
                'disable_web_page_preview' => true,
            ]);
            if (($r['ok'] ?? false) === true) {
                $entregados++;
            } else {
                $errores[] = 'chat ' . $chat . ': ' . ($r['description'] ?? 'error');
            }
        } catch (Throwable $e) {
            $errores[] = 'chat ' . $chat . ': ' . $e->getMessage();
        }
    }
    if ($entregados === 0) {
        throw new RuntimeException('Telegram: no se pudo entregar (' . implode('; ', $errores) . ')');
    }
}

function alerta_activa(PDO $db, string $tipo): bool
{
    $st = $db->prepare('SELECT activa FROM alertas_estado WHERE tipo = ?');
    $st->execute([$tipo]);
    $f = $st->fetch();
    return $f !== false && (int) $f['activa'] === 1;
}

function alerta_fijar(PDO $db, string $tipo, bool $activa): void
{
    $db->prepare(
        'INSERT INTO alertas_estado (tipo, activa, cambiado) VALUES (?, ?, NOW())
         ON DUPLICATE KEY UPDATE activa = VALUES(activa), cambiado = NOW()'
    )->execute([$tipo, $activa ? 1 : 0]);
}

function detalle_bms(array $b): string
{
    $partes = [];
    if ($b['voltaje'] !== null) {
        $partes[] = number_format($b['voltaje'], 1, ',', '.') . ' V';
    }
    if ($b['corriente'] !== null) {
        $i = $b['corriente'];
        $partes[] = ($i > 0.5 ? 'cargando ' : ($i < -0.5 ? 'descargando ' : 'en reposo '))
            . number_format(abs($i), 1, ',', '.') . ' A';
    }
    return $partes ? ' (' . implode(', ', $partes) . ')' : '';
}

/**
 * Evalua los umbrales con la ultima lectura del BMS y envia los avisos que toquen.
 * El estado solo se marca tras entregar el aviso: si Telegram falla, se reintenta en la siguiente.
 * @return string[] avisos enviados
 */
function evaluar_alertas(PDO $db, array $b): array
{
    $soc = $b['soc'] ?? null;
    if ($soc === null) {
        return [];
    }
    $soc = (float) $soc;
    $enviados = [];
    $txt = number_format($soc, 0) . ' %' . detalle_bms($b);

    // Rearmes (se hacen primero, con el SOC actual)
    if ($soc >= REARME_CRITICA && alerta_activa($db, 'critica')) {
        alerta_fijar($db, 'critica', false);
    }
    if ($soc >= REARME_BAJA && alerta_activa($db, 'baja')) {
        alerta_fijar($db, 'baja', false);
    }
    if ($soc <= REARME_LLENA && alerta_activa($db, 'llena')) {
        alerta_fijar($db, 'llena', false);
    }

    // Disparos: si cruza los dos umbrales a la vez, solo el mas grave
    if ($soc <= UMBRAL_CRITICA && !alerta_activa($db, 'critica')) {
        avisar('Batería CRÍTICA', "Batería al $txt. Reduce el consumo.", 5, '🚨');
        alerta_fijar($db, 'critica', true);
        alerta_fijar($db, 'baja', true);
        $enviados[] = 'critica';
    } elseif ($soc <= UMBRAL_BAJA && !alerta_activa($db, 'baja')) {
        avisar('Batería baja', "Batería al $txt.", 4, '🪫');
        alerta_fijar($db, 'baja', true);
        $enviados[] = 'baja';
    }
    if ($soc >= UMBRAL_LLENA && !alerta_activa($db, 'llena')) {
        avisar('Batería completa', "Batería al $txt. Ya está llena.", 3, '🔋');
        alerta_fijar($db, 'llena', true);
        $enviados[] = 'llena';
    }
    return $enviados;
}

/**
 * Avisa si el BMS lleva mas de MINUTOS_SIN_DATOS sin enviar datos (ESP32 apagado, sin wifi...)
 * y cuando vuelve. @return string[] avisos enviados
 */
function vigilar_bms(PDO $db, ?DateTimeImmutable $ahora = null): array
{
    $st = $db->query("SELECT ultima_ok FROM estado_recogida WHERE fuente = 'bms'");
    $f = $st->fetch();
    if ($f === false || $f['ultima_ok'] === null) {
        return [];
    }
    $ahora ??= new DateTimeImmutable();
    $minutos = (int) floor(($ahora->getTimestamp() - strtotime($f['ultima_ok'])) / 60);
    $enviados = [];
    if ($minutos > MINUTOS_SIN_DATOS && !alerta_activa($db, 'sin_datos')) {
        avisar('Sin datos del BMS', "Hace $minutos min que no llegan datos de la batería. Revisa el ESP32 y el wifi.", 4, '🔌');
        alerta_fijar($db, 'sin_datos', true);
        $enviados[] = 'sin_datos';
    } elseif ($minutos <= MINUTOS_SIN_DATOS && alerta_activa($db, 'sin_datos')) {
        avisar('BMS de vuelta', 'La batería vuelve a enviar datos.', 2, '✅');
        alerta_fijar($db, 'sin_datos', false);
        $enviados[] = 'recuperado';
    }
    return $enviados;
}
