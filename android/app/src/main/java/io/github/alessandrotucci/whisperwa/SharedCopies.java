package io.github.alessandrotucci.whisperwa;

import android.content.Context;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Copie in filesDir/shared dei file audio condivisi con l'app. Restano finché la voce è nella lista,
 * per l'ascolto, e al massimo KEEP_MS dall'arrivo; le copie che nessuna voce usa più si cancellano.
 */
final class SharedCopies {

    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;

    private SharedCopies() {
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "shared");
    }

    /** Cancella le copie scadute e quelle senza voce; le righe in attesa di trascrizione restano. */
    static void prune(Context context) {
        Db db = Db.get(context);
        long now = System.currentTimeMillis();
        Set<String> keep = new HashSet<>();
        for (Db.Note n : db.withLocalCopy()) {
            boolean waiting = n.status == Db.PENDING || n.status == Db.RUNNING;
            if (!waiting && now - n.received > KEEP_MS) {
                //noinspection ResultOfMethodCallIgnored
                new File(n.localPath).delete();
                db.clearLocalPath(n.id);
            } else {
                keep.add(new File(n.localPath).getAbsolutePath());
            }
        }
        File[] files = dir(context).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!keep.contains(f.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static void deleteAll(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
