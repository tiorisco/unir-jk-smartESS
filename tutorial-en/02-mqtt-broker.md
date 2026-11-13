# 2. MQTT broker: get the data onto the internet safely

The ESP32 is at home, behind the router, and the phone can be anywhere. For them to find each other without opening ports
on the router, both connect **outwards** to an **MQTT broker** on the internet: the ESP32 publishes and the others subscribe.

## 2.1 Why a cloud broker and not an open port at home

- No need to touch the router or depend on a fixed IP.
- All connections are outgoing and encrypted (TLS, port 8883).
- A free plan is plenty for this use: a handful of clients that send or receive a message every few seconds.

**EMQX Cloud Serverless** was used here. Any other broker with TLS and per-topic user permissions works (HiveMQ Cloud, your
own Mosquitto…). One warning: on HiveMQ's site it is easy to end up in the *self-managed* product flow ("How to deploy
your broker", with local downloads and installs) instead of the cloud service.

## 2.2 Create the broker on EMQX

1. Create an EMQX Cloud account and a **Serverless** deployment (free plan, no card).
2. Open the deployment (the *Deployment Overview* page) and scroll down to **MQTT Connection Information**: there is the
   host (something like `xxxxxxxx.ala.eu-central-1.emqxsl.com`) and the **MQTTS/TLS port: 8883**. The *Connection Guide*
   side menu shows it too.
3. Free-plan limits at the time of writing: 1 million session minutes a month and 1 GB of traffic, with a $0 spend limit
   (you are not charged if you exceed it). An ESP32 and a phone connected 24 hours use about 90,000 minutes a month. Check
   their page.

## 2.3 Users: one per client, all read-only except the ESP32

This is the most important security point of the chapter. Each client has its own user with just the permissions it needs:

| User | Who uses it | Permissions |
|---|---|---|
| `esp32` | The ESP32 | Publish on `solar/bateria/#` |
| `phone` | Your app | **Subscribe only** to `solar/bateria/#` |
| `family` | The family APK | **Subscribe only** to `solar/bateria/#` |
| `collector` | The server from chapter 4 | **Subscribe only** to `solar/bateria/#` |

Why so many? Because **the app's credentials live inside the APK** and anyone who opens it will see them. If that user
can only read, the worst that can happen is someone reads your battery data, and you can revoke it without affecting the
others.

### Create the users

*Access Control → Authentication → Users → Add*: name and password. **A different, random password for each one**, and
different from any other account of yours (see [chapter 8](08-security-and-troubleshooting.md)).

### Give them permissions (authorization)

*Access Control → Authorization → Add*. For a read-only user, two rules:

| Topic | Action | Permission |
|---|---|---|
| `solar/bateria/#` | Subscribe | Allow |
| `#` | Publish | **Deny** |

**Careful:** the orange notice on that page usually says the mode is a blacklist: clients **without rules** have free
access and only what you explicitly deny is blocked. That is why the second rule (deny publish) is what really prevents
writing. For the ESP32's user do the opposite: *Publish → Allow* on `solar/bateria/#`.

## 2.4 Really check that a user is read-only

Do not trust that you configured it: test it. This script (Python with `paho-mqtt`) connects as the user, subscribes,
tries to publish a message and checks whether it comes back (if the broker had accepted it, the subscriber itself would
receive it):

```python
import ssl, time, paho.mqtt.client as mqtt

HOST, USER, PASSWORD = "your-broker.example.com", "phone", "the_password"
TOPIC = "solar/bateria/_acl_test"
st = {"rc": None, "sensors": 0, "echo": 0}

c = mqtt.Client(client_id="verification")
c.username_pw_set(USER, PASSWORD)
c.tls_set(cert_reqs=ssl.CERT_REQUIRED)
c.on_connect = lambda cl, u, f, rc: (st.__setitem__("rc", rc), cl.subscribe("solar/bateria/#", qos=1))
def on_msg(cl, u, m):
    st["echo" if m.topic == TOPIC else "sensors"] += 1
c.on_message = on_msg

c.connect(HOST, 8883, 30); c.loop_start(); time.sleep(6)
print("connection (0 = OK; 4 = wrong user or password):", st["rc"])
print("sensor messages received:", st["sensors"])
c.publish(TOPIC, "test", qos=1); time.sleep(6)
print("publish blocked (this is what you want):", st["echo"] == 0)
c.loop_stop(); c.disconnect()
```

Expected result for a read-only user: connection 0, dozens of sensor messages received and publishing blocked. A
connection code of **4** means wrong user or password.

## 2.5 Watching the data while you test

With any MQTT client (for example `mosquitto_sub` or MQTT Explorer) subscribed to `solar/bateria/#` you will see
everything the ESP32 publishes, including `solar/bateria/status`.

Next: [3. The inverter: the SmartESS API](03-inverter-smartess.md).
