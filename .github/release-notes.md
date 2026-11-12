## Come provarla

Serve Windows 10 o 11 a 64 bit, con Chrome o Edge. Con il modello large-v3 servono circa 4 GB di
RAM liberi e un vocale di un minuto richiede un paio di minuti su una CPU normale: su un PC meno
potente conviene large-v3-turbo.

1. Scarica **whisper-for-whatsapp-web-extension.zip** qui sotto ed estrailo in una cartella che
   non cancellerai (per esempio `Documenti\whisper-for-whatsapp-web`).
2. Apri `chrome://extensions` oppure `edge://extensions`, attiva **Modalità sviluppatore**
   (interruttore in alto a destra in Chrome, a sinistra in Edge), premi **Carica estensione non
   pacchettizzata** e scegli la cartella estratta.
3. Si apre la pagina dell'estensione: premi **Scarica l'installer per Windows**, apri il file e
   premi *Avanti* fino alla fine. Se Windows mostra «Windows ha protetto il PC», premi
   *Ulteriori informazioni* → *Esegui comunque* (l'installer non è firmato).
4. Torna alla pagina: trova da sola il componente installato. Scegli il modello e premi
   **Scarica** (large-v3 ≈ 3 GB, una volta sola).
5. Apri o ricarica WhatsApp Web: sotto ogni vocale c'è **Trascrivi**, e i vocali in arrivo si
   trascrivono da soli.

Aggiornare: dalla 1.3.0 l'estensione controlla da sola una volta al giorno e propone **Aggiorna**,
che aggiorna componente locale ed estensione. Da una versione precedente alla 1.3.0 si passa a mano
un'ultima volta: nuovo zip nella cartella, **Ricarica** sull'estensione, poi l'installer.
Disinstallare: rimuovi l'estensione dal browser e il componente da *Impostazioni → App*.

Con la modalità sviluppatore attiva, il browser all'avvio può proporre di disattivare le estensioni
non pacchettizzate: va lasciata attiva.
