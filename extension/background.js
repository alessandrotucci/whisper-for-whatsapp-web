// Service worker: coda seriale delle trascrizioni, porta verso l'host nativo, archivio dei risultati.
//
// La coda è unica per il browser e il lavoro parte un elemento per volta: il
// successivo si avvia solo quando il precedente ha un risultato o un errore.
// L'audio non sta in coda: lo si chiede alla tab di WhatsApp quando tocca a quel vocale.
//
// Archivio: chrome.storage.local, chiave "t:<id messaggio>".
// Coda: chrome.storage.session, così sopravvive al riavvio del service worker.

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

let settings = { ...DEFAULT_SETTINGS };
let queue = []; // [{key, tabId, manual, chat, addedAt}]
let current = null; // {key, tabId, chat, state, partial, progress, startedAt}
let restored = null;
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
      const s = await chrome.storage.session.get(['queue', 'current']);
      queue = s.queue || [];
      // Il lavoro in corso al momento del riavvio torna in testa alla coda.
      if (s.current) queue.unshift({ key: s.current.key, tabId: s.current.tabId, manual: true, chat: s.current.chat, addedAt: Date.now() });
      await persistQueue();
      pump();
    })();
  }
  return restored;
}

function persistQueue() {
  const cur = current && { key: current.key, tabId: current.tabId, chat: current.chat };
  return chrome.storage.session.set({ queue, current: cur || null });
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

function connect() {
  if (port) return port;
  lastHostError = null;
  port = chrome.runtime.connectNative(HOST_NAME);
  port.onMessage.addListener(onHostMessage);
  port.onDisconnect.addListener(() => {
    const err = chrome.runtime.lastError ? chrome.runtime.lastError.message : chrome.i18n.getMessage('err_host_exited');
    lastHostError = err;
    port = null;
    for (const p of pending.values()) p.reject(new Error(err));
    pending.clear();
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

function hostTranscribe(audioB64, onEvent) {
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
      });
    } catch (e) {
      pending.delete(id);
      reject(e);
    }
  });
  return { id, promise };
}

function scheduleIdleDisconnect() {
  clearTimeout(idleTimer);
  if (!port || current || queue.length || downloading) return;
  const minutes = Math.max(1, Number(settings.idleMinutes) || 10);
  idleTimer = setTimeout(() => {
    if (port && !current && !queue.length && !downloading) {
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
  if (current && current.key === key) {
    return { state: current.state, partial: current.partial, progress: current.progress };
  }
  const i = queue.findIndex((j) => j.key === key);
  if (i >= 0) return { state: 'queued', position: i + 1 };
  return null;
}

function broadcastQueue() {
  const updates = queue.map((j, i) => ({ key: j.key, state: 'queued', position: i + 1 }));
  if (current) updates.push({ key: current.key, state: current.state, partial: current.partial, progress: current.progress });
  broadcast({ cmd: 'update', updates });
  chrome.action.setBadgeText({ text: queue.length + (current ? 1 : 0) ? String(queue.length + (current ? 1 : 0)) : '' });
  chrome.action.setBadgeBackgroundColor({ color: '#128c7e' });
}

async function fetchAudio(job) {
  const tabs = await waTabs();
  const order = [job.tabId, ...tabs.map((t) => t.id).filter((id) => id !== job.tabId)].filter((id) => id != null);
  let lastErr = new Error(chrome.i18n.getMessage('err_no_wa_tab'));
  for (const tabId of order) {
    try {
      const res = await chrome.tabs.sendMessage(tabId, { cmd: 'fetchAudio', key: job.key });
      if (res && res.ok) return res.result;
      lastErr = new Error((res && res.error) || chrome.i18n.getMessage('err_empty_tab_reply'));
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr;
}

// ---------- coda ----------

async function enqueue(key, { tabId, manual, chat, force }) {
  await restore();
  if (!force) {
    const cached = (await getCached([key]))[key];
    if (cached) return { state: 'done', ...cached };
  } else {
    await chrome.storage.local.remove('t:' + key);
  }
  if (current && current.key === key) return stateOf(key);
  const existing = queue.findIndex((j) => j.key === key);
  if (existing >= 0) {
    if (!manual) return stateOf(key);
    queue.splice(existing, 1);
  }
  const job = { key, tabId, manual: !!manual, chat: chat || '', addedAt: Date.now() };
  if (manual) {
    // Le richieste manuali passano davanti a quelle automatiche, in ordine di arrivo tra loro.
    let i = 0;
    while (i < queue.length && queue[i].manual) i++;
    queue.splice(i, 0, job);
  } else {
    queue.push(job);
  }
  await persistQueue();
  broadcastQueue();
  pump();
  return stateOf(key);
}

async function cancel(key) {
  const i = queue.findIndex((j) => j.key === key);
  if (i >= 0) {
    queue.splice(i, 1);
    await persistQueue();
    broadcast({ cmd: 'update', updates: [{ key, state: 'none' }] });
    broadcastQueue();
    return true;
  }
  if (current && current.key === key && current.hostJobId && port) {
    port.postMessage({ type: 'cancel', id: current.hostJobId });
    return true;
  }
  return false;
}

let pumping = false;
async function pump() {
  if (pumping || current || !queue.length) return;
  pumping = true;
  clearTimeout(idleTimer);
  const job = queue.shift();
  current = { ...job, state: 'downloading', partial: '', progress: null, startedAt: Date.now() };
  pumping = false;
  await persistQueue();
  broadcastQueue();
  try {
    connect(); // la porta aperta tiene vivo il service worker anche durante il download
    const audio = await fetchAudio(job);
    current.state = 'waiting';
    broadcastQueue();
    const { id, promise } = hostTranscribe(audio.b64, (ev) => {
      if (ev.type === 'status') current.state = ev.state;
      if (ev.type === 'partial') {
        current.state = 'transcribing';
        current.partial = ev.text;
        current.progress = ev.progress;
      }
      broadcastQueue();
    });
    current.hostJobId = id;
    const res = await promise;
    const record = {
      text: res.text,
      language: res.language,
      duration: res.duration,
      elapsed: res.elapsed,
      chat: job.chat,
      at: Date.now(),
    };
    await chrome.storage.local.set({ ['t:' + job.key]: record });
    broadcast({ cmd: 'update', updates: [{ key: job.key, state: 'done', ...record }] });
  } catch (e) {
    const message = e && e.message ? e.message : String(e);
    broadcast({
      cmd: 'update',
      updates: [{ key: job.key, state: e && e.cancelled ? 'none' : 'error', error: message, hostMissing: isHostSetupError(message) }],
    });
  } finally {
    current = null;
    await persistQueue();
    broadcastQueue();
    if (queue.length) pump();
    else scheduleIdleDisconnect();
  }
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
    return {
      queue: queue.map((j) => ({ key: j.key, chat: j.chat, manual: j.manual })),
      current: current && { key: current.key, chat: current.chat, state: current.state, progress: current.progress, startedAt: current.startedAt },
      hostConnected: !!port,
      lastHostError,
      settings,
    };
  },
  async ping() {
    try {
      const pong = await hostRequest({ type: 'ping' }, ['pong']);
      scheduleIdleDisconnect();
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
    if (current) return { ok: false, error: chrome.i18n.getMessage('err_busy_transcribing') };
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
    clearTimeout(idleTimer);
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
    const keys = queue.map((j) => j.key);
    queue = [];
    await persistQueue();
    broadcast({ cmd: 'update', updates: keys.map((key) => ({ key, state: 'none' })) });
    broadcastQueue();
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

chrome.runtime.onInstalled.addListener((details) => {
  if (details.reason === 'install') chrome.runtime.openOptionsPage();
});

restore();
