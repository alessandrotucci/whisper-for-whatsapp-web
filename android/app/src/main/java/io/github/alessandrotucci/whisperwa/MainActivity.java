package io.github.alessandrotucci.whisperwa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

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
    private static final int MENU_SHOW_WHISPER = 5;
    private static final int MENU_SHOW_REVISED = 6;
    private static final int MENU_REVIEW_AGAIN = 7;

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
        if (note.hasRevision()) {
            menu.add(0, note.showWhisper ? MENU_SHOW_REVISED : MENU_SHOW_WHISPER, 0,
                    note.showWhisper ? R.string.action_show_revised : R.string.action_show_whisper);
        }
        if (note.status == Db.DONE && note.text != null && !note.text.isEmpty()
                && note.reviewState != Db.REVIEW_PENDING && note.reviewState != Db.REVIEW_RUNNING
                && AiStore.load(this).active()) {
            menu.add(0, MENU_REVIEW_AGAIN, 0, R.string.action_review_again);
        }
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
            case MENU_SHOW_WHISPER:
            case MENU_SHOW_REVISED:
                Db.get(this).setShowWhisper(note.id, item.getItemId() == MENU_SHOW_WHISPER);
                refresh();
                return true;
            case MENU_REVIEW_AGAIN:
                TranscriptionQueue.get(this).reviewAgain(note.id);
                refresh();
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

    /**
     * Una riga per vocale: mittente, dati del vocale e testo o stato. Con la revisione dell'IA il
     * testo mostra le parole corrette sottolineate, che toccate dicono cosa aveva capito Whisper,
     * e sotto la traduzione.
     */
    private static final class NoteAdapter extends ArrayAdapter<Db.Note> {

        private float touchX;
        private float touchY;

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
            if (n.reviewState == Db.REVIEW_PENDING || n.reviewState == Db.REVIEW_RUNNING) {
                String with = TranscriptionQueue.get(c).reviewingWith(n.id);
                meta.append(" · ").append(with != null ? c.getString(R.string.review_reviewing, with)
                        : c.getString(R.string.review_reviewing_ai));
            } else if (n.reviewState == Db.REVIEW_DONE && n.reviewProvider != null) {
                if (n.hasRevision() && n.showWhisper) meta.append(" · ").append(c.getString(R.string.review_whisper));
                else if (n.hasRevision() || n.translation != null) {
                    meta.append(" · ").append(c.getString(R.string.review_reviewed_by, n.reviewProvider));
                }
            }
            ((TextView) v.findViewById(R.id.note_meta)).setText(meta);
            TextView body = v.findViewById(R.id.note_text);
            body.setOnTouchListener(this::spanTouch);
            View trBox = v.findViewById(R.id.note_translation);
            TextView warn = v.findViewById(R.id.note_warn);
            trBox.setVisibility(View.GONE);
            warn.setVisibility(View.GONE);
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
                    if (n.text == null || n.text.isEmpty()) {
                        body.setText(R.string.empty_transcript);
                    } else if (n.hasRevision() && !n.showWhisper) {
                        body.setText(revised(n));
                    } else if (n.hasRevision()) {
                        body.setText(whisperMarked(n));
                    } else {
                        body.setText(n.text);
                    }
                    if (n.translation != null) {
                        trBox.setVisibility(View.VISIBLE);
                        String lang = n.targetLang != null ? n.targetLang : "";
                        ((TextView) v.findViewById(R.id.note_translation_label))
                                .setText(c.getString(R.string.review_translation, lang));
                        ((TextView) v.findViewById(R.id.note_translation_text)).setText(n.translation);
                    }
                    if (n.reviewState == Db.REVIEW_FAILED) {
                        warn.setVisibility(View.VISIBLE);
                        warn.setText(c.getString(R.string.review_failed, n.reviewError == null ? "" : n.reviewError));
                    }
            }
            return v;
        }

        /** Il testo rivisto, con le parole cambiate sottolineate e toccabili. */
        private CharSequence revised(Db.Note n) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            try {
                JSONArray parts = new JSONArray(n.reviewSegments);
                for (int i = 0; i < parts.length(); i++) {
                    JSONArray p = parts.getJSONArray(i);
                    String text = p.getString(0);
                    int start = sb.length();
                    sb.append(text);
                    if (!p.isNull(1)) {
                        String orig = p.getString(1);
                        sb.setSpan(new FixSpan(orig), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } catch (Exception e) {
                return n.reviewText;
            }
            return sb;
        }

        /** Il testo di Whisper, con evidenziate le parole che la revisione ha cambiato. */
        private CharSequence whisperMarked(Db.Note n) {
            SpannableStringBuilder sb = new SpannableStringBuilder(n.text);
            try {
                JSONArray parts = new JSONArray(n.reviewSegments);
                int from = 0;
                for (int i = 0; i < parts.length(); i++) {
                    JSONArray p = parts.getJSONArray(i);
                    if (p.isNull(1) || p.getString(1).isEmpty()) continue;
                    String orig = p.getString(1);
                    int at = n.text.indexOf(orig, from);
                    if (at < 0) continue;
                    sb.setSpan(new BackgroundColorSpan(0x38FF7864), at, at + orig.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from = at + orig.length();
                }
            } catch (Exception ignored) {
                // Pezzi illeggibili: il testo resta senza evidenziazione.
            }
            return sb;
        }

        /**
         * Tocco sul testo: se cade su una parola corretta mostra il fumetto e trattiene il tocco,
         * altrimenti lo lascia alla riga, che apre il menu come sempre.
         */
        private boolean spanTouch(View view, MotionEvent ev) {
            TextView tv = (TextView) view;
            int action = ev.getActionMasked();
            if ((action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) || !(tv.getText() instanceof Spanned)) {
                return false;
            }
            Layout layout = tv.getLayout();
            if (layout == null) return false;
            int x = (int) ev.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
            int y = (int) ev.getY() - tv.getTotalPaddingTop() + tv.getScrollY();
            int line = layout.getLineForVertical(y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false;
            int off = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = ((Spanned) tv.getText()).getSpans(off, off, ClickableSpan.class);
            if (spans.length == 0) return false;
            if (action == MotionEvent.ACTION_UP) {
                touchX = ev.getRawX();
                touchY = ev.getRawY();
                spans[0].onClick(tv);
            }
            return true;
        }

        /** Parola cambiata dalla revisione: sottolineata nel colore d'accento, toccata mostra l'originale. */
        private final class FixSpan extends ClickableSpan {
            private final String original;

            FixSpan(String original) {
                this.original = original;
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setUnderlineText(true);
                ds.setColor(getContext().getColor(R.color.accent));
            }

            @Override
            public void onClick(View widget) {
                Context c = getContext();
                showTip(widget, original.isEmpty() ? c.getString(R.string.review_fix_added)
                        : c.getString(R.string.review_fix, original));
            }
        }

        private void showTip(View anchor, String text) {
            Context c = getContext();
            float d = c.getResources().getDisplayMetrics().density;
            TextView tip = new TextView(c);
            tip.setText(text);
            tip.setTextColor(0xFFF1F1EB);
            tip.setTextSize(14);
            tip.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF03B3F38);
            bg.setCornerRadius(10 * d);
            tip.setBackground(bg);
            tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            PopupWindow pw = new PopupWindow(tip, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pw.setOutsideTouchable(true);
            pw.setElevation(6 * d);
            int x = Math.max(0, (int) touchX - tip.getMeasuredWidth() / 2);
            int y = Math.max(0, (int) touchY - tip.getMeasuredHeight() - (int) (14 * d));
            tip.setOnClickListener(t -> pw.dismiss());
            pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
            anchor.postDelayed(pw::dismiss, 4000);
        }
    }
}
