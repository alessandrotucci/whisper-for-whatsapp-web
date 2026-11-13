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
  const listeners = { message: [], installed: [], alarm: [], changed: [], native: [] };
  const posted = [];
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
      getPlatformInfo: async () => ({ os: 'win' }),
      sendMessage: async () => {},
      lastError: null,
      connectNative: () => ({ onMessage: ev('native'), onDisconnect: { addListener() {} }, postMessage: (m) => posted.push(m), disconnect() {} }),
      openOptionsPage() {},
      reload() {},
    },
    i18n: { getMessage: (k) => k },
    action: { setBadgeText() {}, setBadgeBackgroundColor() {} },
    alarms: { get: async () => null, create() {}, onAlarm: ev('alarm') },
    tabs: {
      query: async () => [{ id: 1 }],
      sendMessage: async (_id, m) => (m && m.cmd === 'fetchAudio' ? { ok: true, result: { b64: 'AAAA', mime: 'audio/ogg' } } : null),
      reload: async () => {},
      create: async () => {},
    },
  };
  return { chrome, listeners, posted };
}

function load(preset) {
  const { chrome, listeners, posted } = makeChrome(preset);
  const ctx = { chrome, console, setTimeout, clearTimeout, setInterval, clearInterval, atob, URL };
  ctx.fetch = async () => { throw new Error('rete non disponibile nella prova'); };
  ctx.self = ctx;
  ctx.importScripts = (...files) => {
    for (const f of files) vm.runInContext(fs.readFileSync(path.join(extDir, f), 'utf8'), ctx, { filename: f });
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(path.join(extDir, 'background.js'), 'utf8'), ctx, { filename: 'background.js' });
  const send = (msg, sender = {}) =>
    new Promise((resolve) => {
      let replied = false;
      const ret = listeners.message[0](msg, sender, (r) => { replied = true; resolve(r); });
      if (ret !== true && !replied) resolve(undefined);
    });
  const fromHost = (m) => listeners.native.forEach((fn) => fn(m));
  return { send, fromHost, posted };
}

const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
function check(name, cond, detail) {
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (cond ? '' : ' -> ' + JSON.stringify(detail).slice(0, 300)));
  if (!cond) failures++;
}

(async () => {
  const tab = { tab: { id: 1 } };
  const cached = { 't:k1': { text: 'ciao', language: 'it', duration: 3 }, hostVersion: '1.0.0' };
  const { send, fromHost, posted } = load(cached);
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
  await tick();
  const sent = posted.filter((m) => m.type === 'transcribe');
  const j2 = sent.find((m) => m.manual === true);
  check('i vocali con l\'audio vanno al componente, quelli manuali con priorità', sent.length === 2 && j2, sent.map((m) => ({ manual: m.manual })));
  fromHost({ type: 'queue', length: 3, waiting: 2, others: 2, running: { mine: false, id: null }, jobs: [{ id: j2.id, position: 2 }] });
  const q = await send({ cmd: 'get', keys: ['k2'] }, tab);
  check('la posizione in coda arriva dal componente', q.k2 && q.k2.state === 'queued' && q.k2.position === 2, q);
  const st2 = await send({ cmd: 'status' });
  check('status riporta i lavori degli altri browser', st2.host && st2.host.others === 2 && st2.host.othersRunning === true, st2.host);
  fromHost({ type: 'status', id: j2.id, state: 'transcribing' });
  fromHost({ type: 'partial', id: j2.id, text: 'ciao a', progress: 0.5 });
  const run = await send({ cmd: 'get', keys: ['k2'] }, tab);
  check('stato e testo parziale del lavoro in corso', run.k2 && run.k2.state === 'transcribing' && run.k2.progress === 0.5, run);
  fromHost({ type: 'result', id: j2.id, text: 'ciao a tutti', language: 'it', duration: 2, elapsed: 1 });
  await tick();
  const done = await send({ cmd: 'get', keys: ['k2'] }, tab);
  check('il risultato finisce in archivio', done.k2 && done.k2.state === 'done' && done.k2.text === 'ciao a tutti', done);
  const info = await send({ cmd: 'updateInfo' });
  check('updateInfo ha le versioni installate', info && info.current && info.current.extension, info);
  const stats = await send({ cmd: 'cacheStats' });
  check('cacheStats conta le trascrizioni', stats && stats.count === 2, stats);
  const clear = await send({ cmd: 'clearQueue' });
  const k3 = sent.find((m) => m !== j2);
  check('clearQueue annulla nel componente i vocali in attesa', clear === true && posted.some((m) => m.type === 'cancel' && m.id === k3.id), posted.filter((m) => m.type === 'cancel'));
  const unknown = await send({ cmd: 'inesistente' });
  check('un comando sconosciuto non riceve risposta', unknown === undefined, unknown);

  console.log(failures ? failures + ' controlli falliti' : 'service worker: tutti i controlli passati');
  process.exit(failures ? 1 : 0);
})().catch((e) => {
  console.log('FAIL eccezione: ' + (e && e.stack));
  process.exit(1);
});
