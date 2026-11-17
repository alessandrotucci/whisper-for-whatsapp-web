package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.icu.util.ULocale;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>I messaggi della stessa chat arrivati entro GROUP_MS dall'ultimo formano un gruppo, una riga
 * dell'archivio con un messaggio per riga di testo; ogni messaggio nuovo rimette in attesa la
 * traduzione dell'intero gruppo, e la notifica del gruppo si aggiorna. Le traduzioni girano su un
 * thread proprio, senza servizio in primo piano: sono richieste di pochi secondi e non devono
 * aspettare la coda delle trascrizioni.
 */
final class MessageTranslator {

    private static final String TAG = "whisperwa";
    private static final long GROUP_MS = 3 * 60 * 1000;
    private static final long MAX_AGE_MS = 10 * 60 * 1000;
    private static final int MIN_LETTERS = 4;
    private static final float MIN_CONFIDENCE = 0.5f;
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

    /** Rimette in attesa la traduzione di un gruppo e la rifà. */
    void retry(long noteId) {
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
        String lang = detect(text);
        if (lang == null || AiProviders.baseLang(lang).equals(target)) return;
        Db db = Db.get(app);
        Db.Note group = db.lastMessages(pkg, chat, time - GROUP_MS);
        if (group != null) {
            db.appendMessage(group.id, text, time);
        } else {
            String source = "msg:" + pkg + ":" + chat + ":" + time + ":" + Integer.toHexString(text.hashCode());
            db.insertMessages(pkg, chat, sender, source, text, lang, time);
        }
        Log.i(TAG, "messaggio da tradurre: lingua " + lang + ", " + text.length() + " caratteri");
        TranscriptionQueue.get(app).changed();
        drain();
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
        List<String> lines = new ArrayList<>(Arrays.asList(note.text.split("\n")));
        try {
            AiReview.MessagesResult r = AiReview.translateMessages(app, lines, note.chat, name -> {
                translatingWith = name;
                TranscriptionQueue.get(app).changed();
            });
            List<String> out = new ArrayList<>();
            for (String t : r.translations) if (t != null) out.add(t);
            Log.i(TAG, "tradotti " + lines.size() + " messaggi con " + r.provider + " (" + r.model + ") in " + r.ms + " ms");
            if (out.isEmpty()) {
                // Per l'IA nessun messaggio era da tradurre: il gruppo non resta nella lista.
                db.delete(note.id);
            } else {
                db.markTranslated(note.id, String.join("\n", out), r.targetLang, r.provider, r.model);
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
        Db.Note done = db.byId(note.id);
        if (done != null && (done.translation != null || done.reviewState == Db.REVIEW_FAILED)) {
            Notifier.showMessages(app, done);
        }
        TranscriptionQueue.get(app).changed();
    }
}
