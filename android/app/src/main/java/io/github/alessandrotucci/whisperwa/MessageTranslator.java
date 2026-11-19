package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.icu.util.ULocale;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Traduzione dei messaggi scritti che arrivano in un'altra lingua, letti dalle notifiche di
 * WhatsApp. La lingua si riconosce sul telefono con il TextClassifier di Android: all'IA va solo
 * il testo dei messaggi da tradurre, mai quello già nella lingua dell'utente.
 *
 * <p>I messaggi della stessa chat e della stessa lingua arrivati entro GROUP_MS dall'ultimo formano
 * un gruppo, una riga dell'archivio con i messaggi separati da Db.MSG_SEP; ogni messaggio nuovo
 * rimette in attesa il gruppo, si traducono solo i messaggi non ancora tradotti e la notifica del
 * gruppo si aggiorna. Un messaggio in
 * un'altra lingua apre un gruppo suo, mentre quello della prima lingua resta aperto. Un messaggio
 * dalla lingua incerta (riconoscimento poco sicuro o testo breve) va nel gruppo aperto più recente
 * della chat, per non moltiplicare i gruppi per errore. Le traduzioni girano su un
 * thread proprio, senza servizio in primo piano: sono richieste di pochi secondi e non devono
 * aspettare la coda delle trascrizioni.
 */
final class MessageTranslator {

    private static final String TAG = "whisperwa";
    private static final long GROUP_MS = 3 * 60 * 1000;
    private static final long MAX_AGE_MS = 10 * 60 * 1000;
    private static final int MIN_LETTERS = 4;
    private static final float MIN_CONFIDENCE = 0.5f;
    private static final float SURE_CONFIDENCE = 0.8f;
    private static final int SURE_LETTERS = 12;
    private static final int SEEN_MAX = 500;

    private static MessageTranslator instance;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "message-translator"));
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private volatile long translatingId = -1;
    private volatile String translatingWith;

    static synchronized MessageTranslator get(Context context) {
        if (instance == null) instance = new MessageTranslator(context.getApplicationContext());
        return instance;
    }

    private MessageTranslator(Context app) {
        this.app = app;
    }

    /** Un messaggio scritto letto da una notifica; i duplicati delle notifiche ripubblicate si ignorano. */
    void onMessage(String pkg, String chat, String sender, String text, long time) {
        if (chat == null || text == null || System.currentTimeMillis() - time > MAX_AGE_MS) return;
        String key = pkg + '\u0000' + chat + '\u0000' + sender + '\u0000' + time + '\u0000' + text;
        synchronized (seen) {
            if (!seen.add(key)) return;
            if (seen.size() > SEEN_MAX) {
                Iterator<String> it = seen.iterator();
                it.next();
                it.remove();
            }
        }
        worker.execute(() -> handle(pkg, chat, sender, text.trim(), time));
    }

    /** Riprende le traduzioni rimaste in attesa, per esempio dopo un riavvio del processo. */
    void resume() {
        worker.execute(this::drain);
    }

    /** Traduce di nuovo da capo un gruppo dicendo all'IA che i messaggi sono nella lingua data. */
    void retryIn(long noteId, String language) {
        Db.get(app).setMessagesLanguage(noteId, language);
        TranscriptionQueue.get(app).changed();
        worker.execute(this::drain);
    }

    /** Rimette in attesa la traduzione di un gruppo e la rifà. */
    void retry(long noteId) {
        Db.get(app).clearTranslation(noteId);
        Db.get(app).setReviewState(noteId, Db.REVIEW_PENDING);
        TranscriptionQueue.get(app).changed();
        worker.execute(this::drain);
    }

    /** Il fornitore con cui si sta traducendo il gruppo noteId, oppure null. */
    String translatingWith(long noteId) {
        return translatingId == noteId ? translatingWith : null;
    }

    private void handle(String pkg, String chat, String sender, String text, long time) {
        AiStore.Data data = AiStore.load(app);
        if (!data.messagesActive()) return;
        String target = AiProviders.baseLang(data.targetOrDefault());
        Detection d = detectFull(text);
        if (d.letters < MIN_LETTERS) return;
        Db db = Db.get(app);
        long since = time - GROUP_MS;
        boolean sure = d.lang != null && d.confidence >= SURE_CONFIDENCE && d.letters >= SURE_LETTERS;
        Db.Note group;
        String lang = d.lang;
        if (sure) {
            if (AiProviders.baseLang(lang).equals(target)) return;
            group = db.lastMessages(pkg, chat, AiProviders.baseLang(lang), since);
        } else {
            group = db.lastMessages(pkg, chat, null, since);
            if (group == null && (lang == null || d.confidence < MIN_CONFIDENCE
                    || AiProviders.baseLang(lang).equals(target))) {
                return;
            }
        }
        if (group != null) {
            db.appendMessage(group.id, text, time, lang != null ? AiProviders.baseLang(lang) : null);
        } else {
            String source = "msg:" + pkg + ":" + chat + ":" + time + ":" + Integer.toHexString(text.hashCode());
            db.insertMessages(pkg, chat, sender, source, text, AiProviders.baseLang(lang), time);
        }
        Log.i(TAG, "messaggio da tradurre: lingua " + lang + (sure ? "" : " (incerta)") + ", " + text.length()
                + " caratteri, " + (group != null ? "gruppo " + group.id : "gruppo nuovo"));
        TranscriptionQueue.get(app).changed();
        drain();
    }

    /** Esito del riconoscimento: lingua (o null), affidabilità e numero di lettere del testo. */
    private static final class Detection {
        String lang;
        float confidence;
        int letters;
    }

    private Detection detectFull(String text) {
        Detection d = new Detection();
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) d.letters++;
        if (d.letters < MIN_LETTERS) return d;
        try {
            TextClassifier tc = app.getSystemService(TextClassificationManager.class).getTextClassifier();
            TextLanguage tl = tc.detectLanguage(new TextLanguage.Request.Builder(text).build());
            if (tl.getLocaleHypothesisCount() == 0) return d;
            ULocale best = tl.getLocale(0);
            String lang = best.getLanguage();
            if (lang == null || lang.isEmpty()) return d;
            d.lang = lang;
            d.confidence = tl.getConfidenceScore(best);
        } catch (RuntimeException e) {
            Log.w(TAG, "riconoscimento della lingua non disponibile", e);
        }
        return d;
    }

    /** La lingua del testo riconosciuta sul telefono, oppure null se troppo corto o incerto. */
    private String detect(String text) {
        int letters = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) letters++;
        if (letters < MIN_LETTERS) return null;
        try {
            TextClassifier tc = app.getSystemService(TextClassificationManager.class).getTextClassifier();
            TextLanguage tl = tc.detectLanguage(new TextLanguage.Request.Builder(text).build());
            if (tl.getLocaleHypothesisCount() == 0) return null;
            ULocale best = tl.getLocale(0);
            float confidence = tl.getConfidenceScore(best);
            if (confidence < MIN_CONFIDENCE) return null;
            String lang = best.getLanguage();
            return lang == null || lang.isEmpty() ? null : lang;
        } catch (RuntimeException e) {
            Log.w(TAG, "riconoscimento della lingua non disponibile", e);
            return null;
        }
    }

    private static boolean same(String a, String b) {
        return a.replaceAll("\\s+", " ").trim().equalsIgnoreCase(b.replaceAll("\\s+", " ").trim());
    }

    private void drain() {
        Db db = Db.get(app);
        Db.Note n;
        while ((n = db.nextMessagesPending()) != null) translate(db, n);
    }

    private void translate(Db db, Db.Note note) {
        db.setReviewState(note.id, Db.REVIEW_RUNNING);
        translatingId = note.id;
        translatingWith = null;
        TranscriptionQueue.get(app).changed();
        List<String> all = note.messages();
        // Si traducono solo i messaggi arrivati dopo l'ultima traduzione: le traduzioni già fatte
        // restano. "Traduci di nuovo" cancella la traduzione e rifà tutto il gruppo.
        List<String> done = note.translations();
        if (done.size() > all.size()) done = new ArrayList<>();
        int from = done.size();
        List<String> lines = new ArrayList<>(all.subList(from, all.size()));
        if (lines.isEmpty()) {
            db.setReviewState(note.id, note.translation != null ? Db.REVIEW_DONE : Db.REVIEW_FAILED);
            translatingId = -1;
            TranscriptionQueue.get(app).changed();
            return;
        }
        String target = AiProviders.baseLang(AiStore.load(app).targetOrDefault());
        List<String> langs = new ArrayList<>();
        boolean stated = note.forcedLang != null;
        for (String line : lines) langs.add(stated ? note.forcedLang : detect(line));
        try {
            AiReview.AttemptListener attempt = name -> {
                translatingWith = name;
                TranscriptionQueue.get(app).changed();
            };
            AiReview.MessagesResult r = AiReview.translateMessages(app, lines, langs, stated, note.chat, attempt);
            // Un messaggio in un'altra lingua rimasto senza traduzione, o "tradotto" con il testo
            // originale, si richiede da solo una volta.
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = r.translations.get(i);
                boolean foreign = langs.get(i) != null && !AiProviders.baseLang(langs.get(i)).equals(target);
                if (foreign && (t == null || same(t, lines.get(i)))) missing.add(i);
            }
            if (!missing.isEmpty()) {
                List<String> again = new ArrayList<>();
                List<String> againLangs = new ArrayList<>();
                for (int i : missing) {
                    again.add(lines.get(i));
                    againLangs.add(langs.get(i));
                }
                Log.i(TAG, "messaggi senza traduzione: " + missing.size() + ", nuova richiesta");
                AiReview.MessagesResult r2 = AiReview.translateMessages(app, again, againLangs, stated, note.chat, attempt);
                for (int k = 0; k < missing.size(); k++) {
                    String t = r2.translations.get(k);
                    if (t != null && !same(t, again.get(k))) r.translations.set(missing.get(k), t);
                }
            }
            List<String> merged = new ArrayList<>(done);
            for (int i = 0; i < lines.size(); i++) {
                String t = r.translations.get(i);
                merged.add(t != null && !same(t, lines.get(i)) ? t.replace(Db.MSG_SEP, " ") : "");
            }
            Log.i(TAG, "tradotti " + lines.size() + " messaggi di " + all.size() + " con " + r.provider + " ("
                    + r.model + ") in " + r.ms + " ms");
            boolean any = false;
            for (String t : merged) if (!t.isEmpty()) any = true;
            if (!any) {
                // Per l'IA nessun messaggio era da tradurre: il gruppo non resta nella lista.
                db.delete(note.id);
            } else {
                db.markTranslated(note.id, String.join(Db.MSG_SEP, merged), r.targetLang, r.provider, r.model);
            }
        } catch (AiReview.AllFailed e) {
            Log.w(TAG, "traduzione dei messaggi non riuscita");
            db.markReviewFailed(note.id, AiReview.failuresText(app, e.errors));
        } catch (RuntimeException e) {
            Log.e(TAG, "traduzione dei messaggi fallita", e);
            db.markReviewFailed(note.id, String.valueOf(e.getMessage()));
        } finally {
            translatingId = -1;
            translatingWith = null;
        }
        Db.Note after = db.byId(note.id);
        if (after != null && (after.translation != null || after.reviewState == Db.REVIEW_FAILED)) {
            Notifier.showMessages(app, after);
        }
        TranscriptionQueue.get(app).changed();
    }
}
