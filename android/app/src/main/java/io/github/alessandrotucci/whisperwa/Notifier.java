package io.github.alessandrotucci.whisperwa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * Notifiche dell'app: una per ogni trascrizione, raggruppate sotto un riepilogo, più quelle di
 * avanzamento e di configurazione.
 *
 * <p>Restano visibili al massimo KEEP trascrizioni: le più vecchie si ritirano, perché Android
 * limita il numero di notifiche per app e la lista completa è nell'app.
 */
public final class Notifier {

    public static final String CH_TRANSCRIPTS = "transcripts";
    public static final String CH_PROGRESS = "progress";
    public static final String CH_PROBLEMS = "problems";

    static final int ID_SUMMARY = 1;
    static final int ID_PROGRESS = 2;
    static final int ID_DOWNLOAD = 3;
    static final int ID_PROBLEM = 4;
    private static final int ID_NOTE_BASE = 1000;
    private static final int KEEP = 8;
    private static final String GROUP = "transcripts";
    private static final String GLOBE = new String(Character.toChars(0x1F310));

    private Notifier() {
    }

    public static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel t = new NotificationChannel(CH_TRANSCRIPTS,
                context.getString(R.string.channel_transcripts), NotificationManager.IMPORTANCE_DEFAULT);
        t.setSound(null, null);
        nm.createNotificationChannel(t);
        nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS,
                context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_PROBLEMS,
                context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT));
    }

    static PendingIntent openList(Context context, long noteId) {
        Intent i = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_NOTE, noteId);
        return PendingIntent.getActivity(context, (int) noteId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void showTranscript(Context context, Db.Note note) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        Intent copy = new Intent(context, CopyReceiver.class).putExtra(CopyReceiver.EXTRA_NOTE, note.id);
        PendingIntent copyPi = PendingIntent.getBroadcast(context, (int) note.id, copy,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String shown = note.shownText();
        String text = shown == null || shown.isEmpty() ? context.getString(R.string.empty_transcript) : shown;
        String big = note.translation != null ? text + "\n\n" + GLOBE + " " + note.translation : text;
        Notification n = new Notification.Builder(context, CH_TRANSCRIPTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(note.title())
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setWhen(note.received)
                .setShowWhen(true)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(openList(context, note.id))
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.action_copy), copyPi).build())
                .build();
        nm.notify(ID_NOTE_BASE + (int) note.id, n);
        refreshSummary(context);
    }

    /** Riepilogo del gruppo e ritiro delle notifiche oltre le ultime KEEP. */
    static void refreshSummary(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        List<Db.Note> done = Db.get(context).lastDone(KEEP + 20);
        for (int i = KEEP; i < done.size(); i++) nm.cancel(ID_NOTE_BASE + (int) done.get(i).id);
        Notification.InboxStyle inbox = new Notification.InboxStyle();
        int shown = Math.min(KEEP, done.size());
        for (int i = 0; i < Math.min(5, shown); i++) {
            Db.Note d = done.get(i);
            String t = d.shownText();
            inbox.addLine(d.title() + ": " + (t == null ? "" : t));
        }
        Notification summary = new Notification.Builder(context, CH_TRANSCRIPTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(context.getString(R.string.app_name))
                .setStyle(inbox)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(openList(context, -1))
                .build();
        nm.notify(ID_SUMMARY, summary);
    }

    public static void cancelTranscript(Context context, long noteId) {
        context.getSystemService(NotificationManager.class).cancel(ID_NOTE_BASE + (int) noteId);
    }

    public static void cancelAll(Context context) {
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    /** Notifica silenziosa e persistente della trascrizione in corso. */
    public static void showProgress(Context context, Db.Note note, int percent, int queued) {
        String title = context.getString(R.string.progress_title, note.title());
        String text = queued > 1
                ? context.getString(R.string.progress_queue, percent, queued - 1)
                : context.getString(R.string.progress_percent, percent);
        context.getSystemService(NotificationManager.class)
                .notify(ID_PROGRESS, progressNotification(context, title, text, percent));
    }

    /** Notifica di avanzamento durante la revisione con l'IA. */
    public static void showReviewProgress(Context context, Db.Note note, String provider) {
        String title = context.getString(R.string.progress_review, note.title());
        String text = context.getString(R.string.progress_review_with, provider);
        context.getSystemService(NotificationManager.class)
                .notify(ID_PROGRESS, progressNotification(context, title, text, 0));
    }

    static Notification progressNotification(Context context, String title, String text, int percent) {
        return new Notification.Builder(context, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, percent <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openList(context, -1))
                .build();
    }

    public static void clearProgress(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ID_PROGRESS);
    }

    /** Un problema che richiede l'utente (modello mancante, cartella non scelta, errore). */
    public static void showProblem(Context context, String title, String text) {
        Intent i = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        context.getSystemService(NotificationManager.class).notify(ID_PROBLEM, n);
    }
}
