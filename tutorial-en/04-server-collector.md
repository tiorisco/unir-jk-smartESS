# 4. The server: collector, history and API

A small always-on server does four things: it **collects** the data every 5 minutes, **stores** it, **serves** it to the
app and to Alexa through a read-only API, and **alerts** via Telegram. Here it was done in PHP + MySQL on Apache because
such a server already existed, but anything that can run a task every 5 minutes works: a Raspberry Pi at home does the same.

## 4.1 Why it is needed

- The inverter's cloud **only keeps the current day** (chapter 3). Without this server there is no history.
- The MQTT broker keeps **only the last value** of each topic. There is no battery history either.
- Alexa and the family app need something stable to ask, without the inverter credentials.

## 4.2 What it contains

```
servidor/
  api/recoger.php        # called by cron: reads SmartESS and the BMS and stores
  api/dia.php            # series of one day (inverter + BMS SOC)
  api/resumenes.php      # daily energy summary + collector status
  api/ahora.php          # fast current status, for Alexa
  api/probar_aviso.php   # sends a test alert
  api/telegram_chats.php # lists who wrote to the bot (chapter 6)
  core/config.php        # environment variables and helpers
  core/db.php            # PDO connection
  core/smartess.php      # SmartESS client (read-only)
  core/mqtt.php          # minimal MQTT reader over TLS (no dependencies)
  core/resumen.php       # energy calculation (trapezoid rule)
  core/alertas.php       # thresholds and alerts
  database/esquema.sql   # tables
```

## 4.3 The tables

| Table | Content |
|---|---|
| `inversor_puntos` | One row per SmartESS sample: PV, house, battery and grid in W |
| `bms_muestras` | One row every 5 min with SOC, voltage, current, power, cells |
| `resumen_dia` | Each day's energy: kWh of PV, house, charge and discharge, and min/max SOC |
| `estado_recogida` | Last successful collection of each source (to know whether the collector is alive) |
| `alertas_estado` | Which alerts were already sent (so they are not repeated) |

Times are **local and zone-less** (`Europe/Madrid` is set; change it to yours).

## 4.4 The collection

`api/recoger.php` does the following, and each source is independent: if SmartESS fails, the BMS is still stored.

1. Checks the `X-Token` header and takes a MySQL **lock** (`GET_LOCK`) so two collections never run at once.
2. **Inverter:** asks SmartESS for the day's series and stores it with `INSERT … ON DUPLICATE KEY UPDATE`. So running it
   twice in a row **neither duplicates nor loses anything**.
3. **Battery:** connects to the broker, reads the latest values and stores one sample. If `status` is not `online` or the
   BMS is not `ON`, **it stores nothing** (they would be old retained values that look new).
4. Recomputes today's summary and evaluates the alerts.

### A 100-line MQTT client

To avoid depending on Composer, `core/mqtt.php` implements the bare minimum of MQTT 3.1.1 over TLS: CONNECT, SUBSCRIBE and
reading PUBLISH. As the ESP32 publishes with retain, all the latest values arrive at once on subscribing. Two details
matter for speed:

- It subscribes only to the topics it needs and **stops as soon as they have all arrived**, instead of waiting for silence.
  That brought the query for Alexa from 3–5 s down to ~1.2 s.
- There is a full mode (all topics) for the collector and a fast one (7 topics) for Alexa.

### The two sources and their failures

Learned the hard way: **do not trust that the data exists**. Every column that can be missing must be tolerated, every
call to the cloud may return a new format (chapter 3) and the retained MQTT values may be old.

## 4.5 The API and its tokens

There are **two different tokens**, both in the `X-Token` header:

| Token | What for | Who has it |
|---|---|---|
| `SOLAR_CRON_TOKEN` | Trigger the collection and send test alerts | Only the server's cron |
| `SOLAR_APP_TOKEN` | **Read-only**: `dia`, `resumenes`, `ahora` | The app, the family APK and the Alexa skill |

Neither works on the other's endpoints: tested that the app token gets `401` on `recoger.php` and vice versa. They are
compared with `hash_equals`. If a phone is ever lost, you change `SOLAR_APP_TOKEN` and rebuild.

The data endpoints return JSON. For example `ahora.php`:

```json
{"hora":"2026-11-13 05:20:48",
 "bateria":{"soc":62,"voltaje":52.5,"corriente":-6.4,"potencia":-337,
            "en_vivo":true,"hace_minutos":0,
            "estimacion":{"tipo":"vacia","horas":20.1}},
 "inversor":{"pv_w":7,"casa_w":246,"bateria_w":-255,"hace_minutos":1}}
```

`estimacion` is computed from the remaining capacity and the SOC: `total_capacity = remaining / (SOC/100)`; when charging,
time to full; when discharging, time to empty. If the broker does not answer, `ahora.php` returns the last stored sample
with `"en_vivo": false` and how many minutes old it is.

## 4.6 Secrets: Apache environment variables

Credentials go neither in the code nor in a `.env` (this app does not read one): they are defined with `SetEnv` in an
Apache file, for example `/etc/apache2/conf-available/my-app-env.conf`:

```apache
SetEnv SOLAR_DB_HOST localhost
SetEnv SOLAR_DB_NAME solar
SetEnv SOLAR_DB_USER db_user
SetEnv SOLAR_DB_PASS CHANGE_ME
SetEnv SOLAR_SMARTESS_USER CHANGE_ME
SetEnv SOLAR_SMARTESS_PASS CHANGE_ME
SetEnv SOLAR_MQTT_HOST your-broker.example.com
SetEnv SOLAR_MQTT_USER collector
SetEnv SOLAR_MQTT_PASS CHANGE_ME
SetEnv SOLAR_CRON_TOKEN CHANGE_ME     # openssl rand -hex 24
SetEnv SOLAR_APP_TOKEN CHANGE_ME      # a different one
SetEnv SOLAR_TELEGRAM_TOKEN CHANGE_ME
SetEnv SOLAR_TELEGRAM_CHAT CHANGE_ME
```

```bash
sudo a2enconf my-app-env
sudo chmod 640 /etc/apache2/conf-available/my-app-env.conf
sudo systemctl reload apache2        # ESSENTIAL after every change!
```

> **The classic stumble:** if you edit that file and **do not reload Apache**, the code still does not see the new
> variables and says "Missing environment variable" even though you can see it in the file. `apache2ctl configtest`
> does **not** reload anything.

## 4.7 The cron

Since `SetEnv` only exists inside Apache, cron **does not run PHP directly**: it calls the website with the token.

```bash
# /etc/cron.d/solar  (chmod 600, owner root)
*/5 * * * * www-data curl -fsS -m 60 -H "X-Token: THE_CRON_TOKEN" https://your-server.duckdns.org/api/recoger.php >/dev/null 2>&1
```

When you create it, check the token is not empty: a line with a blank `X-Token: ` fails silently every 5 minutes.

## 4.8 Deployment, step by step

1. Domain and HTTPS: a subdomain (DuckDNS works) pointing at the server, an Apache site with `AllowOverride All` and
   `Options -Indexes`, and `certbot --apache -d your-server.duckdns.org`.
2. Database: `CREATE DATABASE solar CHARACTER SET utf8mb4;` and `mysql … < database/esquema.sql`.
3. Code: pack it in a `.tar.gz` **with a version in the name** (`solar_v1.tar.gz`, `solar_v2…`) and extract it into the
   docroot with `--no-same-owner`. The versioned name prevents an FTP client from silently skipping a file that "already exists".
4. Environment variables (4.6) and **reload Apache**.
5. Test: `curl -H "X-Token: …" https://…/api/recoger.php` must return `"ok":true`. Then schedule the cron.
6. Verify from outside: `core/` must give `403` (it has an `.htaccess` with `Require all denied`), `index.php` a `404`,
   and any endpoint without a token a `401`.

## 4.9 Test locally before uploading

One advantage of it being dependency-free PHP: it is tested on your PC with the built-in server (`php -S`) and a temporary
MySQL database, using the real services. That caught several errors before deploying. Two Windows traps:

- A PHP file saved **with a BOM** (what `Set-Content -Encoding utf8` does in PowerShell 5) prints an invisible character
  before `<?php` and **breaks the JSON** of the response. Save without BOM.
- PHP 8.1 added the `never` type; if your server has 8.0, do not use it.

## 4.10 What it costs

Almost nothing: one query every 5 minutes, about 300 rows a day. In a year that is tens of thousands of small rows.

Next: [5. The Android app](05-android-app.md).
