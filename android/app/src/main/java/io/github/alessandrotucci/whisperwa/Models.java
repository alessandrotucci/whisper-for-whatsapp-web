package io.github.alessandrotucci.whisperwa;

import android.content.Context;

import java.io.File;

/**
 * Modelli ggml di whisper.cpp scaricabili dall'app, con dimensione e SHA-256 dei file
 * pubblicati su huggingface.co/ggerganov/whisper.cpp. Un modello è installato quando il suo
 * file sta in filesDir/models con il nome definitivo; il download scrive su un file .part.
 */
public final class Models {

    public static final class Model {
        public final String id;
        public final String file;
        public final long size;
        public final String sha256;
        public final int labelRes;

        Model(String id, String file, long size, String sha256, int labelRes) {
            this.id = id;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
            this.labelRes = labelRes;
        }

        public String url() {
            return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + file;
        }

        public File path(Context context) {
            return new File(dir(context), file);
        }

        public File partPath(Context context) {
            return new File(dir(context), file + ".part");
        }

        public boolean installed(Context context) {
            File f = path(context);
            return f.isFile() && f.length() == size;
        }
    }

    public static final Model[] ALL = {
            new Model("large-v3-q5_0", "ggml-large-v3-q5_0.bin", 1081140203L,
                    "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1",
                    R.string.model_large),
            new Model("large-v3-turbo-q5_0", "ggml-large-v3-turbo-q5_0.bin", 574041195L,
                    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
                    R.string.model_turbo),
            new Model("medium-q5_0", "ggml-medium-q5_0.bin", 539212467L,
                    "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
                    R.string.model_medium),
            new Model("small-q5_1", "ggml-small-q5_1.bin", 190085487L,
                    "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
                    R.string.model_small),
            new Model("base-q5_1", "ggml-base-q5_1.bin", 59707625L,
                    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
                    R.string.model_base),
    };

    public static final String DEFAULT_ID = "large-v3-q5_0";

    private Models() {
    }

    public static Model byId(String id) {
        for (Model m : ALL) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    /** Il modello scelto nelle impostazioni, se è installato. */
    public static Model selectedInstalled(Context context) {
        Model m = byId(Prefs.model(context));
        return m != null && m.installed(context) ? m : null;
    }

    static File dir(Context context) {
        File d = new File(context.getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }
}
