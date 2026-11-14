package io.github.alessandrotucci.whisperwa;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Azione «Riprova» della notifica di interruzione: rimette in coda il vocale fermato dal sistema. */
public final class RetryReceiver extends BroadcastReceiver {

    public static final String EXTRA_NOTE = "note";

    static PendingIntent pendingIntent(Context c, long noteId) {
        Intent i = new Intent(c, RetryReceiver.class).putExtra(EXTRA_NOTE, noteId);
        return PendingIntent.getBroadcast(c, (int) noteId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (BackgroundWork.retry(context, intent.getLongExtra(EXTRA_NOTE, -1))) {
            Toast.makeText(context, context.getResources().getQuantityString(R.plurals.queued, 1, 1),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
