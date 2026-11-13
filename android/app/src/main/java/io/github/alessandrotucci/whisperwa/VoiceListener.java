package io.github.alessandrotucci.whisperwa;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Riceve le notifiche di WhatsApp e WhatsApp Business e segnala allo scanner i vocali in arrivo.
 *
 * <p>Un vocale si riconosce dal simbolo del microfono (U+1F3A4) nel testo del messaggio, che
 * non dipende dalla lingua dell'interfaccia di WhatsApp. Le notifiche di
 * WhatsApp sono in stile MessagingStyle e vengono ripubblicate con tutta la conversazione a ogni
 * messaggio nuovo: ogni messaggio si considera una volta sola, per chat, mittente e ora.
 */
@SuppressWarnings("deprecation")
public final class VoiceListener extends NotificationListenerService {

    private static final String MIC = new String(Character.toChars(0x1F3A4));
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
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!Packages.isWhatsApp(pkg)) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = n.extras;
        if (extras == null) return;

        CharSequence conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        long now = System.currentTimeMillis();

        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            for (Parcelable p : messages) {
                if (!(p instanceof Bundle)) continue;
                Bundle m = (Bundle) p;
                CharSequence text = m.getCharSequence("text");
                if (text == null || !text.toString().contains(MIC)) continue;
                long time = m.getLong("time", sbn.getPostTime());
                CharSequence sender = m.getCharSequence("sender");
                Person person = m.getParcelable("sender_person");
                if (person != null && person.getName() != null) sender = person.getName();
                String chat = str(conversation != null ? conversation : title);
                String who = sender != null ? sender.toString() : chat;
                hint(pkg, chat, who, time, now);
            }
            return;
        }

        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && text.toString().contains(MIC)) {
            hint(pkg, str(title), str(title), sbn.getPostTime(), now);
        }
    }

    private void hint(String pkg, String chat, String sender, long time, long now) {
        if (now - time > MAX_AGE_MS) return;
        String key = pkg + '\u0000' + chat + '\u0000' + sender + '\u0000' + time;
        if (!seen.add(key)) return;
        Log.i("whisperwa", "vocale annunciato: " + pkg + " chat=" + chat + " mittente=" + sender + " ora=" + time);
        if (seen.size() > SEEN_MAX) {
            Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        VoiceScanner.get(this).onVoiceHint(pkg, new VoiceScanner.Hint(chat, sender, time));
    }

    private static String str(CharSequence s) {
        return s == null ? null : s.toString();
    }
}
