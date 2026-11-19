# Informativa sulla privacy · Privacy policy

[English below](#english)

Vale per l'estensione per browser *WA Web Transcriber, Translator and Reviewer* (pubblicata
anche come *Whisper per WhatsApp Web*) e per il suo componente locale per Windows.

## Italiano

**In breve.** I vocali si trascrivono sul tuo computer. Lo sviluppatore non riceve, non raccoglie e
non vende alcun dato: non ci sono server dello sviluppatore, account, statistiche d'uso né
pubblicità. Solo se colleghi tu un account di intelligenza artificiale, il testo dei messaggi va al
fornitore che hai scelto.

**Cosa si elabora e dove**

- *Audio dei vocali.* L'estensione legge l'audio dei vocali che compaiono in WhatsApp Web e lo passa
  al componente locale installato sul tuo computer, che lo trascrive con il modello Whisper. L'audio
  non lascia il computer.
- *Trascrizioni, traduzioni, impostazioni.* Restano nella memoria dell'estensione nel tuo browser e
  si cancellano con "Cancella le trascrizioni salvate" o disinstallando l'estensione.
- *Account di IA (facoltativi).* Se colleghi un account (per esempio Claude, ChatGPT, Gemini), la
  chiave resta sul tuo computer, cifrata con il tuo account di Windows. Per la revisione e la
  traduzione il componente manda al fornitore scelto: il testo trascritto o il messaggio scritto da
  tradurre, la lingua, i nomi e termini che hai scritto nelle impostazioni e, solo se attivi
  l'opzione "contesto", gli ultimi messaggi scritti della chat. Quei dati li tratta il fornitore,
  sotto il tuo account e secondo le sue condizioni; lo sviluppatore non li vede. Con Ollama o
  LM Studio il testo resta sul tuo computer o nella tua rete.

**Collegamenti di rete che l'estensione e il componente fanno**

- GitHub (api.github.com, github.com, raw.githubusercontent.com): controllo e download degli
  aggiornamenti, guide per ottenere le chiavi. Non si invia nessun dato personale.
- Hugging Face (huggingface.co): download del modello di trascrizione, quando lo scegli.
- PyPI (files.pythonhosted.org): download delle librerie NVIDIA, solo se scegli la GPU.
- I fornitori di IA che colleghi, come descritto sopra.

**Permessi del browser.** Accesso a web.whatsapp.com, per leggere i vocali e i messaggi e mostrare
trascrizioni e traduzioni; native messaging, per parlare con il componente locale; archiviazione,
per salvare trascrizioni e impostazioni; allarmi, per il controllo giornaliero degli aggiornamenti.

**Contatti.** Per domande o richieste apri una segnalazione su
<https://github.com/alessandrotucci/whisper-for-whatsapp-web/issues>.

Progetto personale, non affiliato a WhatsApp, Meta o ai fornitori di IA citati.

<a id="english"></a>

## English

**In short.** Voice messages are transcribed on your computer. The developer receives, collects and
sells no data: there are no developer servers, accounts, usage statistics or ads. Only if you connect
an artificial intelligence account yourself does message text go to the provider you chose.

**What is processed and where**

- *Voice message audio.* The extension reads the audio of voice messages shown in WhatsApp Web and
  hands it to the local component installed on your computer, which transcribes it with the Whisper
  model. The audio never leaves your computer.
- *Transcriptions, translations, settings.* They stay in the extension storage in your browser and
  are deleted with "Clear saved transcriptions" or by uninstalling the extension.
- *AI accounts (optional).* If you connect an account (for example Claude, ChatGPT, Gemini), its key
  stays on your computer, encrypted with your Windows account. For review and translation the
  component sends the chosen provider: the transcribed text or the text message to translate, the
  language, the names and terms you entered in the settings and, only if you turn on the "context"
  option, the last text messages of the chat. The provider processes that data under your account
  and its own terms; the developer never sees it. With Ollama or LM Studio the text stays on your
  computer or your network.

**Network connections made by the extension and the component**

- GitHub (api.github.com, github.com, raw.githubusercontent.com): update checks and downloads, guides
  to get the keys. No personal data is sent.
- Hugging Face (huggingface.co): download of the transcription model, when you choose it.
- PyPI (files.pythonhosted.org): download of the NVIDIA libraries, only if you choose the GPU.
- The AI providers you connect, as described above.

**Browser permissions.** Access to web.whatsapp.com, to read voice and text messages and show
transcriptions and translations; native messaging, to talk to the local component; storage, to keep
transcriptions and settings; alarms, for the daily update check.

**Contact.** For questions or requests open an issue at
<https://github.com/alessandrotucci/whisper-for-whatsapp-web/issues>.

Personal project, not affiliated with WhatsApp, Meta or the AI providers mentioned.
