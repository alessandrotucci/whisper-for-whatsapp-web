package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/**
 * Riproduce l'audio originale di un vocale della lista, uno alla volta: avviarne un altro ferma
 * quello in corso. A ogni cambiamento e ogni TICK_MS durante la riproduzione chiama onChange,
 * sul thread principale, perché la lista aggiorni pulsante e avanzamento.
 */
final class VoicePlayer {

    private static final String TAG = "whisperwa";
    private static final long TICK_MS = 500;

    private final Context context;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            onChange.run();
            main.postDelayed(this, TICK_MS);
        }
    };
    private MediaPlayer player;
    private long playingId = -1;
    private boolean prepared;

    VoicePlayer(Context context, Runnable onChange) {
        this.context = context;
        this.onChange = onChange;
    }

    /** L'audio di una voce: la copia locale dei file condivisi, oppure il file nella cartella di WhatsApp. */
    static Uri audioOf(Db.Note n) {
        if (n.localPath != null) return new File(n.localPath).isFile() ? Uri.fromFile(new File(n.localPath)) : null;
        return n.source.startsWith("share:") ? null : Uri.parse(n.source);
    }

    long playingId() {
        return playingId;
    }

    boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    /** Posizione corrente in millisecondi, 0 se non si sta riproducendo. */
    int position() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    /** Avvia, mette in pausa o riprende la voce n. */
    void toggle(Db.Note n) {
        if (player != null && playingId == n.id) {
            if (!prepared) return;
            if (player.isPlaying()) {
                player.pause();
                main.removeCallbacks(tick);
            } else {
                player.start();
                main.post(tick);
            }
            onChange.run();
            return;
        }
        stop();
        Uri uri = audioOf(n);
        if (uri == null) {
            missing();
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        playingId = n.id;
        prepared = false;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(context, uri);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                prepared = true;
                p.start();
                main.post(tick);
                onChange.run();
            });
            mp.setOnCompletionListener(p -> stop());
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "riproduzione fallita: " + what + "/" + extra);
                stop();
                missing();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "audio non leggibile: " + uri, e);
            stop();
            missing();
            return;
        }
        onChange.run();
    }

    void stop() {
        main.removeCallbacks(tick);
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
                // Un lettore già rilasciato o in errore non ha altro da chiudere.
            }
            player = null;
        }
        boolean was = playingId >= 0;
        playingId = -1;
        prepared = false;
        if (was) onChange.run();
    }

    private void missing() {
        Toast.makeText(context, R.string.audio_missing, Toast.LENGTH_SHORT).show();
    }
}
