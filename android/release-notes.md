App Android di Whisper for WhatsApp: trascrive sul telefono, senza cloud, i vocali che arrivano su
WhatsApp e WhatsApp Business, e mostra il testo nelle notifiche. Toccando una notifica si apre la
lista completa. Serve Android 10 o successivo su un processore a 64 bit.

## Installazione

1. Scarica **WhisperForWhatsApp-android-….apk** qui sotto dal telefono e aprilo. Android chiede
   di consentire l'installazione di app da questa fonte (il browser o il gestore file): consentila.
2. Apri l'app e segui le righe in cima alla schermata:
   - **Notifiche**: consenti, per vedere le trascrizioni.
   - **Accesso alle notifiche**: serve a sapere quando arriva un vocale. Da Android 13, per le app
     installate da file, l'interruttore è grigio («impostazione con restrizioni»): apri *Info app*,
     menu ⋮, *Consenti impostazioni con restrizioni*, e torna ad attivarlo.
   - **Modello**: scaricalo dalle Impostazioni. large-v3 (1,1 GB) è il consigliato;
     large-v3-turbo (574 MB) è più veloce di circa un quinto ma meno preciso; small (190 MB) è per
     i telefoni datati.
   - **Cartella dei vocali**: il selettore si apre su
     `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes`; tocca *Usa questa cartella*
     e *Consenti*. Per WhatsApp Business la cartella è quella di `com.whatsapp.w4b`.
   - **Batteria**: togli le limitazioni, così le trascrizioni finiscono anche a schermo spento.

**Revisione e traduzione con l'IA (facoltativa).** Nelle Impostazioni, *Aggiungi un account*
collega uno o più account di IA (Claude, ChatGPT, Gemini, Grok, Mistral, OpenRouter e altri, con la
guida per ottenere la chiave). Dopo ogni trascrizione il testo, non l'audio, va al primo account:
corregge le parole capite male, sottolineate nella lista, e traduce i messaggi in un'altra lingua.
Le chiavi restano sul telefono, nel Keystore di Android.

**Lavoro in background.** Se il sistema ferma le trascrizioni, la notifica porta alla sezione
*Lavoro in background* delle Impostazioni, con lo stato dei permessi e i pulsanti per sistemarli.
Sui telefoni OPPO, OnePlus e realme tieni bassi i thread in background (2).

Se un vocale non viene rilevato (chat silenziata, chat aperta mentre arriva), tienilo premuto in
WhatsApp, *Condividi*, **Trascrivi**. Dal menu dell'app, *Cerca vocali nuovi* mette in coda quelli
delle ultime 24 ore non ancora trascritti.

Aggiornare: scarica e apri l'APK della release più recente con tag `android-v…`; si installa sopra
la versione precedente senza perdere modello e trascrizioni.

---

Android app of Whisper for WhatsApp: transcribes incoming WhatsApp and WhatsApp Business voice
messages on the phone, with no cloud, and shows the text as notifications. Install the APK, then
follow the checklist at the top of the app: notifications, notification access (on Android 13+
first allow restricted settings from App info, menu ⋮), a model, the voice notes folder and the
battery exemption. Optionally, connect one or more AI accounts in Settings to fix misheard words
and translate messages in another language; keys stay on the phone.
