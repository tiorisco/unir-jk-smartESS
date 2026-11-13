# 1. ESP32 y BMS: leer la batería por Bluetooth y publicarla

El objetivo de este capítulo: un ESP32 junto a las baterías que se conecta al BMS por Bluetooth, lee su estado y lo
publica por wifi. Usaremos **ESPHome** con el componente comunitario
[`syssi/esphome-jk-bms`](https://github.com/syssi/esphome-jk-bms), así que no hay que programar firmware: se configura
con un archivo YAML.

## 1.1 Cómo habla el BMS JK (lo que conviene saber)

- El BMS expone un servicio Bluetooth LE (`0xFFE0`, característica `0xFFE1`) donde manda tramas de unos 300 bytes con
  voltaje total, corriente, porcentaje (SOC), temperaturas, tensión de cada celda, etc.
- **Solo admite una conexión Bluetooth a la vez.** Mientras el ESP32 esté conectado, la app oficial del JK no podrá
  conectarse al BMS. Es normal.
- Hay variantes del protocolo (`JK02_24S` y `JK02_32S`). Dependen de la versión de hardware del BMS: en este montaje, un
  JK-B2A20S20P con hardware 11.XW y software 11.287 usa **`JK02_32S`**. Si pones la variante equivocada verás valores
  absurdos (SOC fuera de 0–100, voltaje que no coincide con la suma de las celdas): cambia a la otra.
- **Solo lectura:** el componente puede también escribir ajustes o activar/desactivar carga y descarga, pero eso solo
  ocurre si declaras explícitamente los `switch`. En esta configuración **no se declara ninguno**.

## 1.2 Preparar el PC

### Instalar ESPHome

```bash
python -m pip install esphome
esphome version
```

### El cable y el driver USB (los dos tropiezos más comunes)

1. **Muchos cables USB son solo de carga.** Si al enchufar el ESP32 Windows no hace nada, cambia de cable por uno de
   datos (el de un móvil o un disco externo suele serlo).
2. Si Windows detecta *«CP2102 USB to UART Bridge Controller»* pero con error y sin puerto COM, falta el driver. Descarga
   el *CP210x Universal Windows Driver* de Silicon Labs **desde el navegador** (su web bloquea las descargas por script),
   instálalo con clic derecho sobre `silabser.inf` → Instalar y reenchufa. Debe aparecer un puerto COM
   (*Silicon Labs CP210x USB to UART Bridge (COMx)*). Placas con chip CH340 necesitan el driver CH340.

### Rutas de más de 260 caracteres (Windows)

ESP-IDF genera rutas muy largas. Si ESPHome está instalado en una ruta profunda (por ejemplo la carpeta virtualizada de
una app de la tienda de Windows), la compilación falla con errores absurdos como
`fatal error: bits/c++config.h: No such file or directory` aunque el archivo exista. **Solución:** poner la caché de
ESP-IDF en una ruta corta:

```powershell
$env:ESPHOME_ESP_IDF_PREFIX = "C:\espidf"
```

Si ya había fallado una vez, borra la carpeta `.esphome\build\<nombre>` del proyecto: la configuración antigua queda
guardada y seguiría apuntando al compilador de la ruta larga.

## 1.3 Primer paso: averiguar la MAC del BMS

El ESP32 necesita la dirección Bluetooth (MAC) del BMS. Se descubre con un firmware mínimo que escanea y escribe en el
log los dispositivos cuyo nombre empieza por «JK» (`esp32/descubrir-mac.yaml`):

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
              ESP_LOGW("descubrir", "BMS encontrado: nombre=%s MAC=%s RSSI=%d",
                       n.c_str(), x.address_str().c_str(), x.get_rssi());
            }
```

Cierra la app del JK en el móvil, graba y mira el log (`esphome run descubrir-mac.yaml --device COMx`). Verás algo
como `nombre=JK_B2A20S20P MAC=AA:BB:CC:DD:EE:FF RSSI=-80`. Apunta la MAC.

## 1.4 El firmware definitivo

Crea un `secrets.yaml` junto al YAML (**no lo subas a git**; en el repo hay un `secrets.yaml.example`):

```yaml
wifi_ssid: "nombre_de_tu_wifi"        # debe ser de 2,4 GHz: el ESP32 clásico no ve 5 GHz
wifi_password: "clave_wifi"
bms_mac: "AA:BB:CC:DD:EE:FF"
mqtt_broker: "tu-broker.ejemplo.com"
mqtt_user: "usuario_del_esp32"
mqtt_password: "clave_mqtt"
ota_password: "una_clave_larga_para_actualizar_por_wifi"
```

Y `jk-bms.yaml` (el completo está en `esp32/jk-bms.yaml`):

```yaml
substitutions:
  name: jk-bms
  protocol_version: JK02_32S     # si los valores son absurdos, prueba JK02_24S

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

ota:                              # tras la primera grabación por USB, las siguientes son por wifi
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
  certificate_authority: |        # ¡OBLIGATORIO para usar TLS! ver 1.5
    -----BEGIN CERTIFICATE-----
    ...certificado raíz del broker...
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
    charging:       { name: "carga activada" }
    discharging:    { name: "descarga activada" }
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
    # ... hasta el número de celdas en serie de tu batería (16 en este montaje)

  # Diagnóstico del puente
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

Graba por USB y mira el log:

```bash
esphome compile jk-bms.yaml
esphome upload jk-bms.yaml --device COMx
esphome logs jk-bms.yaml --device COMx
```

Debes ver cómo conecta al wifi, al BMS (*Cell info frame received*) y al broker (*MQTT Connected*).

## 1.5 El detalle que casi nadie cuenta: ESPHome no usa TLS sin `certificate_authority`

En ESP-IDF, **si no indicas `certificate_authority`, ESPHome habla MQTT en texto plano** aunque pongas el puerto 8883.
El broker corta la conexión y el log muestra `MQTT_EVENT_ERROR … transport_read(): EOF`. Hay que pegar el certificado
raíz del broker. Para EMQX Serverless es *DigiCert Global Root G2*. Si usas otro broker, averigua su raíz. Puedes
comprobar que un certificado valida tu broker con Python antes de pegarlo:

```python
import ssl, socket
ctx = ssl.create_default_context(cadata=PEM_DEL_CERTIFICADO_RAIZ)
with socket.create_connection(("tu-broker.ejemplo.com", 8883), timeout=10) as s:
    with ctx.wrap_socket(s, server_hostname="tu-broker.ejemplo.com") as ss:
        print(ss.version())   # TLSv1.3 si valida
```

## 1.6 Dónde colocar el ESP32

La señal Bluetooth cae mucho con la distancia y con el metal de la caja de la batería. En este montaje:

| Ubicación | Bluetooth con el BMS (RSSI) | wifi |
|---|---|---|
| Lejos, probando en el PC | −80 dBm (se corta a ratos) | −79 dBm |
| Junto al BMS | **−43 dBm** (excelente) | −63 dBm (buena) |

Como referencia: mejor que −70 dBm es buena señal; peor que −80 dBm se corta. Los sensores `wifi dbm` y `bms rssi` del
YAML están para comprobar esto desde el PC una vez colocado. Alimenta el ESP32 con un cargador USB de 5 V y 1 A, con un
cable corto y no muy fino (un cable flojo provoca reinicios; `tiempo encendido` te lo dirá).

## 1.7 Lo que publica

Con `topic_prefix: solar/bateria` y `discovery: false`, cada sensor sale en
`solar/bateria/sensor/<nombre>/state` (por ejemplo `solar/bateria/sensor/soc/state`), los binarios en
`solar/bateria/binary_sensor/<nombre>/state` y el estado del propio ESP32 en `solar/bateria/status` (`online`/`offline`).
ESPHome publica con **retención**: el broker guarda el último valor de cada tema, y quien se suscribe lo recibe al
instante. Esto tiene una consecuencia: si el ESP32 o el BMS se caen, **los valores retenidos siguen ahí y parecen
recientes**. Cualquier cosa que lea el broker debería mirar `status` y `online` antes de fiarse (el colector del
capítulo 4 lo hace).

**El signo de la corriente:** en este BMS, positiva = cargando y negativa = descargando. Compruébalo con tu equipo
mirando la app oficial mientras cargas o descargas.

Siguiente: [2. Broker MQTT](02-broker-mqtt.md).
