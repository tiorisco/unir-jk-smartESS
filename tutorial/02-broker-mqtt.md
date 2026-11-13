# 2. Broker MQTT: llevar los datos a internet de forma segura

El ESP32 está en casa, detrás del router, y el móvil puede estar en cualquier parte. Para que se encuentren sin abrir
puertos en el router, los dos se conectan **hacia fuera** a un **broker MQTT** en internet: el ESP32 publica y los
demás se suscriben.

## 2.1 Por qué un broker en la nube y no abrir un puerto en casa

- No hay que tocar el router ni depender de una IP fija.
- Todas las conexiones son salientes y van cifradas (TLS, puerto 8883).
- Un plan gratuito sobra para este uso: unos pocos clientes que mandan o reciben un mensaje cada pocos segundos.

Aquí se usó **EMQX Cloud Serverless**. Sirve cualquier otro broker con TLS y usuarios con permisos por tema
(HiveMQ Cloud, un Mosquitto propio…). Un aviso: en la web de HiveMQ es fácil acabar en el flujo del producto
*autoalojado* («How to deploy your broker», con descargas e instalación local) en lugar de en el servicio en la nube.

## 2.2 Crear el broker en EMQX

1. Crea una cuenta en EMQX Cloud y un despliegue **Serverless** (plan gratuito, sin tarjeta).
2. Entra en el despliegue (la página *Deployment Overview*) y baja hasta **MQTT Connection Information**: ahí está el host
   (algo como `xxxxxxxx.ala.eu-central-1.emqxsl.com`) y el puerto **MQTTS/TLS: 8883**. El menú lateral
   *Connection Guide* también lo muestra.
3. Límites del plan gratuito en el momento de escribir esto: 1 millón de minutos de sesión al mes y 1 GB de tráfico, con
   un límite de gasto de 0 $ (no te cobran si te pasas). Un ESP32 y un móvil conectados 24 horas gastan unos 90.000
   minutos al mes. Compruébalo en su página.

## 2.3 Usuarios: uno por cliente, todos de solo lectura menos el ESP32

Este es el punto de seguridad más importante del capítulo. Cada cliente tiene su usuario con los permisos justos:

| Usuario | Quién lo usa | Permisos |
|---|---|---|
| `esp32` | El ESP32 | Publicar en `solar/bateria/#` |
| `movil` | Tu app | **Solo suscribirse** a `solar/bateria/#` |
| `familia` | La APK de la familia | **Solo suscribirse** a `solar/bateria/#` |
| `colector` | El servidor del capítulo 4 | **Solo suscribirse** a `solar/bateria/#` |

¿Por qué tantos? Porque **las credenciales de la app van dentro del APK** y cualquiera que lo abra por dentro las
verá. Si ese usuario solo puede leer, lo peor que puede pasar es que alguien lea los datos de tu batería, y puedes
revocarlo sin afectar a los demás.

### Crear los usuarios

*Access Control → Authentication → Users → Add*: nombre y contraseña. **Una contraseña distinta y aleatoria para cada
uno** y distinta de cualquier otra cuenta tuya (ver [el capítulo 8](08-seguridad-y-problemas.md)).

### Darles permisos (autorización)

*Access Control → Authorization → Add*. Para un usuario de solo lectura, dos reglas:

| Topic | Action | Permission |
|---|---|---|
| `solar/bateria/#` | Subscribe | Allow |
| `#` | Publish | **Deny** |

**Atención:** el aviso naranja de esa pantalla suele decir que el modo es de lista negra: los clientes **sin reglas**
tienen acceso libre y solo se bloquea lo que niegues expresamente. Por eso la segunda regla (denegar publicar) es la
que de verdad impide escribir. Para el usuario del ESP32 haz lo contrario: *Publish → Allow* en `solar/bateria/#`.

## 2.4 Comprobar de verdad que un usuario es de solo lectura

No te fíes de haberlo configurado: pruébalo. Este script (Python con `paho-mqtt`) se conecta como el usuario, se
suscribe, intenta publicar un mensaje y mira si le llega de vuelta (si el broker lo hubiera aceptado, el propio
suscriptor lo recibiría):

```python
import ssl, time, paho.mqtt.client as mqtt

HOST, USER, CLAVE = "tu-broker.ejemplo.com", "movil", "la_clave"
TOPIC = "solar/bateria/_prueba_acl"
est = {"rc": None, "sensores": 0, "eco": 0}

c = mqtt.Client(client_id="verificacion")
c.username_pw_set(USER, CLAVE)
c.tls_set(cert_reqs=ssl.CERT_REQUIRED)
c.on_connect = lambda cl, u, f, rc: (est.__setitem__("rc", rc), cl.subscribe("solar/bateria/#", qos=1))
def on_msg(cl, u, m):
    est["eco" if m.topic == TOPIC else "sensores"] += 1
c.on_message = on_msg

c.connect(HOST, 8883, 30); c.loop_start(); time.sleep(6)
print("conexión (0 = OK; 4 = usuario o clave incorrectos):", est["rc"])
print("mensajes de sensores recibidos:", est["sensores"])
c.publish(TOPIC, "prueba", qos=1); time.sleep(6)
print("publicar bloqueado (esto es lo correcto):", est["eco"] == 0)
c.loop_stop(); c.disconnect()
```

Resultado esperado para un usuario de solo lectura: conexión 0, decenas de mensajes de sensores recibidos y publicación
bloqueada. Un código de conexión **4** significa usuario o contraseña incorrectos.

## 2.5 Cómo ver los datos mientras pruebas

Con cualquier cliente MQTT (por ejemplo `mosquitto_sub` o MQTT Explorer) suscrito a `solar/bateria/#` verás pasar todo lo
que publica el ESP32, incluido `solar/bateria/status`.

Siguiente: [3. El inversor: la API de SmartESS](03-inversor-smartess.md).
