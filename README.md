# Whisper for WhatsApp Web

Estensione per Chrome ed Edge che trascrive i vocali di WhatsApp Web con
[faster-whisper](https://github.com/SYSTRAN/faster-whisper) **sul tuo computer** e mostra il testo
sotto ogni vocale. L'audio non lascia la macchina: nessun servizio cloud, nessun account.

- Trascrizione automatica dei vocali in arrivo, in qualsiasi chat.
- Pulsante **Trascrivi** sotto ogni vocale, anche quelli arrivati prima dell'installazione.
- Copia con Ctrl+C: selezionando più messaggi, i vocali trascritti finiscono negli appunti al loro
  posto, con ora e mittente come i messaggi scritti.
- Coda seriale: una trascrizione per volta, anche con più vocali in arrivo insieme e con Chrome ed
  Edge aperti contemporaneamente.
- Modelli Whisper a scelta (large-v3 predefinito), scaricati dalla pagina dell'estensione o già
  presenti nella cache di faster-whisper.

Interfaccia in italiano e in inglese, secondo la lingua del browser. Solo Windows, per ora.
Progetto personale, non affiliato a WhatsApp o Meta.

## Installazione

1. Scarica `whisper-for-whatsapp-web-extension.zip` dall'[ultima release](https://github.com/alessandrotucci/whisper-for-whatsapp-web/releases/latest)
   ed estrailo in una cartella stabile.
2. Apri `chrome://extensions` (o `edge://extensions`), attiva **Modalità sviluppatore**, premi
   **Carica estensione non pacchettizzata** e scegli la cartella.
3. Si apre la pagina dell'estensione, che guida il resto:
   - **Componente locale**: scarica `WhisperForWhatsAppWeb-Setup.exe` e installalo (per il solo utente,
     senza privilegi di amministratore). L'installer non è firmato: se Windows mostra «Windows ha
     protetto il PC», premi *Ulteriori informazioni* → *Esegui comunque*.
   - **Modello**: se hai già scaricato un modello con faster-whisper viene trovato da solo; altrimenti
     premi *Scarica* (large-v3 ≈ 3 GB).
4. Ricarica WhatsApp Web.

## Come funziona

```
WhatsApp Web ─ page.js (mondo MAIN): moduli interni di WhatsApp, trova i vocali, scarica e decifra l'audio
      │ window.postMessage
   content.js: riquadro sotto il vocale
      │ chrome.runtime
   background.js (service worker): coda seriale, archivio delle trascrizioni
      │ native messaging (stdin/stdout)
   whisper_host.exe: faster-whisper, modello in memoria tra una richiesta e l'altra
```

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
release di questo repository. `tools/release_check.py`, in CI, blocca una release se tag, manifest,
`version.json` e `HOST_VERSION` non coincidono, o se `host/` o `installer/` sono cambiati senza
alzare la versione del componente.

Le trascrizioni restano in `chrome.storage.local`, legate all'id del messaggio. Il componente
locale resta attivo, col modello in memoria, per alcuni minuti dopo l'ultima trascrizione.

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
installer/   script di Inno Setup
tools/       generatori: icone e cataloghi delle traduzioni
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

Prove dell'host senza browser: `whisper_host.exe --info`, `--selftest`, `--file vocale.ogg --lang pt`.

Per far girare l'host da sorgente con un proprio Python, `host\install.ps1 -Python <python.exe> -Model large-v3`
registra un lanciatore `.cmd` al posto dell'eseguibile (va lanciato da un PowerShell normale, non
dal terminale di un'app MSIX: Windows gli darebbe una copia privata di AppData e del registro).

L'ID dell'estensione (`mmfdonlfmjnahpaebjhjlgkpplkfplop`) è fissato dalla chiave pubblica in
`manifest.json`; l'installer lo elenca in `allowed_origins`. Una pubblicazione su uno store, con un
ID diverso, va aggiunta in `ExtOrigins` di `installer/setup.iss`.

## Licenza

MIT
