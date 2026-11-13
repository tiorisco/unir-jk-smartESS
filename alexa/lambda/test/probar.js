'use strict';

// Prueba la skill con peticiones de Alexa simuladas y un servidor falso.
//   cd alexa/lambda && node test/probar.js
// No toca internet ni el servidor real.

const http = require('http');

let respuesta = { codigo: 200, cuerpo: {} };
const servidor = http.createServer((req, res) => {
  const token = req.headers['x-token'];
  if (token !== 'token-de-prueba') {
    res.writeHead(401); res.end('{"error":"no autorizado"}'); return;
  }
  res.writeHead(respuesta.codigo, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(respuesta.cuerpo));
});

function evento(tipo, intent) {
  const req = { type: tipo, requestId: 'amzn1.echo-api.request.1', timestamp: new Date().toISOString(), locale: 'es-ES' };
  if (intent) req.intent = { name: intent, confirmationStatus: 'NONE' };
  return {
    version: '1.0',
    session: { new: true, sessionId: 'amzn1.echo-api.session.1', application: { applicationId: 'amzn1.ask.skill.x' }, user: { userId: 'u' } },
    context: { System: { application: { applicationId: 'amzn1.ask.skill.x' }, user: { userId: 'u' }, device: { deviceId: 'd', supportedInterfaces: {} } } },
    request: req,
  };
}

function llamar(handler, ev) {
  return new Promise((resolve, reject) => handler(ev, {}, (err, r) => (err ? reject(err) : resolve(r))));
}

const escenarios = [
  { nombre: 'Descargando, con estimacion', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 62, voltaje: 52.6, corriente: -4.16, potencia: -218.7, en_vivo: true, hace_minutos: 0, estimacion: { tipo: 'vacia', horas: 31.2 } } },
    espera: [/al 62 por ciento/, /descargando a unos 219 vatios/] },
  { nombre: 'Descargando, dura menos de un dia', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 45, voltaje: 51.9, corriente: -9.5, potencia: -500, en_vivo: true, hace_minutos: 0, estimacion: { tipo: 'vacia', horas: 5.4 } } },
    espera: [/al 45 por ciento/, /durará aproximadamente 5 horas/] },
  { nombre: 'Cargando de dia', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 58, voltaje: 53.4, corriente: 24, potencia: 1296, en_vivo: true, hace_minutos: 0, estimacion: { tipo: 'llena', horas: 1.6 } } },
    espera: [/al 58 por ciento/, /cargando a unos 1,3 kilovatios/, /llenará en aproximadamente una hora y 36 minutos/] },
  { nombre: 'Completa', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 100, voltaje: 54.6, corriente: 0.2, potencia: 10, en_vivo: true, hace_minutos: 0, estimacion: null } },
    espera: [/está completa, al 100 por cien\.$/], noEspera: [/en reposo/, /cargando/] },
  { nombre: 'Baja y descargando', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 18, voltaje: 49.8, corriente: -7, potencia: -350, en_vivo: true, hace_minutos: 0, estimacion: { tipo: 'vacia', horas: 2.9 } } },
    espera: [/al 18 por ciento/, /Está baja/] },
  { nombre: 'Dato antiguo (sin conexion en directo)', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 58, voltaje: 52.1, corriente: -3, potencia: -156, en_vivo: false, hace_minutos: 7, estimacion: null } },
    espera: [/al 58 por ciento/], noEspera: [/Ojo/] },
  { nombre: 'Dato muy antiguo', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: { soc: 58, voltaje: 52.1, corriente: -3, potencia: -156, en_vivo: false, hace_minutos: 45, estimacion: null } },
    espera: [/Ojo, el dato de la batería es de hace 45 minutos/] },
  { nombre: 'Sin datos de bateria', intent: 'EstadoBateriaIntent',
    cuerpo: { bateria: null, inversor: null }, espera: [/No tengo datos de la batería/] },
  { nombre: 'Placas produciendo', intent: 'EstadoSolarIntent',
    cuerpo: { inversor: { pv_w: 2182, casa_w: 237, bateria_w: 1500, hace_minutos: 2 } },
    espera: [/producen 2,2 kilovatios/, /la casa consume 237 vatios/], noEspera: [/Ojo/] },
  { nombre: 'Noche', intent: 'EstadoSolarIntent',
    cuerpo: { inversor: { pv_w: 7, casa_w: 169, bateria_w: -183, hace_minutos: 3 } },
    espera: [/no están produciendo/, /consume 169 vatios/] },
  { nombre: 'Inversor con dato viejo', intent: 'EstadoSolarIntent',
    cuerpo: { inversor: { pv_w: 500, casa_w: 200, bateria_w: 0, hace_minutos: 30 } },
    espera: [/hace 30 minutos/] },
  { nombre: 'Abrir la skill: bateria y placas de una vez', tipo: 'LaunchRequest',
    cuerpo: {
      bateria: { soc: 62, voltaje: 52.6, corriente: -4.16, potencia: -218.7, en_vivo: true, hace_minutos: 0, estimacion: null },
      inversor: { pv_w: 7, casa_w: 169, bateria_w: -183, hace_minutos: 2 },
    },
    espera: [/al 62 por ciento/, /Las placas no están produciendo y la casa consume 169 vatios/] },
  { nombre: 'Abrir la skill sin datos del inversor', tipo: 'LaunchRequest',
    cuerpo: { bateria: { soc: 62, voltaje: 52.6, corriente: -4.16, potencia: -218.7, en_vivo: true, hace_minutos: 0, estimacion: null }, inversor: null },
    espera: [/al 62 por ciento/], noEspera: [/placas/] },
  { nombre: 'Ayuda', intent: 'AMAZON.HelpIntent', cuerpo: {}, espera: [/cómo están las baterías/], sesionAbierta: true },
  { nombre: 'Parar', intent: 'AMAZON.StopIntent', cuerpo: {}, espera: [/Hasta luego/] },
  { nombre: 'No entendido', intent: 'AMAZON.FallbackIntent', cuerpo: {}, espera: [/No te he entendido/], sesionAbierta: true },
  { nombre: 'El servidor falla (HTTP 500)', intent: 'EstadoBateriaIntent', codigo: 500, cuerpo: {},
    espera: [/No he podido consultar las baterías/] },
  { nombre: 'Token incorrecto en el servidor (401)', intent: 'EstadoBateriaIntent', tokenMalo: true, cuerpo: {},
    espera: [/No he podido consultar las baterías/] },
];

(async () => {
  await new Promise((r) => servidor.listen(8097, '127.0.0.1', r));
  process.env.TERRENICO_URL = 'http://127.0.0.1:8097';
  let fallos = 0;

  for (const e of escenarios) {
    process.env.TERRENICO_TOKEN = e.tokenMalo ? 'token-malo' : 'token-de-prueba';
    // el modulo lee las variables al cargarse: se recarga para cada escenario
    delete require.cache[require.resolve('../index.js')];
    const { handler } = require('../index.js');
    respuesta = { codigo: e.codigo || 200, cuerpo: e.cuerpo };
    const r = await llamar(handler, evento(e.tipo || 'IntentRequest', e.intent));
    const texto = (r.response.outputSpeech && r.response.outputSpeech.ssml || '').replace(/<[^>]+>/g, '');
    const abierta = r.response.shouldEndSession === false;
    const errores = [];
    for (const re of e.espera) if (!re.test(texto)) errores.push(`falta ${re}`);
    for (const re of e.noEspera || []) if (re.test(texto)) errores.push(`sobra ${re}`);
    if (e.sesionAbierta === true && !abierta) errores.push('debia dejar la sesion abierta');
    if (!e.sesionAbierta && abierta) errores.push('debia cerrar la sesion');
    if (errores.length) fallos++;
    console.log(`${errores.length ? 'FALLO' : 'ok   '} | ${e.nombre}\n        "${texto}"${errores.length ? '\n        -> ' + errores.join('; ') : ''}`);
  }

  servidor.close();
  console.log(`\n${escenarios.length - fallos}/${escenarios.length} escenarios correctos`);
  process.exit(fallos ? 1 : 0);
})();
