# Schede degli store per l'estensione

Testi da incollare nelle schede di Chrome Web Store, Edge Add-ons, Firefox Add-ons e Opera Add-ons. I conteggi accanto ai titoli sono calcolati sul testo esatto. I limiti indicati sono quelli che conosco fino a giugno 2026: lo store li ricontrolla quando si incolla.

## Dati comuni

| Campo | Valore |
|---|---|
| Nome | WA Web Transcriber, Translator and Reviewer (43 caratteri) |
| Categoria | Chrome Web Store e Edge: Productivity / Produttività; Firefox: Social e comunicazione, Supporto lingue; Opera: Productivity |
| Licenza | MIT (Firefox: "Licenza MIT"; Opera: URL della licenza https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/LICENSE) |
| Informativa sulla privacy | https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/docs/privacy.md |
| Sito e supporto | https://github.com/alessandrotucci/whisper-for-whatsapp-web ; segnalazioni: https://github.com/alessandrotucci/whisper-for-whatsapp-web/issues |
| Codice sorgente | https://github.com/alessandrotucci/whisper-for-whatsapp-web |

## Italiano

### Sommario (102 caratteri; Chrome Web Store fino a 132, Firefox fino a 250)

```
Trascrive sul tuo computer i vocali di WhatsApp Web; revisione e traduzione facoltative con la tua IA.
```

### Descrizione (2411 caratteri)

```
WA Web Transcriber, Translator and Reviewer trascrive i messaggi vocali di WhatsApp Web direttamente sul tuo computer, con Whisper, e mostra il testo sotto ogni vocale. L'audio non lascia il computer.

TRASCRIZIONE SUL TUO COMPUTER
• Usa Whisper, il modello di riconoscimento vocale open source, tramite un piccolo componente per Windows o Linux da installare una volta (gratuito, dalla pagina dell'estensione).
• Trascrive da solo i vocali in arrivo, oppure su richiesta con il pulsante Trascrivi, anche quelli vecchi.
• Scegli il modello: large-v3 (il più preciso), large-v3-turbo (più veloce), medium o small. Con una scheda NVIDIA può usare la GPU.
• Riconosce la lingua da solo; se sbaglia, scegli quella giusta dal menu e il vocale si trascrive di nuovo.
• Chrome, Edge e Firefox aperti insieme condividono una sola coda: una trascrizione per volta, con la posizione di ogni vocale.

REVISIONE E TRADUZIONE CON L'IA (FACOLTATIVA)
• Collega un tuo account di IA: Claude, ChatGPT, Gemini, Grok, Mistral, DeepSeek, OpenRouter e altri, oppure Ollama o LM Studio sul tuo computer. Una guida passo passo ti aiuta a ottenere la chiave.
• L'IA corregge le parole capite male (le vedi sottolineate) e traduce i vocali in un'altra lingua nella tua. Con l'interruttore Trascrizione | Revisione confronti i due testi.
• Traduce anche i messaggi scritti che ricevi in un'altra lingua, sotto la bolla.
• All'IA va solo il testo, mai l'audio, e solo verso l'account che colleghi tu. Le chiavi restano sul tuo computer, cifrate.

COMODA DA USARE
• Copia con Ctrl+C più messaggi: trascrizioni e traduzioni finiscono negli appunti al loro posto, con ora e mittente.
• Interfaccia in italiano e in inglese.

PRIVACY
• Nessun server dello sviluppatore, nessuna pubblicità, nessuna statistica d'uso.
• Codice sorgente aperto, licenza MIT: https://github.com/alessandrotucci/whisper-for-whatsapp-web

Requisiti: Windows 10 o 11 a 64 bit, oppure Linux x86_64 (Ubuntu 22.04, Debian 12, Fedora 36 o successivi). Il componente locale si installa dalla pagina dell'estensione: su Windows con un installer, su Linux con un comando da terminale, senza privilegi di root. Con il modello large-v3 servono circa 4 GB di RAM liberi. Su macOS e ChromeOS il componente non esiste ancora.

WA Web Transcriber, Translator and Reviewer non è affiliata a WhatsApp, a Meta né agli altri marchi citati, che appartengono ai rispettivi proprietari.
```

## English

### Sommario (106 caratteri; Chrome Web Store fino a 132, Firefox fino a 250)

```
Transcribes WhatsApp Web voice messages on your computer, with optional review and translation by your AI.
```

### Descrizione (2333 caratteri)

```
WA Web Transcriber, Translator and Reviewer transcribes WhatsApp Web voice messages right on your computer, with Whisper, and shows the text under each voice message. The audio never leaves your computer.

TRANSCRIPTION ON YOUR COMPUTER
• Uses Whisper, the open source speech recognition model, through a small Windows or Linux component you install once (free, from the extension page).
• Transcribes incoming voice messages automatically, or on request with the Transcribe button, older ones too.
• Pick the model: large-v3 (most accurate), large-v3-turbo (faster), medium or small. With an NVIDIA card it can use the GPU.
• Detects the language by itself; if it gets it wrong, pick the right one from the menu and the voice message is transcribed again.
• Chrome, Edge and Firefox open together share a single queue: one transcription at a time, with each voice message's position.

AI REVIEW AND TRANSLATION (OPTIONAL)
• Connect your own AI account: Claude, ChatGPT, Gemini, Grok, Mistral, DeepSeek, OpenRouter and more, or Ollama or LM Studio on your computer. A step-by-step guide helps you get the key.
• The AI fixes misheard words (shown underlined) and translates voice messages in another language into yours. The Transcription | Review switch lets you compare the two texts.
• It also translates text messages you receive in another language, under the bubble.
• Only the text goes to the AI, never the audio, and only to the account you connect. Keys stay on your computer, encrypted.

EASY TO USE
• Copy several messages with Ctrl+C: transcriptions and translations land in the clipboard in their place, with time and sender.
• Interface in English and Italian.

PRIVACY
• No developer servers, no ads, no usage statistics.
• Open source, MIT license: https://github.com/alessandrotucci/whisper-for-whatsapp-web

Requirements: 64-bit Windows 10 or 11, or x86_64 Linux (Ubuntu 22.04, Debian 12, Fedora 36 or later). The local component is installed from the extension page: on Windows with an installer, on Linux with a terminal command, no root needed. The large-v3 model needs about 4 GB of free RAM. The component is not available yet for macOS and ChromeOS.

WA Web Transcriber, Translator and Reviewer is not affiliated with WhatsApp, Meta or the other trademarks mentioned, which belong to their respective owners.
```

## Note per i revisori (in inglese, per tutti gli store)

```
How to test (64-bit Windows 10/11 or x86_64 Linux; the extension needs a native messaging host, not available on macOS):
1. Install the extension, open its options page ("Setup and guide"). Step 1 installs the local component from https://github.com/alessandrotucci/whisper-for-whatsapp-web/releases/latest, per-user, no admin or root rights. On Windows it links WhisperForWhatsAppWeb-Setup.exe; on Linux it shows a terminal command to copy, which downloads whisper-for-whatsapp-web-linux-x86_64.tar.gz into a private temporary folder and runs its install.sh. The page detects the component automatically.
2. Step 2: download a model. "small" (about 0.5 GB) is enough for testing.
3. Open https://web.whatsapp.com and sign in with any WhatsApp account. Under each voice message a "Transcribe" button appears; incoming voice messages are transcribed automatically.
4. Optional AI features (section 4 of the options page): connect an account with an API key of any listed provider, for example a free Google Gemini key from https://aistudio.google.com/apikey. Text messages in another language then get a translation under the bubble.

What the permissions are for: nativeMessaging talks to the local component that runs Whisper; storage and unlimitedStorage keep transcriptions, translations and settings in the browser; alarms runs the daily update check of the local component; host access to web.whatsapp.com reads voice messages and text messages and shows the results under them.

Remote code: none. The extension fetches only JSON data (release information from api.github.com, the key guides from raw.githubusercontent.com). The local component is open source in the same repository: https://github.com/alessandrotucci/whisper-for-whatsapp-web

Privacy policy: https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/docs/privacy.md
```

## Chrome Web Store: scheda Privacy

**Scopo unico (single purpose)**

```
Transcribe WhatsApp Web voice messages locally with Whisper and, optionally, review and translate WhatsApp Web messages with the user's own AI account.
```

**nativeMessaging**

```
Talks to the local component (Windows or Linux) that runs the Whisper speech recognition model on the user's computer; the audio is processed there and never leaves the computer.
```

**storage**

```
Keeps transcriptions, translations and settings in the browser.
```

**unlimitedStorage**

```
Transcriptions and translations of many voice and text messages can exceed the default storage quota.
```

**alarms**

```
Runs the daily check for updates of the local component.
```

**Host permission (https://web.whatsapp.com/*)**

```
Reads voice messages and text messages on WhatsApp Web to transcribe and translate them, and shows the results under each message.
```

**Remote code**

```
No, the extension does not use remote code.
```

**Dati dell'utente.** Confermato: dichiarare "Contenuti del sito web" e "Comunicazioni personali" (l'estensione legge i messaggi di WhatsApp Web), e certificare che i dati non si vendono, non si usano per scopi estranei alla funzione unica e non servono a valutazioni di credito. Lo sviluppatore non riceve nulla; il testo va al fornitore di IA solo se l'utente collega un account, come funzione richiesta dall'utente.

## Vincoli degli store

- Firefox (AMO): l'indirizzo della scheda accetta al massimo 30 caratteri; quello in uso è `wa-web-transcriber`. Il modulo dei dettagli si salva solo con l'invio della versione. La descrizione italiana, l'icona e le catture si aggiungono dopo l'invio, da «Modifica pagina del prodotto». Alla domanda sul codice sorgente si risponde «No»: il pacchetto contiene il JavaScript così com'è, senza bundler né minimizzatori.
- Opera Add-ons: al massimo 3 catture per versione, 612x408, scattate nel browser Opera (`opera-*.png`); icona 64x64. Per i moderatori servono l'URL dell'archivio del sorgente al tag della versione e le istruzioni di build (`python tools/build_extension.py store --zip …` dal tag; il risultato coincide con il pacchetto pubblicato a meno dei fine riga). Ogni campo si conferma con la spunta verde.
- Edge Add-ons: logo 300x300, fino a 6 catture 1280x800 per lingua. Le tre attestazioni della sezione Privacy si spuntano a mano.
- Chrome Web Store: il campo «URL ufficiale» accetta solo siti verificati in Google Search Console e resta vuoto; il repository va in «URL della home page». Per pubblicare serve un'email di contatto del publisher verificata nelle impostazioni dell'account. Riquadro promozionale piccolo 440x280.
