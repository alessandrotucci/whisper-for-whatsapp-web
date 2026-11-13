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
     * Numero di core ad alte prestazioni, almeno 2: i core la cui frequenza massima supera quella
     * del gruppo più lento. ggml divide il lavoro in parti uguali tra i thread, quindi un thread
     * su un core lento rallenta tutti gli altri.
     */
    public static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        long[] max = new long[cores];
        long min = Long.MAX_VALUE;
        try {
            for (int i = 0; i < cores; i++) {
                java.io.File f = new java.io.File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                    max[i] = Long.parseLong(r.readLine().trim());
                }
                min = Math.min(min, max[i]);
            }
        } catch (Exception e) {
            return Math.max(2, Math.min(4, cores - 4));
        }
        int fast = 0;
        for (long m : max) {
            if (m > min) fast++;
        }
        if (fast == 0) fast = Math.min(4, cores);
        return Math.max(2, fast);
    }

    public static void setThreads(Context context, int n) {
        sp(context).edit().putInt("threads", n).apply();
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
