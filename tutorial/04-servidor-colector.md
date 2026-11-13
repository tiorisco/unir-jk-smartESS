# 4. El servidor: colector, histórico y API

Un pequeño servidor siempre encendido hace cuatro cosas: **recoge** los datos cada 5 minutos, **los guarda**, **los
sirve** a la app y a Alexa con una API de solo lectura, y **avisa** por Telegram. Aquí se hizo en PHP + MySQL sobre
Apache porque ya existía un servidor así, pero vale cualquier cosa que ejecute una tarea cada 5 minutos: una
Raspberry Pi en casa funciona igual.

## 4.1 Por qué hace falta

- La nube del inversor **solo guarda el día actual** (capítulo 3). Sin este servidor no hay histórico.
- El broker MQTT guarda **solo el último valor** de cada tema. Tampoco hay histórico de la batería.
- Alexa y la app de la familia necesitan algo estable al que preguntar, sin credenciales del inversor.

## 4.2 Qué contiene

```
servidor/
  api/recoger.php        # lo llama el cron: lee SmartESS y el BMS y guarda
  api/dia.php            # serie de un día (inversor + SOC del BMS)
  api/resumenes.php      # resumen diario de energía + estado del colector
  api/ahora.php          # estado actual rápido, para Alexa
  api/probar_aviso.php   # envía un aviso de prueba
  api/telegram_chats.php # lista quién ha escrito al bot (capítulo 6)
  core/config.php        # variables de entorno y utilidades
  core/db.php            # conexión PDO
  core/smartess.php      # cliente de SmartESS (solo lectura)
  core/mqtt.php          # lector MQTT mínimo por TLS (sin dependencias)
  core/resumen.php       # cálculo de energías (regla del trapecio)
  core/alertas.php       # umbrales y avisos
  database/esquema.sql   # tablas
```

## 4.3 Las tablas

| Tabla | Contenido |
|---|---|
| `inversor_puntos` | Una fila por muestra de SmartESS: placas, casa, batería y red en W |
| `bms_muestras` | Una fila cada 5 min con SOC, voltaje, corriente, potencia, celdas |
| `resumen_dia` | Energía de cada día: kWh de placas, casa, carga y descarga, y SOC mínimo y máximo |
| `estado_recogida` | Última recogida correcta de cada fuente (para saber si el colector está vivo) |
| `alertas_estado` | Qué avisos ya se enviaron (para no repetirlos) |

Las horas son **locales y sin zona** (se fija `Europe/Madrid`, cámbiala por la tuya).

## 4.4 La recogida

`api/recoger.php` hace lo siguiente, y cada fuente es independiente: si SmartESS falla, el BMS se guarda igualmente.

1. Comprueba la cabecera `X-Token` y toma un **bloqueo** de MySQL (`GET_LOCK`) para que no haya dos recogidas a la vez.
2. **Inversor:** pide la serie del día a SmartESS y la guarda con `INSERT … ON DUPLICATE KEY UPDATE`. Así ejecutarlo
   dos veces seguidas **no duplica ni pierde nada**.
3. **Batería:** se conecta al broker, lee los últimos valores y guarda una muestra. Si `status` no es `online` o el BMS
   no está `ON`, **no guarda nada** (serían valores retenidos viejos que parecen nuevos).
4. Recalcula el resumen de hoy y evalúa los avisos.

### Un cliente MQTT de 100 líneas

Para no depender de Composer, `core/mqtt.php` implementa lo mínimo de MQTT 3.1.1 por TLS: CONNECT, SUBSCRIBE y leer
PUBLISH. Como el ESP32 publica con retención, al suscribirse llegan todos los últimos valores de golpe. Dos detalles
que importan para la velocidad:

- Se suscribe solo a los temas que necesita y **corta en cuanto han llegado todos**, en lugar de esperar un silencio.
  Eso bajó la consulta para Alexa de 3–5 s a ~1,2 s.
- Hay un modo completo (todos los temas) para el colector y uno rápido (7 temas) para Alexa.

### Las dos fuentes y sus fallos

Aprendido a base de tropiezos: **no fiarse de que el dato exista**. Cada columna que pueda faltar debe tolerarse, cada
llamada a la nube puede devolver un formato nuevo (capítulo 3) y los valores retenidos de MQTT pueden ser viejos.

## 4.5 La API y sus tokens

Hay **dos tokens distintos**, ambos en cabecera `X-Token`:

| Token | Para qué | Quién lo tiene |
|---|---|---|
| `SOLAR_CRON_TOKEN` | Disparar la recogida y mandar avisos de prueba | Solo el cron del servidor |
| `SOLAR_APP_TOKEN` | **Solo lectura**: `dia`, `resumenes`, `ahora` | La app, la APK de la familia y la skill de Alexa |

Ninguno sirve para el otro endpoint: probado que el token de la app da `401` en `recoger.php` y viceversa. Se comparan
con `hash_equals`. Si algún día un móvil se pierde, cambias `SOLAR_APP_TOKEN` y recompilas.

Los endpoints de datos devuelven JSON. Por ejemplo `ahora.php`:

```json
{"hora":"2026-11-13 05:20:48",
 "bateria":{"soc":62,"voltaje":52.5,"corriente":-6.4,"potencia":-337,
            "en_vivo":true,"hace_minutos":0,
            "estimacion":{"tipo":"vacia","horas":20.1}},
 "inversor":{"pv_w":7,"casa_w":246,"bateria_w":-255,"hace_minutos":1}}
```

`estimacion` se calcula con la capacidad restante y el SOC: `capacidad_total = restante / (SOC/100)`; si carga, tiempo hasta
llenar; si descarga, tiempo hasta vaciar. Si el broker no responde, `ahora.php` devuelve la última muestra guardada con
`"en_vivo": false` y cuántos minutos tiene.

## 4.6 Secretos: variables de entorno de Apache

Las credenciales no van en el código ni en un `.env` (esta app no lo lee): se definen con `SetEnv` en un archivo de
Apache, por ejemplo `/etc/apache2/conf-available/mi-app-env.conf`:

```apache
SetEnv SOLAR_DB_HOST localhost
SetEnv SOLAR_DB_NAME solar
SetEnv SOLAR_DB_USER usuario_bd
SetEnv SOLAR_DB_PASS CAMBIAR
SetEnv SOLAR_SMARTESS_USER CAMBIAR
SetEnv SOLAR_SMARTESS_PASS CAMBIAR
SetEnv SOLAR_MQTT_HOST tu-broker.ejemplo.com
SetEnv SOLAR_MQTT_USER colector
SetEnv SOLAR_MQTT_PASS CAMBIAR
SetEnv SOLAR_CRON_TOKEN CAMBIAR     # openssl rand -hex 24
SetEnv SOLAR_APP_TOKEN CAMBIAR      # otro distinto
SetEnv SOLAR_TELEGRAM_TOKEN CAMBIAR
SetEnv SOLAR_TELEGRAM_CHAT CAMBIAR
```

```bash
sudo a2enconf mi-app-env
sudo chmod 640 /etc/apache2/conf-available/mi-app-env.conf
sudo systemctl reload apache2        # ¡IMPRESCINDIBLE tras cada cambio!
```

> **El tropiezo clásico:** si editas ese archivo y **no recargas Apache**, el código sigue sin ver las variables nuevas
> y dice «Falta la variable de entorno» aunque la veas en el archivo. `apache2ctl configtest` **no** recarga nada.

## 4.7 El cron

Como `SetEnv` solo existe dentro de Apache, el cron **no ejecuta PHP directamente**: llama a la web con el token.

```bash
# /etc/cron.d/solar  (chmod 600, propietario root)
*/5 * * * * www-data curl -fsS -m 60 -H "X-Token: EL_TOKEN_DEL_CRON" https://tu-servidor.duckdns.org/api/recoger.php >/dev/null 2>&1
```

Al crearlo, comprueba que el token no queda vacío: una línea con `X-Token: ` en blanco falla en silencio cada 5 minutos.

## 4.8 Despliegue, paso a paso

1. Dominio y HTTPS: un subdominio (DuckDNS sirve) apuntando al servidor, un sitio de Apache con `AllowOverride All` y
   `Options -Indexes`, y `certbot --apache -d tu-servidor.duckdns.org`.
2. Base de datos: `CREATE DATABASE solar CHARACTER SET utf8mb4;` y `mysql … < database/esquema.sql`.
3. Código: empaquétalo en un `.tar.gz` **con versión en el nombre** (`solar_v1.tar.gz`, `solar_v2…`) y extráelo en el docroot
   con `--no-same-owner`. El nombre versionado evita que un cliente FTP se salte en silencio un archivo «que ya existe».
4. Variables de entorno (4.6) y **recargar Apache**.
5. Prueba: `curl -H "X-Token: …" https://…/api/recoger.php` debe devolver `"ok":true`. Después programa el cron.
6. Verifica desde fuera: `core/` debe dar `403` (lleva un `.htaccess` con `Require all denied`), el `index.php` un `404`,
   y cualquier endpoint sin token un `401`.

## 4.9 Probar en local antes de subir

Una ventaja de que sea PHP sin dependencias: se prueba en tu PC con el servidor integrado (`php -S`) y una base MySQL
temporal, usando los servicios reales. Así se cazaron varios errores antes de desplegar. Dos trampas de Windows:

- Un archivo PHP guardado **con BOM** (lo hace `Set-Content -Encoding utf8` en PowerShell 5) imprime un carácter
  invisible antes de `<?php` y **rompe el JSON** de la respuesta. Guarda sin BOM.
- PHP 8.1 añadió el tipo `never`; si tu servidor tiene 8.0, no lo uses.

## 4.10 Cuánto cuesta

Casi nada: una consulta cada 5 minutos, unas 300 filas al día. En un año son decenas de miles de filas pequeñas.

Siguiente: [5. La app Android](05-app-android.md).
