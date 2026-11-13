# 1. ESP32 and BMS: read the battery over Bluetooth and publish it

Goal of this chapter: an ESP32 next to the batteries that connects to the BMS over Bluetooth, reads its state and
publishes it over wifi. We use **ESPHome** with the community component
[`syssi/esphome-jk-bms`](https://github.com/syssi/esphome-jk-bms), so there is no firmware to write: it is configured
with a YAML file.

## 1.1 How the JK BMS talks (what is worth knowing)

- The BMS exposes a Bluetooth LE service (`0xFFE0`, characteristic `0xFFE1`) that sends ~300-byte frames with total
  voltage, current, state of charge (SOC), temperatures, each cell's voltage, and so on.
- **It accepts only one Bluetooth connection at a time.** While the ESP32 is connected, the official JK app cannot
  connect to the BMS. That is normal.
- There are protocol variants (`JK02_24S` and `JK02_32S`). They depend on the BMS hardware version: in this build, a
  JK-B2A20S20P with hardware 11.XW and software 11.287 uses **`JK02_32S`**. With the wrong variant you will see absurd
  values (SOC outside 0–100, a voltage that does not match the sum of the cells): switch to the other one.
- **Read-only:** the component can also write settings or switch charge and discharge on and off, but only if you
  explicitly declare the `switch` entities. In this configuration **none is declared**.

## 1.2 Preparing the PC

### Install ESPHome

```bash
python -m pip install esphome
esphome version
```

### The USB cable and driver (the two most common stumbles)

1. **Many USB cables are charge-only.** If Windows does nothing when you plug in the ESP32, swap the cable for a data
   cable (the one from a phone or an external disk usually is).
2. If Windows detects *"CP2102 USB to UART Bridge Controller"* but with an error and no COM port, the driver is missing.
   Download the Silicon Labs *CP210x Universal Windows Driver* **from the browser** (their site blocks scripted downloads),
   install it by right-clicking `silabser.inf` → Install, and replug. A COM port should appear (*Silicon Labs CP210x USB
   to UART Bridge (COMx)*). Boards with a CH340 chip need the CH340 driver.

### Paths longer than 260 characters (Windows)

ESP-IDF generates very long paths. If ESPHome is installed in a deep path (for example the virtualised folder of a
Windows Store app), the build fails with absurd errors such as
`fatal error: bits/c++config.h: No such file or directory` even though the file exists. **Fix:** put the ESP-IDF cache
in a short path:

```powershell
$env:ESPHOME_ESP_IDF_PREFIX = "C:\espidf"
```

If it already failed once, delete the project's `.esphome\build\<name>` folder: the old configuration is cached and
would keep pointing at the compiler in the long path.

## 1.3 First step: find the BMS MAC address

The ESP32 needs the BMS Bluetooth address (MAC). You find it with a minimal firmware that scans and logs the devices
whose name starts with "JK" (`esp32/descubrir-mac.yaml`):

```yaml
esphome:
  name: jk-descubrir

esp32:
  board: esp32dev
  framework:
    type: esp-idf

logger:

esp32_ble_tracker:
  scan_parameters:
    active: true
  on_ble_advertise:
    - then:
        - lambda: |-
            std::string n = x.get_name();
            if (n.rfind("JK", 0) == 0) {
              ESP_LOGW("discover", "BMS found: name=%s MAC=%s RSSI=%d",
                       n.c_str(), x.address_str().c_str(), x.get_rssi());
            }
```

Close the JK app on your phone, flash it and watch the log (`esphome run descubrir-mac.yaml --device COMx`). You will see
something like `name=JK_B2A20S20P MAC=AA:BB:CC:DD:EE:FF RSSI=-80`. Write the MAC down.

## 1.4 The final firmware

Create a `secrets.yaml` next to the YAML (**do not commit it**; the repo has a `secrets.yaml.example`):

```yaml
wifi_ssid: "your_wifi_name"           # must be 2.4 GHz: the classic ESP32 cannot see 5 GHz
wifi_password: "wifi_password"
bms_mac: "AA:BB:CC:DD:EE:FF"
mqtt_broker: "your-broker.example.com"
mqtt_user: "esp32_user"
mqtt_password: "mqtt_password"
ota_password: "a_long_password_for_wifi_updates"
```

And `jk-bms.yaml` (the full one is in `esp32/jk-bms.yaml`):

```yaml
substitutions:
  name: jk-bms
  protocol_version: JK02_32S     # if the values are absurd, try JK02_24S

esphome:
  name: ${name}

esp32:
  board: esp32dev
  framework:
    type: esp-idf

external_components:
  - source: github://syssi/esphome-jk-bms@main
    refresh: 1d

logger:

ota:                              # after the first USB flash, later ones go over wifi
  - platform: esphome
    password: !secret ota_password

wifi:
  ssid: !secret wifi_ssid
  password: !secret wifi_password

mqtt:
  broker: !secret mqtt_broker
  port: 8883
  username: !secret mqtt_user
  password: !secret mqtt_password
  topic_prefix: solar/bateria
  discovery: false
  certificate_authority: |        # MANDATORY to get TLS! see 1.5
    -----BEGIN CERTIFICATE-----
    ...root certificate of your broker...
    -----END CERTIFICATE-----

esp32_ble_tracker:
  scan_parameters:
    active: false

ble_client:
  - mac_address: !secret bms_mac
    id: client0

jk_bms_ble:
  - ble_client_id: client0
    protocol_version: ${protocol_version}
    throttle: 10s
    id: bms0

binary_sensor:
  - platform: jk_bms_ble
    jk_bms_ble_id: bms0
    charging:       { name: "carga activada" }     # charging enabled
    discharging:    { name: "descarga activada" }  # discharging enabled
    online_status:  { name: "online" }

sensor:
  - platform: jk_bms_ble
    jk_bms_ble_id: bms0
    state_of_charge:      { name: "soc" }
    total_voltage:        { name: "voltaje" }
    current:              { name: "corriente" }
    power:                { name: "potencia" }
    capacity_remaining:   { name: "capacidad restante" }
    temperature_sensor_1: { name: "temp1" }
    temperature_sensor_2: { name: "temp2" }
    mosfet_temperature:   { name: "temp mosfet" }
    min_cell_voltage:     { name: "celda min" }
    max_cell_voltage:     { name: "celda max" }
    delta_cell_voltage:   { name: "celda delta" }
    cell_voltage_1:       { name: "celda 1" }
    # ... up to the number of cells in series of your battery (16 in this build)

  # Bridge diagnostics
  - platform: wifi_signal
    name: "wifi dbm"
    update_interval: 60s
  - platform: uptime
    name: "tiempo encendido"
    update_interval: 60s
  - platform: ble_client
    ble_client_id: client0
    type: rssi
    name: "bms rssi"
    update_interval: 60s

text_sensor:
  - platform: jk_bms_ble
    jk_bms_ble_id: bms0
    errors:           { name: "errores" }
    software_version: { name: "version software" }
    hardware_version: { name: "version hardware" }
```

Flash over USB and watch the log:

```bash
esphome compile jk-bms.yaml
esphome upload jk-bms.yaml --device COMx
esphome logs jk-bms.yaml --device COMx
```

You should see it join the wifi, the BMS (*Cell info frame received*) and the broker (*MQTT Connected*).

## 1.5 The detail almost nobody mentions: ESPHome does not use TLS without `certificate_authority`

On ESP-IDF, **if you do not set `certificate_authority`, ESPHome speaks plain-text MQTT** even if you put port 8883. The
broker drops the connection and the log shows `MQTT_EVENT_ERROR … transport_read(): EOF`. You have to paste the broker's
root certificate. For EMQX Serverless it is *DigiCert Global Root G2*. If you use another broker, find out its root. You
can check that a certificate validates your broker with Python before pasting it:

```python
import ssl, socket
ctx = ssl.create_default_context(cadata=ROOT_CERTIFICATE_PEM)
with socket.create_connection(("your-broker.example.com", 8883), timeout=10) as s:
    with ctx.wrap_socket(s, server_hostname="your-broker.example.com") as ss:
        print(ss.version())   # TLSv1.3 if it validates
```

## 1.6 Where to put the ESP32

Bluetooth signal drops a lot with distance and with the metal of the battery case. In this build:

| Location | Bluetooth to the BMS (RSSI) | wifi |
|---|---|---|
| Far away, testing at the PC | −80 dBm (drops now and then) | −79 dBm |
| Next to the BMS | **−43 dBm** (excellent) | −63 dBm (good) |

As a rule of thumb: better than −70 dBm is good signal; worse than −80 dBm drops. The `wifi dbm` and `bms rssi` sensors in
the YAML are there to check this from the PC once it is installed. Power the ESP32 from a 5 V / 1 A USB charger with a
short, not-too-thin cable (a weak cable causes reboots; `tiempo encendido` (uptime) will tell you).

## 1.7 What it publishes

With `topic_prefix: solar/bateria` and `discovery: false`, each sensor appears on
`solar/bateria/sensor/<name>/state` (for example `solar/bateria/sensor/soc/state`), binary sensors on
`solar/bateria/binary_sensor/<name>/state`, and the ESP32's own state on `solar/bateria/status` (`online`/`offline`).
ESPHome publishes with **retain**: the broker keeps the last value of each topic, and whoever subscribes receives it
immediately. This has a consequence: if the ESP32 or the BMS go down, **the retained values stay there and look
recent**. Anything that reads the broker should check `status` and `online` before trusting a value (the collector in
chapter 4 does).

**The sign of the current:** on this BMS, positive = charging and negative = discharging. Check it on your equipment with
the official app while charging or discharging.

Next: [2. MQTT broker](02-mqtt-broker.md).
