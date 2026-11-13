package io.github.alessandrotucci.whisperwa;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trova i file dei vocali nelle cartelle di WhatsApp scelte con lo Storage Access Framework e
 * li mette in coda.
 *
 * <p>La cartella è la fonte dei vocali; le notifiche di WhatsApp servono a sapere quando
 * guardarla e a conoscere il mittente. WhatsApp non notifica ogni vocale (per esempio con
 * WhatsApp Web attivo) e a volte ritira la notifica dopo pochi millisecondi, quindi ogni sua
 * notifica avvia un controllo della cartella ogni POLL_MS per WATCH_MS, e il controllo mette in
 * coda tutti i .opus nuovi, annunciati o no. Un file è pronto quando la dimensione non cambia tra
 * due controlli o quando non è modificato da STABLE_AGE_MS.
 *
 * <p>Mittente e chat vengono dai vocali annunciati ({@link Hint}): a un file si assegna l'annuncio
 * con la stessa dimensione del contenuto indicato dalla notifica, se Android ha permesso di
 * leggerla, altrimenti quello con l'ora più vicina all'ora di modifica entro MATCH_MS. Un file
 * senza annuncio resta senza mittente.
 *
 * <p>La cartella dei vocali contiene una sottocartella per settimana (per esempio 202646): si
 * guardano i file .opus nella cartella scelta e nelle RECENT_DIRS sottocartelle modificate per
 * ultime, saltando quelle chiamate Sent. Contano i file modificati dopo la scelta della cartella e
 * nelle ultime MAX_AGE_MS.
 */
public final class VoiceScanner {

    private static final String TAG = "whisperwa";
    private static final long POLL_MS = 3000;
    private static final long WATCH_MS = 120_000;
    private static final long STABLE_AGE_MS = 10_000;
    private static final long MATCH_MS = 8000;
    private static final long HINT_TTL_MS = 10 * 60 * 1000;
    private static final long MAX_AGE_MS = 6 * 3600 * 1000L;
    private static final int RECENT_DIRS = 2;

    /** Mittente e chat di un vocale annunciato da una notifica, con la dimensione se nota. */
    public static final class Hint {
        final String chat;
        final String sender;
        final long time;
        final Uri uri;
        long size = -1;

        public Hint(String chat, String sender, long time, Uri uri) {
            this.chat = chat;
            this.sender = sender;
            this.time = time;
            this.uri = uri;
        }
    }

    private static final class Doc {
        String id;
        String name;
        long modified;
        long size;
        Uri uri;
    }

    private static VoiceScanner instance;

    private final Context app;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, List<Hint>> hints = new HashMap<>();
    private final Map<String, Long> watchUntil = new HashMap<>();
    private final Map<String, Long> lastSize = new HashMap<>();
    private final Map<String, Boolean> polling = new HashMap<>();
    private final Map<String, Boolean> folderMissingNotified = new HashMap<>();

    public static synchronized VoiceScanner get(Context context) {
        if (instance == null) instance = new VoiceScanner(context.getApplicationContext());
        return instance;
    }

    private VoiceScanner(Context app) {
        this.app = app;
    }

    /**
     * WhatsApp ha pubblicato una notifica per pkg: guarda la cartella per i prossimi WATCH_MS.
     * voiceHints sono i vocali annunciati dalla notifica, anche nessuno.
     */
    public void onActivity(String pkg, List<Hint> voiceHints) {
        // La dimensione si legge subito: il permesso sull'URI della notifica dura quanto la notifica.
        for (Hint h : voiceHints) h.size = sizeOf(h.uri);
        exec.execute(() -> {
            if (Prefs.folder(app, pkg) == null) {
                if (!voiceHints.isEmpty() && !Boolean.TRUE.equals(folderMissingNotified.get(pkg))) {
                    folderMissingNotified.put(pkg, true);
                    Notifier.showProblem(app, app.getString(R.string.problem_folder_title),
                            app.getString(R.string.problem_folder_text, Packages.label(app, pkg)));
                }
                return;
            }
            hints.computeIfAbsent(pkg, k -> new ArrayList<>()).addAll(voiceHints);
            watch(pkg);
        });
    }

    /** Un controllo della cartella di ogni pacchetto configurato, per esempio all'avvio del processo. */
    public void sweepAll() {
        exec.execute(() -> {
            for (String pkg : Packages.ALL) {
                if (Prefs.folder(app, pkg) != null) watch(pkg);
            }
        });
    }

    private void watch(String pkg) {
        watchUntil.put(pkg, System.currentTimeMillis() + WATCH_MS);
        if (!Boolean.TRUE.equals(polling.get(pkg))) {
            polling.put(pkg, true);
            exec.execute(() -> poll(pkg));
        }
    }

    private long sizeOf(Uri uri) {
        if (uri == null) return -1;
        try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(uri, "r")) {
            long size = fd == null ? -1 : fd.getStatSize();
            Log.i(TAG, "uri della notifica leggibile: " + uri + " (" + size + " byte)");
            return size;
        } catch (Exception e) {
            Log.i(TAG, "uri della notifica non leggibile: " + uri + " (" + e.getClass().getSimpleName() + ")");
            return -1;
        }
    }

    /**
     * Ricerca manuale: mette in coda i vocali delle ultime 24 ore non ancora trascritti, al
     * massimo limit per cartella. Restituisce, tramite callback sul thread dello scanner, quanti
     * ne ha aggiunti.
     */
    public void scanRecent(int limit, java.util.function.IntConsumer done) {
        exec.execute(() -> {
            int added = 0;
            long since = System.currentTimeMillis() - 24 * 3600 * 1000L;
            for (String pkg : Packages.ALL) {
                Uri tree = Prefs.folder(app, pkg);
                if (tree == null) continue;
                try {
                    List<Doc> docs = list(tree, since);
                    docs.removeIf(d -> d.size <= 0 || Db.get(app).hasSource(d.uri.toString()));
                    docs.sort(Comparator.comparingLong(d -> d.modified));
                    if (docs.size() > limit) docs = docs.subList(docs.size() - limit, docs.size());
                    for (Doc d : docs) {
                        if (enqueue(pkg, d, takeHint(pkg, d)) > 0) added++;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ricerca manuale fallita per " + pkg, e);
                }
            }
            if (added > 0) {
                TranscriptionQueue.get(app).changed();
                TranscriptionQueue.get(app).kick();
            }
            done.accept(added);
        });
    }

    private void poll(String pkg) {
        Uri tree = Prefs.folder(app, pkg);
        long now = System.currentTimeMillis();
        Long until = watchUntil.get(pkg);
        boolean unstable = false;
        boolean added = false;
        if (tree != null) {
            try {
                long since = Math.max(Prefs.since(app, pkg), now - MAX_AGE_MS);
                List<Doc> ready = new ArrayList<>();
                for (Doc d : list(tree, since)) {
                    String key = d.uri.toString();
                    if (d.size <= 0 || Db.get(app).hasSource(key)) continue;
                    Long prev = lastSize.put(key, d.size);
                    if ((prev != null && prev == d.size) || now - d.modified > STABLE_AGE_MS) {
                        ready.add(d);
                    } else {
                        unstable = true;
                    }
                }
                ready.sort(Comparator.comparingLong(d -> d.modified));
                for (Doc d : ready) {
                    if (enqueue(pkg, d, takeHint(pkg, d)) > 0) added = true;
                    lastSize.remove(d.uri.toString());
                }
            } catch (SecurityException e) {
                Log.w(TAG, "permesso sulla cartella perso per " + pkg, e);
                Notifier.showProblem(app, app.getString(R.string.problem_folder_title),
                        app.getString(R.string.problem_folder_text, Packages.label(app, pkg)));
                until = 0L;
            } catch (Exception e) {
                Log.w(TAG, "controllo della cartella fallito per " + pkg, e);
            }
        }
        if (added) {
            TranscriptionQueue.get(app).changed();
            TranscriptionQueue.get(app).kick();
        }
        pruneHints(pkg, now);
        if (tree == null || until == null || (now > until && !unstable)) {
            polling.put(pkg, false);
            return;
        }
        exec.schedule(() -> poll(pkg), POLL_MS, TimeUnit.MILLISECONDS);
    }

    private long enqueue(String pkg, Doc d, Hint h) {
        long id = Db.get(app).insertPending(pkg, h == null ? null : h.chat, h == null ? null : h.sender,
                d.uri.toString(), d.name, null, d.modified);
        Log.i(TAG, "vocale in coda: " + d.name + " (" + d.size + " byte, modificato " + d.modified
                + ") mittente=" + (h == null ? null : h.sender) + " id=" + id);
        return id;
    }

    /** L'annuncio che corrisponde al file, tolto dall'elenco; null se nessuno corrisponde. */
    private Hint takeHint(String pkg, Doc d) {
        List<Hint> list = hints.get(pkg);
        if (list == null || list.isEmpty()) return null;
        Hint best = null;
        for (Hint h : list) {
            if (h.size > 0 && h.size == d.size) {
                best = h;
                break;
            }
        }
        if (best == null) {
            long bestDelta = MATCH_MS + 1;
            for (Hint h : list) {
                if (h.size > 0) continue;
                long delta = Math.abs(h.time - d.modified);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = h;
                }
            }
        }
        if (best != null) list.remove(best);
        return best;
    }

    private void pruneHints(String pkg, long now) {
        List<Hint> list = hints.get(pkg);
        if (list == null) return;
        for (Iterator<Hint> it = list.iterator(); it.hasNext(); ) {
            if (now - it.next().time > HINT_TTL_MS) it.remove();
        }
    }

    /** File .opus della cartella e delle sottocartelle più recenti, modificati da since in poi. */
    private List<Doc> list(Uri tree, long since) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        List<Doc> files = new ArrayList<>();
        List<Doc> dirs = new ArrayList<>();
        children(tree, rootId, files, dirs);
        dirs.removeIf(d -> d.name != null && d.name.equalsIgnoreCase("Sent"));
        dirs.sort(Collections.reverseOrder(Comparator.comparingLong(d -> d.modified)));
        for (int i = 0; i < Math.min(RECENT_DIRS, dirs.size()); i++) {
            children(tree, dirs.get(i).id, files, null);
        }
        files.removeIf(d -> d.modified < since || d.name == null || !d.name.toLowerCase().endsWith(".opus"));
        return files;
    }

    private void children(Uri tree, String parentId, List<Doc> files, List<Doc> dirs) {
        ContentResolver cr = app.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                String mime = c.getString(2);
                d.modified = c.isNull(3) ? 0 : c.getLong(3);
                d.size = c.isNull(4) ? 0 : c.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (dirs != null) dirs.add(d);
                } else {
                    d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id);
                    files.add(d);
                }
            }
        }
    }
}
