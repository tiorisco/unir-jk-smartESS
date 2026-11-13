<?php
declare(strict_types=1);

// Envia un aviso de prueba por Telegram (cabecera X-Token = SOLAR_CRON_TOKEN).
// Sirve para comprobar que el movil recibe los avisos.

require_once __DIR__ . '/../core/alertas.php';

exigir_token('SOLAR_CRON_TOKEN');

try {
    avisar('Prueba de Terrenico', 'Si lees esto, los avisos de la batería funcionan.', 3, '✅');
    responder_json(['ok' => true, 'chats' => count(telegram_chats())]);
} catch (Throwable $e) {
    responder_json(['ok' => false, 'error' => substr($e->getMessage(), 0, 250)], 500);
}
