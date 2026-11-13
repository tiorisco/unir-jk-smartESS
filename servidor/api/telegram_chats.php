<?php
declare(strict_types=1);

// Lista los chats que han escrito al bot (cabecera X-Token = SOLAR_CRON_TOKEN), para saber
// que id poner en SOLAR_TELEGRAM_CHAT. Cada persona debe abrir el bot y pulsar Iniciar/Start.
// No envia nada ni da acceso: solo los ids que tu pongas en SOLAR_TELEGRAM_CHAT reciben avisos.

require_once __DIR__ . '/../core/alertas.php';

exigir_token('SOLAR_CRON_TOKEN');

try {
    $r = telegram_api('getUpdates', ['limit' => 100, 'timeout' => 0]);
    if (($r['ok'] ?? false) !== true) {
        responder_json(['ok' => false, 'error' => (string) ($r['description'] ?? 'error de Telegram')], 502);
    }
    $chats = [];
    foreach ($r['result'] ?? [] as $u) {
        $m = $u['message'] ?? $u['my_chat_member'] ?? null;
        $c = $m['chat'] ?? null;
        if (!is_array($c) || !isset($c['id'])) {
            continue;
        }
        $nombre = trim(($c['first_name'] ?? '') . ' ' . ($c['last_name'] ?? ''));
        $chats[(string) $c['id']] = [
            'id' => (string) $c['id'],
            'tipo' => $c['type'] ?? '',
            'nombre' => $nombre !== '' ? $nombre : ($c['title'] ?? ($c['username'] ?? '')),
        ];
    }
    try {
        $autorizados = telegram_chats();
    } catch (Throwable $e) {
        $autorizados = []; // SOLAR_TELEGRAM_CHAT todavia no existe
    }
    responder_json(['ok' => true, 'chats' => array_values($chats), 'autorizados' => $autorizados]);
} catch (Throwable $e) {
    responder_json(['ok' => false, 'error' => substr($e->getMessage(), 0, 250)], 500);
}
