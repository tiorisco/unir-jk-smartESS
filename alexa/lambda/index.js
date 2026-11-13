'use strict';

/**
 * Skill de Alexa "Terrenico" (es-ES): contesta con el estado de la bateria y de las placas.
 * Solo LEE: consulta https://tu-servidor.duckdns.org/api/ahora.php con el token de
 * lectura (SOLAR_APP_TOKEN). No puede cambiar nada.
 *
 * El token va en config.js (copia config.example.js) o en las variables de entorno
 * TERRENICO_URL y TERRENICO_TOKEN. config.js NO se sube a git.
 */

const Alexa = require('ask-sdk-core');
const http = require('http');
const https = require('https');

let config = {};
try {
  config = require('./config');
} catch (e) {
  // sin config.js: se usan las variables de entorno
}
const URL_BASE = process.env.TERRENICO_URL || config.url || 'https://tu-servidor.duckdns.org';
const TOKEN = process.env.TERRENICO_TOKEN || config.token;

/** Pide el estado actual al servidor. Alexa espera la respuesta unos 8 s: se corta a los 6. */
function pedirEstado() {
  return new Promise((resolve, reject) => {
    if (!TOKEN) {
      reject(new Error('Falta el token en config.js'));
      return;
    }
    const url = new URL(URL_BASE.replace(/\/$/, '') + '/api/ahora.php');
    const lib = url.protocol === 'http:' ? http : https;
    const req = lib.get(url, { headers: { 'X-Token': TOKEN } }, (res) => {
      let datos = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { datos += c; });
      res.on('end', () => {
        if (res.statusCode !== 200) {
          reject(new Error('El servidor respondio HTTP ' + res.statusCode));
          return;
        }
        try {
          resolve(JSON.parse(datos));
        } catch (e) {
          reject(new Error('Respuesta no valida'));
        }
      });
    });
    req.setTimeout(6000, () => req.destroy(new Error('Tiempo agotado')));
    req.on('error', reject);
  });
}

// ---------- Frases ----------

/** "3 horas", "una hora y 20 minutos", "35 minutos". Devuelve null si pasa de 24 horas. */
function duracion(horas) {
  if (horas === null || horas === undefined || horas > 24) return null;
  const min = Math.max(1, Math.round(horas * 60));
  if (min < 60) return `${min} minutos`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (h >= 3 || m < 10) return h === 1 ? 'una hora' : `${h + (m >= 30 ? 1 : 0)} horas`;
  return `${h === 1 ? 'una hora' : h + ' horas'} y ${m} minutos`;
}

function vatios(w) {
  const v = Math.round(Math.abs(w));
  if (v >= 1000) return `${(v / 1000).toFixed(1).replace('.', ',')} kilovatios`;
  return `${v} vatios`;
}

function aviso(minutos) {
  return minutos > 10 ? ` Ojo, este dato es de hace ${minutos} minutos.` : '';
}

function fraseBateria(estado) {
  const b = estado && estado.bateria;
  if (!b || b.soc === null || b.soc === undefined) {
    return 'No tengo datos de la batería ahora mismo. Revisa si el sistema está conectado.';
  }
  const soc = Math.round(b.soc);
  const i = b.corriente;
  let t = soc >= 100 ? 'La batería está completa, al 100 por cien' : `La batería está al ${soc} por ciento`;
  const potencia = b.potencia !== null && b.potencia !== undefined && Math.abs(b.potencia) >= 1
    ? ` a unos ${vatios(b.potencia)}` : '';
  if (i > 0.5 && soc < 100) t += ` y se está cargando${potencia}`;
  else if (i < -0.5) t += ` y se está descargando${potencia}`;
  else if (soc < 100) t += ' y está en reposo';
  t += '.';

  const est = b.estimacion;
  if (est) {
    const d = duracion(est.horas);
    if (d && est.tipo === 'vacia') t += ` A este ritmo durará aproximadamente ${d}.`;
    else if (d && est.tipo === 'llena' && soc < 100) t += ` Se llenará en aproximadamente ${d}.`;
  }
  if (b.en_vivo === false) t += aviso(b.hace_minutos).replace('este dato', 'el dato de la batería');
  if (soc <= 20 && i < -0.5) t += ' Está baja: conviene reducir el consumo.';
  return t;
}

function fraseSolar(estado) {
  const inv = estado && estado.inversor;
  if (!inv) return 'No tengo datos del inversor ahora mismo.';
  const placas = inv.pv_w < 20 ? 'Las placas no están produciendo' : `Las placas producen ${vatios(inv.pv_w)}`;
  return `${placas} y la casa consume ${vatios(inv.casa_w)}.${aviso(inv.hace_minutos)}`;
}

// ---------- Manejadores ----------

const LaunchRequestHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'LaunchRequest',
  // "Alexa, abre Terrenico": respuesta completa de una vez (bateria y placas) y se cierra.
  // Asi no hace falta una segunda pregunta, que desde la app del telefono no se recoge.
  async handle(h) {
    const estado = await pedirEstado();
    const partes = [fraseBateria(estado)];
    if (estado && estado.inversor) partes.push(fraseSolar(estado));
    return h.responseBuilder.speak(partes.join(' ')).withShouldEndSession(true).getResponse();
  },
};

const EstadoBateriaHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'EstadoBateriaIntent',
  async handle(h) {
    return h.responseBuilder.speak(fraseBateria(await pedirEstado())).withShouldEndSession(true).getResponse();
  },
};

const EstadoSolarHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'EstadoSolarIntent',
  async handle(h) {
    return h.responseBuilder.speak(fraseSolar(await pedirEstado())).withShouldEndSession(true).getResponse();
  },
};

const HelpHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'AMAZON.HelpIntent',
  handle: (h) => h.responseBuilder
    .speak('Puedes preguntarme: cómo están las baterías, o cuánto producen las placas.')
    .reprompt('¿Qué quieres saber? Por ejemplo: cómo están las baterías.')
    .getResponse(),
};

const StopHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && ['AMAZON.CancelIntent', 'AMAZON.StopIntent', 'AMAZON.NavigateHomeIntent'].includes(Alexa.getIntentName(h.requestEnvelope)),
  handle: (h) => h.responseBuilder.speak('Hasta luego.').withShouldEndSession(true).getResponse(),
};

const FallbackHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'IntentRequest'
    && Alexa.getIntentName(h.requestEnvelope) === 'AMAZON.FallbackIntent',
  handle: (h) => h.responseBuilder
    .speak('No te he entendido. Prueba con: cómo están las baterías.')
    .reprompt('Prueba con: cómo están las baterías.')
    .getResponse(),
};

const SessionEndedHandler = {
  canHandle: (h) => Alexa.getRequestType(h.requestEnvelope) === 'SessionEndedRequest',
  handle: (h) => h.responseBuilder.getResponse(),
};

const ErrorHandler = {
  canHandle: () => true,
  handle(h, error) {
    console.log('Error en la skill:', error && error.message);
    return h.responseBuilder
      .speak('No he podido consultar las baterías ahora mismo. Inténtalo de nuevo en un momento.')
      .withShouldEndSession(true)
      .getResponse();
  },
};

exports.handler = Alexa.SkillBuilders.custom()
  .addRequestHandlers(
    LaunchRequestHandler, EstadoBateriaHandler, EstadoSolarHandler,
    HelpHandler, StopHandler, FallbackHandler, SessionEndedHandler,
  )
  .addErrorHandlers(ErrorHandler)
  .lambda();

// Para las pruebas
exports.fraseBateria = fraseBateria;
exports.fraseSolar = fraseSolar;
