package io.github.alessandrotucci.whisperwa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Lista delle trascrizioni, dalla più recente, con in cima ciò che manca per funzionare:
 * permesso di notifica, accesso alle notifiche, modello, cartella dei vocali, batteria, e una
 * riga finché il sistema ha fermato una trascrizione negli ultimi sette giorni.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_NOTE = "note";
    private static final int REQ_NOTIFICATIONS = 1;
    private static final int REQ_FOLDER_WHATSAPP = 10;
    private static final int REQ_FOLDER_BUSINESS = 11;
    private static final int MENU_COPY = 1;
    private static final int MENU_RETRY = 2;
    private static final int MENU_DELETE = 3;
    private static final int MENU_CANCEL = 4;

    private LinearLayout setup;
    private TextView status;
    private ListView list;
    private NoteAdapter adapter;
    private long highlight = -1;
    private final Runnable refresher = this::refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.applyInsets(findViewById(R.id.root));
        setup = findViewById(R.id.setup);
        status = findViewById(R.id.status);
        list = findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));
        adapter = new NoteAdapter(this);
        list.setAdapter(adapter);
        registerForContextMenu(list);
        list.setOnItemClickListener((parent, view, position, id) -> list.showContextMenuForChild(view));
        highlight = getIntent().getLongExtra(EXTRA_NOTE, -1);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        highlight = intent.getLongExtra(EXTRA_NOTE, -1);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TranscriptionQueue.get(this).addListener(refresher);
        refresh();
        refreshSetup();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TranscriptionQueue.get(this).removeListener(refresher);
    }

    private void refresh() {
        List<Db.Note> notes = Db.get(this).recent(300);
        adapter.setNotes(notes);
        int pending = Db.get(this).countPending();
        status.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
        status.setText(getResources().getQuantityString(R.plurals.pending, pending, pending));
        if (highlight >= 0) {
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id == highlight) {
                    list.setSelection(i);
                    Notifier.cancelTranscript(this, highlight);
                    break;
                }
            }
            highlight = -1;
        }
    }

    private void refreshSetup() {
        setup.removeAllViews();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            addSetupRow(R.string.setup_notifications, R.string.setup_allow,
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
        }
        if (!Ui.listenerGranted(this)) {
            addSetupRow(R.string.setup_listener, R.string.setup_open_settings, () -> Ui.openListenerSettings(this));
            if (Build.VERSION.SDK_INT >= 33) {
                addSetupRow(R.string.setup_restricted, R.string.setup_app_info, () -> Ui.openAppDetails(this));
            }
        }
        if (Models.selectedInstalled(this) == null) {
            addSetupRow(R.string.setup_model, R.string.setup_download,
                    () -> startActivity(new Intent(this, SettingsActivity.class)));
        }
        boolean business = Packages.installed(this, Packages.BUSINESS);
        boolean whatsapp = Packages.installed(this, Packages.WHATSAPP) || !business;
        if (whatsapp && Prefs.folder(this, Packages.WHATSAPP) == null) {
            addSetupRow(getString(R.string.setup_folder, getString(R.string.whatsapp)), R.string.setup_choose,
                    () -> startActivityForResult(Ui.folderPicker(Packages.WHATSAPP), REQ_FOLDER_WHATSAPP));
        }
        if (business && Prefs.folder(this, Packages.BUSINESS) == null) {
            addSetupRow(getString(R.string.setup_folder, getString(R.string.whatsapp_business)), R.string.setup_choose,
                    () -> startActivityForResult(Ui.folderPicker(Packages.BUSINESS), REQ_FOLDER_BUSINESS));
        }
        if (!Ui.batteryUnrestricted(this)) {
            addSetupRow(R.string.setup_battery, R.string.setup_allow, () -> Ui.requestBatteryUnrestricted(this));
        }
        long killed = BackgroundWork.recentKill(this);
        if (killed > 0) {
            String when = BackgroundWork.when(this, killed);
            when = when.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + when.substring(1);
            addSetupRow(getString(R.string.setup_killed, when), R.string.setup_check,
                    () -> startActivity(SettingsActivity.backgroundSection(this)));
        }
        setup.setVisibility(setup.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addSetupRow(int text, int button, Runnable action) {
        addSetupRow(getString(text), button, action);
    }

    private void addSetupRow(String text, int button, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setup, setup, false);
        ((TextView) row.findViewById(R.id.setup_text)).setText(text);
        Button b = row.findViewById(R.id.setup_button);
        b.setText(button);
        b.setOnClickListener(v -> action.run());
        setup.addView(row);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_FOLDER_WHATSAPP) Ui.onFolderPicked(this, Packages.WHATSAPP, data.getData());
        if (requestCode == REQ_FOLDER_BUSINESS) Ui.onFolderPicked(this, Packages.BUSINESS, data.getData());
        refreshSetup();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshSetup();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.menu_scan) {
            VoiceScanner.get(this).scanRecent(10, added -> runOnUiThread(() -> Toast.makeText(this,
                    getResources().getQuantityString(R.plurals.queued, added, added), Toast.LENGTH_SHORT).show()));
            return true;
        }
        if (id == R.id.menu_clear) {
            new android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.clear_confirm)
                    .setPositiveButton(R.string.clear_yes, (d, w) -> {
                        Db.get(this).deleteAll();
                        Notifier.cancelAll(this);
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        Db.Note note = noteAt(menuInfo);
        if (note == null) return;
        menu.setHeaderTitle(note.title());
        if (note.status == Db.DONE && note.text != null) menu.add(0, MENU_COPY, 0, R.string.action_copy);
        if (note.status == Db.RUNNING) menu.add(0, MENU_CANCEL, 0, R.string.action_cancel);
        if ((note.status == Db.DONE || note.status == Db.ERROR) && (note.localPath != null || !note.source.startsWith("share:"))) {
            menu.add(0, MENU_RETRY, 0, R.string.action_retry);
        }
        if (note.status != Db.RUNNING) menu.add(0, MENU_DELETE, 0, R.string.action_delete);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Db.Note note = noteAt(item.getMenuInfo());
        if (note == null) return false;
        switch (item.getItemId()) {
            case MENU_COPY:
                CopyReceiver.copy(this, note);
                return true;
            case MENU_CANCEL:
                TranscriptionQueue.get(this).cancelCurrent();
                return true;
            case MENU_RETRY:
                Db.get(this).requeue(note.id);
                TranscriptionQueue.get(this).kick();
                refresh();
                return true;
            case MENU_DELETE:
                Db.get(this).delete(note.id);
                Notifier.cancelTranscript(this, note.id);
                if (note.localPath != null) {
                    //noinspection ResultOfMethodCallIgnored
                    new java.io.File(note.localPath).delete();
                }
                refresh();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private Db.Note noteAt(ContextMenu.ContextMenuInfo info) {
        if (!(info instanceof AdapterView.AdapterContextMenuInfo)) return null;
        int pos = ((AdapterView.AdapterContextMenuInfo) info).position;
        return pos >= 0 && pos < adapter.getCount() ? adapter.getItem(pos) : null;
    }

    /** Una riga per vocale: mittente, dati del vocale e testo o stato. */
    private static final class NoteAdapter extends ArrayAdapter<Db.Note> {

        NoteAdapter(Context context) {
            super(context, R.layout.item_note);
        }

        void setNotes(List<Db.Note> notes) {
            setNotifyOnChange(false);
            clear();
            addAll(notes);
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_note, parent, false);
            Db.Note n = getItem(position);
            Context c = getContext();
            ((TextView) v.findViewById(R.id.note_title)).setText(n.title());
            StringBuilder meta = new StringBuilder();
            meta.append(DateUtils.formatDateTime(c, n.received,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH));
            if (n.pkg != null) meta.append(" · ").append(Packages.label(c, n.pkg));
            if (n.audioMs > 0) meta.append(" · ").append(DateUtils.formatElapsedTime(n.audioMs / 1000));
            if (n.status == Db.DONE && n.workMs > 0) {
                meta.append(" · ").append(c.getString(R.string.meta_work, (n.workMs + 500) / 1000));
            }
            if (n.language != null && !n.language.isEmpty()) meta.append(" · ").append(n.language);
            ((TextView) v.findViewById(R.id.note_meta)).setText(meta);
            TextView body = v.findViewById(R.id.note_text);
            switch (n.status) {
                case Db.PENDING:
                    body.setText(R.string.status_pending);
                    break;
                case Db.RUNNING:
                    body.setText(R.string.status_running);
                    break;
                case Db.ERROR:
                    body.setText(c.getString(R.string.status_error, n.error == null ? "" : n.error));
                    break;
                default:
                    body.setText(n.text == null || n.text.isEmpty() ? c.getString(R.string.empty_transcript) : n.text);
            }
            return v;
        }
    }
}
