package io.github.alessandrotucci.whisperwa;

import android.app.Application;

/** All'avvio del processo crea i canali di notifica e riprende la coda lasciata in sospeso. */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.createChannels(this);
        TranscriptionQueue.get(this).resume();
    }
}
