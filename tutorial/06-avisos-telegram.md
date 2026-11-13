# 6. Avisos por Telegram

Un aviso en el móvil cuando la batería está baja, crítica o llena, o cuando el sistema deja de enviar datos. Lo manda el
servidor del capítulo 4, que está siempre encendido, así que funciona aunque la app esté cerrada.

## 6.1 Por qué Telegram y no otra cosa

Se probó primero **ntfy** (un servicio de notificaciones push sencillo): funciona, pero la app mantiene una conexión
abierta para recibir los avisos al instante y deja **una notificación fija** en la barra, que molesta. Un bot de Telegram
no deja nada fijo, es gratuito y permite repartir los avisos a otras personas añadiendo su chat.

## 6.2 Crear el bot

1. En Telegram busca **@BotFather** (el oficial, con el check azul) y pulsa *Iniciar*.
2. Escribe `/newbot`, ponle un nombre visible (por ejemplo «Solar») y un usuario que **acabe en `bot`** y sea único
   (por ejemplo `mi_solar_bot`).
3. BotFather te da el **token** (algo como `123456789:AAH…`). **Es un secreto:** quien lo tenga controla el bot.
4. Abre tu bot desde el enlace que te da y pulsa **Iniciar** (o escríbele «hola»). Sin eso el bot no puede escribirte.

## 6.3 Averiguar tu chat id

El servidor solo manda avisos a los **identificadores de chat autorizados**, para que nadie que encuentre el bot reciba
tus datos. Para conocer tu id, el servidor trae `api/telegram_chats.php`, que pregunta a Telegram quién ha escrito al
bot (con el token del cron):

```bash
curl -sS -H "X-Token: EL_TOKEN_DEL_CRON" https://tu-servidor.duckdns.org/api/telegram_chats.php
# {"ok":true,"chats":[{"id":"123456789","tipo":"private","nombre":"Tu Nombre"}],"autorizados":[]}
```

Pon `SetEnv SOLAR_TELEGRAM_TOKEN …` y `SetEnv SOLAR_TELEGRAM_CHAT 123456789` en el archivo de variables de Apache y
**recarga Apache** (`sudo systemctl reload apache2`; sin eso dice «Falta la variable de entorno» aunque esté escrita).

Para que reciba también otra persona de la familia: que abra el bot y pulse Iniciar, ejecuta otra vez el `curl` de arriba
y añade su id separado por comas: `SetEnv SOLAR_TELEGRAM_CHAT 123456789,987654321`.

Prueba de que llega:

```bash
curl -sS -H "X-Token: EL_TOKEN_DEL_CRON" https://tu-servidor.duckdns.org/api/probar_aviso.php
# {"ok":true,"chats":1}   y en el móvil: "✅ Prueba de Terrenico"
```

## 6.4 Cuándo avisa

| Aviso | Se envía cuando | Se «rearma» al |
|---|---|---|
| 🪫 Batería baja | SOC ≤ 20 % | subir a ≥ 25 % |
| 🚨 Batería crítica | SOC ≤ 10 % | subir a ≥ 15 % |
| 🔋 Batería completa | SOC ≥ 100 % | bajar a ≤ 95 % |
| 🔌 Sin datos del BMS | llevan más de 30 min sin datos | volver los datos (avisa también al recuperarse) |

Es el SOC del **BMS**, no el del inversor.

### Histéresis: un aviso, una vez

Si el porcentaje oscila entre 19 % y 21 %, no quieres veinte avisos. Cada aviso se envía **una sola vez** y no vuelve a
enviarse hasta que el nivel se aleja del umbral (la columna «se rearma»). El estado de «ya avisado» se guarda en la
tabla `alertas_estado`. Un detalle: si en una lectura cruzas a la vez el umbral de baja y el de crítica (por ejemplo,
de 25 % a 9 %), solo se envía el más grave.

El estado se marca **solo después de que Telegram confirme la entrega**: si Telegram falla, el aviso se reintenta en la
siguiente recogida en lugar de perderse.

### Probado antes de usarlo

La lógica de umbrales se probó con un Telegram falso y una secuencia de valores (50, 22, 19, 21, 26, 9, 12, 16, 99, 100,
100, 94, 96, 100): se envían exactamente baja (19), crítica (9) y completa (100, y otra vez tras bajar a 94). Los avisos
de recuperación van sin sonido (`disable_notification`).

## 6.5 Detalles de seguridad

- Los errores **nunca incluyen el token** del bot (la URL de la API lo lleva, así que se evita volcarla en mensajes).
- Si un chat falla y otro no, se entrega al que funciona. Falla solo si no llega a ninguno.

Siguiente: [7. Alexa](07-alexa.md).
