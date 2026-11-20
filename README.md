# Whisper for WhatsApp Web

Estensione per Chrome, Edge e Firefox che trascrive i vocali di WhatsApp Web con
[faster-whisper](https://github.com/SYSTRAN/faster-whisper) **sul tuo computer** e mostra il testo
sotto ogni vocale. L'audio non lascia la macchina: nessun servizio cloud, nessun account.
Solo la revisione con l'IA, facoltativa, manda il testo trascritto al fornitore che scegli.

- Trascrizione automatica dei vocali in arrivo, in qualsiasi chat.
- Pulsante **Trascrivi** sotto ogni vocale, anche quelli arrivati prima dell'installazione.
- Copia con Ctrl+C: selezionando più messaggi, i vocali trascritti finiscono negli appunti al loro
  posto, con ora e mittente come i messaggi scritti.
- Coda unica nel componente locale: una trascrizione per volta, in ordine, anche con più vocali in
  arrivo insieme e con Chrome ed Edge aperti contemporaneamente, su account WhatsApp diversi. Ogni
  vocale mostra la sua posizione nella coda comune.
- GPU NVIDIA facoltativa: la pagina dell'estensione verifica il driver e scarica da sé le librerie
  CUDA (cuBLAS e cuDNN, circa 1,3 GB), che l'installer non contiene.
- Revisione e traduzione con l'IA, facoltative: collegando uno o più account (Claude, ChatGPT,
  Gemini, Grok, Mistral, DeepSeek, OpenRouter, Ollama in locale e altri, 21 in tutto) il testo di
  Whisper si corregge, con le parole cambiate sottolineate, e i messaggi in un'altra lingua
  ricevono la traduzione. Gli account si provano in ordine, con passaggio al successivo se uno non
  risponde. [Come ottenere una chiave](docs/ai-keys/README.it.md). Nel riquadro l'interruttore
  «Trascrizione | Revisione» alterna il testo di Whisper e quello corretto.
- Traduzione dei messaggi scritti in arrivo in un'altra lingua, sotto la bolla: da sola per quelli
  arrivati a chat aperta, con «Traduci» per gli altri. La lingua la riconosce il browser, quindi i
  messaggi nella tua lingua non vanno all'IA; più messaggi partono in una sola richiesta.
- Lingua correggibile: nel riquadro di un messaggio o di un vocale la lingua è un menu (per un
  vocale, «Trascrivi di nuovo in»), e sui messaggi brevi scambiati per italiano un «🌐» al
  passaggio del mouse permette di tradurli lo stesso.
- Modelli Whisper a scelta (large-v3 predefinito), scaricati dalla pagina dell'estensione o già
  presenti nella cache di faster-whisper.

Interfaccia in italiano e in inglese, secondo la lingua del browser. Windows e Linux (x86_64).
Progetto personale, non affiliato a WhatsApp o Meta.

## Installazione

1. Scarica `whisper-for-whatsapp-web-extension.zip` dall'[ultima release](https://github.com/alessandrotucci/whisper-for-whatsapp-web/releases/latest)
   ed estrailo in una cartella stabile.
2. Apri `chrome://extensions` (o `edge://extensions`), attiva **Modalità sviluppatore**, premi
   **Carica estensione non pacchettizzata** e scegli la cartella.
3. Si apre la pagina dell'estensione, che guida il resto:
   - **Componente locale**, su Windows: scarica `WhisperForWhatsAppWeb-Setup.exe` e installalo (per il
     solo utente, senza privilegi di amministratore). L'installer non è firmato: se Windows mostra
     «Windows ha protetto il PC», premi *Ulteriori informazioni* → *Esegui comunque*.
   - **Componente locale**, su Linux: la pagina mostra un comando da incollare in un terminale, che
     scarica `whisper-for-whatsapp-web-linux-x86_64.tar.gz` e lancia il suo `install.sh` (per il solo
     utente, senza root). Serve glibc 2.35 o successiva: Ubuntu 22.04, Debian 12, Fedora 36 o più
     recenti.
   - **Modello**: se hai già scaricato un modello con faster-whisper viene trovato da solo; altrimenti
     premi *Scarica* (large-v3 ≈ 3 GB).
4. Ricarica WhatsApp Web.

### Linux

`install.sh` copia il programma in `~/.local/lib/whisper-for-whatsapp-web/versions/<versione>` e scrive
i manifest di native messaging per i browser di cui trova la cartella dei dati: Chrome, Chromium, Edge,
Brave, Vivaldi, Opera (in `~/.config/<browser>/NativeMessagingHosts`) e Firefox
(`~/.mozilla/native-messaging-hosts`). Firefox snap di Ubuntu passa dal portale WebExtensions del
sistema, che al primo uso chiede il permesso. I dati stanno in `~/.local/share/WhisperForWhatsAppWeb`,
le chiavi delle IA nel portachiavi del sistema (GNOME Keyring, KWallet) o, senza portachiavi, in un file
leggibile solo dall'utente. Il componente si aggiorna da solo dalla pagina dell'estensione.

Per disinstallarlo: `~/.local/lib/whisper-for-whatsapp-web/current/uninstall.sh` (con `--keep-data` i
dati restano). I modelli stanno nella cache di Hugging Face (`~/.cache/huggingface`) e restano comunque.

### Linux: browser Flatpak

Un browser installato come Flatpak (per esempio da Flathub) non legge i manifest del sistema e non può
avviare programmi fuori dalla sua sandbox. Si può fare a mano, dandogli il permesso di uscire dalla
sandbox: con quel permesso il browser, e qualunque cosa giri dentro di esso, può eseguire programmi sul
sistema come se non fosse confinato. Chromium snap non ha una via equivalente.

Dopo aver installato il componente con `install.sh`, per un browser Chromium (`APP` e `DIR` per
Chrome; gli altri nella tabella):

```bash
APP=com.google.Chrome; DIR=google-chrome
flatpak override --user --talk-name=org.freedesktop.Flatpak "$APP"
mkdir -p ~/.var/app/$APP/data ~/.var/app/$APP/config/$DIR/NativeMessagingHosts
printf '#!/bin/sh\nexec flatpak-spawn --host --env=DISPLAY="$DISPLAY" --env=WAYLAND_DISPLAY="$WAYLAND_DISPLAY" "$HOME/.local/lib/whisper-for-whatsapp-web/current/whisper_host/whisper_host" "$@"\n' > ~/.var/app/$APP/data/whisper-host.sh
chmod 755 ~/.var/app/$APP/data/whisper-host.sh
sed "s|\"path\": \"[^\"]*\"|\"path\": \"$HOME/.var/app/$APP/data/whisper-host.sh\"|" \
  ~/.config/google-chrome/NativeMessagingHosts/local.wa_whisper.host.json \
  > ~/.var/app/$APP/config/$DIR/NativeMessagingHosts/local.wa_whisper.host.json
```

| Browser | `APP` | `DIR` |
|---|---|---|
| Chrome | `com.google.Chrome` | `google-chrome` |
| Chromium | `org.chromium.Chromium` | `chromium` |
| Edge | `com.microsoft.Edge` | `microsoft-edge` |
| Brave | `com.brave.Browser` | `BraveSoftware/Brave-Browser` |

Se `~/.config/google-chrome/NativeMessagingHosts/local.wa_whisper.host.json` non esiste, lo si crea
rilanciando `install.sh` dopo `mkdir -p ~/.config/google-chrome`. Per Firefox da Flathub
(`org.mozilla.firefox`) il manifest va in `~/.var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts/`
e parte da `~/.mozilla/native-messaging-hosts/local.wa_whisper.host.json`. Riavviare il browser dopo
le modifiche. Per togliere il permesso: `flatpak override --user --no-talk-name=org.freedesktop.Flatpak "$APP"`.

## Come funziona

```
WhatsApp Web ─ page.js (mondo MAIN): moduli interni di WhatsApp, trova i vocali, scarica e decifra l'audio
      │ window.postMessage
   content.js: riquadro sotto il vocale
      │ chrome.runtime
   background.js (service worker): audio dalla scheda, archivio delle trascrizioni
      │ native messaging (stdin/stdout)
   whisper_host.exe (relay, uno per browser): configurazione, modelli, aggiornamenti
      │ named pipe (Windows) o socket Unix (Linux) con chiave di autenticazione
   whisper_host.exe --engine (motore, uno per utente): coda unica, faster-whisper, modello in memoria
```

Chrome ed Edge avviano ciascuno il proprio processo del componente. Il primo che deve trascrivere
avvia il motore; gli altri si collegano allo stesso. Il motore tiene una sola copia del modello e
una sola coda (le richieste fatte a mano prima di quelle automatiche, poi in ordine di arrivo) e a
ogni cambio manda a ogni browser le posizioni dei suoi vocali. Si chiude 30 secondi dopo l'ultimo
browser collegato.

GPU: le librerie CUDA si scaricano dai wheel NVIDIA su PyPI (`nvidia-cublas-cu12`,
`nvidia-cudnn-cu12` della stessa versione di `cudnn64_9.dll` incluso in ctranslate2), con
dimensione e SHA-256 fissati in `host/cuda_libs.py`, e se ne estraggono le DLL in
`%LOCALAPPDATA%\WhisperForWhatsAppWeb\cuda`. Il driver NVIDIA non si scarica: è un driver di sistema
e richiede privilegi di amministratore. Se la GPU fallisce, il motore ripete il lavoro sulla CPU e
la pagina dell'estensione mostra l'errore.

Un'estensione non può avviare programmi: il componente locale è un host di
[native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging),
registrato dall'installer in `HKCU\Software\Google\Chrome\NativeMessagingHosts` e
`HKCU\Software\Microsoft\Edge\NativeMessagingHosts`. Installazione in
`%LOCALAPPDATA%\Programs\WhisperForWhatsAppWeb`; configurazione e log in
`%LOCALAPPDATA%\WhisperForWhatsAppWeb`; i modelli nella cache di Hugging Face
(`%USERPROFILE%\.cache\huggingface\hub`), che la disinstallazione non tocca.

Aggiornamenti: una volta al giorno l'estensione legge dall'API di GitHub l'ultima release e il suo
`version.json` (versione dell'estensione e del componente) e li confronta con il manifest e con la
versione dichiarata dal componente. «Aggiorna» fa prima il componente (il componente scarica
l'installer della release e lo avvia in modalità silenziosa) e poi l'estensione (il componente
estrae lo zip nelle cartelle da cui Chrome ed Edge la caricano, solo se contengono il `release.json`
degli zip di release, e l'estensione si ricarica). Il componente scarica solo dagli asset delle
release di questo repository. Firefox e gli store: `tools/build_extension.py` costruisce da `extension/` il pacchetto per
Firefox (sfondo come pagina di eventi, id in `browser_specific_settings.gecko`) e quello per Chrome Web
Store, Edge Add-ons e Opera Add-ons, con il nome "WA Web Transcriber, Translator and Reviewer". Nei
pacchetti per gli store l'estensione la aggiorna lo store e il componente aggiorna solo se stesso.
L'installer registra il componente anche per Firefox, sotto `HKCU\Software\Mozilla\NativeMessagingHosts`.
Informativa sulla privacy: [docs/privacy.md](docs/privacy.md).

`tools/release_check.py`, in CI, blocca una release se tag, manifest,
`version.json` e `HOST_VERSION` non coincidono, o se `host/` o `installer/` sono cambiati senza
alzare la versione del componente.

IA: gli account stanno in `%LOCALAPPDATA%\WhisperForWhatsAppWeb\ai.json`, con la chiave cifrata
con DPAPI per l'utente di Windows; verso il browser non torna mai. Dopo una trascrizione il relay
manda il testo al primo account (`host/ai_review.py`: una sola richiesta che chiede testo corretto
e, se la lingua è diversa da quella dell'utente, traduzione), passa al successivo su errore o
timeout e ricava le parole cambiate confrontando i due testi; una correzione troppo diversa
dall'originale si scarta. La maggior parte dei fornitori parla l'API chat/completions di OpenAI,
Anthropic la sua. OpenRouter si collega con l'accesso all'account (OAuth PKCE con ritorno su
localhost). Le guide per ottenere le chiavi stanno in `extension/ai-guides.json`, che la finestra
di collegamento rilegge da GitHub; `tools/ai_guides.py` ne genera `docs/ai-keys/README*.md`.

Le trascrizioni restano in `chrome.storage.local`, legate all'id del messaggio. Il componente
locale resta attivo, col modello in memoria, per alcuni minuti dopo l'ultima trascrizione.

Prove: `node tools/sw_smoke_test.js extension` esegue il service worker con le API di Chrome
simulate e un componente finto; gira in CI prima di ogni release.

L'audio si scarica senza passare dal lettore di WhatsApp. Che questo non invii la conferma di
ascolto è un'inferenza, non verificata.

## Dipendenze da WhatsApp Web

L'estensione usa moduli interni non documentati, verificati su WhatsApp Web 2.3000.1054281195
(novembre 2026): `WAWebCollections` (`Msg`, `Chat`),
`WAWebDownloadManager.downloadManager.downloadAndMaybeDecrypt` (con il parametro `mimetype`),
l'attributo `data-id` del messaggio uguale a `msg.id.id` e il contenitore
`[data-testid="msg-container"]`. Un aggiornamento di WhatsApp può romperli: i punti da toccare sono
in `extension/page.js` e in `placeBox` di `extension/content.js`.

## Sviluppo

```
extension/   estensione MV3 (caricabile così com'è)
host/        whisper_host.py, spec di PyInstaller, requisiti di build, install.ps1 per lo sviluppo
installer/   script di Inno Setup; linux/ con install.sh e uninstall.sh
tools/       generatori: icone, cataloghi delle traduzioni, pacchetti dell'estensione e tarball per Linux
.github/     build e release su tag v*
```

I testi dell'interfaccia stanno in `tools/locales.py`, una voce (italiano, inglese) per chiave;
`python tools/locales.py` rigenera `extension/_locales/{it,en}/messages.json`. Le pagine li
leggono con `t()` e gli attributi `data-i18n*` di `extension/i18n.js`; `page.js` e il componente
locale segnalano gli errori con codici `E:<nome>`, tradotti in `content.js` con la voce `err_<nome>`.

Build locale (Python 3.12, Inno Setup 6):

```powershell
py -3.12 -m venv build\.venv
build\.venv\Scripts\python -m pip install -r host\requirements-build.txt
cd host; ..\build\.venv\Scripts\python -m PyInstaller --noconfirm --distpath ..\build\dist --workpath ..\build\work whisper_host.spec; cd ..
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" /DAppVersion=1.1.0 installer\setup.iss
```

Build per Linux (su Linux, Python 3.12 con libpython condivisa, come `actions/setup-python`): stessi
comandi di PyInstaller, poi `python tools/package_linux.py`, che scrive
`build/installer/whisper-for-whatsapp-web-linux-x86_64.tar.gz`. La glibc della macchina di build è la
minima richiesta: la release si costruisce su Ubuntu 22.04.

Prove dell'host senza browser: `whisper_host.exe --info`, `--selftest`, `--file vocale.ogg --lang pt`.

Per far girare l'host da sorgente con un proprio Python, `host\install.ps1 -Python <python.exe> -Model large-v3`
registra un lanciatore `.cmd` al posto dell'eseguibile (va lanciato da un PowerShell normale, non
dal terminale di un'app MSIX: Windows gli darebbe una copia privata di AppData e del registro).

L'ID dell'estensione (`mmfdonlfmjnahpaebjhjlgkpplkfplop`) è fissato dalla chiave pubblica in
`manifest.json`; l'installer lo elenca in `allowed_origins`. Una pubblicazione su uno store, con un
ID diverso, va aggiunta in `ExtOrigins` di `installer/setup.iss` e in `CHROMIUM_ORIGINS` di
`installer/linux/install.sh` (`tools/release_check.py` controlla che coincidano).

## Licenza

MIT
