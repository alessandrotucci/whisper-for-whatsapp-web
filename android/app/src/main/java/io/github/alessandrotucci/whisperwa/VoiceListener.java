package io.github.alessandrotucci.whisperwa;

import android.app.Notification;
import android.app.Person;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Riceve le notifiche di WhatsApp e WhatsApp Business e le passa allo scanner: ogni notifica
 * avvia un controllo della cartella dei vocali, e i vocali annunciati ne danno mittente e chat.
 *
 * <p>Un vocale si riconosce dal simbolo del microfono (U+1F3A4) nel testo del messaggio, che
 * non dipende dalla lingua dell'interfaccia di WhatsApp. Le notifiche di WhatsApp sono in stile
 * MessagingStyle e vengono ripubblicate con tutta la conversazione a ogni messaggio nuovo: ogni
 * messaggio si considera una volta sola, per chat, mittente e ora. Il Bundle di un messaggio
 * vocale porta anche l'URI del contenuto ("uri"), usato per abbinare il file per dimensione.
 */
@SuppressWarnings("deprecation")
public final class VoiceListener extends NotificationListenerService {

    private static final String TAG = "whisperwa";
    private static final String MIC = new String(Character.toChars(0x1F3A4));
    private static final String REPLY_ARROW = new String(Character.toChars(0x2937));
    private static final int SEEN_MAX = 500;
    private static final long MAX_AGE_MS = 10 * 60 * 1000;

    private static volatile boolean connected;

    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        connected = true;
        VoiceScanner.get(this).sweepAll();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!Packages.isWhatsApp(pkg)) return;
        List<VoiceScanner.Hint> found = new ArrayList<>();
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) == 0 && extras != null) {
            collect(sbn, extras, found);
        }
        VoiceScanner.get(this).onActivity(pkg, found);
    }

    private void collect(StatusBarNotification sbn, Bundle extras, List<VoiceScanner.Hint> out) {
        String pkg = sbn.getPackageName();
        CharSequence conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        long now = System.currentTimeMillis();

        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            for (Parcelable p : messages) {
                if (!(p instanceof Bundle)) continue;
                Bundle m = (Bundle) p;
                CharSequence text = m.getCharSequence("text");
                if (text == null) continue;
                long time = m.getLong("time", sbn.getPostTime());
                CharSequence sender = m.getCharSequence("sender");
                Person person = m.getParcelable("sender_person");
                if (person != null && person.getName() != null) sender = person.getName();
                // Le notifiche di risposta hanno come mittente un avviso che inizia con la freccia
                // U+2937 («Hai ricevuto una risposta»): non è un nome, vale la chat.
                if (sender != null && sender.toString().startsWith(REPLY_ARROW)) sender = null;
                String chat = str(conversation != null ? conversation : title);
                String who = sender != null ? sender.toString() : chat;
                if (!text.toString().contains(MIC)) {
                    ChatContext.add(pkg, chat, who, text.toString(), time);
                    continue;
                }
                Uri uri = m.getParcelable("uri");
                hint(pkg, chat, who, time, uri, now, out);
            }
            return;
        }

        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && text.toString().contains(MIC)) {
            hint(pkg, str(title), str(title), sbn.getPostTime(), null, now, out);
        }
    }

    private void hint(String pkg, String chat, String sender, long time, Uri uri, long now,
                      List<VoiceScanner.Hint> out) {
        if (now - time > MAX_AGE_MS) return;
        String key = pkg + '\u0000' + chat + '\u0000' + sender + '\u0000' + time;
        if (!seen.add(key)) return;
        Log.i(TAG, "vocale annunciato: " + pkg + " chat=" + chat + " mittente=" + sender + " ora=" + time
                + " uri=" + uri);
        if (seen.size() > SEEN_MAX) {
            Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        out.add(new VoiceScanner.Hint(chat, sender, time, uri));
    }

    private static String str(CharSequence s) {
        return s == null ? null : s.toString();
    }
}
