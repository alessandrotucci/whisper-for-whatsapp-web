package io.github.alessandrotucci.whisperwa;

import android.content.Context;

import java.nio.charset.StandardCharsets;

/**
 * Accesso a whisper.cpp tramite la libreria nativa libwhisperwa.so.
 *
 * <p>I backend CPU di ggml stanno in nativeLibraryDir come librerie separate, una per famiglia
 * di processori; {@link #init(Context)} le registra una volta per processo, prima di qualsiasi
 * contesto. Un contesto si usa da un solo thread alla volta.
 */
public final class WhisperLib {

    /** Ricevuto durante la trascrizione con la percentuale di avanzamento, dal thread che trascrive. */
    public interface ProgressListener {
        void onProgress(int percent);
    }

    /** Lingua riconosciuta e testo di una trascrizione. */
    public static final class Result {
        public final String language;
        public final String text;

        Result(String language, String text) {
            this.language = language;
            this.text = text;
        }
    }

    private static boolean loaded;

    private WhisperLib() {
    }

    public static synchronized void init(Context context) {
        if (loaded) return;
        System.loadLibrary("whisperwa");
        nativeLoadBackends(context.getApplicationInfo().nativeLibraryDir);
        loaded = true;
    }

    /** Carica il modello; restituisce 0 se il file non è un modello valido o manca la memoria. */
    public static long load(Context context, String modelPath) {
        init(context);
        return nativeInit(modelPath);
    }

    public static void free(long ctx) {
        nativeFree(ctx);
    }

    /** Chiede l'interruzione della trascrizione in corso, che allora restituisce null. */
    public static void cancel() {
        nativeCancel();
    }

    /**
     * Trascrive audio mono a 16 kHz. language vale "auto" per il riconoscimento automatico,
     * altrimenti un codice ISO 639-1. audioContext è la finestra dell'encoder in frame da 20 ms,
     * 0 per quella intera. Restituisce null se la trascrizione fallisce o è annullata.
     */
    public static Result transcribe(long ctx, float[] samples, String language, int threads,
                                    int audioContext, ProgressListener listener) {
        byte[] raw = nativeTranscribe(ctx, samples, language, threads, audioContext, listener);
        if (raw == null) return null;
        String all = new String(raw, StandardCharsets.UTF_8);
        int nl = all.indexOf('\n');
        return new Result(all.substring(0, nl), all.substring(nl + 1).trim());
    }

    public static String systemInfo(Context context) {
        init(context);
        return nativeSystemInfo();
    }

    private static native void nativeLoadBackends(String libDir);

    private static native long nativeInit(String modelPath);

    private static native void nativeFree(long ctx);

    private static native void nativeCancel();

    private static native byte[] nativeTranscribe(long ctx, float[] samples, String language,
                                                  int threads, int audioContext,
                                                  ProgressListener listener);

    private static native String nativeSystemInfo();
}
