# 8. Seguridad y problemas típicos

## 8.1 Reglas de seguridad que se siguieron

1. **Todo es de solo lectura.** El BMS nunca recibe comandos de escritura, la API de SmartESS nunca se llama con
   `ctrlDevice`, y los usuarios de MQTT de las apps no pueden publicar (y se comprobó).
2. **Un usuario MQTT por cliente**, cada uno con los permisos justos, para poder revocar uno sin afectar a los demás.
3. **Dos tokens de servidor** (uno para disparar la recogida y otro de solo lectura). Cada uno rechaza el endpoint del otro.
4. **Los secretos nunca van al repositorio.** `local.properties`, `secrets.yaml`, `.env`, `config.js`, `key.properties` y los
   `.jks` están en `.gitignore`, y cada carpeta lleva su `*.example` con valores de relleno.
5. **Nada que vaya dentro de un APK debe ser valioso.** Todo lo de `BuildConfig` se puede extraer. Solo lectura, y
   la contraseña de tu cuenta del inversor, fuera.
6. **Contraseñas distintas y sin relación.** Una contraseña que *contiene* otra (la misma con dos caracteres añadidos)
   se considera la misma. En este montaje el usuario de la familia se creó con la contraseña de SmartESS; se detectó
   buscando las cadenas en el APK antes de repartirlo. Genera contraseñas aleatorias:
   `python -c "import secrets; print(secrets.token_urlsafe(12))"`.
7. **Revisa lo que subes a un repositorio**, sobre todo si es público. Busca en los archivos tus propios valores (contraseñas,
   tokens, wifi, MAC, IP, números de serie) y no los muestres al hacerlo. Un número de serie del inversor se había colado
   en los JSON de ejemplo.
8. **Haz copia de seguridad** de lo que no está en git: tus archivos de secretos y el keystore (`.jks`). Sin el keystore no
   se pueden actualizar las instalaciones de una APK ya repartida.

## 8.2 Tabla de problemas

| Síntoma | Causa | Solución |
|---|---|---|
| Windows no hace nada al enchufar el ESP32 | Cable solo de carga | Otro cable, de datos |
| «CP2102 USB to UART Bridge» con error, sin puerto COM | Falta el driver | Instalar el CP210x Universal Windows Driver desde el navegador |
| ESPHome falla con `bits/c++config.h: No such file` | Rutas de más de 260 caracteres | `ESPHOME_ESP_IDF_PREFIX=C:\espidf` y borrar `.esphome\build\<nombre>` |
| El PC no ve el BMS por Bluetooth: «BLE central role not supported» | Adaptador Bluetooth antiguo (sin BLE) | Dongle Bluetooth 4.0+ o saltarse la prueba y usar el ESP32 |
| `MQTT_EVENT_ERROR … transport_read(): EOF` | ESPHome en ESP-IDF sin `certificate_authority`: habla en claro | Pegar el certificado raíz del broker |
| MQTT: código de conexión **4** | Usuario o contraseña incorrectos | Revisar el usuario y la clave en el broker y en el archivo |
| ESP32: «No networks found» | Nombre del wifi mal escrito, o red de 5 GHz | Revisar el SSID; usar la banda de 2,4 GHz |
| Valores del BMS absurdos | Variante de protocolo equivocada | Cambiar `JK02_32S` ↔ `JK02_24S` |
| Bluetooth del BMS a −80 dBm, se corta | ESP32 lejos o tras metal | Colocarlo a uno o dos metros del BMS |
| La app oficial del JK no conecta | El BMS solo admite una conexión | Normal mientras el ESP32 esté conectado |
| SmartESS: `ERR_NOT_FOUND_USR` | Login con el correo | Usar el nombre de usuario |
| SmartESS: `ERR_PASSWORD_VERIF_FAIL` | Contraseña mal guardada (se había pegado dos veces) | Revisar su longitud en el `.env` |
| SmartESS: «ha cambiado el formato» | La nube cambió nombres de columna o campos | Buscar columnas sin distinguir mayúsculas, tolerar las que faltan |
| El SOC del inversor no cuadra con el BMS | El inversor estima por voltaje | Usar siempre el SOC del BMS |
| Datos de SmartESS iguales en varias consultas | La nube repite el dato durante minutos | Normal; mostrar «dato de hace N min» |
| Apache: «Falta la variable de entorno X» aunque esté en el archivo | No se recargó Apache | `sudo systemctl reload apache2` (y que no falle `configtest`) |
| El cron no guarda nada | Línea de cron con `X-Token: ` vacío | Rehacerla con el token real y verificar su longitud |
| La respuesta JSON del PHP no se interpreta | El archivo PHP tiene BOM | Guardarlo sin BOM |
| Alexa: «no tiene baterías» / «funciono sin batería» | La frase no llega a la skill | Usar «abre mi casa solar» por voz |
| Alexa: «no encuentra una skill llamada placas» | Ignora el artículo del nombre | Nombre sin artículo y con 2–3 palabras |
| Alexa: «no puedo abrir esa skill por texto» | Las skills propias no se invocan por texto | Probar por voz |

## 8.3 Limitaciones honestas

- **No es «en directo» para el inversor:** la nube de SmartESS va con minutos de retraso. La batería sí es casi inmediata.
- **La API de SmartESS no es oficial** y cambió durante el montaje; puede volver a hacerlo.
- **El histórico empieza el día que arranca el servidor** y tiene huecos si el servidor o el wifi se caen.
- La **estimación de duración** usa el SOC (un número entero) y la corriente instantánea: es orientativa.
- Un único BMS y un único inversor; para varios habría que parametrizar los temas y los dispositivos.

## 8.4 Ideas para seguir

- Conectar el inversor al BMS por RS485/CAN para que su SOC sea real (depende de tu modelo).
- Leer el dongle del inversor por la red local en lugar de por la nube, para tener datos en directo.
- Un panel web, además de la app.
- Ajustar los umbrales de aviso a tu uso o añadir un aviso de excedente solar.

## 8.5 Créditos

- [`syssi/esphome-jk-bms`](https://github.com/syssi/esphome-jk-bms): el componente de ESPHome para el JK BMS, y la
  referencia del protocolo.
- [`davidsmfreire/shinemonitor-api`](https://github.com/davidsmfreire/shinemonitor-api): la documentación comunitaria de la
  API de ShineMonitor/SmartESS.
- ESPHome, EMQX, HiveMQ MQTT client, Telegram y el SDK de Alexa (`ask-sdk-core`).
