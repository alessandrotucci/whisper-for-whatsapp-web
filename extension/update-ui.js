// Riquadro dell'aggiornamento, comune a popup e opzioni: si disegna in #updateBox.
//
// Compare quando estensione o componente locale sono più vecchi dell'ultima release
// e l'avviso non è stato rimandato. Il titolo dice cosa si aggiorna; le righe mostrano
// versione attuale e nuova, o "già aggiornato"; il pulsante dice sempre "Aggiorna".
// Durante l'aggiornamento i pulsanti lasciano il posto alla riga di avanzamento.
'use strict';

(() => {
  const box = document.getElementById('updateBox');
  if (!box) return;
  let info = null;
  let busy = false;
  let status = null; // {text, error}

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };

  function bytes(n) {
    if (n == null) return '';
    return n >= 1e6 ? Math.round(n / 1e6).toLocaleString() + ' MB' : Math.round(n / 1e3).toLocaleString() + ' KB';
  }

  function amount(p) {
    if (!p.downloaded) return '';
    return p.total ? t('opt_of', bytes(p.downloaded), bytes(p.total)) : bytes(p.downloaded);
  }

  function versionRow(label, cur, next, needed, upToDateKey) {
    const row = el('div', 'upd-row');
    if (needed) {
      row.append(el('span', null, label + ' '), el('s', 'muted', cur || '?'), el('span', null, ' → ' + next));
    } else {
      row.classList.add('muted');
      row.textContent = label + ' ' + t(upToDateKey, cur || next);
    }
    return row;
  }

  function render() {
    box.replaceChildren();
    if (!info || !info.available || (info.dismissed && !busy && !status)) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    const both = info.needExt && info.needHost;
    const card = el('div', 'upd');
    const title = both ? t('upd_title_both', info.extension) : info.needHost ? t('upd_title_host') : t('upd_title_ext');
    card.append(el('div', 'upd-title', title));
    card.append(el('p', 'upd-text', t(both ? 'upd_text_both' : info.needHost ? 'upd_text_host' : 'upd_text_ext')));
    if (info.needHost && !info.hostSelfUpdate) card.append(el('p', 'upd-text', t('upd_text_manual')));
    if (info.current.host || info.needHost) {
      card.append(versionRow(t('upd_row_host'), info.current.host, info.host, info.needHost, 'upd_up_to_date_m'));
    }
    card.append(versionRow(t('upd_row_ext'), info.current.extension, info.extension, info.needExt, 'upd_up_to_date_f'));

    if (status) {
      const s = el('p', status.error ? 'upd-status err' : 'upd-status', status.text);
      card.append(s);
    }
    if (!busy) {
      const actions = el('div', 'upd-actions');
      const go = el('button', 'primary', t('upd_button'));
      go.addEventListener('click', start);
      const notes = el('a', null, t('upd_notes'));
      notes.href = info.notesUrl;
      notes.target = '_blank';
      notes.rel = 'noopener';
      const later = el('button', 'link', t('upd_later'));
      later.addEventListener('click', async () => {
        status = null;
        info = await chrome.runtime.sendMessage({ cmd: 'dismissUpdate', tag: info.tag });
        render();
      });
      actions.append(go, notes, later);
      card.append(actions);
    }
    box.append(card);
  }

  async function start() {
    busy = true;
    status = { text: t('upd_p_starting') };
    render();
    await chrome.runtime.sendMessage({ cmd: 'applyUpdate' });
  }

  const STAGES = {
    waiting_queue: (p) => t('upd_p_waiting_queue'),
    host_download: (p) => t('upd_p_host_download', amount(p)),
    host_install: () => t('upd_p_host_install'),
    ext_download: (p) => t('upd_p_ext_download', amount(p)),
    ext_reload: () => t('upd_p_ext_reload'),
    done: () => t('upd_p_done'),
    manual_host: () => t('upd_p_manual_host'),
  };

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.cmd !== 'updateProgress') return;
    if (msg.stage === 'error') {
      busy = false;
      status = { text: t('upd_p_error', msg.message || ''), error: true };
    } else {
      busy = !['done', 'manual_host'].includes(msg.stage);
      status = { text: (STAGES[msg.stage] || (() => msg.stage))(msg) };
    }
    if (msg.stage === 'done' || msg.stage === 'manual_host') {
      chrome.runtime.sendMessage({ cmd: 'updateInfo' }).then((i) => {
        info = i;
        render();
      });
    }
    render();
  });

  window.wwtUpdateUi = {
    show(newInfo) {
      info = newInfo;
      status = null;
      render();
    },
  };

  chrome.runtime.sendMessage({ cmd: 'updateInfo' }).then((i) => {
    info = i;
    render();
  });
})();
