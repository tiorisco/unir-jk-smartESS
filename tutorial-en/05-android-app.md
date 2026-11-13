# 5. The Android app

A Kotlin app with Jetpack Compose that brings the three sources together: the live battery over MQTT, the inverter and the
history.

## 5.1 What it does

| Tab | What it shows |
|---|---|
| **Now** | Circular gauge with the BMS percentage, charging/discharging with watts and amps, an estimate of how long it will last, and an animated diagram (panels, house, battery, grid) whose dots move in the real direction of the energy |
| **Today** | kWh of production, consumption, battery charge and discharge, the day's power chart, the SOC chart and the PV peak |
| **History** | Bars of production and consumption per day, monthly totals and a list of the latest days |

In addition, a status line distinguishes "live", "data from N min ago", "the ESP32 is disconnected" and "the server has not
collected for…".

## 5.2 Where each piece of data comes from

```
Battery (BMS SOC)           ──►  direct MQTT (HiveMQ MQTT client, TLS)
Inverter, personal version  ──►  direct SmartESS (every 10 s while the app is visible)
Inverter, family version    ──►  your server (api/dia.php)
History and the day's SOC   ──►  your server (api/resumenes.php, api/dia.php)
```

The app **only queries while it is visible** (`repeatOnLifecycle(STARTED)`), so it uses no battery in the background.

## 5.3 Configuration without secrets in the code

Credentials live in `android/local.properties` (ignored by git) and Gradle turns them into `BuildConfig` fields at build
time. There is a template, `local.properties.example`:

```properties
sdk.dir=...
mqtt.host=xxxxxxxx.ala.eu-central-1.emqxsl.com
mqtt.user=app_user
mqtt.password=app_password
smartess.user=your_smartess_user
smartess.password=your_smartess_password
servidor.url=https://your-server.duckdns.org
servidor.token=read_only_token
familia.mqtt.user=family_user
familia.mqtt.password=family_password
```

> **What goes inside an APK.** Everything in `BuildConfig` **can be extracted from the APK** with a few tools. Hence:
> **read-only** MQTT users, a **read-only** server token, and the SmartESS password **never** in an app you are going to
> hand out.

## 5.4 The family version

A different Gradle variant is built (`./gradlew assembleFamilia`) that:

- Uses another application id (`….familia`), so it lives alongside yours.
- Is signed with its own keystore (`key.properties`, **outside git**, and keep a backup: without it the installs cannot
  be updated).
- Leaves the SmartESS credentials **empty**: the inverter and the history come from the server.
- Can carry an MQTT user of its own for the family so you can revoke it without touching yours.

### Really check what it carries

Configuring it is not enough: you verify by searching the strings inside the APK. An APK is a zip and the strings are in
the `.dex` files. This PowerShell script checks whether the APK contains a value (without printing it):

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Contains($apk, $text) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try { foreach ($e in $zip.Entries) { if ($e.Name -like '*.dex') {
      $ms = New-Object System.IO.MemoryStream; $st = $e.Open(); $st.CopyTo($ms); $st.Dispose()
      if ([Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray()).Contains($text)) { return $true } } }
    return $false } finally { $zip.Dispose() }
}
Contains "app-familia.apk" $mySmartessPassword     # must be False
```

That check caught a real mistake: the family MQTT user's password was **the same as the SmartESS one**, so it was going
inside the APK. It was detected before handing it out. More in [chapter 8](08-security-and-troubleshooting.md).

### Verify the signature

`keytool -printcert -jarfile` only reads old-style signatures; for the modern scheme use
`apksigner verify --verbose --print-certs` from `build-tools`.

## 5.5 Why a HiveMQ MQTT client and a hand-rolled HTTP client

`com.hivemq:hivemq-mqtt-client` was chosen (it supports TLS and automatic reconnection on Android) and
`HttpURLConnection` (no dependencies) for SmartESS and the server. JSON responses are read with `org.json`, which ships
with Android. The SmartESS client **never** calls `ctrlDevice`.

## 5.6 Building

Requirements: Android Studio (with its JDK), a filled-in `local.properties` and the project's own Gradle wrapper.

```bash
cd android
./gradlew assembleDebug        # personal version (carries your SmartESS account)
./gradlew assembleFamilia      # version to hand out
```

On Windows use `gradlew.bat` and point `JAVA_HOME` at Android Studio's JDK (`…\Android Studio\jbr`). The first build takes
several minutes (it downloads dependencies); later ones, half a minute. To install:
`adb install -r app\build\outputs\apk\debug\app-debug.apk`.

## 5.7 A warning about adb and the phone

If you automate the phone with `adb shell input tap/swipe`, **first check that there is no incoming call or notification
on top**: in this build a scroll gesture answered a call. For screenshots use `adb shell screencap -p` and `adb pull`, not
PowerShell redirections (they corrupt the PNG).

Next: [6. Telegram alerts](06-telegram-alerts.md).
