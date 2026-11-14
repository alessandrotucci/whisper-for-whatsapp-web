package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coda seriale delle trascrizioni: un solo thread, una trascrizione per volta.
 *
 * <p>La coda vera è la tabella notes (righe PENDING): {@link #kick()} avvia, se non è già attivo,
 * un ciclo che prende la riga più vecchia, la trascrive e passa alla successiva finché non ne
 * restano. Il modello resta in memoria tra un vocale e l'altro e si libera dopo IDLE_UNLOAD_MS
 * senza lavoro. Durante il ciclo un wake lock parziale tiene sveglia la CPU e
 * {@link TranscriptionService} tiene il processo in primo piano.
 *
 * <p>I thread si scelgono all'inizio di ogni vocale: tutti quelli delle impostazioni con l'app
 * sullo schermo, quelli di background altrimenti. Se l'app esce dallo schermo durante una
 * trascrizione con più thread di quelli di background, la trascrizione si interrompe e riparte
 * con i thread di background, senza contare il tentativo: alcuni sistemi uccidono l'app che tiene
 * occupati tutti i core mentre non è visibile.
 *
 * <p>Se la revisione con l'IA è attiva, una trascrizione finita resta in attesa di revisione e la
 * sua notifica parte solo a revisione conclusa o fallita. Le revisioni si fanno nello stesso
 * ciclo, dopo le trascrizioni in coda, così restano protette dal servizio in primo piano.
 */
public final class TranscriptionQueue {

    private static final String TAG = "whisperwa";
    private static final long IDLE_UNLOAD_MS = 3 * 60 * 1000;
    private static final long WAKE_LOCK_MS = 30 * 60 * 1000;
    private static final int MAX_ATTEMPTS = 3;

    private static TranscriptionQueue instance;

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whisper-queue");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    private boolean draining;
    private boolean modelMissingNotified;
    private boolean serviceRefusedNotified;
    private int runningThreads;
    private boolean downgrade;
    private volatile long reviewingId = -1;
    private volatile String reviewingWith;
    private long ctx;
    private String ctxModel;
    private ScheduledFuture<?> unloadTask;

    public static synchronized TranscriptionQueue get(Context context) {
        if (instance == null) instance = new TranscriptionQueue(context.getApplicationContext());
        return instance;
    }

    private TranscriptionQueue(Context app) {
        this.app = app;
    }

    /** Chiamato sul thread principale a ogni cambiamento dell'archivio. */
    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    void changed() {
        main.post(() -> {
            for (Runnable r : listeners) r.run();
        });
    }

    /** Rimette in coda ciò che un processo precedente lasciava in corso e avvia il ciclo. */
    public void resume() {
        // Sincrono: kick() deve già vedere in coda le righe rimesse PENDING, per avviare il servizio.
        Db.get(app).resetRunning();
        kick();
    }

    public synchronized void kick() {
        if (draining) return;
        draining = true;
        worker.execute(this::drain);
    }

    /** Avvia il servizio in primo piano se non è attivo; se il sistema lo rifiuta avvisa una volta. */
    private void ensureService() {
        if (TranscriptionService.isActive()) return;
        if (!TranscriptionService.start(app) && !serviceRefusedNotified) {
            serviceRefusedNotified = true;
            BackgroundWork.notifyServiceRefused(app);
        }
    }

    public synchronized boolean isDraining() {
        return draining;
    }

    /**
     * Chiamato sul thread principale quando nessuna attività dell'app è più visibile: interrompe la
     * trascrizione in corso se usa più thread di quelli di background, per farla ripartire con quelli.
     */
    void onAppHidden() {
        int background = Prefs.backgroundThreads(app);
        synchronized (this) {
            if (runningThreads <= background) return;
            downgrade = true;
        }
        Log.i(TAG, "app non visibile: trascrizione interrotta per ripartire con " + background + " thread");
        WhisperLib.cancel();
    }

    /** Interrompe la trascrizione in corso; la riga va in errore e il ciclo prosegue. */
    public void cancelCurrent() {
        WhisperLib.cancel();
    }

    private void drain() {
        PowerManager pm = app.getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whisperwa:queue");
        wl.setReferenceCounted(false);
        wl.acquire(WAKE_LOCK_MS);
        try {
            cancelUnload();
            Db db = Db.get(app);
            while (true) {
                Db.Note note = db.nextPending();
                Models.Model model = Models.selectedInstalled(app);
                if (note != null && model == null) {
                    if (!modelMissingNotified) {
                        modelMissingNotified = true;
                        Notifier.showProblem(app, app.getString(R.string.problem_model_title),
                                app.getString(R.string.problem_model_text));
                    }
                    note = null;
                } else if (note != null) {
                    modelMissingNotified = false;
                }
                if (note != null) {
                    ensureService();
                    process(db, note, model);
                    continue;
                }
                Db.Note toReview = db.nextReviewPending();
                if (toReview == null) break;
                ensureService();
                review(db, toReview);
            }
        } finally {
            synchronized (this) {
                draining = false;
            }
            TranscriptionService.stop();
            Notifier.clearProgress(app);
            scheduleUnload();
            if (wl.isHeld()) wl.release();
            changed();
        }
        // Una riga arrivata tra l'ultima lettura e draining = false resta in coda: ricontrolla.
        Db db = Db.get(app);
        if ((Models.selectedInstalled(app) != null && db.nextPending() != null) || db.nextReviewPending() != null) kick();
    }

    private void process(Db db, Db.Note note, Models.Model model) {
        // Un vocale già iniziato MAX_ATTEMPTS volte senza finire ha visto il processo ucciso
        // ogni volta: si ferma invece di ricominciare all'infinito.
        if (note.attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "troppi tentativi interrotti: " + note.name);
            db.markError(note.id, app.getString(R.string.error_killed));
            changed();
            BackgroundWork.notifyKilled(app, note);
            return;
        }
        db.markRunning(note.id);
        changed();
        int queued = db.countPending();
        Notifier.showProgress(app, note, 0, queued);
        long started = SystemClock.elapsedRealtime();
        try {
            Uri uri = note.localPath != null ? Uri.fromFile(new File(note.localPath)) : Uri.parse(note.source);
            float[] samples = AudioDecoder.decode(app, uri);
            long audioMs = samples.length * 1000L / AudioDecoder.RATE;
            if (!ensureModel(model)) {
                db.markError(note.id, app.getString(R.string.error_model_load));
                return;
            }
            int[] last = {-1};
            boolean visible;
            int threads;
            // Nello stesso blocco di onAppHidden: o la scelta vede l'app già nascosta, o
            // onAppHidden vede i thread scelti e interrompe.
            synchronized (this) {
                visible = App.isVisible();
                threads = visible ? Prefs.threads(app) : Prefs.backgroundThreads(app);
                runningThreads = threads;
                downgrade = false;
            }
            Log.i(TAG, "inizio " + note.name + ": tentativo " + (note.attempts + 1) + ", thread " + threads
                    + (visible ? " (app visibile)" : " (app non visibile)") + ", modello " + model.id);
            WhisperLib.Result r = WhisperLib.transcribe(ctx, samples, Prefs.language(app),
                    threads, percent -> {
                        if (percent != last[0]) {
                            last[0] = percent;
                            Notifier.showProgress(app, note, percent, queued);
                        }
                    });
            long workMs = SystemClock.elapsedRealtime() - started;
            boolean downgraded;
            synchronized (this) {
                runningThreads = 0;
                downgraded = downgrade;
                downgrade = false;
            }
            if (r == null && downgraded) {
                Log.i(TAG, "rimesso in coda senza contare il tentativo: " + note.name + " dopo " + workMs + " ms");
                db.requeueUncounted(note.id);
                changed();
                return;
            }
            if (r == null) {
                db.markError(note.id, app.getString(R.string.error_transcribe));
                changed();
                return;
            }
            Log.i(TAG, "trascritto " + note.name + ": audio " + audioMs + " ms, lavoro " + workMs + " ms, modello "
                    + model.id + ", thread " + threads + ", lingua " + r.language);
            db.markDone(note.id, r.text, r.language, audioMs, workMs, model.id);
            deleteLocalCopy(note);
            if (!r.text.isEmpty() && AiStore.load(app).needsReview(r.language)) {
                db.setReviewState(note.id, Db.REVIEW_PENDING);
            } else {
                Db.Note done = db.byId(note.id);
                if (done != null) Notifier.showTranscript(app, done);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "trascrizione fallita: " + note.source, e);
            db.markError(note.id, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        changed();
    }

    /** Il fornitore con cui si sta rivedendo la riga noteId, oppure null. */
    public String reviewingWith(long noteId) {
        return reviewingId == noteId ? reviewingWith : null;
    }

    /** Rimette in attesa la revisione di una riga trascritta e avvia il ciclo. */
    public void reviewAgain(long noteId) {
        Db.get(app).setReviewState(noteId, Db.REVIEW_PENDING);
        changed();
        kick();
    }

    private void review(Db db, Db.Note note) {
        db.setReviewState(note.id, Db.REVIEW_RUNNING);
        reviewingId = note.id;
        reviewingWith = null;
        changed();
        String context = ChatContext.before(note.pkg, note.chat, note.received + 60_000);
        try {
            AiReview.Result r = AiReview.review(app, note.text == null ? "" : note.text, note.language, null, context,
                    name -> {
                        reviewingWith = name;
                        Notifier.showReviewProgress(app, note, name);
                        changed();
                    });
            if (r == null) {
                db.setReviewState(note.id, Db.REVIEW_NONE);
            } else {
                Log.i(TAG, "rivisto " + note.name + " con " + r.provider + " (" + r.model + ") in " + r.ms + " ms"
                        + (r.rejected ? ", correzione scartata" : "") + (r.translation != null ? ", tradotto" : ""));
                db.markReviewDone(note.id, r.revised ? r.text : null, r.revised ? r.segments.toString() : null,
                        r.translation, r.targetLang, r.provider, r.model);
            }
        } catch (AiReview.AllFailed e) {
            Log.w(TAG, "revisione non riuscita: " + note.name);
            db.markReviewFailed(note.id, AiReview.failuresText(app, e.errors));
        } catch (RuntimeException e) {
            Log.e(TAG, "revisione fallita: " + note.name, e);
            db.markReviewFailed(note.id, String.valueOf(e.getMessage()));
        } finally {
            reviewingId = -1;
            reviewingWith = null;
        }
        Db.Note done = db.byId(note.id);
        if (done != null) Notifier.showTranscript(app, done);
        changed();
    }

    private static void deleteLocalCopy(Db.Note note) {
        if (note.localPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(note.localPath).delete();
        }
    }

    private boolean ensureModel(Models.Model model) {
        if (ctx != 0 && model.id.equals(ctxModel)) return true;
        freeModel();
        ctx = WhisperLib.load(app, model.path(app).getAbsolutePath());
        ctxModel = ctx != 0 ? model.id : null;
        return ctx != 0;
    }

    private void freeModel() {
        if (ctx != 0) {
            WhisperLib.free(ctx);
            ctx = 0;
            ctxModel = null;
        }
    }

    private synchronized void scheduleUnload() {
        cancelUnload();
        unloadTask = timer.schedule(() -> worker.execute(() -> {
            synchronized (this) {
                if (draining) return;
            }
            freeModel();
        }), IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelUnload() {
        if (unloadTask != null) {
            unloadTask.cancel(false);
            unloadTask = null;
        }
    }

    /** Libera subito il modello se la coda è ferma, per esempio dopo averlo cambiato o cancellato. */
    public void releaseModel() {
        worker.execute(() -> {
            synchronized (this) {
                if (draining) return;
            }
            freeModel();
        });
    }
}
