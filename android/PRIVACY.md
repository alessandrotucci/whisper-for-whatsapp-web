# Informativa sulla privacy: WA Transcriber and Translator (Android)

*English below.*

Versione del 19 novembre 2026. Vale per l'app Android «WA Transcriber and Translator»
(`io.github.alessandrotucci.whisperwa`), distribuita su Google Play e nelle release di questo
repository. Sviluppatore: Alessandro Tucci. Contatti: le
[issue del repository](https://github.com/alessandrotucci/whisper-for-whatsapp-web/issues) oppure
l'indirizzo email indicato nella scheda di Google Play.

## In breve

L'app trascrive sul telefono i messaggi vocali di WhatsApp e WhatsApp Business. Lo sviluppatore non
riceve nessun dato: l'app non ha server propri, statistiche d'uso, pubblicità né tracciamento. Esce
dal telefono soltanto il testo che l'utente sceglie di far rivedere o tradurre, e solo verso il
fornitore di intelligenza artificiale che collega lui, con la sua chiave.

## Dati che l'app tratta sul telefono

- **Notifiche di WhatsApp e WhatsApp Business**: mittente, nome della chat, testo dei messaggi e
  presenza di un vocale. Servono a riconoscere i vocali da trascrivere e, se l'utente lo attiva, i
  messaggi scritti da tradurre. L'app le legge solo dopo che l'utente ha accettato l'informativa in app
  e attivato l'accesso alle notifiche di Android. Le notifiche delle altre app si ignorano. Gli ultimi
  messaggi di ogni chat restano solo in memoria, al più due ore, e non si scrivono su disco.
- **File audio dei vocali**: letti dalla cartella che l'utente sceglie, o ricevuti con *Condividi*.
  L'audio non lascia mai il telefono. Le copie dei file condivisi restano al più 7 giorni.
- **Trascrizioni, revisioni e traduzioni**: salvate in un database privato dell'app, con mittente,
  chat e ora. Si cancellano dalla lista dell'app, con *Cancella tutto* o disinstallando l'app.
- **Chiavi degli account di IA**: cifrate con il Keystore di Android e conservate solo sul telefono.

Il backup automatico di Android è disattivato: questi dati non vanno nel cloud di Google.

## Dati che escono dal telefono

- **Al fornitore di IA scelto dall'utente** (per esempio Anthropic, OpenAI, Google, Mistral,
  OpenRouter), solo se l'utente collega un account e accetta l'informativa in app: il testo di ogni
  trascrizione da rivedere o tradurre, i messaggi scritti in un'altra lingua se la traduzione dei
  messaggi è attiva, il nome della chat e, se l'utente attiva il contesto, gli ultimi messaggi della chat
  con i nomi dei mittenti. La trasmissione è cifrata (HTTPS), salvo un server locale configurato
  dall'utente con un indirizzo `http://`. Il fornitore tratta i testi secondo le proprie condizioni e
  la propria informativa; l'app non ne controlla la conservazione.
- **Download**: i modelli di trascrizione si scaricano da huggingface.co e le guide per ottenere le
  chiavi da raw.githubusercontent.com. Queste richieste non contengono dati dell'utente; come ogni
  richiesta di rete, rivelano al server l'indirizzo IP.

L'app non vende dati e non li condivide con altri soggetti.

## Scelte dell'utente

- Revocare l'accesso alle notifiche dalle impostazioni di Android.
- Scollegare un account di IA dalle Impostazioni dell'app: da quel momento nessun testo esce.
- Rileggere le informative e lo stato dei consensi dal menu ⋮, voce *Privacy*.
- Cancellare tutti i dati disinstallando l'app o cancellandone i dati da *Info app*.

## Minori

L'app non è rivolta ai minori di 13 anni.

## Modifiche

Le modifiche a questa informativa si pubblicano in questo file, con la data aggiornata in cima.

---

# Privacy policy: WA Transcriber and Translator (Android)

Version of 19 November 2026. It applies to the Android app "WA Transcriber and Translator"
(`io.github.alessandrotucci.whisperwa`), distributed on Google Play and in this repository's releases.
Developer: Alessandro Tucci. Contact: the
[repository issues](https://github.com/alessandrotucci/whisper-for-whatsapp-web/issues) or the email
address shown on the Google Play listing.

## In short

The app transcribes WhatsApp and WhatsApp Business voice messages on the phone. The developer receives
no data: the app has no servers of its own, no usage statistics, no ads and no tracking. The only thing
that leaves the phone is the text the user chooses to have reviewed or translated, and only to the AI
provider the user connects, with the user's own key.

## Data the app handles on the phone

- **WhatsApp and WhatsApp Business notifications**: sender, chat name, message text and whether a
  voice message arrived. They are used to find the voice messages to transcribe and, if the user turns
  it on, the written messages to translate. The app reads them only after the user accepts the in-app
  notice and turns on Android's notification access. Notifications from other apps are ignored. The
  latest messages of each chat are kept in memory only, for at most two hours, and are never written to
  storage.
- **Voice message audio files**: read from the folder the user picks, or received through *Share*.
  Audio never leaves the phone. Copies of shared files are kept for at most 7 days.
- **Transcriptions, reviews and translations**: stored in the app's private database, with sender,
  chat and time. They are deleted from the app's list, with *Delete all*, or by uninstalling the app.
- **AI account keys**: encrypted with the Android Keystore and kept on the phone only.

Android automatic backup is turned off: this data does not go to Google's cloud.

## Data that leaves the phone

- **To the AI provider the user chooses** (for example Anthropic, OpenAI, Google, Mistral,
  OpenRouter), only if the user connects an account and accepts the in-app notice: the text of each
  transcription to review or translate, written messages in another language if message translation is
  on, the chat name and, if the user turns on context, the latest messages of the chat with the
  senders' names. Transmission is encrypted (HTTPS), except for a local server the user configures
  with an `http://` address. The provider handles the text under its own terms and privacy policy; the
  app does not control how long it is kept.
- **Downloads**: transcription models come from huggingface.co and the guides for getting keys from
  raw.githubusercontent.com. These requests contain no user data; like any network request, they reveal
  the IP address to the server.

The app does not sell data and does not share it with anyone else.

## User choices

- Revoke notification access in Android settings.
- Disconnect an AI account in the app's Settings: from then on no text leaves the phone.
- Read the notices and the consent status again from the ⋮ menu, *Privacy*.
- Delete all data by uninstalling the app or clearing its data from *App info*.

## Children

The app is not directed at children under 13.

## Changes

Changes to this policy are published in this file, with the updated date at the top.
