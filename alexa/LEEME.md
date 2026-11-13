# Skill de Alexa "Terrenico"

NOMBRE DE INVOCACIÓN: con `terrenico` (una palabra) "abre Terrenico" funcionó por voz pero "pregunta a Terrenico…" no.
`las placas` NO sirve: Alexa ignora el artículo inicial y buscó una skill llamada "placas" ("no encuentro una skill
llamada placas"). Los nombres no deben empezar por artículo. `terrenico solar` se descartó porque Alexa entiende mal
"Terrenico". `sistema solar` se descartó (expresión común, choca con skills de astronomía). Elegido el 13-11-2026:
**`mi casa solar`** ("abre mi casa solar"). Pendiente de confirmar por voz; ver `modelo-es-ES.json`.
Tras cambiar el nombre hay que pulsar Save Model y Build Skill, y las frases de abajo cambian "Terrenico" por el nombre nuevo.

Frase principal (probada por voz el 13-11-2026 con el nombre antiguo): **"Alexa, abre Terrenico"** →
contesta batería y placas de una vez y se cierra. La forma "pregunta a Terrenico cómo están las baterías" NO enlazó
con la skill en la app del teléfono (Alexa contestó "funciono sin batería"): reconocía bien el texto pero no llamaba a
la skill. Escribir a Alexa en la app tampoco invoca skills propias.

Otras frases (es-ES):

- "Alexa, pregunta a Terrenico cómo están las baterías" → "La batería está al 62 por ciento y se está descargando a unos 219 vatios. A este ritmo durará aproximadamente 5 horas."
- "Alexa, pregunta a Terrenico cuánto producen las placas" → "Las placas producen 2,2 kilovatios y la casa consume 237 vatios."
- "Alexa, abre Terrenico" → estado de la batería.

Solo LEE. Consulta `https://tu-servidor.duckdns.org/api/ahora.php` con el token de lectura
(`SOLAR_APP_TOKEN`, el mismo que lleva la app).

## Archivos

- `modelo-es-ES.json`: modelo de voz (frases y nombre de invocación `terrenico`).
- `lambda/index.js`: código de la skill. `lambda/config.example.js`: plantilla del token.
- `lambda/test/probar.js`: 17 pruebas con peticiones de Alexa simuladas (`cd alexa/lambda && npm install && node test/probar.js`).

## Instalación (consola de desarrolladores de Alexa)

1. https://developer.amazon.com/alexa/console/ask con la MISMA cuenta de Amazon que los Echo → Create Skill.
2. Nombre `Terrenico`; idioma principal **Español (ES)**; tipo Other → **Custom**; hosting **Alexa-hosted (Node.js)**,
   región Europa; plantilla Start from scratch.
3. Build → Interaction Model → JSON Editor: pega `modelo-es-ES.json` → Save → Build Model.
   (Si rechaza el nombre de una palabra, cambia `invocationName` por dos, p. ej. `casa terrenico`.)
4. Code: reemplaza `index.js` por el de `lambda/index.js`; crea `config.js` copiando `config.example.js` con el token
   real; Save → Deploy.
5. Test: activa Development y prueba "pregunta a terrenico cómo están las baterías". Después, en un Echo.

## Opcional: decir solo "cómo están las baterías"

App de Alexa → Más → Rutinas → +. Cuando: Voz → "cómo están las baterías". Acción: Personalizado →
"abre terrenico" (esta es la que funciona; "pregunta a terrenico…" no enlazaba).

## Notas

- El token va en `config.js` dentro de tu cuenta de Alexa-hosted (no se sube a git). Es de solo lectura.
  Si cambias `SOLAR_APP_TOKEN` en el servidor hay que cambiarlo también aquí y en la app.
- Una skill en modo Development solo funciona en los dispositivos de la cuenta de desarrollador.
