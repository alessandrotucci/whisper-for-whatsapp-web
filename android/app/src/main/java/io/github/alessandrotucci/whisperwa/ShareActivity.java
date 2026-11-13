package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Riceve file audio condivisi (da WhatsApp: tieni premuto il vocale, Condividi) e li mette in
 * coda. Il permesso su un URI condiviso dura quanto l'attività che lo riceve, quindi il file si
 * copia subito in filesDir/shared e la copia si cancella a trascrizione finita.
 *
 * <p>Gestisce anche la scelta di un file dalle impostazioni, con l'azione OPEN.
 */
public final class ShareActivity extends Activity {

    static final String ACTION_OPEN = "io.github.alessandrotucci.whisperwa.OPEN";
    private static final String TAG = "whisperwa";

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (ACTION_OPEN.equals(intent.getAction()) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        int added = 0;
        for (Uri u : uris) {
            if (enqueue(u)) added++;
        }
        if (added > 0) {
            TranscriptionQueue.get(this).changed();
            TranscriptionQueue.get(this).kick();
            Toast.makeText(this, getResources().getQuantityString(R.plurals.queued, added, added), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private boolean enqueue(Uri uri) {
        ContentResolver cr = getContentResolver();
        String name = displayName(uri);
        String id = UUID.randomUUID().toString();
        File dir = new File(getFilesDir(), "shared");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File copy = new File(dir, id);
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
            if (in == null) return false;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "copia del file condiviso fallita", e);
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            return false;
        }
        return Db.get(this).insertPending(null, null, null, "share:" + id, name, copy.getAbsolutePath(),
                System.currentTimeMillis()) > 0;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
            // il nome è facoltativo
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : getString(R.string.shared_audio);
    }
}
