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
  return n >= 1e9
    ? (n / 1e9).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' GB'
    : Math.round(n / 1e6).toLocaleString() + ' MB';
}

function kvTable(rows) {
  const table = document.createElement('table');
  table.className = 'kv';
  for (const [k, v] of rows) {
    const tr = table.insertRow();
    tr.insertCell().textContent = k;
    tr.insertCell().textContent = v == null ? '' : String(v);
  }
  return table;
}

// ---------- passo 1: componente locale ----------

function explain(error) {
  if (/not found/i.test(error)) return '';
  if (/forbidden/i.test(error)) return t('opt_err_forbidden', chrome.runtime.id);
  if (/exited|communicating/i.test(error)) return t('opt_err_exited');
  return error;
}

async function checkHost() {
  const r = await send({ cmd: 'ping' });
  if (r && r.ok) {
    hostOk = true;
    stopPolling();
    const p = r.pong;
    $('hostStatus').className = 'ok';
    $('hostStatus').textContent = t('opt_host_installed', p.host_version);
    $('hostMissing').hidden = true;
    setStep('step1', 'done');
    fillAdvanced(p);
    send({ cmd: 'updateInfo' }).then(showLastCheck);
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
  const time = new Date().toLocaleTimeString();
  if (!el.querySelector('.wwt-search-spinner')) {
    const spinner = document.createElement('span');
    spinner.className = 'wwt-search-spinner';
    const text = document.createElement('span');
    text.className = 'searching-text';
    text.textContent = t('opt_searching');
    const last = document.createElement('span');
    last.className = 'muted searching-time';
    el.replaceChildren(spinner, text, last);
  }
  el.querySelector('.searching-time').textContent = t('opt_last_check', time);
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

// Nota sotto il nome del modello: dal catalogo per i modelli proposti, "cartella" per un percorso.
const MODEL_NOTES = { 'large-v3': 'note_large_v3', 'large-v3-turbo': 'note_large_v3_turbo', small: 'note_small' };
function modelNote(m) {
  if (MODEL_NOTES[m.name]) return t(MODEL_NOTES[m.name]);
  if (!m.size_gb) return t('note_folder');
  return '';
}

async function loadModels() {
  const r = await send({ cmd: 'models' });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = t('opt_error', r ? r.error : '?');
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
      name.textContent = m.current ? t('opt_model_in_use', m.name) : m.name;
      const sub = document.createElement('div');
      sub.className = 'muted';
      const parts = [];
      const note = modelNote(m);
      if (note) parts.push(note);
      if (m.size_gb) parts.push(t('opt_size', m.size_gb.toLocaleString()));
      parts.push(t(m.path ? 'opt_on_disk' : 'opt_to_download'));
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
        b.textContent = t('opt_use_this');
        b.addEventListener('click', () => useModel(m.name));
        row.append(b);
      } else if (!m.path && m.size_gb) {
        const b = document.createElement('button');
        b.className = 'primary';
        b.textContent = t(downloading === m.name ? 'opt_downloading' : 'opt_download');
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
    $('modelMsg').textContent = t('opt_error', r ? r.error : '?');
  }
  await loadModels();
}

async function download(name) {
  const r = await send({ cmd: 'downloadModel', name });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = t('opt_error', r ? r.error : '?');
    return;
  }
  downloading = name;
  $('modelMsg').className = 'muted';
  $('modelMsg').textContent = t('opt_download_started', name);
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
    const amount = ev.total ? t('opt_of', fmtBytes(ev.downloaded), fmtBytes(ev.total)) : fmtBytes(ev.downloaded);
    $('modelMsg').textContent = t('opt_download_progress', ev.name, amount);
  } else if (ev.type === 'download_done') {
    downloading = null;
    $('modelMsg').className = 'ok';
    $('modelMsg').textContent = t('opt_download_done', ev.name);
    renderModels(ev.models);
  } else if (ev.type === 'download_error') {
    downloading = null;
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = t('opt_download_failed', ev.message);
    loadModels();
  }
});

$('pickFolder').addEventListener('click', async () => {
  $('modelMsg').className = 'muted';
  $('modelMsg').textContent = t('opt_pick_hint');
  const r = await send({ cmd: 'pickFolder' });
  if (!r || !r.ok) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = t('opt_error', r ? r.error : '?');
    return;
  }
  if (!r.path) {
    $('modelMsg').textContent = '';
    return;
  }
  if (!r.valid) {
    $('modelMsg').className = 'err';
    $('modelMsg').textContent = t('opt_pick_invalid', r.path);
    return;
  }
  await useModel(r.path);
  $('modelMsg').className = 'ok';
  $('modelMsg').textContent = t('opt_pick_ok', r.path);
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
    $('saved').textContent = t('opt_saved');
    setTimeout(() => ($('saved').textContent = ''), 1500);
  }, 300);
}

for (const id of ['autoIncoming', 'autoOutgoing', 'autoVisible', 'vadFilter', 'language', 'initialPrompt', 'beamSize', 'idleMinutes']) {
  $(id).addEventListener('input', saveSettings);
  $(id).addEventListener('change', saveSettings);
}

// ---------- GPU NVIDIA ----------

let cudaState = null;
let cudaInstalling = false;

function renderCuda() {
  const box = $('cudaBox');
  const device = $('hDevice').value;
  const st = cudaState;
  const driverPresent = !!(st && st.driver && st.driver.present);
  box.hidden = device === 'cpu' && !driverPresent && !cudaInstalling;
  if (box.hidden) return;
  const drv = $('cudaDriver');
  const libs = $('cudaLibs');
  const btn = $('cudaInstall');
  if (!st) {
    drv.className = 'err';
    drv.textContent = t('opt_cuda_old_host');
    libs.textContent = '';
    btn.hidden = true;
    return;
  }
  if (st.driver.usable) {
    drv.className = 'ok';
    drv.textContent = t('opt_cuda_driver_ok', st.driver.version_str, (st.driver.names || []).join(', ') || '?');
  } else if (driverPresent) {
    drv.className = 'err';
    drv.textContent = t('opt_cuda_driver_old', st.driver.version_str || '?');
  } else {
    drv.className = 'err';
    drv.innerHTML = t('opt_cuda_no_driver');
  }
  if (st.libs.installed) {
    libs.className = 'ok';
    const v = st.libs.versions || {};
    libs.textContent = t('opt_cuda_libs_ok', v.cublas || '?', v.cudnn || '?');
  } else {
    libs.className = 'muted';
    libs.textContent = t('opt_cuda_libs_missing', fmtBytes(st.libs.download_size || 0));
  }
  btn.hidden = st.libs.installed && !cudaInstalling;
  btn.disabled = cudaInstalling || !st.driver.usable;
  btn.textContent = t(st.libs.installed ? 'opt_cuda_redownload' : 'opt_cuda_download');
  if (!cudaInstalling) {
    const msg = $('cudaMsg');
    if (st.last_error) {
      msg.className = 'err';
      msg.textContent = t('opt_cuda_last_error', st.last_error);
    } else if (device !== 'cpu' && /^int8$|float32/.test($('hCompute').value)) {
      msg.className = 'muted';
      msg.textContent = t('opt_cuda_compute_hint');
    } else {
      msg.textContent = '';
    }
  }
}

$('hDevice').addEventListener('change', renderCuda);
$('hCompute').addEventListener('change', renderCuda);

$('cudaInstall').addEventListener('click', async () => {
  const r = await send({ cmd: 'cudaInstall' });
  if (!r || !r.ok) {
    $('cudaMsg').className = 'err';
    $('cudaMsg').textContent = t('opt_error', r ? r.error : '?');
    return;
  }
  cudaInstalling = true;
  $('cudaBar').hidden = false;
  $('cudaBar').removeAttribute('value');
  $('cudaMsg').className = 'muted';
  $('cudaMsg').textContent = t('opt_cuda_started');
  renderCuda();
});

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.cmd !== 'hostEvent') return;
  const ev = msg.event;
  if (ev.type === 'cuda_progress') {
    cudaInstalling = true;
    const bar = $('cudaBar');
    bar.hidden = false;
    if (ev.total) {
      bar.max = ev.total;
      bar.value = ev.downloaded;
    }
    $('cudaMsg').className = 'muted';
    $('cudaMsg').textContent = ev.stage === 'extract'
      ? t('opt_cuda_extracting')
      : t('opt_cuda_progress', t('opt_of', fmtBytes(ev.downloaded), fmtBytes(ev.total)));
  } else if (ev.type === 'cuda_done' || ev.type === 'cuda_error') {
    cudaInstalling = false;
    $('cudaBar').hidden = true;
    if (ev.cuda) cudaState = ev.cuda;
    renderCuda();
    $('cudaMsg').className = ev.type === 'cuda_done' ? 'ok' : 'err';
    $('cudaMsg').textContent = ev.type === 'cuda_done' ? t('opt_cuda_done') : t('opt_cuda_failed', ev.message);
  }
});

function fillAdvanced(p) {
  const cfg = p.config || {};
  cudaState = p.cuda || null;
  $('hDevice').value = cfg.device || 'cpu';
  $('hCompute').value = cfg.compute_type || 'int8';
  $('hThreads').value = cfg.cpu_threads || 0;
  $('saveHost').disabled = false;
  $('hostDetails').replaceChildren(kvTable([
    [t('opt_d_component'), p.frozen ? p.host_version : t('opt_d_from_source', p.host_version)],
    [t('opt_d_exe'), p.executable],
    ['faster-whisper', p.faster_whisper || p.faster_whisper_error],
    ['ctranslate2', (p.ctranslate2 || '?') + ' · ' + (p.cuda_devices ? t('opt_d_cuda', p.cuda_devices) : t('opt_d_nocuda'))],
    [t('opt_d_model'), cfg.model],
    [t('opt_d_model_dir'), p.model_path || t('opt_d_not_found')],
    [t('opt_d_data'), p.data_dir],
  ]));
  renderCuda();
}

$('saveHost').addEventListener('click', async () => {
  $('hostSaved').className = 'muted';
  $('hostSaved').textContent = t('opt_applying');
  const r = await send({
    cmd: 'setHostConfig',
    config: {
      device: $('hDevice').value,
      compute_type: $('hCompute').value,
      cpu_threads: Number($('hThreads').value) || 0,
    },
  });
  $('hostSaved').className = r && r.ok ? 'ok' : 'err';
  $('hostSaved').textContent = r && r.ok ? t('opt_applied') : t('opt_error', r ? r.error : '?');
  if (r && r.ok) fillAdvanced(r.pong);
});

// ---------- archivio ----------

async function cacheInfo() {
  const s = await send({ cmd: 'cacheStats' });
  $('cacheInfo').textContent = s ? t('opt_cache_info', s.count, Math.round(s.bytes / 1024).toLocaleString()) : '';
}

$('clearCache').addEventListener('click', async () => {
  if (!confirm(t('opt_clear_confirm'))) return;
  await send({ cmd: 'clearCache' });
  cacheInfo();
});

// ---------- aggiornamenti ----------

// Riga in cima alla pagina: versioni installate, "Controlla ora" e ora dell'ultimo controllo.
// L'avviso, se c'è, compare subito sotto (update-ui.js).
function showLastCheck(info) {
  if (!info) return;
  $('updVersions').textContent = t('upd_versions', info.current.extension, info.current.host || t('upd_unknown'));
  $('updLast').textContent = info.checkedAt ? t('upd_last_check', new Date(info.checkedAt).toLocaleString()) : '';
}

$('checkUpdates').addEventListener('click', async () => {
  $('updStatus').className = 'muted';
  $('updStatus').textContent = t('upd_checking');
  const r = await send({ cmd: 'checkUpdates' });
  const info = r && r.info;
  if (!r || !r.ok) {
    $('updStatus').className = 'err';
    $('updStatus').textContent = t('upd_check_failed', r ? r.error : '?');
  } else if (info.available) {
    $('updStatus').textContent = '';
    if (window.wwtUpdateUi) window.wwtUpdateUi.show({ ...info, dismissed: false });
  } else {
    $('updStatus').className = 'ok';
    $('updStatus').textContent = t('upd_none', info.current.extension, info.current.host || t('upd_unknown'));
  }
  showLastCheck(info);
});

send({ cmd: 'updateInfo' }).then(showLastCheck);

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !hostOk) checkHost();
});

(async () => {
  await loadSettings();
  cacheInfo();
  await checkHost();
})();
