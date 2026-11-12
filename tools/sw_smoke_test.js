// Prova di fumo del service worker: esegue background.js (e update.js, che carica con
// importScripts) in un contesto vm con le API chrome simulate in memoria, poi invia i
// messaggi che mandano popup, opzioni e content script e controlla le risposte.
//
// Uso: node tools/sw_smoke_test.js [cartella dell'estensione]; esce con 1 al primo errore.
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const extDir = path.resolve(process.argv[2] || path.join(__dirname, '..', 'extension'));

function makeChrome(preset) {
  const local = JSON.parse(JSON.stringify(preset));
  const session = {};
  const listeners = { message: [], installed: [], alarm: [], changed: [] };
  const ev = (name) => ({ addListener: (fn) => listeners[name].push(fn) });
  const store = (obj) => ({
    get: async (keys) => {
      if (keys == null) return JSON.parse(JSON.stringify(obj));
      const ks = Array.isArray(keys) ? keys : typeof keys === 'string' ? [keys] : Object.keys(keys);
      const out = {};
      for (const k of ks) if (k in obj) out[k] = JSON.parse(JSON.stringify(obj[k]));
      return out;
    },
    set: async (o) => { Object.assign(obj, JSON.parse(JSON.stringify(o))); },
    remove: async (ks) => { for (const k of [].concat(ks)) delete obj[k]; },
    getBytesInUse: async () => 0,
  });
  const chrome = {
    storage: { local: store(local), session: store(session), onChanged: ev('changed') },
    runtime: {
      onMessage: ev('message'),
      onInstalled: ev('installed'),
      getManifest: () => JSON.parse(fs.readFileSync(path.join(extDir, 'manifest.json'), 'utf8')),
      id: 'smoke-test',
      sendMessage: async () => {},
      lastError: null,
      connectNative: () => ({ onMessage: ev('message'), onDisconnect: { addListener() {} }, postMessage() {}, disconnect() {} }),
      openOptionsPage() {},
      reload() {},
    },
    i18n: { getMessage: (k) => k },
    action: { setBadgeText() {}, setBadgeBackgroundColor() {} },
    alarms: { get: async () => null, create() {}, onAlarm: ev('alarm') },
    tabs: { query: async () => [], sendMessage: async () => null, reload: async () => {}, create: async () => {} },
  };
  return { chrome, listeners };
}

function load(preset) {
  const { chrome, listeners } = makeChrome(preset);
  const ctx = { chrome, console, setTimeout, clearTimeout, setInterval, clearInterval, atob, URL };
  ctx.fetch = async () => { throw new Error('rete non disponibile nella prova'); };
  ctx.self = ctx;
  ctx.importScripts = (...files) => {
    for (const f of files) vm.runInContext(fs.readFileSync(path.join(extDir, f), 'utf8'), ctx, { filename: f });
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(path.join(extDir, 'background.js'), 'utf8'), ctx, { filename: 'background.js' });
  return (msg, sender = {}) =>
    new Promise((resolve) => {
      let replied = false;
      const ret = listeners.message[0](msg, sender, (r) => { replied = true; resolve(r); });
      if (ret !== true && !replied) resolve(undefined);
    });
}

let failures = 0;
function check(name, cond, detail) {
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (cond ? '' : ' -> ' + JSON.stringify(detail).slice(0, 300)));
  if (!cond) failures++;
}

(async () => {
  const tab = { tab: { id: 1 } };
  const cached = { 't:k1': { text: 'ciao', language: 'it', duration: 3 }, hostVersion: '1.0.0' };
  const send = load(cached);
  await new Promise((r) => setTimeout(r, 20));

  const st = await send({ cmd: 'status' });
  check('status ha le impostazioni', st && st.settings && 'autoIncoming' in st.settings, st);
  const got = await send({ cmd: 'get', keys: ['k1', 'k2'] }, tab);
  check('get restituisce la trascrizione salvata', got && got.k1 && got.k1.state === 'done' && got.k1.text === 'ciao', got);
  check('get senza trascrizione restituisce null', got && got.k2 === null, got);
  const enq = await send({ cmd: 'enqueue', key: 'k1', manual: true }, tab);
  check('enqueue di un vocale già trascritto risponde subito', enq && enq.state === 'done', enq);
  const enq2 = await send({ cmd: 'enqueue', key: 'k2', manual: true }, tab);
  check('enqueue di un vocale nuovo lo mette in lavorazione', enq2 && ['queued', 'downloading'].includes(enq2.state), enq2);
  const auto = await send({ cmd: 'autoEnqueue', key: 'k3', fromMe: false, chat: 'x' }, tab);
  check('autoEnqueue accoda i vocali in arrivo', auto && ['queued', 'downloading'].includes(auto.state), auto);
  const info = await send({ cmd: 'updateInfo' });
  check('updateInfo ha le versioni installate', info && info.current && info.current.extension, info);
  const stats = await send({ cmd: 'cacheStats' });
  check('cacheStats conta le trascrizioni', stats && stats.count === 1, stats);
  const clear = await send({ cmd: 'clearQueue' });
  check('clearQueue', clear === true, clear);
  const unknown = await send({ cmd: 'inesistente' });
  check('un comando sconosciuto non riceve risposta', unknown === undefined, unknown);

  console.log(failures ? failures + ' controlli falliti' : 'service worker: tutti i controlli passati');
  process.exit(failures ? 1 : 0);
})().catch((e) => {
  console.log('FAIL eccezione: ' + (e && e.stack));
  process.exit(1);
});
