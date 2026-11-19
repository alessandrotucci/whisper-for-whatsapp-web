# Play Console: risposte e dichiarazioni

Risposte proposte per le sezioni di *Monitora e migliora → Contenuti dell'app* e per la firma, ricavate dal
codice della versione 1.4.1. Ogni punto dice da dove viene; dove la risposta è una scelta o un'interpretazione
della policy, lo dice.

## Firma dell'app (Play App Signing)

- Chiave di firma dell'app: la chiave di release di GitHub, `whisper-android-release.jks`, alias
  `whisper-release`, RSA 4096, impronta SHA-256
  `A4:A6:9D:6F:D5:DB:04:E4:3A:A4:14:DB:83:BE:53:76:D8:F7:79:66:B1:E7:45:F4:69:1A:D9:4D:39:61:F4:D3`.
  Si carica con PEPK, scegliendo «Usa una chiave diversa / Esporta e carica una chiave da un archivio Java»,
  prima del primo rilascio in test aperto o in produzione. Così APK di GitHub e APK di Play hanno la stessa
  firma e si aggiornano a vicenda.
- Chiave di caricamento: `wa-transcriber-upload.p12`, alias `upload`, RSA 4096, impronta SHA-256
  `BC:FA:D0:36:B0:2F:F6:3A:DE:41:25:FC:28:5A:4F:81:A6:28:39:71:12:0E:96:85:1D:08:3C:CB:3E:0E:7F:54`.
  L'AAB firmato con questa chiave è l'artifact `aab` di ogni build del workflow `android`.
- Codice versione: Play rifiuta un AAB con un `versionCode` già caricato. La 1.4.1 ha `versionCode=15`.

## Norme sulla privacy

URL: https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/android/PRIVACY.md

## Accesso all'app

Risposta: **Tutte le funzionalità sono disponibili senza accesso speciale.** L'app non ha account né login.
La revisione e la traduzione con l'IA chiedono una chiave personale di un fornitore di IA, che l'utente
ottiene dal fornitore; nelle note per i revisori si può scrivere:

```
The app has no account or login. Transcription works without any key after downloading a model in Settings.
Optional AI review and translation need the user's own API key from a provider of their choice (for example a
free Google Gemini or Groq key); Settings > Add an account shows a step-by-step guide to get one.
```

## Annunci

**No**, l'app non contiene annunci (nessun SDK pubblicitario nel progetto).

## Classificazione dei contenuti (questionario IARC)

- Categoria: **Utilità, produttività, comunicazione o altro**.
- Violenza, sesso, linguaggio volgare, sostanze, gioco d'azzardo, orrore: **No** a tutto. L'app non contiene
  contenuti propri: mostra la trascrizione dei messaggi che l'utente riceve.
- Gli utenti possono interagire o scambiarsi contenuti tramite l'app? **No**: l'app non invia messaggi e non
  mette in contatto utenti. È un'interpretazione: i messaggi arrivano da WhatsApp, l'app li legge soltanto.
- Condivide la posizione? **No**. Acquisti digitali? **No**. Browser o motore di ricerca? **No**.

## Pubblico di destinazione

- Fasce d'età: **18 anni e oltre**. È una scelta: con fasce più giovani si applicano le norme per le famiglie,
  incompatibili con la lettura delle notifiche e l'invio di testi a servizi di IA scelti dall'utente.
- L'app attrae i bambini? **No**.

## App di notizie, governative, finanziarie, sanitarie

**No** a tutte.

## ID pubblicità

L'app usa l'ID pubblicità? **No** (nessuna libreria che lo legga; il manifest non dichiara
`com.google.android.gms.permission.AD_ID`).

## Sicurezza dei dati

Fatti, dal codice:
- L'audio non esce mai dal telefono; trascrizioni e revisioni restano nel database privato dell'app;
  `allowBackup="false"`.
- Lo sviluppatore non riceve nessun dato: nessun server, nessuna analitica, nessun SDK di terze parti.
- Se l'utente collega un account di IA e accetta l'informativa, l'app invia al fornitore scelto, con la chiave
  dell'utente: il testo delle trascrizioni, i messaggi scritti in un'altra lingua (se la traduzione dei messaggi
  è attiva), il nome della chat e, se l'utente attiva il contesto, gli ultimi messaggi della chat con i nomi dei
  mittenti.
- Tutte le chiamate ai fornitori sono HTTPS: dalla 1.4.1 l'app rifiuta gli indirizzi `http://`, anche per
  Ollama e LM Studio nella rete di casa, e il manifest non permette traffico in chiaro.

Risposte proposte. Google considera «raccolti» i dati che l'app trasmette fuori dal telefono, anche se lo
sviluppatore non li riceve; dichiararli è la scelta prudente:

- L'app raccoglie o condivide i tipi di dati richiesti? **Sì.**
- I dati sono criptati in transito? **Sì** (dalla 1.4.1: solo HTTPS).
- Gli utenti possono richiedere l'eliminazione dei dati? **Sì**: le voci si cancellano dalla lista, con
  *Cancella tutto* o disinstallando l'app; i testi presso il fornitore di IA seguono le regole del fornitore.
- Tipo di dati: **Messaggi → Altri messaggi in-app**.
  - Raccolti: **Sì**. Condivisi: **Sì** (con il fornitore di IA scelto dall'utente). È un'interpretazione
    prudente: la condivisione decisa dall'utente potrebbe rientrare nell'eccezione per i trasferimenti avviati
    dall'utente, ma l'invio avviene in automatico a ogni messaggio, non con un gesto ogni volta.
  - Trattati in modo temporaneo: **No** (il fornitore può conservarli).
  - Raccolta obbligatoria o facoltativa: **Facoltativa** (solo con un account di IA collegato).
  - Scopo: **Funzionalità dell'app**.
- Nessun altro tipo: niente posizione, contatti dalla rubrica, foto, file audio inviati, identificatori,
  dati finanziari o sanitari.

## Servizi in primo piano

Due dichiarazioni, una per tipo. Ogni dichiarazione chiede descrizione, effetto di un'interruzione, caso d'uso e
un link a un video (YouTube non in elenco va bene).

### dataSync (`FOREGROUND_SERVICE_DATA_SYNC`)

Usato da `DownloadService` per il download del modello e, sui telefoni con Android 10-14, da
`TranscriptionService` per la trascrizione (su Android 15 e successivi la trascrizione usa mediaProcessing).

- Caso d'uso: **Trasferimento di rete: caricamento o download**; per la trascrizione su Android 10-14
  aggiungere **Elaborazione locale: altro**.
- Descrizione:

```
The user downloads a speech recognition model (190 MB to 1.1 GB) from Settings by tapping Download. The download runs as a foreground service with a progress notification so it completes even if the user leaves the app. On Android 10-14 the same service type keeps the on-device transcription of a received voice message running until it finishes (typically 30-120 seconds); on Android 15+ transcription uses the mediaProcessing type.
```

- Effetto di un'interruzione:

```
If the download is interrupted the model is not available and the app cannot transcribe until the user starts the download again. If a transcription is interrupted the voice message stays untranscribed and is retried later, so the user does not get the text in time.
```

- Video (circa 30 s): apri l'app, Impostazioni, Modello, tocca *Scarica* su small; si vede la notifica di
  avanzamento; torna alla Home e apri la tendina: il download prosegue fino alla fine.

### mediaProcessing (`FOREGROUND_SERVICE_MEDIA_PROCESSING`)

Usato da `TranscriptionService` su Android 15 e successivi.

- Caso d'uso: **Transcodifica dei contenuti multimediali**. L'elenco di Google non prevede la trascrizione;
  la più vicina è la transcodifica, perché il servizio decodifica l'audio Opus e lo converte in testo. Il rischio
  di rifiuto è una mia stima: la policy chiede che l'operazione sia avviata dall'utente, mentre qui parte quando
  arriva un vocale, dopo che l'utente ha attivato la funzione. Si può aggiungere un caso d'uso personalizzato.
- Descrizione:

```
When a WhatsApp voice message arrives, the app decodes the Opus audio file and converts it to text on the device with the Whisper speech recognition model (whisper.cpp). The user turns this on explicitly: they grant notification access after an in-app consent notice and pick the voice notes folder. Processing takes 30-120 seconds per message, shows a progress notification and stops as soon as the transcription is done or fails. No audio leaves the device.
```

- Effetto di un'interruzione:

```
If processing is deferred or interrupted, the voice message is not transcribed: the user does not see its text in the notification and has to listen to the audio instead. Android may stop the work midway, so the app retries it later.
```

- Video (circa 40 s): parti dalla lista dell'app con l'accesso alle notifiche attivo; da un altro telefono
  arriva un vocale; compare la notifica di avanzamento della trascrizione; poi la notifica con il testo; apri
  la lista e mostra la voce trascritta. Per un utente che avvia l'operazione a mano: tieni premuto un vocale in
  WhatsApp, *Condividi*, *Trascrivi*, e mostra la stessa sequenza.

## Test chiuso

Account personale creato dopo il 13/11/2023: prima della produzione serve un test chiuso con almeno 12 tester
iscritti da almeno 14 giorni consecutivi. I tester si iscrivono con l'account Google tramite il link di
iscrizione del test; devono installare l'app da Play. Chi ha già l'APK di GitHub, con la stessa chiave di firma, dovrebbe
ricevere da Play l'aggiornamento sopra l'installazione esistente: è una deduzione dal funzionamento della
firma, da verificare con il primo tester che ha l'APK.
