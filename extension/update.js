// Aggiornamenti di estensione e componente locale dalle release su GitHub.
// Caricato da background.js con importScripts: usa hostRequest, port, hostBusy,
// fetching, pumpFetch, updating, updateAvailable, refreshBadge, handlers e notifyPages.
//
// Controllo: una volta al giorno (allarme "update-check") si leggono l'ultima release
// e il suo version.json, {"extension": "x.y.z", "host": "x.y.z"}, dall'API di GitHub;
// si confrontano con la versione del manifest e con quella dichiarata dal componente
// nell'ultimo ping. Il risultato sta in chrome.storage.local, chiave "updateInfo".
//
// Aggiornamento: prima il componente (l'installer silenzioso lo sostituisce; il ping
// successivo deve riportare la versione attesa), poi l'estensione (il componente
// scrive lo zip nella cartella e l'estensione si ricarica). Un componente più vecchio
// di HOST_SELF_UPDATE non conosce i comandi di aggiornamento: in quel caso l'installer
// si scarica nel browser e l'utente lo apre come la prima volta.

const REPO = 'alessandrotucci/whisper-for-whatsapp-web';
const API = 'https://api.github.com/repos/' + REPO;
const DOWNLOAD = 'https://github.com/' + REPO + '/releases/download/';
const INSTALLER_ASSET = 'WhisperForWhatsAppWeb-Setup.exe';
const EXTENSION_ASSET = 'whisper-for-whatsapp-web-extension.zip';
const HOST_SELF_UPDATE = '1.3.0';
const CHECK_EVERY_MIN = 24 * 60;
const HOST_RESTART_TIMEOUT_MS = 3 * 60 * 1000;

function cmpVersion(a, b) {
  const pa = String(a || '0').split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b || '0').split('.').map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d < 0 ? -1 : 1;
  }
  return 0;
}

async function githubJson(path) {
  const r = await fetch(API + path, { headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store' });
  if (!r.ok) throw new Error('GitHub ' + r.status);
  return r.json();
}

// Stato da mostrare: versioni installate, ultime pubblicate e cosa va aggiornato.
async function computeInfo(latest) {
  const { hostVersion, updateDismissed } = await chrome.storage.local.get(['hostVersion', 'updateDismissed']);
  const ext = chrome.runtime.getManifest().version;
  if (!latest) return { current: { extension: ext, host: hostVersion || null } };
  const needExt = cmpVersion(ext, latest.extension) < 0;
  const needHost = !!hostVersion && cmpVersion(hostVersion, latest.host) < 0;
  return {
    ...latest,
    current: { extension: ext, host: hostVersion || null },
    needExt,
    needHost,
    hostSelfUpdate: !!hostVersion && cmpVersion(hostVersion, HOST_SELF_UPDATE) >= 0,
    available: needExt || needHost,
    dismissed: !!updateDismissed && updateDismissed.tag === latest.tag && updateDismissed.until > Date.now(),
  };
}

async function publishInfo() {
  const { updateLatest } = await chrome.storage.local.get('updateLatest');
  const info = await computeInfo(updateLatest);
  updateAvailable = !!info.available;
  refreshBadge();
  return info;
}

async function checkForUpdates() {
  const rel = await githubJson('/releases/latest');
  const file = await githubJson('/contents/version.json?ref=' + encodeURIComponent(rel.tag_name));
  const versions = JSON.parse(atob(String(file.content || '').replace(/\s/g, '')));
  const latest = {
    tag: rel.tag_name,
    notesUrl: rel.html_url,
    extension: versions.extension,
    host: versions.host,
    checkedAt: Date.now(),
  };
  // La versione del componente si rilegge solo se è già installato: il ping lo avvia.
  const { hostVersion } = await chrome.storage.local.get('hostVersion');
  if (hostVersion && !hostBusy() && !updating) {
    try {
      const pong = await hostRequest({ type: 'ping' }, ['pong']);
      await chrome.storage.local.set({ hostVersion: pong.host_version });
      scheduleIdleDisconnect();
    } catch (_) {
      // componente non raggiungibile: resta l'ultima versione nota
    }
  }
  await chrome.storage.local.set({ updateLatest: latest });
  return publishInfo();
}

function progress(stage, extra = {}) {
  notifyPages({ cmd: 'updateProgress', stage, ...extra });
}

async function waitIdleQueue() {
  while (hostBusy() || fetching) await new Promise((r) => setTimeout(r, 1000));
}

// Dopo l'avvio dell'installer: il componente vecchio viene terminato, poi un ping deve
// rispondere con la versione attesa.
async function waitHostVersion(expected) {
  const deadline = Date.now() + HOST_RESTART_TIMEOUT_MS;
  await new Promise((r) => setTimeout(r, 4000));
  while (Date.now() < deadline) {
    if (port) {
      try {
        port.disconnect();
      } catch (_) { /* già chiusa */ }
      port = null;
    }
    try {
      const pong = await hostRequest({ type: 'ping' }, ['pong'], { timeoutMs: 15000 });
      if (cmpVersion(pong.host_version, expected) >= 0) {
        await chrome.storage.local.set({ hostVersion: pong.host_version });
        return pong.host_version;
      }
    } catch (_) {
      // installer ancora al lavoro
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error(chrome.i18n.getMessage('upd_err_host_timeout'));
}

async function applyUpdate() {
  if (updating) return { ok: false, error: chrome.i18n.getMessage('upd_err_running') };
  const info = await publishInfo();
  if (!info.available) return { ok: true, nothing: true };
  updating = true;
  updateKeepAlive();
  try {
    if (hostBusy() || fetching) progress('waiting_queue');
    await waitIdleQueue();
    if (info.needHost) {
      const url = DOWNLOAD + info.tag + '/' + INSTALLER_ASSET;
      if (!info.hostSelfUpdate) {
        await chrome.tabs.create({ url });
        progress('manual_host');
        return { ok: true, manual: true };
      }
      progress('host_download');
      let last = null;
      try {
        await hostRequest({ type: 'update_host', url }, ['update_host_started'], {
          timeoutMs: 0,
          onEvent: (ev) => {
            if (ev.type !== 'update_progress') return;
            last = ev;
            progress('host_download', { downloaded: ev.downloaded, total: ev.total });
          },
        });
      } catch (e) {
        // L'installer può terminare il componente prima della conferma: a download
        // completo si prosegue e decide il ping sulla versione nuova.
        if (!(last && last.total && last.downloaded >= last.total)) throw e;
      }
      progress('host_install');
      await waitHostVersion(info.host);
    }
    if (info.needExt) {
      progress('ext_download');
      const done = await hostRequest(
        { type: 'update_extension', url: DOWNLOAD + info.tag + '/' + EXTENSION_ASSET, ext_id: chrome.runtime.id, expect: info.extension },
        ['update_extension_done'],
        {
          timeoutMs: 0,
          onEvent: (ev) => ev.type === 'update_progress' && progress('ext_download', { downloaded: ev.downloaded, total: ev.total }),
        },
      );
      if (!done.updated.length) throw new Error(chrome.i18n.getMessage('upd_err_no_folder'));
      progress('ext_reload');
      setTimeout(() => chrome.runtime.reload(), 1500);
      return { ok: true, reloading: true };
    }
    progress('done');
    await publishInfo();
    return { ok: true };
  } catch (e) {
    progress('error', { message: e.message });
    return { ok: false, error: e.message };
  } finally {
    updating = false;
    updateKeepAlive();
    pumpFetch();
  }
}

handlers.updateInfo = async () => publishInfo();
handlers.checkUpdates = async () => {
  try {
    return { ok: true, info: await checkForUpdates() };
  } catch (e) {
    return { ok: false, error: e.message, info: await publishInfo() };
  }
};
handlers.applyUpdate = () => {
  applyUpdate();
  return { ok: true };
};
handlers.dismissUpdate = async ({ tag }) => {
  await chrome.storage.local.set({ updateDismissed: { tag, until: Date.now() + 20 * 3600 * 1000 } });
  return publishInfo();
};

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'update-check') checkForUpdates().catch(() => {});
});

(async () => {
  const existing = await chrome.alarms.get('update-check');
  if (!existing) chrome.alarms.create('update-check', { delayInMinutes: 1, periodInMinutes: CHECK_EVERY_MIN });
  publishInfo();
})();
