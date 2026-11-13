package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

/** Funzioni condivise dalle schermate: margini di sistema, selettore di cartelle, impostazioni di sistema. */
final class Ui {

    private Ui() {
    }

    /**
     * Da Android 15 le finestre arrivano fino ai bordi dello schermo: la vista radice riceve come
     * padding lo spazio delle barre di sistema ai lati e in basso; in alto c'è la barra delle azioni.
     */
    static void applyInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        int left = root.getPaddingLeft();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + b.left, v.getPaddingTop(), right + b.right, bottom + b.bottom);
            return insets;
        });
    }

    static Intent folderPicker(String pkg) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Packages.initialFolderUri(pkg));
    }

    /** Salva la cartella scelta per pkg con un permesso di lettura persistente. */
    static void onFolderPicked(Activity a, String pkg, Uri tree) {
        if (tree == null) return;
        try {
            a.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Toast.makeText(a, R.string.folder_permission_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setFolder(a, pkg, tree);
        String docId = DocumentsContract.getTreeDocumentId(tree);
        boolean looksRight = docId != null && docId.endsWith("Voice Notes");
        Toast.makeText(a, looksRight ? R.string.folder_ok : R.string.folder_unexpected, Toast.LENGTH_LONG).show();
    }

    static boolean listenerGranted(Activity a) {
        android.app.NotificationManager nm = a.getSystemService(android.app.NotificationManager.class);
        return nm.isNotificationListenerAccessGranted(new ComponentName(a, VoiceListener.class));
    }

    static void openListenerSettings(Activity a) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(a, VoiceListener.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    static void openAppDetails(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.getPackageName(), null)));
    }

    static boolean batteryUnrestricted(Activity a) {
        return a.getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(a.getPackageName());
    }

    @android.annotation.SuppressLint("BatteryLife")
    static void requestBatteryUnrestricted(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", a.getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
