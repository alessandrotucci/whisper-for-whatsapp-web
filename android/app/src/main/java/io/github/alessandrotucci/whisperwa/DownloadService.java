package io.github.alessandrotucci.whisperwa;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Scarica un modello in primo piano (tipo dataSync), con ripresa da dove si era fermato: il file
 * .part cresce con richieste Range e diventa definitivo solo se dimensione e SHA-256 coincidono
 * con quelli attesi. Un modello per volta.
 *
 * <p>Lo stato si legge da {@link #state()} e i cambiamenti arrivano ai listener sul thread
 * principale.
 */
public final class DownloadService extends Service {

    private static final String TAG = "whisperwa";
    private static final String EXTRA_MODEL = "model";
    private static final String ACTION_CANCEL = "cancel";

    /** Stato del download: modello, byte scaricati, errore dell'ultimo tentativo. */
    public static final class State {
        public volatile String modelId;
        public volatile long done;
        public volatile long total;
        public volatile boolean verifying;
        public volatile String error;
    }

    private static final State STATE = new State();
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled;
    private Thread thread;

    public static State state() {
        return STATE;
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) r.run();
        });
    }

    public static void start(Context context, String modelId) {
        context.startForegroundService(new Intent(context, DownloadService.class).putExtra(EXTRA_MODEL, modelId));
    }

    public static void cancel(Context context) {
        context.startService(new Intent(context, DownloadService.class).setAction(ACTION_CANCEL));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            if (thread == null) stopSelf();
            return START_NOT_STICKY;
        }
        Models.Model model = intent == null ? null : Models.byId(intent.getStringExtra(EXTRA_MODEL));
        startForeground(Notifier.ID_DOWNLOAD, progressNotification(model, 0, model == null ? 0 : model.size),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (model == null || thread != null) {
            if (thread == null) stopSelf();
            return START_NOT_STICKY;
        }
        cancelled = false;
        STATE.modelId = model.id;
        STATE.done = 0;
        STATE.total = model.size;
        STATE.verifying = false;
        STATE.error = null;
        notifyListeners();
        thread = new Thread(() -> run(model), "model-download");
        thread.start();
        return START_NOT_STICKY;
    }

    private void run(Models.Model model) {
        try {
            download(model);
            if (!cancelled) {
                TranscriptionQueue.get(this).kick();
            }
        } catch (Exception e) {
            Log.w(TAG, "download fallito: " + model.id, e);
            STATE.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (cancelled && STATE.error == null) STATE.error = getString(R.string.download_cancelled);
            STATE.modelId = null;
            STATE.verifying = false;
            notifyListeners();
            MAIN.post(() -> {
                thread = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }
    }

    private void download(Models.Model model) throws Exception {
        File part = model.partPath(this);
        File target = model.path(this);
        long have = part.isFile() ? part.length() : 0;
        if (have > model.size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            have = 0;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (have < model.size) {
            HttpURLConnection c = open(model.url(), have);
            int code = c.getResponseCode();
            boolean append = code == HttpURLConnection.HTTP_PARTIAL;
            if (!append) {
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                have = 0;
            }
            long lastUi = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) return;
                    out.write(buf, 0, n);
                    have += n;
                    STATE.done = have;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 500) {
                        lastUi = now;
                        notifyListeners();
                        nm.notify(Notifier.ID_DOWNLOAD, progressNotification(model, have, model.size));
                    }
                }
            } finally {
                c.disconnect();
            }
        }
        STATE.done = have;
        if (have != model.size) throw new IOException(getString(R.string.download_incomplete));
        STATE.verifying = true;
        notifyListeners();
        String sha = sha256(part);
        if (!sha.equalsIgnoreCase(model.sha256)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException(getString(R.string.download_bad_hash));
        }
        if (!part.renameTo(target)) throw new IOException("rename");
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        // Hugging Face risponde con un redirect verso la CDN: si seguono i redirect a mano per
        // ripetere l'intestazione Range su ogni salto.
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect senza Location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("troppi redirect");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Notification progressNotification(Models.Model model, long done, long total) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        String name = model == null ? "" : getString(model.labelRes);
        return new Notification.Builder(this, Notifier.CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.download_title, name))
                .setContentText(pct + "%")
                .setProgress(100, pct, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(Notifier.openList(this, -1))
                .build();
    }
}
