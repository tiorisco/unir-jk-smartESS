# 7. Alexa

«Alexa, abre *mi casa solar*» y que te cuente cómo va la batería y las placas. Es una **skill propia** en modo
desarrollo: no hace falta publicarla ni certificarla, y funciona en los dispositivos de tu cuenta de Amazon.

## 7.1 Cómo funciona

```
Echo ─► Alexa ─► skill (Lambda en Alexa-hosted) ─► tu servidor: api/ahora.php (token de solo lectura)
                                                      └─► lee el broker MQTT en directo (~1 s)
```

La skill no guarda nada ni tiene credenciales del inversor: solo un token de **solo lectura** del servidor.

## 7.2 Crear la skill

1. Entra en la consola de desarrolladores de Alexa con la **misma cuenta de Amazon que tus Echo** y pulsa *Create Skill*.
2. Nombre a tu gusto; idioma **Español (España)**; tipo *Custom*; hosting **Alexa-hosted (Node.js)**, región Europa;
   plantilla *Start from scratch*.
3. **Build → Interaction Model → JSON Editor**: pega `alexa/modelo-es-ES.json`, *Save* y *Build Skill*.
4. **Code**: reemplaza `lambda/index.js` por `alexa/lambda/index.js`. Crea `lambda/config.js` con la URL y el token de
   solo lectura (plantilla en `config.example.js`), *Save* y *Deploy*.
5. **Test** (con *Development* activado): `abre mi casa solar`.

El código de la skill se prueba antes con peticiones de Alexa simuladas y un servidor falso:

```bash
cd alexa/lambda && npm install && node test/probar.js     # 18 escenarios
```

Cubre batería cargando, descargando, completa y baja, datos antiguos, placas de día y de noche, abrir, ayuda, parar y
errores del servidor (500 y 401).

## 7.3 Lo que se aprendió por el camino (esto es lo valioso)

Funcionaba en el simulador de la consola pero **no** en un dispositivo real. Lo que había detrás:

1. **Las skills propias no se pueden invocar escribiendo a Alexa** en la app: contesta «no puedo abrir esa skill por texto».
   Hay que probar por **voz**: con el micrófono de la app del teléfono y auriculares, para no despertar a nadie.
2. **«Alexa no tiene baterías» / «funciono sin batería»** es la respuesta normal a «cómo están las baterías» cuando no
   llega a tu skill: Alexa lo toma como una pregunta sobre su propia batería. No es un fallo de la skill.
3. **El nombre de invocación manda:**
   - Una sola palabra y un nombre inventado funcionan mal. «Terrenico» lo entendía mal con frecuencia.
   - **Alexa ignora el artículo inicial** del nombre: con «las placas» buscó una skill llamada «placas» y no la encontró.
     No empieces por «el», «la», «las», «los».
   - Evita expresiones comunes como «sistema solar», que chocan con skills de astronomía y con las respuestas de Alexa.
   - Funcionó con **tres palabras corrientes y personales: «mi casa solar»** (el posesivo «mi» no se descarta).
4. **«Abre X» es mucho más fiable que «pregunta a X cómo…»**. Por eso la skill, al abrirse, **responde de golpe**
   (batería y placas) y se cierra, en lugar de esperar una segunda pregunta. En la app del teléfono cada pulsación del
   micrófono es una conversación nueva, y la segunda pregunta caía fuera de la skill.
5. **Los dispositivos deben tener el idioma de la skill.** Si la skill es «Español (España)» y el Echo está en español de
   México o de Estados Unidos, no la verá. Se puede añadir otro idioma a la skill (mismo modelo, otra ficha de idioma).
6. **La latencia importa:** Alexa espera unos 8 segundos. Una primera versión del servidor tardaba de 3 a 5 s en leer
   todos los temas MQTT, demasiado justo; leyendo solo los 7 necesarios y cortando en cuanto llegan bajó a ~1,2 s.
7. Una skill en modo desarrollo solo funciona en los dispositivos de **tu** cuenta, no en los de familiares con otra.

## 7.4 La rutina para decir solo «cómo están las baterías»

En la app de Alexa: *Más → Rutinas → +*. **Cuando:** Voz → `cómo están las baterías`. **Acción:** Personalizado →
`abre mi casa solar`. La rutina lanza la frase que sí funciona.

## 7.5 Qué responde

> «La batería está al 61 por ciento y se está descargando a unos 290 vatios. A este ritmo durará aproximadamente 19 horas.
> Las placas no están produciendo y la casa consume 269 vatios.»

Si el dato viene de la última muestra guardada (el broker no respondió) lo dice: «Ojo, el dato de la batería es de hace
N minutos». Si la batería baja del 20 % mientras descarga, añade que conviene reducir el consumo.

Siguiente: [8. Seguridad y problemas típicos](08-seguridad-y-problemas.md).
