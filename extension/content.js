// Mondo isolato di web.whatsapp.com: interfaccia sotto i vocali e ponte tra page.js e il service worker.
//
// page.js marca gli elementi dei vocali con data-wwt-voice="1" e data-wwt-key;
// qui, per ognuno, si aggiunge sotto la bolla un riquadro .wwt-box che mostra
// lo stato della trascrizione (pulsante, in coda, in corso, testo, errore).
// Sotto i messaggi scritti in arrivo in un'altra lingua (data-wwt-text="1") un riquadro
// .wwt-trbox offre o mostra la traduzione. La lingua la riconosce il browser: i messaggi
// nella lingua dell'utente non vanno mai all'IA.
(() => {
  'use strict';
  if (window.__wwtContentLoaded) return;
  window.__wwtContentLoaded = true;

  const states = new Map(); // key -> stato ricevuto dal service worker
  const asked = new Set(); // key per cui lo stato è già stato chiesto
  let settings = { autoVisible: false, autoOutgoing: false };

  // Dopo una ricarica dell'estensione questo script resta nella pagina ma scollegato:
  // chrome.runtime.id può restare valorizzato, getManifest() invece fallisce.
  let detached = false;
  const alive = () => {
    if (detached) return false;
    try {
      if (chrome.runtime && chrome.runtime.id && chrome.runtime.getManifest()) return true;
    } catch (_) { /* contesto invalidato */ }
    detached = true;
    stop();
    return false;
  };
  const stoppers = [];
  function stop() {
    for (const fn of stoppers.splice(0)) {
      try { fn(); } catch (_) { /* già fermo */ }
    }
  }
  const t = (key, ...subs) => (alive() && chrome.i18n.getMessage(key, subs.map(String))) || key;

  // Gli errori di page.js e del componente locale arrivano come codici "E:<nome>[:dettaglio]",
  // tradotti qui con la voce err_<nome> del catalogo; il resto si mostra com'è.
  function errorText(raw) {
    const m = /E:([a-z_]+)(?::(.*))?/.exec(raw || '');
    if (!m) return raw || t('box_unknown_error');
    const text = t('err_' + m[1], m[2] || '');
    return text === 'err_' + m[1] ? raw : text;
  }

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
        reject(new Error(t('err_wa_no_reply')));
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
      pageRequest('fetchAudio', { key: msg.key, context: !!msg.context })
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
    if (msg.cmd === 'trUpdate') {
      for (const u of msg.updates) {
        const prev = trStates.get(u.key) || {};
        trStates.set(u.key, u.state === 'done' && !u.translation ? { state: 'same' } : { ...u, text: prev.text, language: u.language || prev.language });
        renderTrKey(u.key);
      }
      return false;
    }
    if (msg.cmd === 'translateChat') {
      translateChat()
        .then((n) => sendResponse({ ok: true, result: n }))
        .catch((e) => sendResponse({ ok: false, error: e.message }));
      return true;
    }
    if (msg.cmd === 'reset') {
      states.clear();
      asked.clear();
      trStates.clear();
      trSeen.clear();
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

  // Interruttore a segmenti: options è [[valore, etichetta, titolo], ...].
  function segmented(options, current, onPick) {
    const wrap = el('span', 'wwt-seg');
    for (const [value, label, title] of options) {
      const b = el('button', value === current ? 'on' : null, label);
      b.type = 'button';
      if (title) b.title = title;
      b.setAttribute('aria-pressed', String(value === current));
      b.addEventListener('click', (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        if (value !== current) onPick(value);
      });
      wrap.append(b);
    }
    return wrap;
  }

  function fmtDuration(s) {
    if (!s && s !== 0) return '';
    s = Math.round(s);
    return s >= 60 ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') + ' min' : s + ' s';
  }

  const STATE_LABEL = {
    queued: 'state_queued',
    downloading: 'state_downloading',
    waiting: 'state_waiting',
    waiting_lock: 'state_waiting_lock',
    loading_model: 'state_loading_model',
    transcribing: 'state_transcribing',
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

  // Una revisione rimasta "in corso" oltre questo tempo si considera persa, per esempio
  // perché il service worker è stato terminato: il riquadro offre di ripeterla.
  const REVIEW_STALE_MS = 150000;
  // Testo scelto con l'interruttore, "whisper" o "revised", finché il record salvato non lo riporta.
  const viewChoice = new Map();

  const AI_ERR = {
    key: 'ai_err_key',
    rate: 'ai_err_rate',
    timeout: 'ai_err_timeout',
    network: 'ai_err_network',
    model: 'ai_err_model',
    format: 'ai_err_format',
  };
  const aiErrText = (code) => t(AI_ERR[code] || 'ai_err_http');

  function reviewState(st) {
    const rv = st.review;
    if (!rv) return null;
    if (rv.state === 'running' && Date.now() - (rv.since || 0) > REVIEW_STALE_MS) return 'stale';
    return rv.state;
  }

  // Testo rivisto: le parole cambiate sono sottolineate e mostrano quelle di Whisper al passaggio.
  function revisedText(segments) {
    const div = el('div', 'wwt-text wwt-final');
    for (const [text, orig] of segments) {
      if (orig == null) {
        div.append(text);
      } else {
        const span = el('span', 'wwt-fix', text);
        span.title = orig ? t('box_fix_title', orig) : t('box_fix_added');
        div.append(span);
      }
    }
    return div;
  }

  // Testo di Whisper con evidenziate, nell'ordine, le parole che la revisione ha cambiato.
  function originalText(text, segments) {
    const div = el('div', 'wwt-text wwt-final');
    let pos = 0;
    for (const [, orig] of segments) {
      if (!orig) continue;
      const i = text.indexOf(orig, pos);
      if (i < 0) continue;
      div.append(text.slice(pos, i), el('span', 'wwt-orig', orig));
      pos = i + orig.length;
    }
    div.append(text.slice(pos));
    return div;
  }

  function renderDone(box, key, st) {
    const rv = st.review;
    const rstate = reviewState(st);
    const done = rstate === 'done';
    const fixes = done && rv.revised && (rv.segments || []).some(([, orig]) => orig != null);
    const original = fixes && (viewChoice.get(key) || rv.view) === 'whisper';
    const head = el('div', 'wwt-head');
    head.append(el('span', 'wwt-icon', '🎙'));
    const lang = (done && rv.language) || st.language;
    const meta = [lang, fmtDuration(st.duration)].filter(Boolean).join(' · ');
    let label;
    if (done) {
      // Con la revisione fatta l'etichetta dice chi ha scritto il testo mostrato.
      label = [meta, original || !rv.revised ? 'Whisper' : rv.provider].filter(Boolean).join(' · ');
      if (!original && !rv.revised && rv.translation && rv.provider) label += ' · ' + rv.provider;
    } else {
      label = t('box_transcription') + (meta ? ' · ' + meta : '');
      if (rstate === 'running') {
        head.append(el('span', 'wwt-spinner'));
        label += ' · ' + (rv.provider ? t('box_reviewing', rv.provider) : t('box_reviewing_ai'));
      }
    }
    head.append(el('span', 'wwt-label', label));
    const translation = done ? rv.translation : null;
    const shown = done && rv.revised && !original ? rv.text : st.text;
    const actions = el('span', 'wwt-actions');
    if (fixes) {
      actions.append(segmented(
        [['whisper', t('box_seg_transcription'), t('box_seg_transcription_title')], ['revised', t('box_seg_revision'), t('box_seg_revision_title')]],
        original ? 'whisper' : 'revised',
        (view) => {
          viewChoice.set(key, view);
          send({ cmd: 'setView', key, view });
          renderKey(key);
        },
      ));
    }
    actions.append(
      button(t('box_copy'), t('box_copy_title'), (ev) => {
        const text = (shown || '') + (translation ? '\n\n' + translation : '');
        navigator.clipboard.writeText(text).then(() => {
          ev.target.textContent = t('box_copied');
          setTimeout(() => (ev.target.textContent = t('box_copy')), 1200);
        });
      }),
    );
    actions.append(button('↻', t('box_retranscribe_title'), () => requestTranscription(key, true)));
    head.append(actions);
    box.append(head);
    // wwt-final e wwt-final-tr segnano testo e traduzione, che page.js aggiunge al testo copiato.
    if (!st.text) {
      box.append(el('div', 'wwt-text', t('box_no_speech')));
    } else if (done && rv.revised) {
      box.append(original ? originalText(st.text, rv.segments || []) : revisedText(rv.segments || [[rv.text, null]]));
    } else {
      box.append(el('div', 'wwt-text wwt-final', st.text));
    }
    if (translation) {
      const tr = el('div', 'wwt-tr');
      tr.append(el('div', 'wwt-tr-label', t('box_translation') + ' · ' + (rv.targetLang || '')), el('div', 'wwt-final-tr', translation));
      box.append(tr);
    }
    if (rstate === 'error' || rstate === 'stale') {
      const note = el('div', 'wwt-note');
      const why = rstate === 'stale'
        ? t('ai_err_interrupted')
        : (rv.errors || []).map((e) => (e.name ? e.name + ': ' : '') + aiErrText(e.code)).join('; ');
      note.append(el('span', null, '⚠ ' + t('box_review_failed', why || t('ai_err_http'))));
      note.append(button(t('box_review_retry'), null, () => send({ cmd: 'reviewAgain', key })));
      box.append(note);
    }
  }

  function renderBox(box, key) {
    const st = states.get(key) || { state: 'none' };
    const sig = JSON.stringify(st) + '|' + viewChoice.get(key) + '|' + reviewState(st);
    if (box.__wwtSig === sig) return;
    box.__wwtSig = sig;
    box.replaceChildren();
    box.dataset.state = st.state;

    const head = el('div', 'wwt-head');
    head.append(el('span', 'wwt-icon', '🎙'));

    if (!st.state || st.state === 'none') {
      head.append(button(t('box_transcribe'), t('box_transcribe_title'), () => requestTranscription(key)));
      box.append(head);
      return;
    }

    if (st.state === 'done') {
      renderDone(box, key, st);
      return;
    }

    if (st.state === 'error') {
      const message = st.hostMissing && /not found/i.test(st.error || '') ? t('err_host_not_installed') : errorText(st.error);
      head.append(el('span', 'wwt-label wwt-error', t('box_error', message)));
      const actions = el('span', 'wwt-actions');
      actions.append(button(t('box_retry'), null, () => requestTranscription(key)));
      if (st.hostMissing) actions.append(button(t('box_configure'), t('box_configure_title'), () => send({ cmd: 'openOptions' })));
      head.append(actions);
      box.append(head);
      return;
    }

    let label = STATE_LABEL[st.state] ? t(STATE_LABEL[st.state]) : st.state;
    if (st.state === 'queued' && st.position) label = t('state_queued_pos', st.position);
    if (st.state === 'transcribing' && st.progress != null) label += ' ' + Math.round(st.progress * 100) + '%';
    head.append(el('span', 'wwt-spinner'), el('span', 'wwt-label', label));
    const actions = el('span', 'wwt-actions');
    actions.append(button(t('box_cancel'), null, () => send({ cmd: 'cancel', key })));
    head.append(actions);
    box.append(head);
    if (st.partial) box.append(el('div', 'wwt-text wwt-partial', st.partial));
  }

  function renderKey(key) {
    document.querySelectorAll('.wwt-box:not(.wwt-trbox)').forEach((box) => {
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

  // ---------- traduzione dei messaggi scritti ----------

  // Stati: offer (lingua diversa, si traduce a richiesta), running, done, error, same
  // (nella lingua dell'utente o senza parole da tradurre: nessun riquadro).
  const trStates = new Map();
  const trSeen = new Set(); // chiavi già esaminate
  let trCfg = null;
  let trCfgAt = 0;
  let trBusy = false;
  const TR_CFG_TTL_MS = 60000;
  // Sotto questa percentuale una lingua riconosciuta con poca sicurezza non conta.
  const TR_MIN_PERCENT = 60;

  async function trConfig() {
    if (trCfg && Date.now() - trCfgAt < TR_CFG_TTL_MS) return trCfg;
    const c = await send({ cmd: 'trConfig' });
    if (c) {
      trCfg = c;
      trCfgAt = Date.now();
    }
    return trCfg;
  }

  async function detect(text) {
    try {
      if (chrome.i18n.detectLanguage) return await chrome.i18n.detectLanguage(text);
    } catch (_) { /* si chiede al service worker */ }
    return send({ cmd: 'detectLanguage', text });
  }

  function foreignLanguage(res, target) {
    const top = res && res.languages && res.languages[0];
    if (!top || !top.language || top.language === 'und') return null;
    const lang = top.language.split('-')[0];
    if (lang === String(target || '').split('-')[0]) return null;
    if (!res.isReliable && top.percentage < TR_MIN_PERCENT) return null;
    return lang;
  }

  function langName(code) {
    try {
      const ui = chrome.i18n.getUILanguage();
      const n = new Intl.DisplayNames([ui], { type: 'language' }).of(code);
      return n || code;
    } catch (_) {
      return code;
    }
  }

  const textEls = () => document.querySelectorAll('#main [data-wwt-text="1"][data-wwt-key]');

  // Messaggi scritti nuovi nel DOM: traduzioni salvate, poi lingua dei rimanenti; quelli
  // in un'altra lingua partono da soli se sono arrivati con WhatsApp Web aperto e il modo
  // è "auto", altrimenti ricevono il pulsante Traduci.
  async function textScan() {
    if (trBusy) return;
    trBusy = true;
    try {
      const els = [...textEls()];
      const cfg = els.length ? await trConfig() : trCfg;
      if (!cfg || !cfg.active) {
        document.querySelectorAll('.wwt-trbox').forEach((b) => b.remove());
        return;
      }
      const fresh = els.filter((el) => !trSeen.has(el.getAttribute('data-wwt-key')));
      if (fresh.length) {
        const keys = fresh.map((el) => el.getAttribute('data-wwt-key'));
        keys.forEach((k) => trSeen.add(k));
        const saved = (await send({ cmd: 'trGet', keys })) || {};
        const need = [];
        for (const k of keys) {
          const r = saved[k];
          if (!r) need.push(k);
          else trStates.set(k, r.state === 'done' && !r.translation ? { state: 'same' } : r);
        }
        if (need.length) {
          const texts = await pageRequest('texts', { keys: need });
          const isNew = new Map(fresh.map((el) => [el.getAttribute('data-wwt-key'), el.getAttribute('data-wwt-new') === '1']));
          const toSend = [];
          for (const { key, text } of texts) {
            const lang = foreignLanguage(await detect(text), cfg.target);
            if (!lang) {
              trStates.set(key, { state: 'same' });
            } else if (cfg.mode === 'auto' && isNew.get(key)) {
              trStates.set(key, { state: 'running', language: lang, text });
              toSend.push({ key, text, language: lang });
            } else {
              trStates.set(key, { state: 'offer', language: lang, text });
            }
          }
          if (toSend.length) send({ cmd: 'trEnqueue', items: toSend });
        }
      }
      for (const el of textEls()) placeTrBox(el);
    } catch (_) {
      /* il prossimo giro riprova */
    } finally {
      trBusy = false;
    }
  }

  function placeTrBox(msgEl) {
    const key = msgEl.getAttribute('data-wwt-key');
    const st = trStates.get(key);
    let box = msgEl.querySelector(':scope .wwt-trbox');
    if (box && box.dataset.key !== key) {
      box.remove();
      box = null;
    }
    if (!st || st.state === 'same') {
      if (box) box.remove();
      return;
    }
    if (!box) {
      box = el('div', 'wwt-box wwt-trbox');
      box.dataset.key = key;
    }
    if (!placeBox(msgEl, box)) return;
    renderTr(box, key);
  }

  function renderTrKey(key) {
    for (const msgEl of textEls()) if (msgEl.getAttribute('data-wwt-key') === key) placeTrBox(msgEl);
  }

  function requestTranslation(key) {
    const st = trStates.get(key);
    if (!st || !st.text) return;
    trStates.set(key, { ...st, state: 'running', errors: null });
    renderTrKey(key);
    send({ cmd: 'trEnqueue', items: [{ key, text: st.text, language: st.language }] });
  }

  function renderTr(box, key) {
    const st = trStates.get(key);
    const sig = JSON.stringify(st) + '|' + (trCfg && trCfg.provider);
    if (box.__wwtSig === sig) return;
    box.__wwtSig = sig;
    box.replaceChildren();
    box.dataset.state = st.state;
    box.classList.toggle('wwt-slim', st.state === 'offer');
    const head = el('div', 'wwt-head');
    head.append(el('span', 'wwt-icon', '🌐'));
    const actions = el('span', 'wwt-actions');
    if (st.state === 'offer') {
      head.append(el('span', 'wwt-label', langName(st.language)));
      actions.append(button(t('tr_translate'), t('tr_translate_title'), () => requestTranslation(key)));
      head.append(actions);
      box.append(head);
      return;
    }
    if (st.state === 'running') {
      head.append(el('span', 'wwt-spinner'), el('span', 'wwt-label', trCfg && trCfg.provider ? t('tr_running', trCfg.provider) : t('tr_running_ai')));
      box.append(head);
      return;
    }
    if (st.state === 'error') {
      head.append(el('span', 'wwt-label', langName(st.language || '')));
      actions.append(button(t('box_retry'), null, () => requestTranslation(key)));
      head.append(actions);
      const why = (st.errors || []).map((e) => (e.name ? e.name + ': ' : '') + aiErrText(e.code)).join('; ');
      box.append(head, el('div', 'wwt-note', '⚠ ' + t('tr_failed', why || t('ai_err_http'))));
      return;
    }
    const meta = [st.language, st.target].filter(Boolean).join(' → ');
    head.append(el('span', 'wwt-label', t('box_translation') + (meta ? ' · ' + meta : '') + (st.provider ? ' · ' + st.provider : '')));
    actions.append(
      button(t('box_copy'), t('tr_copy_title'), (ev) => {
        navigator.clipboard.writeText(st.translation || '').then(() => {
          ev.target.textContent = t('box_copied');
          setTimeout(() => (ev.target.textContent = t('box_copy')), 1200);
        });
      }),
    );
    head.append(actions);
    // wwt-final-tr segna la traduzione, che page.js aggiunge al testo copiato.
    box.append(head, el('div', 'wwt-text wwt-final-tr', st.translation || ''));
  }

  // Dal popup: traduce i messaggi scritti in un'altra lingua già caricati nella chat aperta.
  async function translateChat() {
    const cfg = await trConfig();
    if (!cfg || !cfg.active) throw new Error(t('tr_off'));
    const { items } = await pageRequest('chatTexts');
    const saved = (await send({ cmd: 'trGet', keys: items.map((i) => i.key) })) || {};
    const toSend = [];
    for (const { key, text } of items) {
      trSeen.add(key);
      if (saved[key] && saved[key].state === 'done') {
        trStates.set(key, saved[key].translation ? saved[key] : { state: 'same' });
        continue;
      }
      const lang = foreignLanguage(await detect(text), cfg.target);
      if (!lang) {
        trStates.set(key, { state: 'same' });
        continue;
      }
      trStates.set(key, { state: 'running', language: lang, text });
      toSend.push({ key, text, language: lang });
    }
    if (toSend.length) send({ cmd: 'trEnqueue', items: toSend });
    for (const msgEl of textEls()) placeTrBox(msgEl);
    return toSend.length;
  }

  let scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(() => {
      scheduled = false;
      if (!alive()) return;
      try { scan(); } catch (_) { /* il prossimo giro riprova */ }
      textScan();
    }, 150);
  }
  const observer = new MutationObserver(schedule);
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['data-wwt-voice', 'data-wwt-text', 'data-wwt-key'],
  });
  const timer = setInterval(schedule, 2000);

  // I riquadri in uno stato in corso chiedono di nuovo lo stato ogni 15 secondi: la
  // richiesta risveglia il service worker, che riprende i lavori salvati se era stato
  // terminato. Un lavoro che il service worker non conosce più torna a "Trascrivi".
  const BUSY = new Set(['queued', 'downloading', 'waiting', 'waiting_lock', 'loading_model', 'transcribing']);
  const refresher = setInterval(() => {
    if (!alive()) return;
    // Le revisioni in corso si rileggono anche loro; quelle ferme da troppo si ridisegnano.
    for (const [key, st] of states) if (st.state === 'done' && st.review && st.review.state === 'running') renderKey(key);
    const keys = [...states]
      .filter(([, st]) => BUSY.has(st.state) || (st.state === 'done' && st.review && st.review.state === 'running'))
      .map(([key]) => key);
    if (!keys.length) return;
    send({ cmd: 'get', keys }).then((res) => {
      if (!res) return;
      for (const key of keys) {
        const prev = states.get(key);
        if (!prev) continue;
        if (prev.state === 'done') {
          if (res[key] && res[key].state === 'done') states.set(key, res[key]);
          renderKey(key);
          continue;
        }
        if (!BUSY.has(prev.state)) continue;
        states.set(key, res[key] || { state: 'none' });
        renderKey(key);
      }
    });
  }, 15000);
  stoppers.push(() => observer.disconnect(), () => clearInterval(timer), () => clearInterval(refresher));
  schedule();
})();
