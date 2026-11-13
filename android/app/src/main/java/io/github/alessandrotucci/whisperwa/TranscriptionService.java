package io.github.alessandrotucci.whisperwa;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Servizio in primo piano che tiene vivo il processo mentre la coda lavora: senza, i sistemi
 * con gestione aggressiva dei processi in background (per esempio ColorOS) uccidono l'app a metà
 * trascrizione. Non fa lavoro proprio: la coda resta in {@link TranscriptionQueue}.
 *
 * <p>Tipo mediaProcessing da Android 15, dataSync prima. Dal background l'avvio è permesso solo
 * se l'app è esclusa dall'ottimizzazione della batteria; altrimenti {@link #start} restituisce
 * false e la coda lavora senza protezione.
 *
 * <p>La notifica del servizio è quella di avanzamento (Notifier.ID_PROGRESS), che la coda
 * aggiorna. Il servizio si ferma quando la coda non lavora più: lo chiede la coda con
 * {@link #stop}, oppure lo verifica il servizio stesso subito dopo startForeground, per il caso
 * in cui la coda finisca prima che il servizio parta.
 */
public final class TranscriptionService extends Service {

    private static final String TAG = "whisperwa";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static TranscriptionService running;

    /** Avvia il servizio; restituisce false se il sistema non lo permette in questo momento. */
    static boolean start(Context context) {
        try {
            context.startForegroundService(new Intent(context, TranscriptionService.class));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "servizio in primo piano non avviato: " + e);
            return false;
        }
    }

    /** Ferma il servizio se è attivo; se non è ancora partito si fermerà da solo all'avvio. */
    static void stop() {
        MAIN.post(() -> {
            if (running != null && !TranscriptionQueue.get(running).isDraining()) running.finish();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = Notifier.progressNotification(this, getString(R.string.progress_starting), "", 0);
        int type = Build.VERSION.SDK_INT >= 35
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        try {
            startForeground(Notifier.ID_PROGRESS, n, type);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground rifiutato: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = this;
        Log.i(TAG, "servizio in primo piano avviato");
        if (!TranscriptionQueue.get(this).isDraining()) finish();
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "tempo massimo del servizio in primo piano esaurito");
        finish();
    }

    private void finish() {
        if (running == this) running = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Log.i(TAG, "servizio in primo piano fermato");
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
