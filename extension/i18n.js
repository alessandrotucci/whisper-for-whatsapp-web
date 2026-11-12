// Testi localizzati delle pagine dell'estensione (opzioni e popup).
//
// t(key, ...subs) restituisce la voce di _locales nella lingua del browser, con
// $1, $2 sostituiti; una chiave mancante torna com'è, così salta all'occhio.
// applyI18n() riempie gli elementi marcati con data-i18n (testo), data-i18n-html
// (voci del catalogo con strong, em, a), data-i18n-placeholder e data-i18n-title.
'use strict';

function t(key, ...subs) {
  return chrome.i18n.getMessage(key, subs.map(String)) || key;
}

function applyI18n(root = document) {
  document.documentElement.lang = t('lang');
  document.title = t('extName');
  for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
  for (const el of root.querySelectorAll('[data-i18n-html]')) el.innerHTML = t(el.dataset.i18nHtml);
  for (const el of root.querySelectorAll('[data-i18n-placeholder]')) el.placeholder = t(el.dataset.i18nPlaceholder);
  for (const el of root.querySelectorAll('[data-i18n-title]')) el.title = t(el.dataset.i18nTitle);
}

applyI18n();
