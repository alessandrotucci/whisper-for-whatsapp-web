package io.github.alessandrotucci.whisperwa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ultimi messaggi scritti di ogni chat, letti dalle notifiche di WhatsApp, da passare all'IA come
 * contesto quando l'utente lo consente. Stanno solo in memoria: si perdono con il processo e non
 * si scrivono mai su disco.
 */
final class ChatContext {

    private static final int PER_CHAT = 12;
    private static final int MAX_CHATS = 50;
    private static final long MAX_AGE_MS = 2 * 60 * 60 * 1000;
    private static final int MAX_CHARS = 2000;

    private static final class Msg {
        final String sender;
        final String text;
        final long time;

        Msg(String sender, String text, long time) {
            this.sender = sender;
            this.text = text;
            this.time = time;
        }
    }

    private static final Map<String, Deque<Msg>> CHATS = new HashMap<>();

    private ChatContext() {
    }

    private static String key(String pkg, String chat) {
        return pkg + '\u0000' + chat;
    }

    /** Registra un messaggio scritto; i duplicati (stesso mittente, testo e ora) si ignorano. */
    static synchronized void add(String pkg, String chat, String sender, String text, long time) {
        if (chat == null || text == null || text.trim().isEmpty()) return;
        if (CHATS.size() >= MAX_CHATS && !CHATS.containsKey(key(pkg, chat))) CHATS.clear();
        Deque<Msg> q = CHATS.computeIfAbsent(key(pkg, chat), k -> new ArrayDeque<>());
        for (Msg m : q) {
            if (m.time == time && m.text.equals(text) && String.valueOf(m.sender).equals(String.valueOf(sender))) return;
        }
        q.addLast(new Msg(sender, text.trim(), time));
        while (q.size() > PER_CHAT) q.removeFirst();
    }

    /** I messaggi della chat precedenti a before, uno per riga "Mittente: testo", oppure null. */
    static synchronized String before(String pkg, String chat, long before) {
        if (chat == null) return null;
        Deque<Msg> q = CHATS.get(key(pkg, chat));
        if (q == null) return null;
        List<String> lines = new ArrayList<>();
        int chars = 0;
        Msg[] all = q.toArray(new Msg[0]);
        for (int i = all.length - 1; i >= 0; i--) {
            Msg m = all[i];
            if (m.time > before || before - m.time > MAX_AGE_MS) continue;
            String line = (m.sender != null ? m.sender + ": " : "") + m.text;
            if (chars + line.length() > MAX_CHARS) break;
            lines.add(0, line);
            chars += line.length();
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
