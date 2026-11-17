// Sezione 4 della configurazione: account di IA per la revisione e la traduzione delle trascrizioni.
//
// Account, chiavi e impostazioni stanno nel componente locale (ai.json, chiavi cifrate con
// l'utente di Windows): questa pagina li legge e li cambia con le richieste ai_* e non
// vede mai una chiave salvata. Le guide per ottenere le chiavi si leggono dal repository,
// così si aggiornano senza una nuova versione; senza rete vale la copia nell'estensione.
// Il campo "mobile" di un passo e il flag "local" di un fornitore servono all'app Android.
'use strict';

(() => {
  const GUIDES_URL = 'https://raw.githubusercontent.com/alessandrotucci/whisper-for-whatsapp-web/main/extension/ai-guides.json';
  const GUIDES_PAGE = 'https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/docs/ai-keys/';
  const TARGET_LANGS = ['it', 'en', 'pt', 'es', 'fr', 'de', 'nl', 'ro', 'pl', 'ru', 'uk', 'el', 'tr', 'ar', 'he', 'hi', 'zh', 'ja', 'ko', 'sq', 'sr', 'hr', 'cs', 'hu', 'sv', 'da', 'no', 'fi', 'ca', 'id', 'vi', 'th', 'fa', 'bn', 'ur', 'tl'];
  const UI_LANG = (chrome.i18n.getUILanguage() || 'en').split('-')[0];
  const GUIDE_LANG = UI_LANG === 'it' ? 'it' : 'en';

  let state = null; // ultimo ai_status: {providers, accounts, settings}
  let guides = null;
  let current = null; // fornitore aperto nella finestra
  let checked = null; // {key, url, models} verificati per il fornitore aperto

  const ai = (message, done, timeoutMs) => send({ cmd: 'ai', message, done, timeoutMs });
  const errText = (r) => {
    const map = { key: 'ai_err_key', rate: 'ai_err_rate', timeout: 'ai_err_timeout', network: 'ai_err_network', model: 'ai_err_model', format: 'ai_err_format', host: 'ai_err_host' };
    const base = t(map[r && r.code] || 'ai_err_http');
    return r && r.detail ? base + ' (' + r.detail + ')' : base;
  };

  function node(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function logo(p) {
    const e = node('span', 'ai-logo', p.mark || p.name[0]);
    e.style.background = p.color || '#59636e';
    return e;
  }

  function langName(code) {
    try {
      const n = new Intl.DisplayNames([UI_LANG], { type: 'language' }).of(code);
      return n ? n.charAt(0).toUpperCase() + n.slice(1) : code;
    } catch (_) {
      return code;
    }
  }

  function ago(ts) {
    const s = Math.max(0, Math.round(Date.now() / 1000 - ts));
    if (s < 90) return t('ai_ago_now');
    if (s < 5400) return t('ai_ago_min', Math.round(s / 60));
    if (s < 129600) return t('ai_ago_h', Math.round(s / 3600));
    return t('ai_ago_d', Math.round(s / 86400));
  }

  // ---------- sezione ----------

  function fillTargets(selected) {
    const sel = $('aiTarget');
    if (sel.options.length) {
      sel.value = selected;
      return;
    }
    const codes = TARGET_LANGS.includes(UI_LANG) ? TARGET_LANGS : [UI_LANG, ...TARGET_LANGS];
    for (const c of codes) {
      const o = node('option', null, langName(c) + (c === UI_LANG ? ' ' + t('ai_browser_lang') : ''));
      o.value = c;
      sel.append(o);
    }
    sel.value = selected;
  }

  function render() {
    if (!state) return;
    const s = state.settings;
    const has = state.accounts.length > 0;
    $('aiRevise').checked = has && !!s.revise;
    $('aiTranslate').checked = has && !!s.translate;
    for (const id of ['aiRevise', 'aiTranslate', 'aiTarget', 'aiContext']) $(id).disabled = !has;
    $('aiReviseRow').style.opacity = $('aiTranslateRow').style.opacity = has ? '' : '0.55';
    fillTargets(s.target_lang || UI_LANG);
    $('aiEmptyNote').hidden = has;
    $('aiAccountsBox').hidden = !has;
    $('aiContextRow').hidden = !has;
    $('aiContext').checked = !!s.context;
    $('aiTimeoutLine').replaceChildren();
    const tl = t('ai_timeout_line', '§').split('§');
    const num = node('input');
    num.type = 'number';
    num.min = '5';
    num.max = '120';
    num.value = s.timeout_s || 20;
    num.style.width = '60px';
    num.style.padding = '1px 6px';
    num.addEventListener('change', () => saveSettings({ timeout_s: Math.max(5, Math.min(120, Number(num.value) || 20)) }));
    $('aiTimeoutLine').append(tl[0] || '', num, tl[1] || '');
    $('aiAdd').textContent = has ? t('ai_add_more') : t('ai_add');
    const list = $('aiAccounts');
    list.replaceChildren(...state.accounts.map((a, i) => accountRow(a, i, state.accounts.length)));
  }

  function accountRow(a, i, n) {
    const row = node('div', 'ai');
    row.append(node('span', 'ai-ord', String(i + 1)), logo(a));
    const name = node('span', 'ai-name');
    name.append(node('strong', null, a.name), node('span', 'muted', ' · ' + a.vendor));
    const st = node('span', 'small');
    if (a.last_error && (!a.last_ok || a.last_error.at > a.last_ok)) {
      st.className = 'small err';
      st.textContent = '⚠ ' + errText({ code: a.last_error.code }) + ' · ' + ago(a.last_error.at);
    } else if (a.last_ok) {
      st.className = 'small ok';
      st.textContent = '✓ ' + t('ai_last_ok', ago(a.last_ok)) + (a.free ? ' · ' + t('ai_free_tier') : '');
    }
    name.append(document.createElement('br'), st);
    const model = node('label', 'ai-model');
    model.append(node('span', 'muted small', t('ai_model')));
    const sel = node('select');
    const cur = node('option', null, a.model);
    cur.value = a.model;
    sel.append(cur);
    let loaded = false;
    sel.addEventListener('focus', async () => {
      if (loaded) return;
      loaded = true;
      const r = await ai({ type: 'ai_models', account: a.id }, ['ai_models']);
      if (!r || !r.ok) return;
      sel.replaceChildren(...r.models.map((m) => {
        const o = node('option', null, m === r.recommended ? t('ai_recommended', m) : m);
        o.value = m;
        return o;
      }));
      if (!r.models.includes(a.model)) sel.prepend(cur);
      sel.value = a.model;
    });
    sel.addEventListener('change', async () => {
      const r = await ai({ type: 'ai_update', account: a.id, model: sel.value }, ['ai_status']);
      if (r && r.ok) state = r;
    });
    model.append(sel);
    const btns = node('span', 'ai-btns');
    const up = node('button', null, '↑');
    up.title = t('ai_move_up');
    up.disabled = i === 0;
    up.addEventListener('click', () => move(a.id, -1));
    const down = node('button', null, '↓');
    down.title = t('ai_move_down');
    down.disabled = i === n - 1;
    down.addEventListener('click', () => move(a.id, 1));
    const test = node('button', null, t('ai_test'));
    test.title = t('ai_test_title');
    const out = node('p', 'ai-test muted');
    out.hidden = true;
    test.addEventListener('click', async () => {
      test.disabled = true;
      out.hidden = false;
      out.className = 'ai-test muted';
      out.textContent = t('ai_testing');
      const r = await ai({ type: 'ai_test', account: a.id }, ['ai_test'], 90000);
      test.disabled = false;
      if (r && r.ok) {
        out.className = 'ai-test';
        out.textContent = t('ai_test_ok', (r.ms / 1000).toFixed(1)) + ' «' + (r.text || '') + '»' + (r.translation ? ' → «' + r.translation + '»' : '');
      } else {
        out.className = 'ai-test err';
        out.textContent = '⚠ ' + errText(r);
      }
    });
    const rm = node('button', null, t('ai_remove'));
    rm.addEventListener('click', async () => {
      if (!confirm(t('ai_remove_confirm', a.name))) return;
      const r = await ai({ type: 'ai_remove', account: a.id }, ['ai_status']);
      if (r && r.ok) {
        state = r;
        render();
      }
    });
    btns.append(up, down, test, rm);
    row.append(name, model, btns, out);
    return row;
  }

  async function move(id, delta) {
    const r = await ai({ type: 'ai_move', account: id, delta }, ['ai_status']);
    if (r && r.ok) {
      state = r;
      render();
    }
  }

  async function saveSettings(values) {
    const r = await ai({ type: 'ai_settings', settings: values }, ['ai_status']);
    if (r && r.ok) {
      state = r;
      render();
    } else {
      $('aiMsg').textContent = errText(r);
    }
  }

  $('aiRevise').addEventListener('change', (e) => saveSettings({ revise: e.target.checked }));
  $('aiTranslate').addEventListener('change', (e) => saveSettings({ translate: e.target.checked }));
  $('aiTarget').addEventListener('change', (e) => saveSettings({ target_lang: e.target.value }));
  $('aiContext').addEventListener('change', async (e) => {
    const on = e.target.checked;
    await saveSettings({ context: on });
    // Il service worker raccoglie i messaggi della chat solo se il contesto è attivo.
    const s = (await chrome.storage.local.get('settings')).settings || {};
    s.aiContext = on;
    await chrome.storage.local.set({ settings: s });
  });

  async function load() {
    const r = await ai({ type: 'ai_status' }, ['ai_status']);
    if (!r || !r.ok) {
      $('aiMsg').textContent = r && r.code === 'host' ? '' : errText(r);
      $('step4').dataset.state = 'locked';
      return;
    }
    $('step4').dataset.state = 'active';
    state = r;
    render();
  }

  // ---------- guide ----------

  async function loadGuides() {
    if (guides) return guides;
    for (const url of [GUIDES_URL, chrome.runtime.getURL('ai-guides.json')]) {
      try {
        const res = await fetch(url, { cache: 'no-store' });
        if (res.ok) {
          guides = await res.json();
          return guides;
        }
      } catch (_) { /* si prova la copia locale */ }
    }
    guides = { providers: {} };
    return guides;
  }

  function linkify(parent, text, url) {
    parent.append(text);
    if (url) {
      parent.append(' ');
      const a = node('a', null, url.replace(/^https?:\/\//, '').replace(/\/$/, ''));
      a.href = url;
      a.target = '_blank';
      a.rel = 'noopener';
      parent.append(a);
    }
  }

  function renderGuide(p) {
    const g = guides && guides.providers[p.id];
    $('aiGuide').hidden = !g;
    if (!g) return;
    const meta = [t('ai_guide_minutes', g.minutes || 3)];
    meta.push(g.card ? t('ai_guide_card') : p.local ? t('ai_guide_local') : t('ai_guide_nocard'));
    $('aiGuideMeta').textContent = '· ' + meta.join(' · ');
    $('aiGuideSteps').replaceChildren(...g.steps.map((s) => {
      const li = node('li');
      li.append(node('strong', null, (s.title && (s.title[GUIDE_LANG] || s.title.en)) || ''), document.createElement('br'));
      linkify(li, (s.text && (s.text[GUIDE_LANG] || s.text.en)) || '', s.url);
      if (s.img && guides.img_base) {
        const img = node('img');
        img.alt = '';
        img.loading = 'lazy';
        img.src = guides.img_base + s.img;
        img.addEventListener('error', () => img.remove());
        img.addEventListener('click', () => img.classList.toggle('big'));
        li.append(img);
      }
      return li;
    }));
    $('aiGuideFull').href = GUIDES_PAGE + (GUIDE_LANG === 'it' ? 'README.it.md' : 'README.md') + '#' + p.id;
  }

  // ---------- finestra di collegamento ----------

  const dlg = $('aiDialog');

  function showPick() {
    current = null;
    checked = null;
    dlg.classList.remove('narrow');
    $('aiBack').hidden = true;
    $('aiDlgLogo').replaceChildren();
    $('aiDlgTitle').textContent = t('ai_dialog_title');
    $('aiPick').hidden = false;
    $('aiForm').hidden = true;
    const grid = $('aiProviders');
    grid.replaceChildren(...state.providers.map((p) => {
      const b = node('button', 'prov');
      b.type = 'button';
      const txt = node('span');
      txt.append(node('strong', null, p.name), document.createElement('br'));
      const sub = node('span', 'muted');
      const vendor = p.id === 'custom' ? t('ai_custom_vendor') : p.vendor;
      if (p.oauth) {
        sub.append(node('span', 'tag acc', t('ai_tag_oauth')));
      } else if (p.local) {
        sub.append(node('span', 'tag loc', t('ai_tag_local')));
      } else if (p.free) {
        sub.append(vendor + ' · ', node('span', 'tag', t('ai_tag_free')));
      } else {
        sub.append(vendor + ' · ' + (p.needs_url ? t('ai_tag_url_key') : t('ai_tag_key')));
      }
      txt.append(sub);
      b.append(logo(p), txt);
      b.addEventListener('click', () => showForm(p));
      return b;
    }));
  }

  async function showForm(p) {
    current = p;
    checked = null;
    dlg.classList.add('narrow');
    $('aiBack').hidden = false;
    $('aiDlgLogo').replaceChildren(logo(p));
    $('aiDlgTitle').textContent = p.id === 'custom' ? t('ai_custom_title') : p.name + ' · ' + p.vendor;
    $('aiPick').hidden = true;
    $('aiForm').hidden = false;
    $('aiFormMsg').textContent = '';
    $('aiVerifyMsg').textContent = '';
    $('aiKey').value = '';
    $('aiUrl').value = p.local ? (p.id === 'ollama' ? 'http://localhost:11434/v1' : 'http://localhost:1234/v1') : '';
    $('aiUrl').placeholder = p.url_hint || '';
    $('aiUrlField').hidden = !(p.needs_url || p.local);
    $('aiKeyField').hidden = !!p.no_key;
    $('aiModelField').hidden = true;
    $('aiConnect').disabled = true;
    $('aiOauthBox').hidden = !p.oauth;
    $('aiKeyBox').hidden = !!p.oauth;
    await loadGuides();
    renderGuide(p);
  }

  $('aiAdd').addEventListener('click', () => {
    if (!state) return;
    showPick();
    dlg.showModal();
  });
  $('aiBack').addEventListener('click', showPick);
  $('aiClose').addEventListener('click', () => dlg.close());
  $('aiCancel').addEventListener('click', () => dlg.close());
  $('aiPasteKey').addEventListener('click', (e) => {
    e.preventDefault();
    $('aiKeyBox').hidden = false;
  });
  for (const id of ['aiKey', 'aiUrl']) {
    $(id).addEventListener('input', () => {
      checked = null;
      $('aiConnect').disabled = true;
      $('aiModelField').hidden = true;
      $('aiVerifyMsg').textContent = '';
    });
  }

  $('aiVerify').addEventListener('click', async () => {
    const p = current;
    const key = $('aiKey').value.trim();
    const url = $('aiUrl').value.trim();
    if (!p.no_key && !key) {
      $('aiVerifyMsg').className = 'err';
      $('aiVerifyMsg').textContent = t('ai_need_key');
      return;
    }
    $('aiVerify').disabled = true;
    $('aiVerifyMsg').className = 'muted';
    $('aiVerifyMsg').textContent = t('ai_verifying');
    const r = await ai({ type: 'ai_models', provider: p.id, key, base_url: url || null }, ['ai_models'], 30000);
    $('aiVerify').disabled = false;
    if (!r || !r.ok) {
      $('aiVerifyMsg').className = 'err';
      $('aiVerifyMsg').textContent = '⚠ ' + errText(r);
      return;
    }
    if (!r.models.length) {
      $('aiVerifyMsg').className = 'err';
      $('aiVerifyMsg').textContent = '⚠ ' + t('ai_no_models');
      return;
    }
    checked = { key, url, models: r.models };
    $('aiVerifyMsg').className = 'ok';
    $('aiVerifyMsg').textContent = '✓ ' + (p.no_key ? t('ai_server_ok') : t('ai_key_ok'));
    $('aiModel').replaceChildren(...r.models.map((m) => {
      const o = node('option', null, m === r.recommended ? t('ai_recommended_long', m) : m);
      o.value = m;
      return o;
    }));
    $('aiModel').value = r.recommended || r.models[0];
    $('aiModelField').hidden = false;
    $('aiConnect').disabled = false;
  });

  $('aiConnect').addEventListener('click', async () => {
    if (!checked) return;
    $('aiConnect').disabled = true;
    $('aiFormMsg').className = 'small muted';
    $('aiFormMsg').textContent = t('ai_connecting');
    const r = await ai({
      type: 'ai_add', provider: current.id, key: checked.key, base_url: checked.url || null,
      model: $('aiModel').value, target_lang: $('aiTarget').value || UI_LANG,
    }, ['ai_added'], 90000);
    if (!r || !r.ok) {
      $('aiConnect').disabled = false;
      $('aiFormMsg').className = 'small err';
      $('aiFormMsg').textContent = '⚠ ' + errText(r);
      return;
    }
    state = r;
    dlg.close();
    render();
    $('aiMsg').className = 'ok';
    $('aiMsg').textContent = t('ai_connected', current.name);
  });

  $('aiOauth').addEventListener('click', async () => {
    $('aiOauth').disabled = true;
    $('aiFormMsg').className = 'small muted';
    $('aiFormMsg').textContent = t('ai_oauth_wait');
    const r = await send({ cmd: 'aiOauth', targetLang: $('aiTarget').value || UI_LANG });
    $('aiOauth').disabled = false;
    if (!r || !r.ok) {
      $('aiFormMsg').className = 'small err';
      $('aiFormMsg').textContent = '⚠ ' + errText(r);
      return;
    }
    state = r;
    if (dlg.open) dlg.close();
    render();
    $('aiMsg').className = 'ok';
    $('aiMsg').textContent = t('ai_connected', 'OpenRouter');
  });

  load();
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && !dlg.open) load();
  });
})();
