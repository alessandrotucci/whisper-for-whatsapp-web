package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Impostazioni dell'app in SharedPreferences. */
public final class Prefs {

    private static final String FILE = "settings";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Il modello scelto. Senza una scelta esplicita vale il primo modello già installato, nell'ordine
     * di {@link Models#ALL}, così un modello scaricato resta in uso anche se cambia il predefinito;
     * se nessuno è installato vale {@link Models#DEFAULT_ID}.
     */
    public static String model(Context context) {
        String chosen = sp(context).getString("model", null);
        if (chosen != null) return chosen;
        for (Models.Model m : Models.ALL) {
            if (m.installed(context)) return m.id;
        }
        return Models.DEFAULT_ID;
    }

    public static void setModel(Context context, String id) {
        sp(context).edit().putString("model", id).apply();
    }

    /** "auto" oppure un codice ISO 639-1. */
    public static String language(Context context) {
        return sp(context).getString("language", "auto");
    }

    public static void setLanguage(Context context, String code) {
        sp(context).edit().putString("language", code).apply();
    }

    public static int threads(Context context) {
        return sp(context).getInt("threads", defaultThreads());
    }

    /**
     * Tutti i core, fino a 8: anche sui processori big.LITTLE i core lenti accorciano
     * l'encoder più di quanto lo rallentino.
     */
    public static int defaultThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    public static void setThreads(Context context, int n) {
        sp(context).edit().putInt("threads", n).apply();
    }

    /**
     * Thread con l'app non visibile. Alcuni sistemi (ColorOS) uccidono anche un servizio in
     * primo piano che tiene occupati tutti i core mentre l'utente fa altro.
     */
    public static int backgroundThreads(Context context) {
        return sp(context).getInt("threads_bg", Math.min(2, defaultThreads()));
    }

    public static void setBackgroundThreads(Context context, int n) {
        sp(context).edit().putInt("threads_bg", n).apply();
    }

    /** Ultimo vocale fermato dal sistema tre volte: id della riga, mittente e ora. */
    public static void setLastKill(Context context, long noteId, String who, long when) {
        sp(context).edit()
                .putLong("kill.note", noteId)
                .putString("kill.who", who)
                .putLong("kill.time", when)
                .apply();
    }

    /** Id del vocale dell'ultima interruzione, oppure -1. */
    public static long lastKillNote(Context context) {
        return sp(context).getLong("kill.note", -1);
    }

    public static String lastKillWho(Context context) {
        return sp(context).getString("kill.who", "");
    }

    /** Ora dell'ultima interruzione in millisecondi, oppure 0. */
    public static long lastKillTime(Context context) {
        return sp(context).getLong("kill.time", 0);
    }

    /** Vero dopo che l'archivio è stato letto una volta in cerca di interruzioni già avvenute. */
    public static boolean killSeeded(Context context) {
        return sp(context).getBoolean("kill.seeded", false);
    }

    public static void setKillSeeded(Context context) {
        sp(context).edit().putBoolean("kill.seeded", true).apply();
    }

    /** Cartella dei vocali scelta con lo Storage Access Framework per un pacchetto di WhatsApp. */
    public static Uri folder(Context context, String pkg) {
        String s = sp(context).getString("folder." + pkg, null);
        return s == null ? null : Uri.parse(s);
    }

    /**
     * Salva la cartella e da quel momento conta come "nuovo" solo ciò che è modificato dopo:
     * i vocali già presenti non entrano in coda da soli.
     */
    public static void setFolder(Context context, String pkg, Uri uri) {
        sp(context).edit()
                .putString("folder." + pkg, uri == null ? null : uri.toString())
                .putLong("since." + pkg, System.currentTimeMillis())
                .apply();
    }

    public static long since(Context context, String pkg) {
        return sp(context).getLong("since." + pkg, System.currentTimeMillis());
    }
}
