'use strict';

const $ = (id) => document.getElementById(id);
const send = (m) => chrome.runtime.sendMessage(m);

const STATE = {
  downloading: "scarico l'audio",
  waiting: "invio all'host",
  waiting_lock: "attende un'altra trascrizione",
  loading_model: 'carico il modello',
  transcribing: 'trascrivo',
};

async function refresh() {
  const st = await send({ cmd: 'status' });
  if (!st) return;
  $('autoIncoming').checked = !!st.settings.autoIncoming;
  if (st.current) {
    const p = st.current.progress != null ? ' ' + Math.round(st.current.progress * 100) + '%' : '';
    $('current').textContent = 'In corso: ' + (st.current.chat || 'vocale') + ' (' + (STATE[st.current.state] || st.current.state) + p + ')';
  } else {
    $('current').textContent = 'Nessuna trascrizione in corso.';
  }
  $('queueInfo').textContent = st.queue.length ? 'In coda: ' + st.queue.length : '';
  $('queue').replaceChildren(
    ...st.queue.slice(0, 30).map((j) => {
      const li = document.createElement('li');
      li.textContent = (j.chat || 'vocale') + (j.manual ? ' (richiesto)' : '');
      return li;
    }),
  );
}

async function checkHost() {
  const r = await send({ cmd: 'ping' });
  const el = $('host');
  if (r && r.ok) {
    const model = String(r.pong.config && r.pong.config.model || '').split(/[\\/]/).filter(Boolean);
    const name = model.find((p) => p.startsWith('models--')) || model[model.length - 1] || '?';
    el.className = 'ok';
    el.textContent = 'Host pronto · ' + name.replace('models--', '') + ' · ' + (r.pong.config.device || '');
  } else {
    el.className = 'err';
    el.textContent = 'Host non raggiungibile: ' + (r ? r.error : 'nessuna risposta');
  }
}

$('autoIncoming').addEventListener('change', async (ev) => {
  const s = (await chrome.storage.local.get('settings')).settings || {};
  s.autoIncoming = ev.target.checked;
  await chrome.storage.local.set({ settings: s });
});

$('chat').addEventListener('click', async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !/^https:\/\/web\.whatsapp\.com\//.test(tab.url || '')) {
    $('msg').textContent = 'Apri una chat in WhatsApp Web in questa scheda.';
    return;
  }
  const r = await chrome.tabs.sendMessage(tab.id, { cmd: 'chatVoices' }).catch((e) => ({ ok: false, error: e.message }));
  if (!r || !r.ok) {
    $('msg').textContent = 'Errore: ' + (r ? r.error : 'nessuna risposta');
    return;
  }
  const { keys, title } = r.result;
  if (!keys.length) {
    $('msg').textContent = 'Nessun vocale caricato nella chat aperta. Scorri verso l\'alto per caricarne altri.';
    return;
  }
  let added = 0;
  for (const key of keys) {
    const res = await chrome.runtime.sendMessage({ cmd: 'enqueueFromPopup', key, tabId: tab.id, chat: title });
    if (res && res.state !== 'done') added++;
  }
  $('msg').textContent = added ? added + ' vocali messi in coda.' : 'Tutti i vocali caricati sono già trascritti.';
  refresh();
});

$('clearQueue').addEventListener('click', async () => {
  await send({ cmd: 'clearQueue' });
  refresh();
});

$('options').addEventListener('click', () => chrome.runtime.openOptionsPage());

refresh();
checkHost();
setInterval(refresh, 1000);
