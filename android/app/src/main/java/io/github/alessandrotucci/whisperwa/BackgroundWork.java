package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Lavoro in background: le due notifiche che segnalano un sistema che ferma l'app, le pagine di
 * sistema che ne controllano i permessi e l'ultima interruzione registrata.
 *
 * <p>La notifica del servizio rifiutato parte prima del vocale, quando Android non lascia avviare
 * {@link TranscriptionService}. Quella dell'interruzione parte quando un vocale è stato iniziato
 * tre volte senza finire, e nomina il vocale: per lo stesso vocale si mostra una volta sola.
 * Entrambe aprono la sezione «Lavoro in background» delle impostazioni.
 *
 * <p>Su ColorOS (OPPO, OnePlus, realme) e sui telefoni Xiaomi le impostazioni del produttore
 * contano quanto quelle di Android, ma l'app non può leggerne lo stato: la sezione dice cosa
 * scegliere e apre la pagina giusta.
 */
final class BackgroundWork {

    private static final String TAG = "whisperwa";

    /** Identificativi delle notifiche, distinti da quelli di {@link Notifier}. */
    static final int ID_REFUSED = 20;
    static final int ID_KILLED = 21;

    /** Un'interruzione resta segnalata nella schermata principale per questo tempo. */
    static final long RECENT_MS = 7L * 24 * 60 * 60 * 1000;

    /** Sistema del produttore con impostazioni proprie sul lavoro in background. */
    enum Oem { COLOROS, XIAOMI, OTHER }

    private BackgroundWork() {
    }

    static Oem oem() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")) return Oem.COLOROS;
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return Oem.XIAOMI;
        return Oem.OTHER;
    }

    /** Notifica (a): il sistema non lascia partire il servizio in primo piano. */
    static void notifyServiceRefused(Context c) {
        PendingIntent open = openSection(c);
        String text = c.getString(R.string.problem_refused_text);
        Notification n = new Notification.Builder(c, Notifier.CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(c.getString(R.string.problem_refused_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.action_fix), open).build())
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_REFUSED, n);
    }

    /**
     * Notifica (b): il vocale è stato fermato tre volte. Registra l'interruzione e notifica, salvo
     * che l'ultima interruzione registrata sia dello stesso vocale.
     */
    static void notifyKilled(Context c, Db.Note note) {
        boolean repeated = Prefs.lastKillNote(c) == note.id;
        long audioMs = note.audioMs > 0 ? note.audioMs : durationMs(c, note);
        Prefs.setLastKill(c, note.id, who(note), System.currentTimeMillis());
        if (repeated) return;
        String text = audioMs > 0
                ? c.getString(R.string.problem_killed_text, who(note), DateUtils.formatElapsedTime(audioMs / 1000))
                : c.getString(R.string.problem_killed_text_short, who(note));
        PendingIntent open = openSection(c);
        Notification n = new Notification.Builder(c, Notifier.CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(c.getString(R.string.problem_killed_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.action_fix), open).build())
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.bg_retry),
                        RetryReceiver.pendingIntent(c, note.id)).build())
                .build();
        c.getSystemService(NotificationManager.class).notify(ID_KILLED, n);
    }

    /** Mittente del vocale, oppure la chat o il nome del file se non si conosce. */
    static String who(Db.Note note) {
        if (note.sender != null) return note.sender;
        if (note.chat != null) return note.chat;
        return note.name;
    }

    /**
     * Rimette in coda un vocale fermato dal sistema, se è ancora in errore, e ritira la notifica
     * dell'interruzione. Restituisce true se il vocale è tornato in coda.
     */
    static boolean retry(Context c, long noteId) {
        c.getSystemService(NotificationManager.class).cancel(ID_KILLED);
        Db db = Db.get(c);
        Db.Note note = noteId < 0 ? null : db.byId(noteId);
        if (note == null || note.status != Db.ERROR) return false;
        db.requeue(noteId);
        TranscriptionQueue.get(c).kick();
        TranscriptionQueue.get(c).changed();
        return true;
    }

    /** Il vocale dell'ultima interruzione, se esiste ancora ed è in errore. */
    static Db.Note retryableKill(Context c) {
        long id = Prefs.lastKillNote(c);
        Db.Note note = id < 0 ? null : Db.get(c).byId(id);
        return note != null && note.status == Db.ERROR ? note : null;
    }

    /** Ora dell'ultima interruzione se è degli ultimi RECENT_MS, altrimenti 0. */
    static long recentKill(Context c) {
        long t = Prefs.lastKillTime(c);
        return t > 0 && System.currentTimeMillis() - t < RECENT_MS ? t : 0;
    }

    /** «oggi alle 18:40», «ieri alle 9:05», «il 12 nov alle 18:40». */
    static String when(Context c, long t) {
        String time = DateFormat.getTimeFormat(c).format(new Date(t));
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        if (t >= today) return c.getString(R.string.when_today, time);
        day.add(Calendar.DAY_OF_YEAR, -1);
        if (t >= day.getTimeInMillis()) return c.getString(R.string.when_yesterday, time);
        String date = DateUtils.formatDateTime(c, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        return c.getString(R.string.when_date, date, time);
    }

    /** Apre la sezione delle impostazioni, con la lista sotto per il tasto Indietro. */
    private static PendingIntent openSection(Context c) {
        return TaskStackBuilder.create(c)
                .addNextIntentWithParentStack(SettingsActivity.backgroundSection(c))
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Apre la pagina del produttore per l'attività in background (autostart false) o per l'avvio
     * automatico (autostart true); se il telefono non la offre, apre Info app.
     */
    static void openOemPage(Activity a, boolean autostart) {
        for (Intent i : oemIntents(a, autostart)) {
            try {
                a.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.i(TAG, "pagina del produttore non disponibile: " + i.getComponent());
            }
        }
        Ui.openAppDetails(a);
    }

    private static Intent[] oemIntents(Context c, boolean autostart) {
        if (oem() == Oem.XIAOMI && autostart) {
            return new Intent[]{
                    component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            };
        }
        return new Intent[0];
    }

    private static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Durata del vocale letta dal contenitore audio, senza decodificarlo; 0 se non si legge. */
    private static long durationMs(Context c, Db.Note note) {
        MediaExtractor ex = new MediaExtractor();
        try {
            Uri uri = note.localPath != null ? Uri.fromFile(new File(note.localPath)) : Uri.parse(note.source);
            ex.setDataSource(c, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.containsKey(MediaFormat.KEY_DURATION)) return f.getLong(MediaFormat.KEY_DURATION) / 1000;
            }
        } catch (Exception e) {
            Log.i(TAG, "durata non disponibile: " + note.name);
        } finally {
            ex.release();
        }
        return 0;
    }
}
