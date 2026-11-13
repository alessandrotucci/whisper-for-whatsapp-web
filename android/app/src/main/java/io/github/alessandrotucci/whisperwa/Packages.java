package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Le due app di WhatsApp seguite e le cartelle dove salvano i vocali. */
public final class Packages {

    public static final String WHATSAPP = "com.whatsapp";
    public static final String BUSINESS = "com.whatsapp.w4b";
    public static final String[] ALL = {WHATSAPP, BUSINESS};

    private Packages() {
    }

    public static boolean isWhatsApp(String pkg) {
        return WHATSAPP.equals(pkg) || BUSINESS.equals(pkg);
    }

    public static String label(Context context, String pkg) {
        return context.getString(BUSINESS.equals(pkg) ? R.string.whatsapp_business : R.string.whatsapp);
    }

    public static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Percorso abituale della cartella dei vocali, relativo alla memoria interna. */
    public static String voiceNotesPath(String pkg) {
        return BUSINESS.equals(pkg)
                ? "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"
                : "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes";
    }

    /** URI da cui il selettore di cartelle parte, per arrivare già sulla cartella giusta. */
    public static Uri initialFolderUri(String pkg) {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:" + voiceNotesPath(pkg));
    }
}
