'use strict';

const $ = (id) => document.getElementById(id);
const send = (m) => chrome.runtime.sendMessage(m);

const STATE_KEYS = {
  downloading: 'st_downloading',
  waiting: 'st_waiting',
  waiting_lock: 'st_waiting_lock',
  loading_model: 'st_loading_model',
  transcribing: 'st_transcribing',
};

async function refresh() {
  const st = await send({ cmd: 'status' });
  if (!st) return;
  $('autoIncoming').checked = !!st.settings.autoIncoming;
  if (st.current) {
    const p = st.current.progress != null ? ' ' + Math.round(st.current.progress * 100) + '%' : '';
    const state = STATE_KEYS[st.current.state] ? t(STATE_KEYS[st.current.state]) : st.current.state;
    $('current').textContent = t('popup_current', st.current.chat || t('popup_voice'), state + p);
  } else if (st.host && st.host.othersRunning) {
    $('current').textContent = t('popup_other_running');
  } else {
    $('current').textContent = t('popup_idle');
  }
  const info = [];
  if (st.queue.length) info.push(t('popup_queue', st.queue.length));
  if (st.host && st.host.others) info.push(t('popup_queue_others', st.host.others));
  $('queueInfo').textContent = info.join(' · ');
  $('queue').replaceChildren(
    ...st.queue.slice(0, 30).map((j) => {
      const li = document.createElement('li');
      const name = j.chat || t('popup_voice');
      const label = j.manual ? t('popup_item_requested', name) : name;
      li.textContent = j.position ? t('popup_item_pos', j.position, label) : label;
      return li;
    }),
  );
}

async function checkHost() {
  const r = await send({ cmd: 'ping' });
  const el = $('host');
  if (r && r.ok) {
    const model = String((r.pong.config && r.pong.config.model) || '').split(/[\\/]/).filter(Boolean);
    const name = model.find((p) => p.startsWith('models--')) || model[model.length - 1] || '?';
    el.className = 'ok';
    el.textContent = t('popup_host_ready', name.replace('models--', ''), r.pong.config.device || '');
  } else {
    el.className = 'err';
    el.textContent = t('popup_host_unreachable', r ? r.error : t('popup_no_reply'));
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
    $('msg').textContent = t('popup_open_wa');
    return;
  }
  const r = await chrome.tabs.sendMessage(tab.id, { cmd: 'chatVoices' }).catch((e) => ({ ok: false, error: e.message }));
  if (!r || !r.ok) {
    $('msg').textContent = t('box_error', r ? r.error : t('popup_no_reply'));
    return;
  }
  const { keys, title } = r.result;
  if (!keys.length) {
    $('msg').textContent = t('popup_no_voices');
    return;
  }
  let added = 0;
  for (const key of keys) {
    const res = await chrome.runtime.sendMessage({ cmd: 'enqueueFromPopup', key, tabId: tab.id, chat: title });
    if (res && res.state !== 'done') added++;
  }
  $('msg').textContent = added ? t('popup_queued_n', added) : t('popup_all_done');
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
