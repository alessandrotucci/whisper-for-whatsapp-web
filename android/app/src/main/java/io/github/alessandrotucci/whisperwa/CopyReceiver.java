package io.github.alessandrotucci.whisperwa;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Azione "Copia" delle notifiche: mette il testo della trascrizione negli appunti. */
public final class CopyReceiver extends BroadcastReceiver {

    public static final String EXTRA_NOTE = "note";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_NOTE, -1);
        Db.Note note = id < 0 ? null : Db.get(context).byId(id);
        if (note == null || note.text == null) return;
        copy(context, note);
    }

    static void copy(Context context, Db.Note note) {
        ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(note.title(), note.text));
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }
}
