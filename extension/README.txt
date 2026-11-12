WHISPER FOR WHATSAPP WEB
========================

Italiano più sotto / English below.

------------------------------------------------------------------
ITALIANO
------------------------------------------------------------------

Trascrive i vocali di WhatsApp Web sul tuo computer, con Whisper.
L'audio non lascia il PC: nessun servizio cloud, nessun account.
Le pagine dell'estensione sono in italiano se il browser è in italiano,
in inglese altrimenti.

Requisiti: Windows 10 o 11 a 64 bit, Google Chrome o Microsoft Edge.
Con il modello large-v3 servono circa 4 GB di RAM liberi; un vocale di
un minuto richiede un paio di minuti su una CPU normale. Su un PC meno
potente scegli large-v3-turbo.

INSTALLAZIONE

1. Estrai questo zip in una cartella che non cancellerai, per esempio
   Documenti\whisper-for-whatsapp-web. L'estensione gira da lì: se
   sposti o cancelli la cartella, smette di funzionare.

2. Apri il browser e vai su:
     Chrome:  chrome://extensions
     Edge:    edge://extensions
   Attiva "Modalità sviluppatore" (in alto a destra in Chrome, nel
   menu a sinistra in Edge), premi "Carica estensione non
   pacchettizzata" e scegli la cartella del punto 1.

3. Si apre la pagina dell'estensione. Premi "Scarica l'installer per
   Windows", apri il file scaricato e premi Avanti fino alla fine.
   Non servono privilegi di amministratore.
   Se Windows mostra "Windows ha protetto il PC", premi "Ulteriori
   informazioni" e poi "Esegui comunque": l'installer non è firmato.

4. Torna alla pagina dell'estensione: trova da sola il componente
   appena installato. Scegli il modello e premi "Scarica" (large-v3,
   circa 3 GB, si scarica una volta sola).

5. Apri o ricarica https://web.whatsapp.com : sotto ogni vocale compare
   il pulsante "Trascrivi", e i vocali in arrivo si trascrivono da soli.
   Le trascrizioni avvengono una per volta, in coda.

BUONO A SAPERSI

- All'avvio il browser può proporre di disattivare le estensioni in
  modalità sviluppatore: lasciala attiva.
- Aggiornare: l'estensione controlla da sola una volta al giorno se è
  uscita una versione nuova. Se sì, l'icona mostra una freccia e il
  popup un avviso: premi "Aggiorna" e fa tutto da sola (componente
  locale ed estensione).
- Disinstallare: rimuovi l'estensione dal browser e "Whisper for
  WhatsApp Web" da Impostazioni > App. Il modello scaricato resta in
  %USERPROFILE%\.cache\huggingface\hub e si può cancellare a mano.
- Impostazioni (lingua, vocabolario, trascrizione automatica): icona
  dell'estensione > "Configurazione e guida".

Progetto personale, non affiliato a WhatsApp o Meta.
https://github.com/alessandrotucci/whisper-for-whatsapp-web


------------------------------------------------------------------
ENGLISH
------------------------------------------------------------------

Transcribes WhatsApp Web voice messages on your own computer, with
Whisper. Audio never leaves your PC: no cloud service, no account.

Requirements: 64-bit Windows 10 or 11, Google Chrome or Microsoft Edge.
The large-v3 model needs about 4 GB of free RAM; a one-minute voice
message takes a couple of minutes on a typical CPU. On a slower PC pick
large-v3-turbo.

The extension shows its pages in English, or in Italian when the
browser is set to Italian.

INSTALLATION

1. Extract this zip into a folder you will keep, for example
   Documents\whisper-for-whatsapp-web. The extension runs from there:
   if you move or delete the folder, it stops working.

2. Open your browser and go to:
     Chrome:  chrome://extensions
     Edge:    edge://extensions
   Turn on "Developer mode" (top right in Chrome, left menu in Edge),
   click "Load unpacked" and pick the folder from step 1.

3. The extension page opens. Click "Download the Windows installer",
   run the downloaded file and click Next until it finishes. No
   administrator rights needed.
   If Windows shows "Windows protected your PC", click "More info" and
   then "Run anyway": the installer is not signed.

4. Go back to the extension page: it detects the component you just
   installed. Pick a model and click "Download": large-v3 is about
   3 GB and is downloaded only once.

5. Open or reload https://web.whatsapp.com : every voice message gets a
   "Transcribe" button, and incoming voice messages are transcribed
   automatically, one at a time.

GOOD TO KNOW

- At startup the browser may offer to disable developer-mode
  extensions: keep it enabled.
- Updating: the extension checks once a day for a new version. When
  there is one, the icon shows an arrow and the popup a notice: press
  "Update" and it does everything by itself (local component and
  extension).
- Uninstalling: remove the extension from the browser and "Whisper for
  WhatsApp Web" from Settings > Apps. The downloaded model stays in
  %USERPROFILE%\.cache\huggingface\hub and can be deleted by hand.
- Settings (language, vocabulary, automatic transcription): extension
  icon > "Setup and guide".

Personal project, not affiliated with WhatsApp or Meta.
https://github.com/alessandrotucci/whisper-for-whatsapp-web
