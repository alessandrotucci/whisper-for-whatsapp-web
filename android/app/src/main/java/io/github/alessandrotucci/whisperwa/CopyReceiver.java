package io.github.alessandrotucci.whisperwa;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Azione "Copia" delle notifiche: mette negli appunti il testo della trascrizione e la traduzione. */
public final class CopyReceiver extends BroadcastReceiver {

    public static final String EXTRA_NOTE = "note";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_NOTE, -1);
        Db.Note note = id < 0 ? null : Db.get(context).byId(id);
        if (note == null || note.shownText() == null) return;
        copy(context, note);
    }

    /** Copia il testo mostrato e, se c'è, la traduzione, separati da una riga vuota. */
    static void copy(Context context, Db.Note note) {
        ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        String text = note.shownText();
        if (note.translation != null) text = text + "\n\n" + note.translation;
        cm.setPrimaryClip(ClipData.newPlainText(note.title(), text));
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }
}
