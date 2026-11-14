package io.github.alessandrotucci.whisperwa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Impostazioni: modello (download e scelta), lingua, revisione con l'IA, thread, lavoro in
 * background, cartelle dei vocali, prova su un file.
 */
public final class SettingsActivity extends Activity {

    private static final int REQ_FOLDER_WHATSAPP = 10;
    private static final int REQ_FOLDER_BUSINESS = 11;
    private static final int REQ_TEST_FILE = 12;
    private static final String[] LANGUAGES = {"auto", "it", "en", "pt", "es", "fr", "de"};

    private RadioGroup models;
    private TextView modelStatus;
    private ProgressBar progress;
    private Button download;
    private Button delete;
    private Button cancel;
    private AiSettings ai;
    private final Runnable downloadRefresher = this::refreshModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applyInsets(findViewById(R.id.root));

        models = findViewById(R.id.models);
        modelStatus = findViewById(R.id.model_status);
        progress = findViewById(R.id.download_progress);
        download = findViewById(R.id.btn_download);
        delete = findViewById(R.id.btn_delete);
        cancel = findViewById(R.id.btn_cancel_download);

        for (int i = 0; i < Models.ALL.length; i++) {
            Models.Model m = Models.ALL[i];
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(m.id);
            rb.setText(getString(m.labelRes) + " · " + Formatter.formatShortFileSize(this, m.size));
            models.addView(rb);
            if (m.id.equals(Prefs.model(this))) rb.setChecked(true);
        }
        models.setOnCheckedChangeListener((group, checkedId) -> {
            View rb = group.findViewById(checkedId);
            if (rb == null) return;
            Prefs.setModel(this, (String) rb.getTag());
            TranscriptionQueue.get(this).releaseModel();
            TranscriptionQueue.get(this).kick();
            refreshModel();
        });
        download.setOnClickListener(v -> DownloadService.start(this, Prefs.model(this)));
        cancel.setOnClickListener(v -> DownloadService.cancel(this));
        delete.setOnClickListener(v -> {
            Models.Model m = Models.byId(Prefs.model(this));
            if (m == null) return;
            TranscriptionQueue.get(this).releaseModel();
            //noinspection ResultOfMethodCallIgnored
            m.path(this).delete();
            //noinspection ResultOfMethodCallIgnored
            m.partPath(this).delete();
            refreshModel();
        });

        Spinner language = findViewById(R.id.language);
        language.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.language_labels)));
        int langIdx = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language(this));
        language.setSelection(Math.max(0, langIdx));
        language.setOnItemSelectedListener(new SimpleSelection(pos -> Prefs.setLanguage(this, LANGUAGES[pos])));

        ai = new AiSettings(this);

        Spinner threads = findViewById(R.id.threads);
        int cores = Runtime.getRuntime().availableProcessors();
        Integer[] options = new Integer[Math.max(1, cores)];
        for (int i = 0; i < options.length; i++) options[i] = i + 1;
        threads.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        threads.setSelection(Math.min(options.length, Prefs.threads(this)) - 1);
        threads.setOnItemSelectedListener(new SimpleSelection(pos -> Prefs.setThreads(this, pos + 1)));

        Spinner threadsBg = findViewById(R.id.threads_bg);
        threadsBg.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        threadsBg.setSelection(Math.min(options.length, Prefs.backgroundThreads(this)) - 1);
        threadsBg.setOnItemSelectedListener(new SimpleSelection(pos -> Prefs.setBackgroundThreads(this, pos + 1)));

        if (savedInstanceState == null && SECTION_BACKGROUND.equals(getIntent().getStringExtra(EXTRA_SECTION))) {
            highlightBackground();
        }

        findViewById(R.id.btn_folder_whatsapp).setOnClickListener(v ->
                startActivityForResult(Ui.folderPicker(Packages.WHATSAPP), REQ_FOLDER_WHATSAPP));
        findViewById(R.id.btn_folder_business).setOnClickListener(v ->
                startActivityForResult(Ui.folderPicker(Packages.BUSINESS), REQ_FOLDER_BUSINESS));
        findViewById(R.id.btn_listener).setOnClickListener(v -> Ui.openListenerSettings(this));
        findViewById(R.id.btn_test_file).setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"),
                REQ_TEST_FILE));

        TextView info = findViewById(R.id.info);
        String version = getString(R.string.info_version, versionName());
        info.setText(version);
        new Thread(() -> {
            String sys;
            try {
                sys = WhisperLib.systemInfo(this);
            } catch (Throwable t) {
                sys = t.toString();
            }
            String text = version + "\n" + getString(R.string.info_cores, cores) + "\n" + sys;
            runOnUiThread(() -> info.setText(text));
        }, "system-info").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.addListener(downloadRefresher);
        refreshModel();
        refreshFolders();
        ai.refresh();
        refreshBackground();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadService.removeListener(downloadRefresher);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void refreshModel() {
        Models.Model m = Models.byId(Prefs.model(this));
        DownloadService.State s = DownloadService.state();
        boolean downloading = s.modelId != null;
        boolean thisOne = m != null && m.id.equals(s.modelId);
        boolean installed = m != null && m.installed(this);
        for (int i = 0; i < models.getChildCount(); i++) models.getChildAt(i).setEnabled(!downloading);
        progress.setVisibility(thisOne ? View.VISIBLE : View.GONE);
        if (thisOne) {
            progress.setIndeterminate(s.verifying || s.total <= 0);
            progress.setMax(1000);
            progress.setProgress(s.total > 0 ? (int) (s.done * 1000 / s.total) : 0);
        }
        download.setVisibility(!installed && !downloading ? View.VISIBLE : View.GONE);
        cancel.setVisibility(thisOne ? View.VISIBLE : View.GONE);
        delete.setVisibility(installed && !downloading ? View.VISIBLE : View.GONE);
        String text;
        if (thisOne && s.verifying) {
            text = getString(R.string.model_verifying);
        } else if (thisOne) {
            text = getString(R.string.model_downloading, Formatter.formatShortFileSize(this, s.done),
                    Formatter.formatShortFileSize(this, s.total));
        } else if (installed) {
            text = getString(R.string.model_installed);
        } else if (s.error != null) {
            text = getString(R.string.model_error, s.error);
        } else {
            text = getString(R.string.model_missing);
        }
        modelStatus.setText(text);
    }

    private void refreshFolders() {
        ((TextView) findViewById(R.id.folder_whatsapp)).setText(folderText(Packages.WHATSAPP));
        ((TextView) findViewById(R.id.folder_business)).setText(folderText(Packages.BUSINESS));
        ((TextView) findViewById(R.id.listener_status)).setText(Ui.listenerGranted(this)
                ? R.string.listener_on : R.string.listener_off);
    }

    static final String EXTRA_SECTION = "section";
    static final String SECTION_BACKGROUND = "background";
    private static final long HIGHLIGHT_MS = 2000;
    private static final long HIGHLIGHT_FADE_MS = 600;
    private static final int CHECK_OK = 0;
    private static final int CHECK_ERROR = 1;
    private static final int CHECK_UNKNOWN = 2;

    /** Le impostazioni aperte sulla sezione «Lavoro in background», che resta evidenziata per un momento. */
    static Intent backgroundSection(Context c) {
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_SECTION, SECTION_BACKGROUND);
    }

    /**
     * Sezione «Lavoro in background»: le limitazioni della batteria di Android, che l'app legge, e
     * su ColorOS e Xiaomi le impostazioni del produttore, che l'app non può leggere; in fondo
     * l'ultima interruzione degli ultimi sette giorni.
     */
    private void refreshBackground() {
        boolean unrestricted = Ui.batteryUnrestricted(this);
        bindCheck(R.id.bg_battery, unrestricted ? CHECK_OK : CHECK_ERROR, R.string.bg_battery_title,
                unrestricted ? R.string.bg_battery_off : R.string.bg_battery_on,
                unrestricted ? 0 : R.string.bg_remove, () -> Ui.requestBatteryUnrestricted(this));

        BackgroundWork.Oem oem = BackgroundWork.oem();
        boolean vendor = oem != BackgroundWork.Oem.OTHER;
        boolean xiaomi = oem == BackgroundWork.Oem.XIAOMI;
        findViewById(R.id.bg_activity).setVisibility(vendor ? View.VISIBLE : View.GONE);
        findViewById(R.id.bg_autostart).setVisibility(vendor ? View.VISIBLE : View.GONE);
        if (vendor) {
            bindCheck(R.id.bg_activity, CHECK_UNKNOWN, R.string.bg_activity_title,
                    xiaomi ? R.string.bg_activity_xiaomi : R.string.bg_activity_coloros,
                    R.string.bg_open, () -> BackgroundWork.openOemPage(this, false));
            bindCheck(R.id.bg_autostart, CHECK_UNKNOWN, R.string.bg_autostart_title,
                    xiaomi ? R.string.bg_autostart_xiaomi : R.string.bg_autostart_coloros,
                    R.string.bg_open, () -> BackgroundWork.openOemPage(this, true));
        }
        refreshLastProblem();
    }

    private void bindCheck(int rowId, int state, int title, int text, int button, Runnable action) {
        View row = findViewById(rowId);
        TextView icon = row.findViewById(R.id.check_icon);
        int color = getColor(state == CHECK_OK ? R.color.check_ok
                : state == CHECK_ERROR ? R.color.check_error : R.color.check_unknown);
        icon.setText(state == CHECK_OK ? "✓" : state == CHECK_ERROR ? "✗" : "?");
        icon.setTextColor(color);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((color & 0x00FFFFFF) | 0x33000000);
        icon.setBackground(circle);
        ((TextView) row.findViewById(R.id.check_title)).setText(title);
        ((TextView) row.findViewById(R.id.check_text)).setText(text);
        Button b = row.findViewById(R.id.check_button);
        b.setVisibility(button != 0 ? View.VISIBLE : View.GONE);
        if (button != 0) {
            b.setText(button);
            b.setOnClickListener(v -> action.run());
        }
    }

    /** L'ultima interruzione con il collegamento Riprova finché il vocale è in errore. */
    private void refreshLastProblem() {
        TextView last = findViewById(R.id.bg_last);
        long t = BackgroundWork.recentKill(this);
        last.setVisibility(t > 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.bg_no_problem).setVisibility(t > 0 ? View.GONE : View.VISIBLE);
        if (t == 0) return;
        SpannableStringBuilder sb = new SpannableStringBuilder(
                getString(R.string.bg_last, BackgroundWork.when(this, t), Prefs.lastKillWho(this)));
        Db.Note note = BackgroundWork.retryableKill(this);
        if (note != null) {
            sb.append(' ');
            int start = sb.length();
            sb.append(getString(R.string.bg_retry));
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (BackgroundWork.retry(SettingsActivity.this, note.id)) {
                        Toast.makeText(SettingsActivity.this,
                                getResources().getQuantityString(R.plurals.queued, 1, 1), Toast.LENGTH_SHORT).show();
                    }
                    refreshLastProblem();
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(getColor(R.color.accent));
                    ds.setFakeBoldText(true);
                    ds.setUnderlineText(false);
                }
            }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        last.setText(sb);
        last.setMovementMethod(note != null ? LinkMovementMethod.getInstance() : null);
    }

    /** Scorre fino alla sezione «Lavoro in background» e la evidenzia per HIGHLIGHT_MS. */
    private void highlightBackground() {
        ScrollView root = findViewById(R.id.root);
        View section = findViewById(R.id.background_section);
        GradientDrawable glow = new GradientDrawable();
        glow.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        glow.setColor(getColor(R.color.section_highlight));
        section.setBackground(glow);
        section.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                section.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root.smoothScrollTo(0, section.getTop());
                ObjectAnimator fade = ObjectAnimator.ofInt(glow, "alpha", 255, 0);
                fade.setStartDelay(HIGHLIGHT_MS);
                fade.setDuration(HIGHLIGHT_FADE_MS);
                fade.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        section.setBackground(null);
                    }
                });
                fade.start();
            }
        });
    }

    private String folderText(String pkg) {
        Uri tree = Prefs.folder(this, pkg);
        String label = Packages.label(this, pkg);
        if (tree == null) return getString(R.string.folder_none, label);
        String docId = DocumentsContract.getTreeDocumentId(tree);
        int colon = docId.indexOf(':');
        return label + ": " + (colon >= 0 ? docId.substring(colon + 1) : docId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_FOLDER_WHATSAPP) Ui.onFolderPicked(this, Packages.WHATSAPP, data.getData());
        if (requestCode == REQ_FOLDER_BUSINESS) Ui.onFolderPicked(this, Packages.BUSINESS, data.getData());
        if (requestCode == REQ_TEST_FILE) {
            startActivity(new Intent(this, ShareActivity.class)
                    .setAction(ShareActivity.ACTION_OPEN)
                    .setData(data.getData())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
        refreshFolders();
    }

    /** Adattatore per OnItemSelectedListener che ignora la selezione iniziale. */
    private static final class SimpleSelection implements AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer onSelect;
        private boolean first = true;

        SimpleSelection(java.util.function.IntConsumer onSelect) {
            this.onSelect = onSelect;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (first) {
                first = false;
                return;
            }
            onSelect.accept(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
