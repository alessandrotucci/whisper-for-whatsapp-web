package io.github.alessandrotucci.whisperwa;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
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
 * <p>Una notifica di vocale ({@link #onVoiceHint}) non porta il file: avvia un controllo della
 * cartella ogni POLL_MS per WATCH_MS, perché WhatsApp può scrivere il file prima o dopo la
 * notifica. Un file entra in coda quando ha dimensione non nulla e invariata tra due controlli,
 * non è già nell'archivio ed è modificato dopo la scelta della cartella. Ai file nuovi, in ordine
 * di modifica, si assegnano mittente e chat dei suggerimenti in attesa, in ordine di arrivo.
 *
 * <p>La cartella dei vocali contiene una sottocartella per settimana (per esempio 202646): si
 * guardano i file .opus nella cartella scelta e nelle RECENT_DIRS sottocartelle modificate per
 * ultime, saltando quelle chiamate Sent.
 */
public final class VoiceScanner {

    private static final String TAG = "whisperwa";
    private static final long POLL_MS = 3000;
    private static final long WATCH_MS = 120_000;
    private static final long HINT_TTL_MS = 10 * 60 * 1000;
    private static final int RECENT_DIRS = 2;

    /** Mittente e chat di un vocale annunciato da una notifica. */
    public static final class Hint {
        final String chat;
        final String sender;
        final long time;

        public Hint(String chat, String sender, long time) {
            this.chat = chat;
            this.sender = sender;
            this.time = time;
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
    private final Map<String, Deque<Hint>> hints = new HashMap<>();
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

    /** Una notifica annuncia un vocale per il pacchetto pkg. */
    public void onVoiceHint(String pkg, Hint hint) {
        exec.execute(() -> {
            if (Prefs.folder(app, pkg) == null) {
                if (!Boolean.TRUE.equals(folderMissingNotified.get(pkg))) {
                    folderMissingNotified.put(pkg, true);
                    Notifier.showProblem(app, app.getString(R.string.problem_folder_title),
                            app.getString(R.string.problem_folder_text, Packages.label(app, pkg)));
                }
                return;
            }
            Deque<Hint> q = hints.computeIfAbsent(pkg, k -> new ArrayDeque<>());
            q.addLast(hint);
            watchUntil.put(pkg, System.currentTimeMillis() + WATCH_MS);
            if (!Boolean.TRUE.equals(polling.get(pkg))) {
                polling.put(pkg, true);
                exec.execute(() -> poll(pkg));
            }
        });
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
                        if (Db.get(app).insertPending(pkg, null, null, d.uri.toString(), d.name, null, d.modified) > 0) {
                            added++;
                        }
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
        if (tree == null || until == null || now > until) {
            polling.put(pkg, false);
            pruneHints(pkg, now);
            return;
        }
        try {
            long since = Prefs.since(app, pkg);
            Deque<Hint> q = hints.get(pkg);
            if (q != null && !q.isEmpty()) since = Math.max(since, q.peekFirst().time - HINT_TTL_MS);
            List<Doc> docs = list(tree, since);
            List<Doc> ready = new ArrayList<>();
            for (Doc d : docs) {
                String key = d.uri.toString();
                if (d.size <= 0 || Db.get(app).hasSource(key)) continue;
                Long prev = lastSize.put(key, d.size);
                if (prev != null && prev == d.size) ready.add(d);
            }
            ready.sort(Comparator.comparingLong(d -> d.modified));
            boolean added = false;
            for (Doc d : ready) {
                Hint h = q == null ? null : q.pollFirst();
                long id = Db.get(app).insertPending(pkg, h == null ? null : h.chat, h == null ? null : h.sender,
                        d.uri.toString(), d.name, null, d.modified);
                Log.i(TAG, "vocale in coda: " + d.name + " (" + d.size + " byte, modificato " + d.modified
                        + ") mittente=" + (h == null ? null : h.sender) + " id=" + id);
                lastSize.remove(d.uri.toString());
                if (id > 0) added = true;
            }
            if (added) {
                TranscriptionQueue.get(app).changed();
                TranscriptionQueue.get(app).kick();
            }
            // Tutti i vocali annunciati hanno un file: il controllo si ferma prima della scadenza.
            if (added && (q == null || q.isEmpty())) watchUntil.put(pkg, now);
        } catch (SecurityException e) {
            Log.w(TAG, "permesso sulla cartella perso per " + pkg, e);
            Notifier.showProblem(app, app.getString(R.string.problem_folder_title),
                    app.getString(R.string.problem_folder_text, Packages.label(app, pkg)));
            watchUntil.put(pkg, now);
        } catch (Exception e) {
            Log.w(TAG, "controllo della cartella fallito per " + pkg, e);
        }
        exec.schedule(() -> poll(pkg), POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void pruneHints(String pkg, long now) {
        Deque<Hint> q = hints.get(pkg);
        if (q == null) return;
        for (Iterator<Hint> it = q.iterator(); it.hasNext(); ) {
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
