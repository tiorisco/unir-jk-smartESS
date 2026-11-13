# 5. La app Android

Una app en Kotlin con Jetpack Compose que junta las tres fuentes: la batería en directo por MQTT, el inversor y el
histórico.

## 5.1 Qué hace

| Pestaña | Qué muestra |
|---|---|
| **Ahora** | Medidor circular del porcentaje del BMS, cargando/descargando con vatios y amperios, estimación de cuánto durará, y un diagrama animado (placas, casa, batería, red) cuyos puntos se mueven en el sentido real de la energía |
| **Hoy** | kWh de producción, consumo, carga y descarga de la batería, gráfica de potencia del día, gráfica del SOC y pico de placas |
| **Histórico** | Barras de producción y consumo por día, totales por meses y lista de los últimos días |

Además, una línea de estado distingue «en directo», «dato de hace N min», «el ESP32 está desconectado» y «el servidor no
recoge desde hace…».

## 5.2 De dónde sale cada dato

```
Batería (SOC del BMS)  ──►  MQTT directo (HiveMQ MQTT client, TLS)
Inversor, versión personal ──►  SmartESS directo (cada 10 s mientras la app está visible)
Inversor, versión familia  ──►  tu servidor (api/dia.php)
Histórico y SOC del día    ──►  tu servidor (api/resumenes.php, api/dia.php)
```

La app **solo consulta mientras está visible** (`repeatOnLifecycle(STARTED)`), así no gasta batería en segundo plano.

## 5.3 Configuración sin secretos en el código

Las credenciales viven en `android/local.properties` (ignorado por git) y Gradle las convierte en campos de `BuildConfig`
al compilar. Hay una plantilla, `local.properties.example`:

```properties
sdk.dir=...
mqtt.host=xxxxxxxx.ala.eu-central-1.emqxsl.com
mqtt.user=usuario_app
mqtt.password=clave_app
smartess.user=tu_usuario_smartess
smartess.password=tu_clave_smartess
servidor.url=https://tu-servidor.duckdns.org
servidor.token=token_de_lectura
familia.mqtt.user=usuario_familia
familia.mqtt.password=clave_familia
```

> **Qué va dentro de un APK.** Todo lo que está en `BuildConfig` **se puede extraer del APK** con unas pocas
> herramientas. Por eso: usuarios MQTT **solo de lectura**, un token de servidor **solo de lectura**, y la contraseña
> de SmartESS **nunca** en una app que vayas a repartir.

## 5.4 La versión para la familia

Se compila una variante de Gradle distinta (`./gradlew assembleFamilia`) que:

- Usa otro identificador de aplicación (`….familia`), así convive con la tuya.
- Se firma con un keystore propio (`key.properties`, **fuera de git**, y haz copia de seguridad: sin ella no se pueden
  actualizar las instalaciones).
- Pone **vacías** las credenciales de SmartESS: el inversor y el histórico salen del servidor.
- Puede llevar un usuario MQTT propio de la familia para poder revocarlo sin tocar el tuyo.

### Comprobar de verdad qué lleva dentro

No basta con configurarlo: se verifica buscando las cadenas dentro del APK. Un APK es un zip y las cadenas están en los
archivos `.dex`. Este script de PowerShell comprueba si el APK contiene un valor (sin mostrarlo):

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Contiene($apk, $texto) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try { foreach ($e in $zip.Entries) { if ($e.Name -like '*.dex') {
      $ms = New-Object System.IO.MemoryStream; $st = $e.Open(); $st.CopyTo($ms); $st.Dispose()
      if ([Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray()).Contains($texto)) { return $true } } }
    return $false } finally { $zip.Dispose() }
}
Contiene "app-familia.apk" $miContrasenaSmartess     # debe ser False
```

Esa comprobación cazó un fallo real: la contraseña del usuario MQTT de la familia era **la misma que la de SmartESS**, y
por tanto iba dentro del APK. Se detectó antes de repartirla. Más en el [capítulo 8](08-seguridad-y-problemas.md).

### Verificar la firma

`keytool -printcert -jarfile` solo lee firmas antiguas; para el esquema moderno usa `apksigner verify --verbose --print-certs`
de `build-tools`.

## 5.5 Por qué un HiveMQ MQTT client y un cliente HTTP propio

Se eligieron `com.hivemq:hivemq-mqtt-client` (soporta TLS y reconexión automática en Android) y `HttpURLConnection`
(sin dependencias) para SmartESS y el servidor. Las respuestas JSON se leen con `org.json`, incluido en Android. El
cliente de SmartESS **nunca** llama a `ctrlDevice`.

## 5.6 Compilar

Requisitos: Android Studio (con su JDK), `local.properties` rellenado y el wrapper de Gradle del propio proyecto.

```bash
cd android
./gradlew assembleDebug        # versión personal (lleva tu cuenta de SmartESS)
./gradlew assembleFamilia      # versión para repartir
```

En Windows usa `gradlew.bat` y apunta `JAVA_HOME` al JDK de Android Studio (`…\Android Studio\jbr`). La primera
compilación tarda varios minutos (descarga dependencias); las siguientes, medio minuto. Para instalar:
`adb install -r app\build\outputs\apk\debug\app-debug.apk`.

## 5.7 Una advertencia sobre adb y el móvil

Si automatizas el móvil con `adb shell input tap/swipe`, **comprueba antes que no hay una llamada entrante ni una
notificación encima**: en este montaje un gesto de desplazamiento contestó una llamada. Para capturas de pantalla usa
`adb shell screencap -p` y `adb pull`, no redirecciones de PowerShell (corrompen el PNG).

Siguiente: [6. Avisos por Telegram](06-avisos-telegram.md).
