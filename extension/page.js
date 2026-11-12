// Gira nel mondo MAIN di web.whatsapp.com: accede ai moduli interni di WhatsApp Web.
//
// Compiti:
//  - marcare nel DOM i messaggi vocali: sull'elemento [data-id] del messaggio
//    imposta data-wwt-key (id completo del messaggio), data-wwt-voice ("1"/"0")
//    e data-wwt-from-me, che content.js usa per costruire l'interfaccia;
//  - scaricare e decifrare in memoria l'audio di un vocale, su richiesta;
//  - segnalare i vocali nuovi appena arrivati.
//
// Lo scambio con content.js passa da window.postMessage con il campo __wwt:
// "req" e "res" per richiesta e risposta, "evt" per gli eventi spontanei.
(() => {
  'use strict';
  if (window.__wwtPageLoaded) return;
  window.__wwtPageLoaded = true;

  const VOICE_TYPES = new Set(['ptt', 'audio']);
  const NEW_MSG_WINDOW_S = 15 * 60;
  let WA = null;

  function tryRequire(name) {
    try {
      return window.require(name);
    } catch (_) {
      return null;
    }
  }

  function loadModules() {
    if (typeof window.require !== 'function') return null;
    const collections = tryRequire('WAWebCollections');
    if (!collections || !collections.Msg || !collections.Chat) return null;
    const dl = tryRequire('WAWebDownloadManager');
    return {
      Msg: collections.Msg,
      Chat: collections.Chat,
      downloadManager: dl && dl.downloadManager,
    };
  }

  function msgKey(m) {
    return m.id && (m.id._serialized || m.id.toString());
  }

  function isVoice(m) {
    return !!m && VOICE_TYPES.has(m.type) && !m.isViewOnce;
  }

  function activeChat() {
    return WA.Chat.getModelsArray().find((c) => c.active) || null;
  }

  // Indice id breve (quello in data-id) -> modello, ricostruito quando non trova un id.
  let shortIndex = new Map();
  function rebuildIndex() {
    const idx = new Map();
    const chat = activeChat();
    const lists = [];
    if (chat && chat.msgs) lists.push(chat.msgs.getModelsArray());
    lists.push(WA.Msg.getModelsArray());
    for (const list of lists) {
      for (const m of list) {
        if (m && m.id && m.id.id && !idx.has(m.id.id)) idx.set(m.id.id, m);
      }
    }
    shortIndex = idx;
  }

  // Id brevi senza modello, con l'istante dell'ultimo tentativo: limita le ricostruzioni dell'indice.
  const missing = new Map();

  // Un elemento [data-id] è marcato quando data-wwt-for coincide con il suo data-id:
  // se React riusa il nodo per un altro messaggio, la marcatura si rifà.
  function tagElements() {
    let rebuilt = false;
    const els = document.querySelectorAll('#main [data-id]');
    for (const el of els) {
      const shortId = el.getAttribute('data-id');
      if (!shortId || el.getAttribute('data-wwt-for') === shortId) continue;
      let m = shortIndex.get(shortId);
      const missedAt = missing.get(shortId);
      if (!m && !rebuilt && !(missedAt && Date.now() - missedAt < 10000)) {
        rebuildIndex();
        rebuilt = true;
        m = shortIndex.get(shortId);
      }
      if (!m) {
        if (!missing.has(shortId) || Date.now() - missing.get(shortId) >= 10000) missing.set(shortId, Date.now());
        continue;
      }
      missing.delete(shortId);
      el.setAttribute('data-wwt-for', shortId);
      if (isVoice(m)) {
        el.setAttribute('data-wwt-key', msgKey(m));
        el.setAttribute('data-wwt-from-me', m.id.fromMe ? '1' : '0');
        el.setAttribute('data-wwt-duration', String(m.duration || ''));
        el.setAttribute('data-wwt-voice', '1');
      } else {
        el.setAttribute('data-wwt-voice', '0');
      }
    }
  }

  function chatTitle(m) {
    try {
      const chat = WA.Chat.get(m.id.remote);
      return (chat && (chat.formattedTitle || (chat.contact && chat.contact.name))) || '';
    } catch (_) {
      return '';
    }
  }

  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).split(',', 2)[1] || '');
      r.onerror = () => reject(r.error);
      r.readAsDataURL(blob);
    });
  }

  async function downloadAudio(m) {
    const md = m.mediaData;
    if (md && md.mediaBlob) {
      const b = typeof md.mediaBlob.forceToBlob === 'function' ? md.mediaBlob.forceToBlob() : md.mediaBlob;
      if (b instanceof Blob) return b;
    }
    const errors = [];
    if (WA.downloadManager && m.directPath) {
      const qpl = { addAnnotations() { return this; }, addPoint() { return this; } };
      try {
        const buf = await WA.downloadManager.downloadAndMaybeDecrypt({
          directPath: m.directPath,
          encFilehash: m.encFilehash,
          filehash: m.filehash,
          mediaKey: m.mediaKey,
          mediaKeyTimestamp: m.mediaKeyTimestamp,
          type: m.type,
          mimetype: m.mimetype,
          signal: new AbortController().signal,
          downloadQpl: qpl,
        });
        return new Blob([buf], { type: m.mimetype || 'audio/ogg' });
      } catch (e) {
        errors.push('downloadAndMaybeDecrypt: ' + (e && e.message ? e.message : e));
      }
    }
    if (typeof m.downloadMedia === 'function') {
      try {
        await m.downloadMedia({ downloadEvenIfExpensive: true, rmrReason: 1, isUserInitiated: true });
        const mb = m.mediaData && m.mediaData.mediaBlob;
        const b = mb && (typeof mb.forceToBlob === 'function' ? mb.forceToBlob() : mb);
        if (b instanceof Blob) return b;
        errors.push('downloadMedia: no blob');
      } catch (e) {
        errors.push('downloadMedia: ' + (e && e.message ? e.message : e));
      }
    }
    throw new Error('E:audio_unavailable:' + (errors.join(' | ') || '-'));
  }

  async function handleRequest(req) {
    if (!WA) throw new Error('E:wa_not_ready');
    switch (req.op) {
      case 'fetchAudio': {
        const m = WA.Msg.get(req.key);
        if (!m) throw new Error('E:msg_not_loaded');
        if (!isVoice(m)) throw new Error('E:not_voice');
        const blob = await downloadAudio(m);
        return { b64: await blobToBase64(blob), mime: blob.type, size: blob.size, duration: m.duration || null };
      }
      case 'chatVoices': {
        const chat = activeChat();
        if (!chat) return { keys: [], title: '' };
        const keys = chat.msgs.getModelsArray().filter(isVoice).map(msgKey);
        return { keys, title: chat.formattedTitle || '' };
      }
      case 'ready':
        return { ready: true };
      default:
        throw new Error('unknown op: ' + req.op);
    }
  }

  window.addEventListener('message', (ev) => {
    if (ev.source !== window || !ev.data || ev.data.__wwt !== 'req') return;
    const { reqId } = ev.data;
    handleRequest(ev.data)
      .then((result) => window.postMessage({ __wwt: 'res', reqId, ok: true, result }, location.origin))
      .catch((e) => window.postMessage({ __wwt: 'res', reqId, ok: false, error: String(e && e.message ? e.message : e) }, location.origin));
  });

  function onMsgAdded(m) {
    if (!isVoice(m)) return;
    const ageS = Date.now() / 1000 - (m.t || 0);
    if (m.isNewMsg !== true && ageS > NEW_MSG_WINDOW_S) return;
    window.postMessage({
      __wwt: 'evt',
      type: 'newVoice',
      key: msgKey(m),
      fromMe: !!m.id.fromMe,
      chat: chatTitle(m),
      duration: m.duration || null,
    }, location.origin);
  }

  function start() {
    WA = loadModules();
    if (!WA) {
      setTimeout(start, 1000);
      return;
    }
    WA.Msg.on('add', onMsgAdded);
    let scheduled = false;
    const schedule = () => {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        try { tagElements(); } catch (_) { /* il prossimo giro riprova */ }
      });
    };
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    setInterval(schedule, 2000);
    schedule();
    window.postMessage({ __wwt: 'evt', type: 'ready' }, location.origin);
  }

  start();
})();
