package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Guide per ottenere la chiave API di ciascun fornitore di IA, mostrate nella schermata di
 * collegamento di un account.
 *
 * <p>Le guide sono il file ai-guides.json dell'estensione. L'ordine delle fonti è: la copia
 * scaricata in filesDir, purché più recente dell'installazione dell'app; altrimenti la copia negli
 * asset, che viaggia con l'APK. Il file scaricato si rinnova al massimo una volta al giorno.
 *
 * <p>Un passo può avere un oggetto facoltativo mobile, con title, text e img: i campi presenti
 * sostituiscono quelli del passo sul telefono. Le catture si scaricano da img_base più img, con
 * cache in memoria e su disco; un passo senza cattura raggiungibile non mostra lo spazio
 * dell'immagine.
 */
final class AiGuides {

    private static final String GUIDES_URL =
            "https://raw.githubusercontent.com/alessandrotucci/whisper-for-whatsapp-web/main/extension/ai-guides.json";
    private static final String GUIDES_PAGE =
            "https://github.com/alessandrotucci/whisper-for-whatsapp-web/blob/main/docs/ai-keys/";
    private static final String ASSET = "ai-guides.json";
    private static final String CACHE_FILE = "ai-guides.json";
    private static final String IMG_DIR = "ai-guides-img";

    /** Versione massima del formato che l'app sa leggere. */
    private static final int FORMAT_VERSION = 1;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final long IMG_MAX_AGE_MS = 7 * DAY_MS;

    /**
     * Fornitori che collegano l'account dal browser, senza una chiave da incollare. Anche i fornitori
     * con local true nel JSON non hanno una chiave: per entrambi manca l'ultimo passo «Torna qui e
     * tocca Incolla».
     */
    private static final Set<String> BROWSER_SIGN_IN = new HashSet<>(Arrays.asList("openrouter"));

    private static final String PREFS = "ai_guides";
    private static final String KEY_COLLAPSED = "collapsed";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_LAST_TRY = "last_try";

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Bitmap decodificate, limitate a un sedicesimo della memoria dell'app. */
    private static final LruCache<String, Bitmap> BITMAPS =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 16, Integer.MAX_VALUE)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Indirizzi di immagini non raggiungibili in questo processo: non si richiedono di nuovo. */
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<>());

    private static JSONObject guides;
    private static boolean refreshing;

    private AiGuides() {
    }

    /**
     * Svuota container e lo riempie con il riquadro richiudibile della guida di providerId; se per
     * quel fornitore non c'è una guida, il container resta vuoto e nascosto.
     */
    static void render(Activity activity, LinearLayout container, String providerId) {
        container.removeAllViews();
        JSONObject all = load(activity);
        JSONObject g = all == null ? null : all.optJSONObject("providers");
        g = g == null || providerId == null ? null : g.optJSONObject(providerId);
        org.json.JSONArray steps = g == null ? null : g.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        String lang = lang();
        String imgBase = all.optString("img_base", "");
        int accent = activity.getColor(R.color.accent);
        int secondary = themeColor(activity, android.R.attr.textColorSecondary);
        int primary = themeColor(activity, android.R.attr.textColorPrimary);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 12));
        bg.setColor((primary & 0x00FFFFFF) | 0x14000000);
        box.setBackground(bg);
        box.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 12));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(0, dp(activity, 8), 0, dp(activity, 4));
        container.addView(box, boxLp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(activity, 40));
        header.setBackground(themeDrawable(activity, android.R.attr.selectableItemBackground));
        TextView title = new TextView(activity);
        title.setText(R.string.guide_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(primary);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.guide_expand);
        header.addView(chevron, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
        box.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        box.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(activity);
        meta.setText(meta(activity, g));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setTextColor(secondary);
        body.addView(meta);

        int n = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            JSONObject mobile = s.optJSONObject("mobile");
            String stepTitle = text(pick(mobile, s, "title"), lang);
            String stepText = text(pick(mobile, s, "text"), lang);
            String img = mobile != null && mobile.has("img") ? mobile.optString("img", "") : s.optString("img", "");
            String url = s.optString("url", "");
            View textView = stepText(activity, stepTitle, stepText, url, accent);
            String imgUrl = img.isEmpty() || imgBase.isEmpty() ? null : imgBase + img;
            body.addView(step(activity, ++n, textView, imgUrl, accent));
        }
        if (!g.optBoolean("local", false) && !BROWSER_SIGN_IN.contains(providerId)) {
            View last = stepText(activity, activity.getString(R.string.guide_back_title),
                    activity.getString(R.string.guide_back_text), "", accent);
            body.addView(step(activity, ++n, last, null, accent));
        }

        TextView full = new TextView(activity);
        full.setText(R.string.guide_full);
        full.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        full.setTextColor(accent);
        full.setPadding(0, dp(activity, 10), 0, dp(activity, 2));
        String page = GUIDES_PAGE + ("it".equals(lang) ? "README.it.md" : "README.md") + "#" + providerId;
        full.setOnClickListener(v -> openUrl(activity, page));
        body.addView(full);

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Runnable apply = () -> {
            boolean collapsed = prefs.getBoolean(KEY_COLLAPSED, false);
            body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            chevron.setRotation(collapsed ? 0 : 180);
            header.setContentDescription(activity.getString(R.string.guide_title) + ", "
                    + activity.getString(collapsed ? R.string.guide_expand : R.string.guide_collapse));
        };
        apply.run();
        header.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_COLLAPSED, !prefs.getBoolean(KEY_COLLAPSED, false)).apply();
            apply.run();
        });
    }

    /**
     * Aggiorna in background la copia scaricata delle guide: dopo un aggiornamento riuscito non
     * riprova per un giorno, dopo un tentativo fallito per un'ora.
     */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        synchronized (AiGuides.class) {
            if (refreshing) return;
            if (now - prefs.getLong(KEY_LAST_OK, 0) < DAY_MS) return;
            if (now - prefs.getLong(KEY_LAST_TRY, 0) < RETRY_MS) return;
            refreshing = true;
        }
        prefs.edit().putLong(KEY_LAST_TRY, now).apply();
        IO.execute(() -> {
            try {
                byte[] data = download(GUIDES_URL, 1024 * 1024);
                if (data == null) return;
                JSONObject parsed = parse(new String(data, StandardCharsets.UTF_8));
                if (parsed == null) return;
                writeAtomically(new File(app.getFilesDir(), CACHE_FILE), data);
                synchronized (AiGuides.class) {
                    guides = parsed;
                }
                prefs.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).apply();
            } catch (IOException ignored) {
                // Senza rete resta la copia precedente; si riprova al prossimo refresh utile.
            } finally {
                synchronized (AiGuides.class) {
                    refreshing = false;
                }
            }
        });
    }

    private static synchronized JSONObject load(Context context) {
        if (guides != null) return guides;
        File cached = new File(context.getFilesDir(), CACHE_FILE);
        if (cached.isFile() && cached.lastModified() > installTime(context)) {
            try {
                guides = parse(new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                guides = null;
            }
        }
        if (guides == null) {
            try (InputStream in = context.getAssets().open(ASSET)) {
                guides = parse(new String(readAll(in, 1024 * 1024), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                guides = null;
            }
        }
        return guides;
    }

    /** Il JSON se ha un formato che l'app sa leggere, altrimenti null. */
    private static JSONObject parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("version", 0) < 1 || o.optInt("version", 0) > FORMAT_VERSION) return null;
            if (o.optJSONObject("providers") == null) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static long installTime(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String lang() {
        return "it".equals(Locale.getDefault().getLanguage()) ? "it" : "en";
    }

    private static Object pick(JSONObject mobile, JSONObject step, String key) {
        if (mobile != null && mobile.has(key)) return mobile.opt(key);
        return step.opt(key);
    }

    private static String text(Object localized, String lang) {
        if (localized instanceof JSONObject) {
            JSONObject o = (JSONObject) localized;
            String s = o.optString(lang, "");
            return s.isEmpty() ? o.optString("en", "") : s;
        }
        return localized instanceof String ? (String) localized : "";
    }

    private static String meta(Context c, JSONObject g) {
        int minutes = Math.max(1, g.optInt("minutes", 3));
        StringBuilder sb = new StringBuilder(c.getResources().getQuantityString(R.plurals.guide_minutes, minutes, minutes));
        sb.append(" · ");
        if (g.optBoolean("card", false)) {
            sb.append(c.getString(R.string.guide_card)).append(" · ").append(c.getString(R.string.guide_cost));
        } else if (g.optBoolean("local", false)) {
            sb.append(c.getString(R.string.guide_local));
        } else {
            sb.append(c.getString(R.string.guide_nocard));
        }
        return sb.toString();
    }

    /** Titolo in grassetto, testo e, se c'è, il collegamento al sito con l'indirizzo senza schema. */
    private static TextView stepText(Activity a, String title, String text, String url, int accent) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!title.isEmpty()) {
            sb.append(title, new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!text.isEmpty()) sb.append('\n');
        }
        sb.append(text);
        if (!url.isEmpty()) {
            sb.append(' ');
            String label = url.replaceFirst("^https?://", "").replaceFirst("/$", "");
            sb.append(label, new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    openUrl(a, url);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(accent);
                    ds.setUnderlineText(false);
                }
            }, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView tv = new TextView(a);
        tv.setText(sb);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(themeColor(a, android.R.attr.textColorPrimary));
        tv.setLineSpacing(0, 1.15f);
        if (!url.isEmpty()) tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    /** Riga di un passo: numero nel cerchio d'accento, testo e spazio della cattura. */
    private static View step(Activity a, int number, View text, String imgUrl, int accent) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(a, 10);
        row.setLayoutParams(rowLp);

        TextView num = new TextView(a);
        num.setText(String.valueOf(number));
        num.setGravity(Gravity.CENTER);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextColor(0xFF06210F);
        num.setIncludeFontPadding(false);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(accent);
        num.setBackground(circle);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22));
        numLp.setMarginEnd(dp(a, 10));
        numLp.topMargin = dp(a, 1);
        row.addView(num, numLp);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text);
        if (imgUrl != null && !MISSING.contains(imgUrl)) {
            ImageView img = new ImageView(a);
            img.setVisibility(View.GONE);
            img.setAdjustViewBounds(true);
            img.setMaxHeight(dp(a, 220));
            img.setScaleType(ImageView.ScaleType.FIT_START);
            img.setContentDescription(a.getString(R.string.guide_image, number));
            img.setTag(imgUrl);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            imgLp.topMargin = dp(a, 6);
            col.addView(img, imgLp);
            loadImage(a, img, imgUrl);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private static void loadImage(Activity a, ImageView view, String url) {
        Bitmap hit = BITMAPS.get(url);
        if (hit != null) {
            show(a, view, url, hit);
            return;
        }
        File dir = new File(a.getCacheDir(), IMG_DIR);
        File file = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.replaceAll(".*/", "").replaceAll("[^A-Za-z0-9._-]", "_"));
        IO.execute(() -> {
            boolean fresh = file.isFile() && System.currentTimeMillis() - file.lastModified() < IMG_MAX_AGE_MS;
            if (!fresh) {
                try {
                    byte[] data = download(url, 8 * 1024 * 1024);
                    if (data != null) {
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        writeAtomically(file, data);
                    } else if (!file.isFile()) {
                        MISSING.add(url);
                    }
                } catch (IOException ignored) {
                    // Senza rete vale la copia su disco, anche se vecchia.
                }
            }
            Bitmap bmp = file.isFile() ? decode(file, 1080) : null;
            if (bmp == null) return;
            BITMAPS.put(url, bmp);
            MAIN.post(() -> show(a, view, url, bmp));
        });
    }

    private static void show(Activity a, ImageView view, String url, Bitmap bmp) {
        if (a.isDestroyed() || !url.equals(view.getTag())) return;
        view.setImageBitmap(bmp);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v -> fullScreen(a, bmp, url));
    }

    /** Cattura a schermo intero su fondo nero; un tocco la chiude, un tocco lungo la apre nel browser. */
    private static void fullScreen(Activity a, Bitmap bmp, String url) {
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(new ColorDrawable(Color.BLACK));
        iv.setContentDescription(a.getString(R.string.guide_image_close));
        iv.setOnClickListener(v -> d.dismiss());
        iv.setOnLongClickListener(v -> {
            d.dismiss();
            openUrl(a, url);
            return true;
        });
        d.setContentView(iv);
        d.show();
    }

    /** Decodifica riducendo per potenze di due finché la larghezza non supera maxWidth. */
    private static Bitmap decode(File f, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Il corpo della risposta se HTTP 200, null per le altre risposte; IOException senza rete. */
    private static byte[] download(String url, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setUseCaches(false);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("file troppo grande");
        }
        return out.toByteArray();
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        File tmp = new File(target.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename");
        }
    }

    private static void openUrl(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }

    private static int themeColor(Context c, int attr) {
        TypedArray ta = c.obtainStyledAttributes(new int[]{attr});
        try {
            return ta.getColor(0, Color.GRAY);
        } finally {
            ta.recycle();
        }
    }

    private static android.graphics.drawable.Drawable themeDrawable(Context c, int attr) {
        TypedArray ta = c.obtainStyledAttributes(new int[]{attr});
        try {
            return ta.getDrawable(0);
        } finally {
            ta.recycle();
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
