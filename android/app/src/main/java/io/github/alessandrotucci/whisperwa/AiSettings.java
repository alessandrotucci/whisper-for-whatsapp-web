package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sezione «Revisione e traduzione con l'IA» delle Impostazioni: interruttori, lingua della
 * traduzione, account in ordine di preferenza con il loro menu, tempo massimo e contesto della
 * chat. Senza account gli interruttori restano visibili ma spenti.
 */
final class AiSettings {

    private static final int[] TIMEOUTS = {10, 15, 20, 30, 45, 60};

    private final Activity activity;
    private final Switch revise;
    private final Switch translate;
    private final Spinner target;
    private final TextView reviseNote;
    private final TextView emptyNote;
    private final TextView accountsTitle;
    private final LinearLayout accounts;
    private final Button add;
    private final View more;
    private final Spinner timeout;
    private final Switch context;
    private final List<String> targetCodes = new ArrayList<>();
    private boolean binding;

    AiSettings(Activity activity) {
        this.activity = activity;
        revise = activity.findViewById(R.id.ai_revise);
        translate = activity.findViewById(R.id.ai_translate);
        target = activity.findViewById(R.id.ai_target);
        reviseNote = activity.findViewById(R.id.ai_revise_note);
        emptyNote = activity.findViewById(R.id.ai_empty_note);
        accountsTitle = activity.findViewById(R.id.ai_accounts_title);
        accounts = activity.findViewById(R.id.ai_accounts);
        add = activity.findViewById(R.id.btn_ai_add);
        more = activity.findViewById(R.id.ai_more);
        timeout = activity.findViewById(R.id.ai_timeout);
        context = activity.findViewById(R.id.ai_context);

        String phone = Locale.getDefault().getLanguage();
        List<String> labels = new ArrayList<>();
        targetCodes.add(phone);
        labels.add(activity.getString(R.string.ai_translate_into,
                activity.getString(R.string.ai_phone_lang, displayLanguage(phone))));
        List<String> others = new ArrayList<>(AiProviders.LANG_NAMES.keySet());
        others.remove(phone);
        others.sort((x, y) -> displayLanguage(x).compareToIgnoreCase(displayLanguage(y)));
        for (String code : others) {
            targetCodes.add(code);
            labels.add(activity.getString(R.string.ai_translate_into, displayLanguage(code)));
        }
        target.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels));

        List<String> tLabels = new ArrayList<>();
        for (int t : TIMEOUTS) tLabels.add(activity.getString(R.string.ai_timeout_value, t));
        timeout.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, tLabels));

        revise.setOnCheckedChangeListener((b, on) -> {
            if (!binding) AiStore.update(activity, d -> d.revise = on);
        });
        translate.setOnCheckedChangeListener((b, on) -> {
            if (!binding) AiStore.update(activity, d -> d.translate = on);
            target.setEnabled(on && revise.isEnabled());
        });
        context.setOnCheckedChangeListener((b, on) -> {
            if (!binding) AiStore.update(activity, d -> d.context = on);
        });
        target.setOnItemSelectedListener(new Selection(pos -> AiStore.update(activity, d -> d.target = targetCodes.get(pos))));
        timeout.setOnItemSelectedListener(new Selection(pos -> AiStore.update(activity, d -> d.timeoutS = TIMEOUTS[pos])));
        add.setOnClickListener(v -> activity.startActivity(new Intent(activity, AiAddActivity.class)));
    }

    static String displayLanguage(String code) {
        String name = new Locale(code).getDisplayLanguage();
        return name.isEmpty() ? code : name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
    }

    /** Rilegge lo stato e ricostruisce la sezione; da chiamare in onResume. */
    void refresh() {
        AiStore.Data d = AiStore.load(activity);
        boolean has = !d.accounts.isEmpty();
        binding = true;
        revise.setChecked(d.revise);
        translate.setChecked(d.translate);
        context.setChecked(d.context);
        int ti = targetCodes.indexOf(AiProviders.baseLang(d.targetOrDefault()));
        target.setSelection(Math.max(0, ti), false);
        int to = 2;
        for (int i = 0; i < TIMEOUTS.length; i++) if (TIMEOUTS[i] == d.timeoutS) to = i;
        timeout.setSelection(to, false);
        binding = false;

        revise.setEnabled(has);
        translate.setEnabled(has);
        target.setEnabled(has && d.translate);
        float alpha = has ? 1f : 0.45f;
        revise.setAlpha(alpha);
        translate.setAlpha(alpha);
        target.setAlpha(alpha);
        reviseNote.setVisibility(has ? View.VISIBLE : View.GONE);
        emptyNote.setVisibility(has ? View.GONE : View.VISIBLE);
        accountsTitle.setVisibility(has ? View.VISIBLE : View.GONE);
        more.setVisibility(has ? View.VISIBLE : View.GONE);
        add.setText(has ? R.string.ai_add_more : R.string.ai_add);

        accounts.removeAllViews();
        for (int i = 0; i < d.accounts.size(); i++) addRow(d, i);
    }

    private void addRow(AiStore.Data d, int index) {
        AiStore.Account a = d.accounts.get(index);
        AiProviders.Provider p = a.provider();
        if (p == null) return;
        View row = LayoutInflater.from(activity).inflate(R.layout.item_ai_account, accounts, false);
        ((TextView) row.findViewById(R.id.ai_order)).setText(String.valueOf(index + 1));
        logo(row.findViewById(R.id.ai_logo), p);
        TextView name = row.findViewById(R.id.ai_name);
        name.setText(providerTitle(activity, p));
        TextView status = row.findViewById(R.id.ai_status);
        String model = a.model == null ? "" : a.model;
        if (a.lastErrorCode != null && a.lastErrorAt >= a.lastOk) {
            status.setText(activity.getString(R.string.ai_status_err, model, AiReview.errorText(activity, a.lastErrorCode)));
            status.setTextColor(activity.getColor(R.color.err));
        } else if (p.free) {
            status.setText(activity.getString(R.string.ai_status_free, model));
            status.setTextColor(activity.getColor(R.color.ok));
        } else {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(a.lastOk * 1000, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            status.setText(activity.getString(R.string.ai_status_ok, model, ago));
            status.setTextColor(activity.getColor(R.color.ok));
        }
        ImageButton menu = row.findViewById(R.id.ai_menu);
        menu.setOnClickListener(v -> showMenu(v, a, p, index, d.accounts.size()));
        accounts.addView(row);
    }

    static CharSequence providerTitle(Activity c, AiProviders.Provider p) {
        String vendor = p.vendor != null ? p.vendor : c.getString(R.string.ai_custom_vendor);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(p.name);
        int start = sb.length();
        sb.append(" · ").append(vendor);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
        ColorStateList secondary = c.getColorStateList(tv.resourceId != 0 ? tv.resourceId : android.R.color.darker_gray);
        sb.setSpan(new android.text.style.ForegroundColorSpan(secondary.getDefaultColor()), start, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, sb.length(), 0);
        return sb;
    }

    /** Il quadrato colorato con la sigla del fornitore. */
    static void logo(TextView v, AiProviders.Provider p) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.color);
        bg.setCornerRadius(9 * v.getResources().getDisplayMetrics().density);
        v.setBackground(bg);
        v.setText(p.mark);
    }

    private void showMenu(View anchor, AiStore.Account a, AiProviders.Provider p, int index, int count) {
        PopupMenu pm = new PopupMenu(activity, anchor);
        pm.getMenu().add(0, 1, 0, R.string.ai_move_up).setEnabled(index > 0);
        pm.getMenu().add(0, 2, 1, R.string.ai_move_down).setEnabled(index < count - 1);
        pm.getMenu().add(0, 3, 2, R.string.ai_change_model);
        pm.getMenu().add(0, 4, 3, R.string.ai_test);
        pm.getMenu().add(0, 5, 4, R.string.ai_remove);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    AiStore.move(activity, a.id, -1);
                    refresh();
                    return true;
                case 2:
                    AiStore.move(activity, a.id, 1);
                    refresh();
                    return true;
                case 3:
                    changeModel(a, p);
                    return true;
                case 4:
                    test(a, p);
                    return true;
                case 5:
                    new AlertDialog.Builder(activity)
                            .setMessage(activity.getString(R.string.ai_remove_confirm, p.name))
                            .setPositiveButton(R.string.ai_remove, (dlg, w) -> {
                                AiStore.remove(activity, a.id);
                                refresh();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                default:
                    return false;
            }
        });
        pm.show();
    }

    private void changeModel(AiStore.Account a, AiProviders.Provider p) {
        Toast.makeText(activity, R.string.ai_loading_models, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                AiClient.Models m = AiClient.listModels(p, AiStore.unseal(a.sealedKey), a.baseUrl, 20000);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    String[] items = new String[m.ids.size()];
                    int checked = 0;
                    for (int i = 0; i < items.length; i++) {
                        String id = m.ids.get(i);
                        items[i] = id.equals(m.recommended) ? activity.getString(R.string.ai_recommended, id) : id;
                        if (id.equals(a.model)) checked = i;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.ai_model)
                            .setSingleChoiceItems(items, checked, (dlg, which) -> {
                                AiStore.setModel(activity, a.id, m.ids.get(which));
                                dlg.dismiss();
                                refresh();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                String msg = e instanceof AiClient.AiError ? AiReview.errorText(activity, ((AiClient.AiError) e).code)
                        : String.valueOf(e.getMessage());
                activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
            }
        }, "ai-models").start();
    }

    private void test(AiStore.Account a, AiProviders.Provider p) {
        Toast.makeText(activity, R.string.ai_testing, Toast.LENGTH_SHORT).show();
        String targetLang = AiStore.load(activity).targetOrDefault();
        new Thread(() -> {
            String title;
            String body;
            try {
                Object[] r = AiReview.test(p, AiStore.unseal(a.sealedKey), a.baseUrl, a.model, targetLang, 30000);
                JSONObject parsed = (JSONObject) r[1];
                title = activity.getString(R.string.ai_test_ok, String.format(Locale.getDefault(), "%.1f", (Long) r[0] / 1000.0));
                String tr = parsed.optString("translation", "");
                body = parsed.optString("text") + (tr.isEmpty() || "null".equals(tr) ? "" : "\n\n" + tr);
                AiStore.mark(activity, a.id, null);
            } catch (AiClient.AiError e) {
                title = activity.getString(R.string.ai_test_failed, AiReview.errorText(activity, e.code));
                body = e.detail;
                AiStore.mark(activity, a.id, e.code);
            } catch (Exception e) {
                title = activity.getString(R.string.ai_test_failed, AiReview.errorText(activity, "http"));
                body = String.valueOf(e.getMessage());
            }
            String t = title, b = body;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                new AlertDialog.Builder(activity).setTitle(t).setMessage(b)
                        .setPositiveButton(android.R.string.ok, null).show();
                refresh();
            });
        }, "ai-test").start();
    }

    /** OnItemSelectedListener che ignora le selezioni fatte dal codice durante refresh. */
    private final class Selection implements AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer onSelect;

        Selection(java.util.function.IntConsumer onSelect) {
            this.onSelect = onSelect;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!binding) onSelect.accept(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
