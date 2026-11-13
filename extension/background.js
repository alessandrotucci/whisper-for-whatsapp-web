// Service worker: lavori di trascrizione del browser, porta verso il componente locale, archivio dei risultati.
//
// La coda delle trascrizioni sta nel componente locale ed è unica per tutti i browser
// del computer: qui un vocale chiesto da trascrivere aspetta solo il proprio audio, che
// si chiede alla scheda di WhatsApp un vocale per volta e si passa subito al componente.
// Posizione in coda e stato arrivano dal componente, con i messaggi "queue" e "status".
//
// Archivio: chrome.storage.local, chiave "t:<id messaggio>".
// Lavori aperti: chrome.storage.session, così sopravvivono al riavvio del service worker;
// alla ripartenza tornano tutti in attesa dell'audio e si inviano di nuovo.

const HOST_NAME = 'local.wa_whisper.host';
const WA_URL = 'https://web.whatsapp.com/*';

const DEFAULT_SETTINGS = {
  autoIncoming: true,
  autoOutgoing: false,
  autoVisible: false,
  language: '',
  initialPrompt: '',
  beamSize: 5,
  vadFilter: true,
  idleMinutes: 10,
};

// Un lavoro passa per gli stati: queued (attende l'audio, o il turno nel componente,
// con position), downloading, waiting (inviato), waiting_lock, loading_model, transcribing.
const PORT_RETRIES = 1; // nuovi invii di un lavoro dopo la chiusura del componente
const RUNNING_STATES = ['waiting_lock', 'loading_model', 'transcribing'];

let settings = { ...DEFAULT_SETTINGS };
let jobs = new Map(); // key -> {key, tabId, manual, chat, addedAt, retries, state, hostJobId, position, partial, progress, startedAt}
let toFetch = []; // chiavi in attesa dell'audio: le richieste manuali in testa
let hostQueue = null; // ultimo stato della coda del componente
let restored = null;
let updating = false; // aggiornamento in corso: la coda non avvia lavori nuovi
let updateAvailable = false;
let lastHostError = null;

// ---------- impostazioni e stato persistente ----------

async function loadSettings() {
  const s = await chrome.storage.local.get('settings');
  settings = { ...DEFAULT_SETTINGS, ...(s.settings || {}) };
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.settings) {
    settings = { ...DEFAULT_SETTINGS, ...(changes.settings.newValue || {}) };
    scheduleIdleDisconnect();
  }
});

function restore() {
  if (!restored) {
    restored = (async () => {
      await loadSettings();
      const s = await chrome.storage.session.get(['jobs']);
      for (const j of s.jobs || []) {
        if (jobs.has(j.key)) continue;
        jobs.set(j.key, { ...j, retries: j.retries || 0, state: 'queued' });
        insertToFetch(j.key, j.manual);
      }
      await persistQueue();
      broadcastQueue();
      pumpFetch();
    })();
  }
  return restored;
}

function persistQueue() {
  const list = [...jobs.values()].map((j) => ({ key: j.key, tabId: j.tabId, manual: j.manual, chat: j.chat, addedAt: j.addedAt, retries: j.retries }));
  return chrome.storage.session.set({ jobs: list });
}

function insertToFetch(key, manual) {
  if (manual) {
    let i = 0;
    while (i < toFetch.length && jobs.get(toFetch[i]) && jobs.get(toFetch[i]).manual) i++;
    toFetch.splice(i, 0, key);
  } else {
    toFetch.push(key);
  }
}

// Lavori inviati al componente e non ancora conclusi.
function hostBusy() {
  for (const j of jobs.values()) if (j.hostJobId) return true;
  return false;
}

async function getCached(keys) {
  const res = await chrome.storage.local.get(keys.map((k) => 't:' + k));
  const out = {};
  for (const k of keys) if (res['t:' + k]) out[k] = res['t:' + k];
  return out;
}

// ---------- host nativo ----------

let port = null;
let pending = new Map(); // id del lavoro di trascrizione -> {resolve, reject, onEvent}
let requests = new Map(); // rid -> {resolve, reject, onEvent, done, timer}
let idleTimer = null;
let jobSeq = 0;
let ridSeq = 0;
let downloading = null; // nome del modello in download
let cudaInstalling = false;

function connect() {
  if (port) return port;
  lastHostError = null;
  port = chrome.runtime.connectNative(HOST_NAME);
  port.onMessage.addListener(onHostMessage);
  port.onDisconnect.addListener(() => {
    const err = chrome.runtime.lastError ? chrome.runtime.lastError.message : chrome.i18n.getMessage('err_host_exited');
    lastHostError = err;
    port = null;
    for (const p of pending.values()) {
      const e = new Error(err);
      e.portLost = true;
      p.reject(e);
    }
    pending.clear();
    hostQueue = null;
    for (const r of requests.values()) {
      clearTimeout(r.timer);
      r.reject(new Error(err));
    }
    requests.clear();
  });
  return port;
}

function onHostMessage(msg) {
  if (msg.rid != null) {
    const r = requests.get(msg.rid);
    if (!r) return;
    if (msg.type === 'error') {
      requests.delete(msg.rid);
      clearTimeout(r.timer);
      r.reject(new Error(msg.message));
    } else if (r.done.includes(msg.type)) {
      requests.delete(msg.rid);
      clearTimeout(r.timer);
      r.resolve(msg);
    } else if (r.onEvent) {
      r.onEvent(msg);
    }
    return;
  }
  if (msg.type === 'queue') {
    applyHostQueue(msg);
    return;
  }
  const p = msg.id != null ? pending.get(msg.id) : null;
  if (!p) return;
  if (msg.type === 'result') {
    pending.delete(msg.id);
    p.resolve(msg);
  } else if (msg.type === 'error') {
    pending.delete(msg.id);
    const e = new Error(msg.message);
    e.cancelled = !!msg.cancelled;
    p.reject(e);
  } else {
    p.onEvent(msg);
  }
}

// Richiesta all'host che si chiude con un messaggio dei tipi in "done"; timeoutMs 0 = nessun limite.
function hostRequest(message, done, { timeoutMs = 20000, onEvent } = {}) {
  return new Promise((resolve, reject) => {
    const rid = 'r' + (++ridSeq);
    const r = { resolve, reject, onEvent, done, timer: null };
    if (timeoutMs) {
      r.timer = setTimeout(() => {
        if (requests.delete(rid)) reject(new Error(chrome.i18n.getMessage('err_host_no_reply')));
      }, timeoutMs);
    }
    requests.set(rid, r);
    try {
      connect().postMessage({ ...message, rid });
    } catch (e) {
      requests.delete(rid);
      clearTimeout(r.timer);
      reject(e);
    }
  });
}

function notifyPages(message) {
  chrome.runtime.sendMessage(message).catch(() => {});
}

function hostTranscribe(audioB64, manual, onEvent) {
  const id = 'j' + Date.now().toString(36) + '-' + (++jobSeq);
  const promise = new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, onEvent });
    try {
      connect().postMessage({
        type: 'transcribe',
        id,
        audio: audioB64,
        language: settings.language || null,
        initial_prompt: settings.initialPrompt || null,
        beam_size: Number(settings.beamSize) || 5,
        vad_filter: !!settings.vadFilter,
        manual: !!manual,
      });
    } catch (e) {
      pending.delete(id);
      reject(e);
    }
  });
  return { id, promise };
}

// Finché ci sono lavori aperti, una chiamata a un'API dell'estensione ogni 20 secondi
// azzera il timer di inattività del service worker: la porta nativa da sola può restare
// muta a lungo (modello in caricamento, vocale in coda dietro quelli di un altro browser).
let keepAliveTimer = null;
function updateKeepAlive() {
  const busy = jobs.size > 0 || !!downloading || cudaInstalling || updating;
  if (busy && !keepAliveTimer) {
    keepAliveTimer = setInterval(() => {
      chrome.runtime.getPlatformInfo().catch(() => {});
    }, 20000);
  } else if (!busy && keepAliveTimer) {
    clearInterval(keepAliveTimer);
    keepAliveTimer = null;
  }
}

function scheduleIdleDisconnect() {
  clearTimeout(idleTimer);
  updateKeepAlive();
  if (!port || jobs.size || downloading || cudaInstalling) return;
  const minutes = Math.max(1, Number(settings.idleMinutes) || 10);
  idleTimer = setTimeout(() => {
    if (port && !jobs.size && !downloading && !cudaInstalling) {
      port.disconnect();
      port = null;
    }
  }, minutes * 60 * 1000);
}

// ---------- comunicazione con le tab ----------

async function waTabs() {
  return chrome.tabs.query({ url: WA_URL });
}

async function broadcast(message) {
  for (const t of await waTabs()) {
    chrome.tabs.sendMessage(t.id, message).catch(() => {});
  }
}

function stateOf(key) {
  const j = jobs.get(key);
  if (!j) return null;
  const out = { state: j.state, partial: j.partial, progress: j.progress };
  if (j.state === 'queued') {
    if (j.position) {
      out.position = j.position;
    } else {
      // In attesa dell'audio: dopo tutto ciò che è già nel componente.
      const i = toFetch.indexOf(key);
      if (i >= 0) out.position = (hostQueue ? hostQueue.length : 0) + i + 1;
    }
  }
  return out;
}

// Il badge mostra i vocali di questo browser ancora da trascrivere; senza, una freccia se c'è un aggiornamento.
function refreshBadge() {
  updateKeepAlive();
  const n = jobs.size;
  chrome.action.setBadgeText({ text: n ? String(n) : updateAvailable ? '↑' : '' });
  chrome.action.setBadgeBackgroundColor({ color: n ? '#128c7e' : '#d97706' });
}

function broadcastQueue() {
  const updates = [...jobs.keys()].map((key) => ({ key, ...stateOf(key) }));
  if (updates.length) broadcast({ cmd: 'update', updates });
  refreshBadge();
}

function applyHostQueue(snap) {
  hostQueue = snap;
  const pos = new Map((snap.jobs || []).map((j) => [j.id, j.position]));
  for (const j of jobs.values()) {
    if (!j.hostJobId) continue;
    if (pos.has(j.hostJobId)) {
      j.state = 'queued';
      j.position = pos.get(j.hostJobId);
    } else if (j.state === 'queued' || j.state === 'waiting') {
      j.position = null;
      if (snap.running && snap.running.mine && snap.running.id === j.hostJobId) j.state = 'loading_model';
    }
  }
  broadcastQueue();
}

async function fetchAudio(job) {
  const tabs = await waTabs();
  const order = [job.tabId, ...tabs.map((t) => t.id).filter((id) => id !== job.tabId)].filter((id) => id != null);
  // L'errore riferito da una scheda (audio scaduto, messaggio non caricato) prevale su
  // quello di una scheda che non risponde, per esempio perché non ha ancora lo script.
  let tabErr = null;
  let connErr = new Error(chrome.i18n.getMessage('err_no_wa_tab'));
  for (const tabId of order) {
    try {
      const res = await chrome.tabs.sendMessage(tabId, { cmd: 'fetchAudio', key: job.key });
      if (res && res.ok) return res.result;
      if (!tabErr) tabErr = new Error((res && res.error) || chrome.i18n.getMessage('err_empty_tab_reply'));
    } catch (e) {
      connErr = e;
    }
  }
  throw tabErr || connErr;
}

// ---------- lavori ----------

async function enqueue(key, { tabId, manual, chat, force }) {
  await restore();
  if (!force) {
    const cached = (await getCached([key]))[key];
    if (cached) return { state: 'done', ...cached };
  } else {
    await chrome.storage.local.remove('t:' + key);
  }
  const existing = jobs.get(key);
  if (existing) {
    // Una richiesta manuale porta in testa un vocale che aspetta ancora l'audio.
    const i = toFetch.indexOf(key);
    if (manual && !existing.manual && i >= 0) {
      existing.manual = true;
      toFetch.splice(i, 1);
      insertToFetch(key, true);
      await persistQueue();
      broadcastQueue();
    }
    return stateOf(key);
  }
  jobs.set(key, { key, tabId, manual: !!manual, chat: chat || '', addedAt: Date.now(), retries: 0, state: 'queued' });
  insertToFetch(key, manual);
  await persistQueue();
  broadcastQueue();
  pumpFetch();
  return stateOf(key);
}

async function dropJob(key, update) {
  jobs.delete(key);
  const i = toFetch.indexOf(key);
  if (i >= 0) toFetch.splice(i, 1);
  await persistQueue();
  broadcast({ cmd: 'update', updates: [{ key, ...update }] });
  broadcastQueue();
  scheduleIdleDisconnect();
}

async function cancel(key) {
  const j = jobs.get(key);
  if (!j) return false;
  if (j.hostJobId && port) {
    // Il componente risponde con un errore "annullata", che chiude il lavoro.
    port.postMessage({ type: 'cancel', id: j.hostJobId });
    return true;
  }
  await dropJob(key, { state: 'none' });
  return true;
}

// Audio dei vocali in attesa, uno per volta, e invio al componente.
let fetching = false;
async function pumpFetch() {
  if (fetching || updating) return;
  fetching = true;
  try {
    while (toFetch.length && !updating) {
      const key = toFetch.shift();
      const job = jobs.get(key);
      if (!job) continue;
      clearTimeout(idleTimer);
      job.state = 'downloading';
      job.position = null;
      broadcastQueue();
      try {
        connect(); // la porta aperta tiene vivo il service worker anche durante il download
        const audio = await fetchAudio(job);
        if (jobs.get(key) !== job) continue;
        submit(job, audio.b64);
      } catch (e) {
        if (jobs.get(key) === job) await finishError(job, e);
      }
    }
  } finally {
    fetching = false;
    scheduleIdleDisconnect();
  }
}

function submit(job, b64) {
  job.state = 'waiting';
  const { id, promise } = hostTranscribe(b64, job.manual, (ev) => {
    if (ev.type === 'status') {
      job.state = ev.state;
      job.position = null;
      if (ev.state !== 'waiting_lock' && !job.startedAt) job.startedAt = Date.now();
    }
    if (ev.type === 'partial') {
      job.state = 'transcribing';
      job.partial = ev.text;
      job.progress = ev.progress;
    }
    broadcastQueue();
  });
  job.hostJobId = id;
  broadcastQueue();
  promise.then((res) => finishResult(job, res), (e) => finishError(job, e));
}

async function finishResult(job, res) {
  const record = {
    text: res.text,
    language: res.language,
    duration: res.duration,
    elapsed: res.elapsed,
    chat: job.chat,
    at: Date.now(),
  };
  await chrome.storage.local.set({ ['t:' + job.key]: record });
  await dropJob(job.key, { state: 'done', ...record });
}

async function finishError(job, e) {
  const message = e && e.message ? e.message : String(e);
  if (jobs.get(job.key) !== job) return;
  // Il componente si è chiuso, per esempio durante un aggiornamento: il lavoro riparte.
  if (e && e.portLost && job.retries < PORT_RETRIES && !isHostSetupError(message)) {
    job.retries++;
    job.hostJobId = null;
    job.state = 'queued';
    job.partial = '';
    job.progress = null;
    insertToFetch(job.key, true);
    await persistQueue();
    broadcastQueue();
    pumpFetch();
    return;
  }
  await dropJob(job.key, { state: e && e.cancelled ? 'none' : 'error', error: message, hostMissing: isHostSetupError(message) });
}

function isHostSetupError(message) {
  return /native messaging host not found|forbidden|has exited|Error when communicating/i.test(message || '');
}

// ---------- messaggi da content script, popup e opzioni ----------

const handlers = {
  async get({ keys }) {
    await restore();
    const cached = await getCached(keys);
    const out = {};
    for (const k of keys) out[k] = cached[k] ? { state: 'done', ...cached[k] } : stateOf(k);
    return out;
  },
  enqueue({ key, manual, force, chat }, sender) {
    return enqueue(key, { tabId: sender.tab && sender.tab.id, manual, force, chat });
  },
  async autoEnqueue({ key, fromMe, chat, visible }, sender) {
    await restore();
    const wanted = visible ? settings.autoVisible : fromMe ? settings.autoOutgoing : settings.autoIncoming;
    if (!wanted) return null;
    if (visible && fromMe && !settings.autoOutgoing) return null;
    return enqueue(key, { tabId: sender.tab && sender.tab.id, manual: false, chat });
  },
  enqueueFromPopup({ key, tabId, chat }) {
    return enqueue(key, { tabId, manual: false, chat });
  },
  async cancel({ key }) {
    await restore();
    return cancel(key);
  },
  async status() {
    await restore();
    const list = [...jobs.values()];
    const running = list.find((j) => RUNNING_STATES.includes(j.state));
    const waiting = list
      .filter((j) => j !== running)
      .map((j) => ({ key: j.key, chat: j.chat, manual: j.manual, state: j.state, position: stateOf(j.key).position || null }))
      .sort((a, b) => (a.position || 1e9) - (b.position || 1e9));
    return {
      queue: waiting,
      current: running && { key: running.key, chat: running.chat, state: running.state, progress: running.progress, startedAt: running.startedAt },
      host: hostQueue && { length: hostQueue.length, others: hostQueue.others, othersRunning: !!(hostQueue.running && !hostQueue.running.mine) },
      hostConnected: !!port,
      lastHostError,
      settings,
    };
  },
  async hostQueue() {
    try {
      const snap = await hostRequest({ type: 'queue' }, ['queue']);
      applyHostQueue(snap);
      scheduleIdleDisconnect();
      return { ok: true, queue: snap };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  },
  async ping() {
    try {
      const pong = await hostRequest({ type: 'ping' }, ['pong']);
      scheduleIdleDisconnect();
      await chrome.storage.local.set({ hostVersion: pong.host_version });
      return { ok: true, pong, downloading };
    } catch (e) {
      return { ok: false, error: e.message, extensionId: chrome.runtime.id };
    }
  },
  async models() {
    try {
      const r = await hostRequest({ type: 'models' }, ['models']);
      scheduleIdleDisconnect();
      return { ok: true, models: r.models, config: r.config, downloading };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  },
  async setHostConfig({ config }) {
    if (hostBusy()) return { ok: false, error: chrome.i18n.getMessage('err_busy_transcribing') };
    try {
      const pong = await hostRequest({ type: 'set_config', config }, ['pong']);
      return { ok: true, pong };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  },
  downloadModel({ name }) {
    if (downloading) return { ok: false, error: chrome.i18n.getMessage('err_download_running', [downloading]) };
    downloading = name;
    scheduleIdleDisconnect();
    hostRequest({ type: 'download_model', name }, ['download_done'], {
      timeoutMs: 0,
      onEvent: (ev) => notifyPages({ cmd: 'hostEvent', event: ev }),
    })
      .then((done) => notifyPages({ cmd: 'hostEvent', event: done }))
      .catch((e) => notifyPages({ cmd: 'hostEvent', event: { type: 'download_error', name, message: e.message } }))
      .finally(() => {
        downloading = null;
        scheduleIdleDisconnect();
      });
    return { ok: true };
  },
  cudaInstall() {
    if (cudaInstalling) return { ok: false, error: chrome.i18n.getMessage('err_cuda_running') };
    cudaInstalling = true;
    scheduleIdleDisconnect();
    hostRequest({ type: 'cuda_install' }, ['cuda_done'], {
      timeoutMs: 0,
      onEvent: (ev) => notifyPages({ cmd: 'hostEvent', event: ev }),
    })
      .then((done) => notifyPages({ cmd: 'hostEvent', event: done }))
      .catch((e) => notifyPages({ cmd: 'hostEvent', event: { type: 'cuda_error', message: e.message } }))
      .finally(() => {
        cudaInstalling = false;
        scheduleIdleDisconnect();
      });
    return { ok: true };
  },
  async pickFolder() {
    try {
      const r = await hostRequest({ type: 'pick_folder' }, ['folder_picked'], { timeoutMs: 0 });
      return { ok: true, path: r.path, valid: r.valid };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  },
  async cacheStats() {
    const all = await chrome.storage.local.get(null);
    const keys = Object.keys(all).filter((k) => k.startsWith('t:'));
    return { count: keys.length, bytes: await chrome.storage.local.getBytesInUse(keys) };
  },
  async clearCache() {
    const all = await chrome.storage.local.get(null);
    await chrome.storage.local.remove(Object.keys(all).filter((k) => k.startsWith('t:')));
    broadcast({ cmd: 'reset' });
    return true;
  },
  async clearQueue() {
    await restore();
    // Si tolgono i vocali in attesa; quello in trascrizione arriva in fondo.
    for (const j of [...jobs.values()]) {
      if (RUNNING_STATES.includes(j.state)) continue;
      await cancel(j.key);
    }
    return true;
  },
  openOptions() {
    chrome.runtime.openOptionsPage();
    return true;
  },
};

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const h = msg && handlers[msg.cmd];
  if (!h) return false;
  Promise.resolve(h(msg, sender))
    .then((r) => sendResponse(r))
    .catch((e) => sendResponse({ ok: false, error: e.message }));
  return true;
});

chrome.runtime.onInstalled.addListener(async (details) => {
  if (details.reason === 'install') chrome.runtime.openOptionsPage();
  if (details.reason !== 'update') return;
  // Dopo una ricarica gli script delle schede già aperte restano scollegati: le schede
  // di WhatsApp Web si ricaricano, così content.js e page.js ripartono nella versione nuova.
  for (const t of await waTabs()) chrome.tabs.reload(t.id).catch(() => {});
});

importScripts('update.js');

restore();
