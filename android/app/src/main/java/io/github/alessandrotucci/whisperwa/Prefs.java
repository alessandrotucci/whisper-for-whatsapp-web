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

    public static String model(Context context) {
        return sp(context).getString("model", Models.DEFAULT_ID);
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
