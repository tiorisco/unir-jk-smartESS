# 8. Security and common problems

## 8.1 Security rules that were followed

1. **Everything is read-only.** The BMS never receives write commands, the SmartESS API is never called with `ctrlDevice`,
   and the apps' MQTT users cannot publish (and this was tested).
2. **One MQTT user per client**, each with just the permissions it needs, so one can be revoked without affecting the others.
3. **Two server tokens** (one to trigger collection and one read-only). Each rejects the other's endpoint.
4. **Secrets never go in the repository.** `local.properties`, `secrets.yaml`, `.env`, `config.js`, `key.properties` and the
   `.jks` files are in `.gitignore`, and each folder carries its `*.example` with placeholder values.
5. **Nothing valuable should go inside an APK.** Everything in `BuildConfig` can be extracted. Read-only access only, and
   your inverter account password stays out.
6. **Different, unrelated passwords.** A password that *contains* another one (the same one with two characters added) counts
   as the same. In this build the family user was created with the SmartESS password; it was caught by searching the
   strings in the APK before handing it out. Generate random passwords:
   `python -c "import secrets; print(secrets.token_urlsafe(12))"`.
7. **Review what you push to a repository**, especially a public one. Search the files for your own values (passwords,
   tokens, wifi, MAC, IP, serial numbers) and do not print them while doing so. An inverter serial number had slipped into
   the sample JSON files.
8. **Back up what is not in git**: your secret files and the keystore (`.jks`). Without the keystore an already
   distributed APK cannot be updated.

## 8.2 Problem table

| Symptom | Cause | Fix |
|---|---|---|
| Windows does nothing when you plug in the ESP32 | Charge-only cable | Use a data cable |
| "CP2102 USB to UART Bridge" with an error, no COM port | Driver missing | Install the CP210x Universal Windows Driver from the browser |
| ESPHome fails with `bits/c++config.h: No such file` | Paths longer than 260 characters | `ESPHOME_ESP_IDF_PREFIX=C:\espidf` and delete `.esphome\build\<name>` |
| The PC cannot see the BMS over Bluetooth: "BLE central role not supported" | Old Bluetooth adapter (no BLE) | A Bluetooth 4.0+ dongle, or skip the test and use the ESP32 |
| `MQTT_EVENT_ERROR … transport_read(): EOF` | ESPHome on ESP-IDF without `certificate_authority`: it speaks plain text | Paste the broker's root certificate |
| MQTT: connection code **4** | Wrong user or password | Check the user and password on the broker and in the file |
| ESP32: "No networks found" | Wifi name mistyped, or a 5 GHz network | Check the SSID; use the 2.4 GHz band |
| Absurd BMS values | Wrong protocol variant | Switch `JK02_32S` ↔ `JK02_24S` |
| BMS Bluetooth at −80 dBm, drops | ESP32 far away or behind metal | Place it one or two metres from the BMS |
| The official JK app will not connect | The BMS accepts a single connection | Normal while the ESP32 is connected |
| SmartESS: `ERR_NOT_FOUND_USR` | Logging in with the e-mail | Use the username |
| SmartESS: `ERR_PASSWORD_VERIF_FAIL` | Password saved wrongly (it had been pasted twice) | Check its length in the `.env` |
| SmartESS: "the format has changed" | The cloud changed column names or fields | Match columns case-insensitively, tolerate missing ones |
| The inverter SOC does not match the BMS | The inverter estimates from voltage | Always use the BMS SOC |
| SmartESS data identical across several queries | The cloud repeats the value for minutes | Normal; show "data from N min ago" |
| Apache: "Missing environment variable X" though it is in the file | Apache was not reloaded | `sudo systemctl reload apache2` (and `configtest` must not fail) |
| The cron stores nothing | Cron line with an empty `X-Token: ` | Redo it with the real token and check its length |
| The PHP JSON response cannot be parsed | The PHP file has a BOM | Save it without BOM |
| Alexa: "doesn't have batteries" / "I work without a battery" | The phrase does not reach the skill | Use "abre mi casa solar" by voice |
| Alexa: "can't find a skill called panels" | It ignores the name's article | A name without article and with 2–3 words |
| Alexa: "I can't open that skill by text" | Custom skills are not invoked by text | Test by voice |

## 8.3 Honest limitations

- **The inverter is not "live":** the SmartESS cloud lags by minutes. The battery is almost immediate.
- **The SmartESS API is unofficial** and it changed during the build; it may do so again.
- **History starts the day the server starts** and has gaps if the server or the wifi go down.
- The **duration estimate** uses the SOC (an integer) and the instantaneous current: it is indicative.
- A single BMS and a single inverter; for several you would have to parametrise the topics and devices.

## 8.4 Ideas to continue

- Connect the inverter to the BMS over RS485/CAN so its SOC is real (depends on your model).
- Read the inverter's dongle over the local network instead of the cloud, to get live data.
- A web dashboard, in addition to the app.
- Tune the alert thresholds to your usage or add a solar-surplus alert.

## 8.5 Credits

- [`syssi/esphome-jk-bms`](https://github.com/syssi/esphome-jk-bms): the ESPHome component for the JK BMS and the protocol
  reference.
- [`davidsmfreire/shinemonitor-api`](https://github.com/davidsmfreire/shinemonitor-api): the community documentation of the
  ShineMonitor/SmartESS API.
- ESPHome, EMQX, HiveMQ MQTT client, Telegram and the Alexa SDK (`ask-sdk-core`).
