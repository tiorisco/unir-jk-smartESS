# 7. Alexa

"Alexa, open *my solar home*" and have it tell you how the battery and the panels are doing. It is a **custom skill** in
development mode: it does not need publishing or certification, and it works on the devices of your Amazon account.

## 7.1 How it works

```
Echo ─► Alexa ─► skill (Lambda on Alexa-hosted) ─► your server: api/ahora.php (read-only token)
                                                      └─► reads the MQTT broker live (~1 s)
```

The skill stores nothing and has no inverter credentials: only a **read-only** token for the server.

## 7.2 Create the skill

1. Open the Alexa developer console with the **same Amazon account as your Echo** and press *Create Skill*.
2. Any name; language **Spanish (Spain)** (or yours: see the note in 7.3); type *Custom*; hosting **Alexa-hosted
   (Node.js)**, Europe region; template *Start from scratch*.
3. **Build → Interaction Model → JSON Editor**: paste `alexa/modelo-es-ES.json`, *Save* and *Build Skill*.
4. **Code**: replace `lambda/index.js` with `alexa/lambda/index.js`. Create `lambda/config.js` with the URL and the
   read-only token (template in `config.example.js`), *Save* and *Deploy*.
5. **Test** (with *Development* enabled): `abre mi casa solar`.

The skill code is tested beforehand with simulated Alexa requests and a fake server:

```bash
cd alexa/lambda && npm install && node test/probar.js     # 18 scenarios
```

It covers battery charging, discharging, full and low, old data, panels by day and night, opening, help, stopping and
server errors (500 and 401).

> **Language.** The skill and its model are in Spanish because that is the author's Alexa language. For English, create the
> skill in English (US/UK), translate the sample utterances and the sentences in `alexa/lambda/index.js` (`fraseBateria`,
> `fraseSolar` and the handlers), and keep the same server calls.

## 7.3 What was learned on the way (this is the valuable part)

It worked in the console simulator but **not** on a real device. What was behind it:

1. **Custom skills cannot be invoked by typing to Alexa** in the app: it answers "I can't open that skill by text". You
   have to test by **voice**: with the phone app's microphone and headphones, so as not to wake anyone.
2. **"Alexa doesn't have batteries" / "I work without a battery"** is the normal answer to "how are the batteries" when it
   does not reach your skill: Alexa takes it as a question about its own battery. It is not a skill bug.
3. **The invocation name is everything:**
   - A single word, and an invented name, work badly. "Terrenico" was often misheard.
   - **Alexa ignores the leading article** of the name: with "las placas" ("the panels") it looked for a skill called
     "placas" and did not find it. Do not start with "the", or its equivalents in your language.
   - Avoid common expressions such as "solar system", which clash with astronomy skills and with Alexa's own answers.
   - What worked was **three ordinary, personal words: "mi casa solar"** ("my solar home"; the possessive is not dropped).
4. **"Open X" is much more reliable than "ask X how…"**. That is why the skill, when opened, **answers at once** (battery and
   panels) and closes, instead of waiting for a second question. In the phone app each press of the microphone is a new
   conversation, and the second question fell outside the skill.
5. **Devices must have the skill's language.** If the skill is "Spanish (Spain)" and the Echo is set to Mexican or US
   Spanish, it will not see it. You can add another language to the skill (same model, another language entry).
6. **Latency matters:** Alexa waits about 8 seconds. A first version of the server took 3 to 5 s reading every MQTT topic,
   too tight; reading only the 7 needed and stopping as soon as they arrive brought it down to ~1.2 s.
7. A skill in development mode only works on **your** account's devices, not on relatives' devices with another account.

## 7.4 The routine to say just "how are the batteries"

In the Alexa app: *More → Routines → +*. **When:** Voice → `cómo están las baterías`. **Action:** Custom →
`abre mi casa solar`. The routine launches the phrase that does work.

## 7.5 What it answers

> "La batería está al 61 por ciento y se está descargando a unos 290 vatios. A este ritmo durará aproximadamente 19 horas.
> Las placas no están produciendo y la casa consume 269 vatios."
> ("The battery is at 61 percent and discharging at about 290 watts. At this rate it will last about 19 hours. The panels
> are not producing and the house is using 269 watts.")

If the data comes from the last stored sample (the broker did not answer) it says so: "Ojo, el dato de la batería es de hace
N minutos" ("Careful, the battery data is N minutes old"). If the battery drops below 20 % while discharging, it adds that
you should reduce consumption.

Next: [8. Security and common problems](08-security-and-troubleshooting.md).
