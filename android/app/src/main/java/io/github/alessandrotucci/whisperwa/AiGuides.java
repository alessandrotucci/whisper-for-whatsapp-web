package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Guide per ottenere le chiavi API dei fornitori di IA, mostrate nella schermata «Aggiungi un
 * account IA». Questa versione nasconde il riquadro: le guide arrivano dal componente dedicato.
 */
final class AiGuides {

    private AiGuides() {
    }

    /** Riempie container con la guida del fornitore, oppure lo nasconde se non c'è. */
    static void render(Activity activity, LinearLayout container, String providerId) {
        container.removeAllViews();
        container.setVisibility(View.GONE);
    }

    /** Aggiorna in background la copia locale delle guide. */
    static void refresh(Context context) {
    }
}
