// Testi localizzati delle pagine dell'estensione (opzioni e popup).
//
// t(key, ...subs) restituisce la voce di _locales nella lingua del browser, con
// $1, $2 sostituiti; una chiave mancante torna com'è, così salta all'occhio.
// applyI18n() riempie gli elementi marcati con data-i18n (testo), data-i18n-html
// (voci del catalogo con strong, em, a), data-i18n-placeholder e data-i18n-title.
// setHtml() costruisce il contenuto con nodi DOM: passano solo testo, strong, em, b, i,
// code, br e a con indirizzo https, e nient'altro della voce.
'use strict';

function t(key, ...subs) {
  return chrome.i18n.getMessage(key, subs.map(String)) || key;
}

const HTML_TAGS = new Set(['STRONG', 'EM', 'B', 'I', 'CODE', 'BR', 'A']);

function copyAllowed(from, to) {
  for (const node of from.childNodes) {
    if (node.nodeType === Node.TEXT_NODE) {
      to.append(node.textContent);
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      if (!HTML_TAGS.has(node.tagName)) {
        copyAllowed(node, to);
        continue;
      }
      const el = document.createElement(node.tagName.toLowerCase());
      if (node.tagName === 'A') {
        const href = node.getAttribute('href') || '';
        if (/^https:\/\//.test(href)) el.href = href;
        el.target = '_blank';
        el.rel = 'noopener';
      }
      copyAllowed(node, el);
      to.append(el);
    }
  }
}

function setHtml(el, html) {
  const doc = new DOMParser().parseFromString('<body>' + html + '</body>', 'text/html');
  el.replaceChildren();
  copyAllowed(doc.body, el);
}

function applyI18n(root = document) {
  document.documentElement.lang = t('lang');
  document.title = t('extName');
  for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
  for (const el of root.querySelectorAll('[data-i18n-html]')) setHtml(el, t(el.dataset.i18nHtml));
  for (const el of root.querySelectorAll('[data-i18n-placeholder]')) el.placeholder = t(el.dataset.i18nPlaceholder);
  for (const el of root.querySelectorAll('[data-i18n-title]')) el.title = t(el.dataset.i18nTitle);
}

applyI18n();
