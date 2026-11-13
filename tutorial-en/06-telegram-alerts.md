# 6. Telegram alerts

A notification on your phone when the battery is low, critical or full, or when the system stops sending data. It is sent
by the server from chapter 4, which is always on, so it works even when the app is closed.

## 6.1 Why Telegram and not something else

**ntfy** (a simple push-notification service) was tried first: it works, but its app keeps a connection open to receive
alerts instantly and leaves **a permanent notification** in the status bar, which is annoying. A Telegram bot leaves
nothing permanent, is free and lets you share the alerts with other people by adding their chat.

## 6.2 Create the bot

1. In Telegram look for **@BotFather** (the official one, with the blue check) and press *Start*.
2. Send `/newbot`, give it a display name (for example "Solar") and a username that **ends in `bot`** and is unique
   (for example `my_solar_bot`).
3. BotFather gives you the **token** (something like `123456789:AAH…`). **It is a secret:** whoever has it controls the bot.
4. Open your bot from the link it gives you and press **Start** (or write "hello"). Without that the bot cannot write to you.

## 6.3 Find your chat id

The server only sends alerts to **authorised chat ids**, so nobody who finds the bot receives your data. To learn your id,
the server ships `api/telegram_chats.php`, which asks Telegram who has written to the bot (using the cron token):

```bash
curl -sS -H "X-Token: THE_CRON_TOKEN" https://your-server.duckdns.org/api/telegram_chats.php
# {"ok":true,"chats":[{"id":"123456789","tipo":"private","nombre":"Your Name"}],"autorizados":[]}
```

Put `SetEnv SOLAR_TELEGRAM_TOKEN …` and `SetEnv SOLAR_TELEGRAM_CHAT 123456789` in the Apache variables file and **reload
Apache** (`sudo systemctl reload apache2`; without that it says "Missing environment variable" even if it is written).

For another family member to receive them too: they open the bot and press Start, you run the `curl` above again and add
their id separated by commas: `SetEnv SOLAR_TELEGRAM_CHAT 123456789,987654321`.

Proof that it arrives:

```bash
curl -sS -H "X-Token: THE_CRON_TOKEN" https://your-server.duckdns.org/api/probar_aviso.php
# {"ok":true,"chats":1}   and on the phone: "✅ Prueba de Terrenico"
```

## 6.4 When it alerts

| Alert | Sent when | "Re-arms" when |
|---|---|---|
| 🪫 Battery low | SOC ≤ 20 % | it rises to ≥ 25 % |
| 🚨 Battery critical | SOC ≤ 10 % | it rises to ≥ 15 % |
| 🔋 Battery full | SOC ≥ 100 % | it drops to ≤ 95 % |
| 🔌 No BMS data | no data for more than 30 min | data returns (it also notifies on recovery) |

It is the **BMS** SOC, not the inverter's.

### Hysteresis: one alert, once

If the percentage oscillates between 19 % and 21 % you do not want twenty alerts. Each alert is sent **only once** and is
not sent again until the level moves away from the threshold (the "re-arms" column). The "already alerted" state is kept in
the `alertas_estado` table. One detail: if in a single reading you cross both the low and the critical threshold (for
example from 25 % to 9 %), only the most serious one is sent.

The state is set **only after Telegram confirms delivery**: if Telegram fails, the alert is retried on the next collection
instead of being lost.

### Tested before use

The threshold logic was tested with a fake Telegram and a sequence of values (50, 22, 19, 21, 26, 9, 12, 16, 99, 100,
100, 94, 96, 100): exactly low (19), critical (9) and full (100, and again after dropping to 94) are sent. Recovery alerts
go out silently (`disable_notification`).

## 6.5 Security details

- Errors **never include the bot token** (the API URL carries it, so dumping it into messages is avoided).
- If one chat fails and another does not, it is delivered to the one that works. It only fails if it reaches none.

Next: [7. Alexa](07-alexa.md).
