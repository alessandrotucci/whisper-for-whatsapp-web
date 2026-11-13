package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * All'avvio del processo crea i canali di notifica e riprende la coda lasciata in sospeso.
 * Conta le attività visibili, per sapere se l'utente sta guardando l'app.
 */
public final class App extends Application {

    private static int started;

    public static synchronized boolean isVisible() {
        return started > 0;
    }

    private static synchronized void changeStarted(int delta) {
        started += delta;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.createChannels(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                changeStarted(1);
            }

            @Override
            public void onActivityStopped(Activity a) {
                changeStarted(-1);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
        TranscriptionQueue.get(this).resume();
    }
}
