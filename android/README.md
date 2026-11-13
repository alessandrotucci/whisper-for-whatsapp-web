# Whisper for WhatsApp per Android

App Android che trascrive **sul telefono** i vocali che arrivano su WhatsApp e WhatsApp Business,
con [whisper.cpp](https://github.com/ggml-org/whisper.cpp), e mostra il testo come notifica.
Toccando una notifica si apre la lista completa delle trascrizioni. Nessun servizio cloud.

Su WhatsApp per telefono non si può scrivere sotto i vocali: le notifiche fanno la parte del
riquadro dell'estensione per WhatsApp Web. L'app non condivide codice con estensione e componente
desktop e ha una sua linea di versione (`version.properties`, tag `android-v…`).

Requisiti: Android 10 o successivo, processore a 64 bit (arm64-v8a).

## Come funziona

```
notifica di WhatsApp con 🎤 ─ VoiceListener (NotificationListenerService)
      │ mittente, chat, ora
   VoiceScanner: controlla la cartella dei vocali (Storage Access Framework) ogni 3 s per 2 minuti
      │ file .opus nuovo e stabile
   tabella notes (SQLite): coda persistente
      │
   TranscriptionQueue: un thread, una trascrizione per volta, wake lock parziale
      │ MediaCodec (Opus) → float mono 16 kHz → whisper.cpp (JNI)
   Notifier: notifica con il testo, azione Copia, riepilogo del gruppo
```

- La notifica serve solo a sapere che è arrivato un vocale; il file si legge dalla cartella
  `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes` (per Business
  `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes`),
  scelta una volta con il selettore di sistema. Ai file nuovi si assegnano mittente e chat delle
  notifiche in ordine di arrivo.
- Ripieghi: *Condividi* un vocale da WhatsApp verso **Trascrivi**; dal menu, *Cerca vocali nuovi*
  mette in coda quelli delle ultime 24 ore.
- Modelli ggml scaricati dall'app da `huggingface.co/ggerganov/whisper.cpp`, verificati con
  SHA-256 (`Models.java`): large-v3 q5_0 (predefinito), large-v3-turbo q5_0, medium q5_0, small q5_1,
  base q5_1.
- ggml è compilato con una variante del backend CPU per famiglia di processori ARM
  (`GGML_CPU_ALL_VARIANTS`); all'avvio si carica la migliore supportata dal telefono. Per questo
  le librerie native sono estratte sul disco (`useLegacyPackaging`).

Limiti noti: le chat silenziate e la chat aperta sul telefono non producono notifiche, quindi i loro
vocali si trascrivono solo con la condivisione o con *Cerca vocali nuovi*.

## Compilare

L'APK si costruisce in CI (`.github/workflows/android.yml`) su ogni push che tocca `android/`.
In locale servono JDK 17 o successivo e l'SDK Android (con NDK e CMake, che AGP scarica da sé):

```powershell
& C:\percorso\whisper-for-whatsapp-web\android\gradlew.bat -p C:\percorso\whisper-for-whatsapp-web\android assembleRelease
```

Senza le variabili `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` e
`ANDROID_KEY_PASSWORD` l'APK di release esce non firmato.

## Release

1. Alza `versionName` e `versionCode` in `version.properties`.
2. Crea e pubblica il tag `android-v<versionName>`.

Il workflow verifica che il tag coincida con `versionName`, firma con il keystore nei secret,
controlla che il certificato sia quello atteso e crea la release con `--latest=false`: "latest"
deve restare una release `v*`, perché l'estensione cerca lì i propri aggiornamenti e l'installer
per Windows.
