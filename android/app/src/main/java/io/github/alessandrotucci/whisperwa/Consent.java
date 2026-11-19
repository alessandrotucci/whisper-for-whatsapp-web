package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.lang.ref.WeakReference;

/**
 * Informative con consenso esplicito, richieste dalla policy sui dati utente di Google Play.
 *
 * <p>La prima riguarda la lettura delle notifiche di WhatsApp e precede la pagina di sistema
 * dell'accesso alle notifiche; senza consenso VoiceListener ignora le notifiche anche se
 * l'accesso è attivo. La seconda riguarda l'invio dei testi al fornitore di IA e precede il
 * collegamento di un account; senza consenso nessun testo va all'IA, anche con account collegati.
 * Il consenso si dà solo con «Accetto»: chiudere la finestra o tornare indietro non conta. Ogni
 * finestra si propone da sola una volta, a chi ha già l'accesso o gli account senza averla vista;
 * poi solo dai pulsanti e dalla voce Privacy del menu.
 */
final class Consent {

    static final String PRIVACY_URL =
            "https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/android/PRIVACY.md";

    private static final String FILE = "consent";
    private static final String LISTENER = "listener";
    private static final String AI = "ai";

    /** La finestra di consenso aperta: una sola alla volta, anche se l'attività riprende due volte. */
    private static WeakReference<AlertDialog> showing;

    private Consent() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean listener(Context context) {
        return sp(context).getBoolean(LISTENER, false);
    }

    static boolean ai(Context context) {
        return sp(context).getBoolean(AI, false);
    }

    /** Esegue next se il consenso alle notifiche c'è già, altrimenti dopo «Accetto». */
    static void askListener(Activity a, Runnable next) {
        ask(a, LISTENER, R.string.consent_listener_title, R.string.consent_listener_text, R.string.consent_not_now, next);
    }

    /** Esegue next se il consenso all'invio dei testi all'IA c'è già, altrimenti dopo «Accetto». */
    static void askAi(Activity a, Runnable next) {
        ask(a, AI, R.string.consent_ai_title, R.string.consent_ai_text, android.R.string.cancel, next);
    }

    /**
     * Propone una volta sola le informative che mancano a chi usa già la funzione: l'accesso alle
     * notifiche attivo, o un account di IA collegato. Si chiama all'apertura della lista.
     */
    static void promptPending(Activity a, Runnable after) {
        AlertDialog open = showing != null ? showing.get() : null;
        if (open != null && open.isShowing()) return;
        SharedPreferences sp = sp(a);
        Runnable again = () -> {
            after.run();
            promptPending(a, after);
        };
        if (!listener(a) && Ui.listenerGranted(a) && !sp.getBoolean(LISTENER + ".asked", false)) {
            sp.edit().putBoolean(LISTENER + ".asked", true).apply();
            ask(a, LISTENER, R.string.consent_listener_title, R.string.consent_listener_text,
                    R.string.consent_not_now, again, again);
            return;
        }
        if (!ai(a) && !AiStore.load(a).accounts.isEmpty() && !sp.getBoolean(AI + ".asked", false)) {
            sp.edit().putBoolean(AI + ".asked", true).apply();
            ask(a, AI, R.string.consent_ai_title, R.string.consent_ai_text, android.R.string.cancel, after, after);
        }
    }

    private static void ask(Activity a, String key, int title, int text, int decline, Runnable next) {
        ask(a, key, title, text, decline, next, null);
    }

    private static void ask(Activity a, String key, int title, int text, int decline, Runnable next,
                            Runnable closed) {
        if (sp(a).getBoolean(key, false)) {
            next.run();
            return;
        }
        AlertDialog open = showing != null ? showing.get() : null;
        if (open != null && open.isShowing()) return;
        boolean[] accepted = {false};
        showing = new WeakReference<>(new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(a.getText(text))
                .setNegativeButton(decline, null)
                .setPositiveButton(R.string.consent_accept, (d, w) -> {
                    accepted[0] = true;
                    sp(a).edit().putBoolean(key, true).apply();
                    next.run();
                })
                .setOnDismissListener(d -> {
                    if (!accepted[0] && closed != null) closed.run();
                })
                .show());
    }

    private static void section(SpannableStringBuilder s, CharSequence title) {
        int start = s.length();
        s.append(title);
        s.setSpan(new StyleSpan(Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append("\n\n");
    }

    /** Le due informative, lo stato dei consensi e il collegamento all'informativa completa. */
    static void showPrivacy(Activity a) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        section(s, a.getText(R.string.consent_listener_title));
        s.append(a.getText(R.string.consent_listener_text)).append("\n\n");
        s.append(a.getText(listener(a) ? R.string.consent_given : R.string.consent_missing)).append("\n\n\n");
        section(s, a.getText(R.string.consent_ai_title));
        s.append(a.getText(R.string.consent_ai_text)).append("\n\n");
        s.append(a.getText(ai(a) ? R.string.consent_given : R.string.consent_missing));
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.privacy_title, a.getString(R.string.app_name)))
                .setMessage(s)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.privacy_policy, (d, w) ->
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))))
                .show();
    }
}
