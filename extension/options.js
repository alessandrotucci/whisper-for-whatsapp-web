'use strict';

const $ = (id) => document.getElementById(id);
const send = (m) => chrome.runtime.sendMessage(m);

const REPO = 'alessandrotucci/whisper-for-whatsapp-web';
const INSTALLER_URL = 'https://github.com/' + REPO + '/releases/latest/download/WhisperForWhatsAppWeb-Setup.exe';

const DEFAULTS = {
  autoIncoming: true,
  autoOutgoing: false,
  autoVisible: false,
  language: '',
  initialPrompt: '',
  beamSize: 5,
  vadFilter: true,
  idleMinutes: 10,
};

let hostOk = false;
let modelReady = false;
let pollTimer = null;
let downloading = null;

function setStep(id, state) {
  $(id).dataset.state = state;
}

function fmtBytes(n) {
  if (n == null) return '?';
  return n >= 1e9 ? (n / 1e9).toFixed(2) + ' GB' : Math.round(n / 1e6) + ' MB';
}

function kvTable(rows) {
  const t = document.createElement('table');
  t.className = 'kv';
  for (const [k, v] of rows) {
    const tr = t.insertRow();
    tr.insertCell().textContent = k;
    tr.insertCell().textContent = v == null ? '' : String(v);
  }
  return t;
}

// ---------- passo 1: componente locale ----------

function explain(error) {
  if (/not found/i.test(error)) return '';
  if (/forbidden/i.test(error)) return "Il componente locale è installato ma non riconosce questa estensione (ID " + chrome.runtime.id + "). Reinstallalo dall'ultima versione.";
  if (/exited|communicating/i.test(error)) return 'Il componente locale si è chiuso subito: reinstallalo. Il registro degli errori è in %LOCALAPPDATA%\\WhisperForWhatsAppWeb\\whisper_host.log.';
  return error;
}

async function checkHost() {
  const r = await send({ cmd: 'ping' });
  if (r && r.ok) {
    hostOk = true;
    stopPolling();
    const p = r.pong;
    $('hostStatus').className = 'ok';
    $('hostStatus').textContent = 'Installato (versione ' + p.host_version + ').';
    $('hostMissing').hidden = true;
    setStep('step1', 'done');
    fillAdvanced(p);
    if (r.downloading) downloading = r.downloading;
    await loadModels();
  } else {
    hostOk = false;
    showSearching();
    $('hostMissing').hidden = false;
    $('hostError').textContent = explain(r ? r.error : '');
    setStep('step1', 'searching');
    setStep('step2', 'locked');
    setStep('step3', 'locked');
    startPolling();
  }
}

// Riga di stato del passo 1 mentre il componente manca: indicatore animato e ora dell'ultimo controllo.
function showSearching() {
  const el = $('hostStatus');
  el.className = 'searching';
  const time = new Date().toLocaleTimeString('it-IT');
  if (!el.querySelector('.wwt-search-spinner')) {
    const spinner = document.createElement('span');
    spinner.className = 'wwt-search-spinner';
    const text = document.createElement('span');
    text.className = 'searching-text';
    text.textContent = 'Cerco il componente locale sul computer…';
    const last = document.createElement('span');
    last.className = 'muted searching-time';
    el.replaceChildren(spinner, text, last);
  }
  el.querySelector('.searching-time').textContent = 'ultimo controllo alle ' + time + ', riprovo ogni 3 secondi';
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(() => {
    if (!document.hidden) checkHost();
  }, 3000);
}

function stopPolling() {
  clearInterval(pollTimer);
  pollTimer = null;
}

$('installerLink').href = INSTALLER_URL;

// ---------- passo 2: modello ----------

async function loadModels() {
  const r = await send({ cmd: 'models' });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'Errore: ' + (r ? r.error : '?');
    return;
  }
  if (r.downloading) downloading = r.downloading;
  renderModels(r.models);
}

function renderModels(models) {
  const current = models.find((m) => m.current);
  modelReady = !!(current && current.path);
  setStep('step2', modelReady ? 'done' : 'active');
  setStep('step3', modelReady ? 'done' : 'locked');
  $('models').replaceChildren(
    ...models.map((m) => {
      const row = document.createElement('div');
      row.className = 'model' + (m.current ? ' current' : '');
      row.dataset.name = m.name;
      const info = document.createElement('div');
      info.className = 'grow';
      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = m.name + (m.current ? ' · in uso' : '');
      const sub = document.createElement('div');
      sub.className = 'muted';
      const parts = [];
      if (m.note) parts.push(m.note);
      if (m.size_gb) parts.push('≈' + String(m.size_gb).replace('.', ',') + ' GB');
      parts.push(m.path ? 'sul disco' : 'da scaricare');
      sub.textContent = parts.join(' · ');
      info.append(name, sub);
      if (downloading === m.name) {
        const bar = document.createElement('progress');
        bar.id = 'bar-' + m.name;
        info.append(bar);
      }
      row.append(info);
      if (m.path && !m.current) {
        const b = document.createElement('button');
        b.textContent = 'Usa questo';
        b.addEventListener('click', () => useModel(m.name));
        row.append(b);
      } else if (!m.path && m.size_gb) {
        const b = document.createElement('button');
        b.className = 'primary';
        b.textContent = downloading === m.name ? 'Download…' : 'Scarica';
        b.disabled = !!downloading;
        b.addEventListener('click', () => download(m.name));
        row.append(b);
      }
      return row;
    }),
  );
}

async function useModel(name) {
  $('modelMsg').className = 'muted';
  $('modelMsg').textContent = '';
  const r = await send({ cmd: 'setHostConfig', config: { model: name } });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'Errore: ' + (r ? r.error : '?');
  }
  await loadModels();
}

async function download(name) {
  const r = await send({ cmd: 'downloadModel', name });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'Errore: ' + (r ? r.error : '?');
    return;
  }
  downloading = name;
  $('modelMsg').className = 'muted';
  $('modelMsg').textContent = 'Download di ' + name + ' avviato: puoi lasciare aperta questa pagina o chiuderla.';
  loadModels();
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.cmd !== 'hostEvent') return;
  const ev = msg.event;
  if (ev.type === 'download_progress') {
    downloading = ev.name;
    const bar = $('bar-' + ev.name);
    if (bar && ev.total) {
      bar.max = ev.total;
      bar.value = ev.downloaded;
    }
    $('modelMsg').className = 'muted';
    $('modelMsg').textContent = 'Download di ' + ev.name + ': ' + fmtBytes(ev.downloaded) + (ev.total ? ' di ' + fmtBytes(ev.total) : '');
  } else if (ev.type === 'download_done') {
    downloading = null;
    $('modelMsg').className = 'ok';
    $('modelMsg').textContent = 'Modello ' + ev.name + ' scaricato e in uso.';
    renderModels(ev.models);
  } else if (ev.type === 'download_error') {
    downloading = null;
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'Download non riuscito: ' + ev.message;
    loadModels();
  }
});

$('pickFolder').addEventListener('click', async () => {
  $('modelMsg').className = 'muted';
  $('modelMsg').textContent = 'Scegli la cartella nella finestra di Windows (potrebbe aprirsi dietro al browser)…';
  const r = await send({ cmd: 'pickFolder' });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'Errore: ' + (r ? r.error : '?');
    return;
  }
  if (!r.path) {
    $('modelMsg').textContent = '';
    return;
  }
  if (!r.valid) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = 'In ' + r.path + ' non c\'è un modello faster-whisper (servono model.bin e config.json).';
    return;
  }
  await useModel(r.path);
  $('modelMsg').className = 'ok';
  $('modelMsg').textContent = 'In uso il modello in ' + r.path + '.';
});

// ---------- impostazioni ----------

async function loadSettings() {
  const s = { ...DEFAULTS, ...((await chrome.storage.local.get('settings')).settings || {}) };
  for (const k of ['autoIncoming', 'autoOutgoing', 'autoVisible', 'vadFilter']) $(k).checked = !!s[k];
  for (const k of ['language', 'initialPrompt', 'beamSize', 'idleMinutes']) $(k).value = s[k];
}

let saveTimer = null;
function saveSettings() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    const s = {
      autoIncoming: $('autoIncoming').checked,
      autoOutgoing: $('autoOutgoing').checked,
      autoVisible: $('autoVisible').checked,
      vadFilter: $('vadFilter').checked,
      language: $('language').value.trim().toLowerCase(),
      initialPrompt: $('initialPrompt').value,
      beamSize: Math.min(10, Math.max(1, Number($('beamSize').value) || 5)),
      idleMinutes: Math.min(240, Math.max(1, Number($('idleMinutes').value) || 10)),
    };
    await chrome.storage.local.set({ settings: s });
    $('saved').textContent = 'Impostazioni salvate.';
    setTimeout(() => ($('saved').textContent = ''), 1500);
  }, 300);
}

for (const id of ['autoIncoming', 'autoOutgoing', 'autoVisible', 'vadFilter', 'language', 'initialPrompt', 'beamSize', 'idleMinutes']) {
  $(id).addEventListener('input', saveSettings);
  $(id).addEventListener('change', saveSettings);
}

function fillAdvanced(p) {
  const cfg = p.config || {};
  $('hDevice').value = cfg.device || 'cpu';
  $('hCompute').value = cfg.compute_type || 'int8';
  $('hThreads').value = cfg.cpu_threads || 0;
  $('saveHost').disabled = false;
  $('hostDetails').replaceChildren(kvTable([
    ['Componente locale', p.host_version + (p.frozen ? '' : ' (da sorgente)')],
    ['Eseguibile', p.executable],
    ['faster-whisper', p.faster_whisper || p.faster_whisper_error],
    ['ctranslate2', (p.ctranslate2 || '?') + (p.cuda_devices ? ' · GPU CUDA: ' + p.cuda_devices : ' · nessuna GPU CUDA')],
    ['Modello', cfg.model],
    ['Cartella del modello', p.model_path || 'non trovata'],
    ['Dati e registro', p.data_dir],
  ]));
}

$('saveHost').addEventListener('click', async () => {
  $('hostSaved').className = 'muted';
  $('hostSaved').textContent = 'Applico…';
  const r = await send({
    cmd: 'setHostConfig',
    config: {
      device: $('hDevice').value,
      compute_type: $('hCompute').value,
      cpu_threads: Number($('hThreads').value) || 0,
    },
  });
  $('hostSaved').className = r && r.ok ? 'ok' : 'err';
  $('hostSaved').textContent = r && r.ok ? 'Applicato: vale dalla prossima trascrizione.' : 'Errore: ' + (r ? r.error : '?');
  if (r && r.ok) fillAdvanced(r.pong);
});

// ---------- archivio ----------

async function cacheInfo() {
  const s = await send({ cmd: 'cacheStats' });
  $('cacheInfo').textContent = s ? s.count + ' trascrizioni salvate (' + Math.round(s.bytes / 1024) + ' KB)' : '';
}

$('clearCache').addEventListener('click', async () => {
  if (!confirm('Cancellare tutte le trascrizioni salvate? Non si possono recuperare.')) return;
  await send({ cmd: 'clearCache' });
  cacheInfo();
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !hostOk) checkHost();
});

(async () => {
  await loadSettings();
  cacheInfo();
  await checkHost();
})();
