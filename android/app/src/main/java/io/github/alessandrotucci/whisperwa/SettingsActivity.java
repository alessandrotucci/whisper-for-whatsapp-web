package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Impostazioni: modello (download e scelta), lingua, revisione con l'IA, thread, cartelle dei
 * vocali, prova su un file.
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
