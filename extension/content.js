// Mondo isolato di web.whatsapp.com: interfaccia sotto i vocali e ponte tra page.js e il service worker.
//
// page.js marca gli elementi dei vocali con data-wwt-voice="1" e data-wwt-key;
// qui, per ognuno, si aggiunge sotto la bolla un riquadro .wwt-box che mostra
// lo stato della trascrizione (pulsante, in coda, in corso, testo, errore).
(() => {
  'use strict';
  if (window.__wwtContentLoaded) return;
  window.__wwtContentLoaded = true;

  const states = new Map(); // key -> stato ricevuto dal service worker
  const asked = new Set(); // key per cui lo stato è già stato chiesto
  let settings = { autoVisible: false, autoOutgoing: false };

  const alive = () => !!(chrome.runtime && chrome.runtime.id);

  function send(message) {
    if (!alive()) return Promise.resolve(null);
    return chrome.runtime.sendMessage(message).catch(() => null);
  }

  chrome.storage.local.get('settings').then((s) => {
    settings = { ...settings, ...(s.settings || {}) };
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.settings) settings = { ...settings, ...(changes.settings.newValue || {}) };
  });

  // ---------- ponte verso page.js ----------

  let reqSeq = 0;
  const waiting = new Map();

  function pageRequest(op, data = {}, timeoutMs = 60000) {
    const reqId = 'r' + (++reqSeq) + '-' + Math.random().toString(36).slice(2);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        waiting.delete(reqId);
        reject(new Error('WhatsApp Web non ha risposto'));
      }, timeoutMs);
      waiting.set(reqId, { resolve, reject, timer });
      window.postMessage({ __wwt: 'req', reqId, op, ...data }, location.origin);
    });
  }

  window.addEventListener('message', (ev) => {
    if (ev.source !== window || !ev.data) return;
    const d = ev.data;
    if (d.__wwt === 'res') {
      const w = waiting.get(d.reqId);
      if (!w) return;
      waiting.delete(d.reqId);
      clearTimeout(w.timer);
      if (d.ok) w.resolve(d.result);
      else w.reject(new Error(d.error));
    } else if (d.__wwt === 'evt' && d.type === 'newVoice') {
      send({ cmd: 'autoEnqueue', key: d.key, fromMe: d.fromMe, chat: d.chat });
    }
  });

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg.cmd === 'fetchAudio') {
      pageRequest('fetchAudio', { key: msg.key })
        .then((result) => sendResponse({ ok: true, result }))
        .catch((e) => sendResponse({ ok: false, error: e.message }));
      return true;
    }
    if (msg.cmd === 'chatVoices') {
      pageRequest('chatVoices')
        .then((result) => sendResponse({ ok: true, result }))
        .catch((e) => sendResponse({ ok: false, error: e.message }));
      return true;
    }
    if (msg.cmd === 'update') {
      for (const u of msg.updates) {
        const prev = states.get(u.key) || {};
        // Un aggiornamento di coda non cancella un testo già pronto.
        if (prev.state === 'done' && u.state === 'queued') continue;
        states.set(u.key, u.state === 'none' ? { state: 'none' } : { ...(u.state === prev.state ? prev : {}), ...u });
        renderKey(u.key);
      }
      return false;
    }
    if (msg.cmd === 'reset') {
      states.clear();
      asked.clear();
      document.querySelectorAll('.wwt-box').forEach((b) => b.remove());
      return false;
    }
    return false;
  });

  // ---------- interfaccia ----------

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function button(label, title, onClick) {
    const b = el('button', 'wwt-btn', label);
    b.type = 'button';
    if (title) b.title = title;
    b.addEventListener('click', (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      onClick(ev);
    });
    return b;
  }

  function fmtDuration(s) {
    if (!s && s !== 0) return '';
    s = Math.round(s);
    return s >= 60 ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') + ' min' : s + ' s';
  }

  const STATE_LABEL = {
    queued: 'In coda',
    downloading: "Scarico l'audio…",
    waiting: "Invio all'host…",
    waiting_lock: "In attesa che finisca un'altra trascrizione…",
    loading_model: 'Carico il modello…',
    transcribing: 'Trascrivo…',
  };

  function requestTranscription(key, force) {
    const chatEl = document.querySelector('#main header span[dir="auto"]');
    states.set(key, { state: 'queued' });
    renderKey(key);
    send({ cmd: 'enqueue', key, manual: true, force: !!force, chat: chatEl ? chatEl.textContent : '' }).then((r) => {
      if (r && r.state) {
        states.set(key, r);
        renderKey(key);
      }
    });
  }

  function renderBox(box, key) {
    const st = states.get(key) || { state: 'none' };
    const sig = JSON.stringify(st);
    if (box.__wwtSig === sig) return;
    box.__wwtSig = sig;
    box.replaceChildren();
    box.dataset.state = st.state;

    const head = el('div', 'wwt-head');
    head.append(el('span', 'wwt-icon', '🎙'));

    if (!st.state || st.state === 'none') {
      head.append(button('Trascrivi', 'Trascrivi questo vocale con Whisper locale', () => requestTranscription(key)));
      box.append(head);
      return;
    }

    if (st.state === 'done') {
      const meta = [st.language, fmtDuration(st.duration)].filter(Boolean).join(' · ');
      head.append(el('span', 'wwt-label', 'Trascrizione' + (meta ? ' · ' + meta : '')));
      const actions = el('span', 'wwt-actions');
      actions.append(
        button('Copia', 'Copia il testo', (ev) => {
          navigator.clipboard.writeText(st.text || '').then(() => {
            ev.target.textContent = 'Copiato';
            setTimeout(() => (ev.target.textContent = 'Copia'), 1200);
          });
        }),
        button('↻', 'Trascrivi di nuovo', () => requestTranscription(key, true)),
      );
      head.append(actions);
      box.append(head, el('div', 'wwt-text', st.text || '(nessun parlato riconosciuto)'));
      return;
    }

    if (st.state === 'error') {
      head.append(el('span', 'wwt-label wwt-error', 'Errore: ' + (st.error || 'sconosciuto')));
      const actions = el('span', 'wwt-actions');
      actions.append(button('Riprova', null, () => requestTranscription(key)));
      if (st.hostMissing) actions.append(button('Configura', "Apri la configurazione dell'host", () => send({ cmd: 'openOptions' })));
      head.append(actions);
      box.append(head);
      return;
    }

    let label = STATE_LABEL[st.state] || st.state;
    if (st.state === 'queued' && st.position) label += ' (n. ' + st.position + ')';
    if (st.state === 'transcribing' && st.progress != null) label += ' ' + Math.round(st.progress * 100) + '%';
    head.append(el('span', 'wwt-spinner'), el('span', 'wwt-label', label));
    const actions = el('span', 'wwt-actions');
    actions.append(button('Annulla', null, () => send({ cmd: 'cancel', key })));
    head.append(actions);
    box.append(head);
    if (st.partial) box.append(el('div', 'wwt-text wwt-partial', st.partial));
  }

  function renderKey(key) {
    document.querySelectorAll('.wwt-box').forEach((box) => {
      if (box.dataset.key === key) renderBox(box, key);
    });
  }

  function placeBox(msgEl, box) {
    const container = msgEl.querySelector('[data-testid="msg-container"]');
    if (!container || !container.parentElement) return false;
    if (box.previousElementSibling !== container) container.insertAdjacentElement('afterend', box);
    // Il riquadro ha la larghezza della bolla e ne riprende il bordo sinistro (in arrivo)
    // o destro (inviati): lo scarto si misura dopo l'inserimento e si somma al margine attuale.
    const fromMe = msgEl.getAttribute('data-wwt-from-me') === '1';
    box.classList.toggle('wwt-out', fromMe);
    const cr = container.getBoundingClientRect();
    if (cr.width <= 0) return true;
    box.style.width = Math.round(cr.width) + 'px';
    const br = box.getBoundingClientRect();
    if (fromMe) {
      const cur = parseFloat(box.style.marginRight) || 0;
      const delta = br.right - cr.right;
      if (Math.abs(delta) > 1) box.style.marginRight = Math.max(0, Math.round(cur + delta)) + 'px';
    } else {
      const cur = parseFloat(box.style.marginLeft) || 0;
      const delta = cr.left - br.left;
      if (Math.abs(delta) > 1) box.style.marginLeft = Math.max(0, Math.round(cur + delta)) + 'px';
    }
    return true;
  }

  function scan() {
    const voices = document.querySelectorAll('#main [data-wwt-voice="1"]');
    const toAsk = [];
    for (const msgEl of voices) {
      const key = msgEl.getAttribute('data-wwt-key');
      if (!key) continue;
      let box = msgEl.querySelector(':scope .wwt-box');
      if (box && box.dataset.key !== key) {
        box.remove();
        box = null;
      }
      if (!box) {
        box = el('div', 'wwt-box');
        box.dataset.key = key;
      }
      if (!placeBox(msgEl, box)) continue;
      if (!asked.has(key)) {
        asked.add(key);
        toAsk.push({ key, fromMe: msgEl.getAttribute('data-wwt-from-me') === '1' });
      }
      renderBox(box, key);
    }
    if (toAsk.length) {
      send({ cmd: 'get', keys: toAsk.map((x) => x.key) }).then((res) => {
        if (!res) return;
        for (const { key, fromMe } of toAsk) {
          if (res[key]) {
            states.set(key, res[key]);
            renderKey(key);
          } else if (settings.autoVisible) {
            send({ cmd: 'autoEnqueue', key, fromMe, visible: true });
          }
        }
      });
    }
  }

  let scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(() => {
      scheduled = false;
      if (!alive()) return;
      try { scan(); } catch (_) { /* il prossimo giro riprova */ }
    }, 150);
  }
  new MutationObserver(schedule).observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['data-wwt-voice', 'data-wwt-key'],
  });
  setInterval(schedule, 2000);
  schedule();
})();
