package io.github.alessandrotucci.whisperwa;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * «Aggiungi un account IA»: prima l'elenco dei fornitori, poi, per quello scelto, guida, chiave,
 * verifica e modello. «Collega» prova una revisione d'esempio e solo se riesce salva l'account.
 *
 * <p>OpenRouter offre anche l'accesso con l'account (OAuth con PKCE): la pagina di OpenRouter si
 * apre nel browser e torna a un piccolo server HTTP dell'app su 127.0.0.1, che riceve il codice e
 * lo scambia con una chiave.
 */
public final class AiAddActivity extends Activity {

    private static final String TAG = "whisperwa";
    private static final int OAUTH_TIMEOUT_MS = 5 * 60 * 1000;

    private ListView providers;
    private ScrollView detail;
    private LinearLayout guide;
    private View oauthNote;
    private View oauthRow;
    private View form;
    private TextView urlLabel;
    private EditText url;
    private TextView keyLabel;
    private View keyRow;
    private EditText key;
    private Button verify;
    private TextView verifyStatus;
    private TextView modelLabel;
    private Spinner model;
    private View modelNote;
    private TextView connectStatus;
    private Button connect;

    private AiProviders.Provider current;
    private final List<String> modelIds = new ArrayList<>();
    private volatile ServerSocket oauthServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_add);
        Ui.applyInsets(findViewById(R.id.root));
        setTitle(R.string.ai_dialog_title);

        providers = findViewById(R.id.providers);
        detail = findViewById(R.id.detail);
        guide = findViewById(R.id.guide);
        oauthNote = findViewById(R.id.oauth_note);
        oauthRow = findViewById(R.id.oauth_row);
        form = findViewById(R.id.form);
        urlLabel = findViewById(R.id.url_label);
        url = findViewById(R.id.url);
        keyLabel = findViewById(R.id.key_label);
        keyRow = findViewById(R.id.key_row);
        key = findViewById(R.id.key);
        verify = findViewById(R.id.btn_verify);
        verifyStatus = findViewById(R.id.verify_status);
        modelLabel = findViewById(R.id.model_label);
        model = findViewById(R.id.model);
        modelNote = findViewById(R.id.model_note);
        connectStatus = findViewById(R.id.connect_status);
        connect = findViewById(R.id.btn_connect);

        TextView note = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        note.setPadding(pad, pad, pad, pad / 2);
        note.setText(R.string.ai_pick_note);
        note.setTextAppearance(android.R.style.TextAppearance_Material_Small);
        providers.addHeaderView(note, null, false);
        providers.setAdapter(new ProviderAdapter());
        providers.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof AiProviders.Provider) showDetail((AiProviders.Provider) item);
        });

        findViewById(R.id.btn_paste).setOnClickListener(v -> paste());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> showList());
        findViewById(R.id.btn_oauth).setOnClickListener(v -> startOAuth());
        findViewById(R.id.btn_paste_instead).setOnClickListener(v -> {
            form.setVisibility(View.VISIBLE);
            key.requestFocus();
        });
        verify.setOnClickListener(v -> verify());
        connect.setOnClickListener(v -> connect());
        TextWatcher reset = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (current != null && current.models == null) setModels(null, null);
                verifyStatus.setText("");
            }
        };
        key.addTextChangedListener(reset);
        url.addTextChangedListener(reset);
        AiGuides.refresh(this);
    }

    @Override
    public void onBackPressed() {
        if (detail.getVisibility() == View.VISIBLE) {
            showList();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeOAuthServer();
    }

    private void showList() {
        closeOAuthServer();
        current = null;
        detail.setVisibility(View.GONE);
        providers.setVisibility(View.VISIBLE);
        setTitle(R.string.ai_dialog_title);
    }

    private void showDetail(AiProviders.Provider p) {
        current = p;
        providers.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        detail.scrollTo(0, 0);
        setTitle("custom".equals(p.id) ? getString(R.string.ai_custom_title)
                : p.name + " · " + (p.vendor != null ? p.vendor : getString(R.string.ai_custom_vendor)));
        AiGuides.render(this, guide, p.id);

        oauthNote.setVisibility(p.oauth ? View.VISIBLE : View.GONE);
        oauthRow.setVisibility(p.oauth ? View.VISIBLE : View.GONE);
        form.setVisibility(p.oauth ? View.GONE : View.VISIBLE);

        boolean showUrl = p.needsUrl || p.local;
        urlLabel.setVisibility(showUrl ? View.VISIBLE : View.GONE);
        url.setVisibility(showUrl ? View.VISIBLE : View.GONE);
        url.setText(p.local ? p.baseUrl : "");
        url.setHint(p.urlHint != null ? p.urlHint : p.baseUrl);
        if (p.local) urlLabel.setText(getString(R.string.ai_url_local, p.baseUrl));
        else urlLabel.setText(R.string.ai_url);

        keyLabel.setVisibility(p.noKey ? View.GONE : View.VISIBLE);
        keyRow.setVisibility(p.noKey ? View.GONE : View.VISIBLE);
        key.setText("");
        verifyStatus.setText("");
        connectStatus.setText("");
        if (p.models != null) {
            List<String> ids = new ArrayList<>();
            java.util.Collections.addAll(ids, p.models);
            String best = AiClient.recommended(p, ids);
            ids.remove(best);
            ids.add(0, best);
            setModels(ids, best);
        } else {
            setModels(null, null);
        }
    }

    private void setModels(List<String> ids, String recommended) {
        modelIds.clear();
        List<String> labels = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                modelIds.add(id);
                labels.add(id.equals(recommended) ? getString(R.string.ai_recommended, id) : id);
            }
        }
        model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int vis = modelIds.isEmpty() ? View.GONE : View.VISIBLE;
        modelLabel.setVisibility(vis);
        model.setVisibility(vis);
        modelNote.setVisibility(vis);
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            if (t != null) key.setText(t.toString().trim());
        }
    }

    /** Chiave e indirizzo dai campi, oppure null dopo aver mostrato cosa manca. */
    private String[] inputs(TextView status) {
        AiProviders.Provider p = current;
        String k = key.getText().toString().trim();
        String u = url.getText().toString().trim();
        if (!p.noKey && k.isEmpty()) {
            status.setText(R.string.ai_need_key);
            return null;
        }
        if ((p.needsUrl || p.local) && u.isEmpty()) {
            status.setText(R.string.ai_need_url);
            return null;
        }
        return new String[]{k, (p.needsUrl || p.local) ? u : null};
    }

    private String failure(AiProviders.Provider p, AiClient.AiError e) {
        if ("key".equals(e.code)) return getString(R.string.ai_key_rejected, p.vendor != null ? p.vendor : p.name);
        String text = AiReview.errorText(this, e.code);
        if (!e.detail.isEmpty() && !"timeout".equals(e.code)) text += " (" + e.detail + ")";
        return getString(R.string.ai_verify_failed, text);
    }

    private void verify() {
        AiProviders.Provider p = current;
        String[] in = inputs(verifyStatus);
        if (in == null) return;
        verify.setEnabled(false);
        verifyStatus.setText(R.string.ai_verifying);
        new Thread(() -> {
            String status;
            AiClient.Models m = null;
            try {
                m = AiClient.listModels(p, in[0], in[1], 20000);
                status = m.ids.isEmpty() ? getString(R.string.ai_verify_failed, getString(R.string.ai_no_models))
                        : getString(p.noKey ? R.string.ai_server_ok : R.string.ai_key_ok);
            } catch (AiClient.AiError e) {
                status = failure(p, e);
            }
            AiClient.Models models = m;
            String s = status;
            runOnUiThread(() -> {
                if (current != p) return;
                verify.setEnabled(true);
                verifyStatus.setText(s);
                if (models != null && !models.ids.isEmpty()) setModels(models.ids, models.recommended);
            });
        }, "ai-verify").start();
    }

    private void connect() {
        AiProviders.Provider p = current;
        String[] in = inputs(connectStatus);
        if (in == null) return;
        int sel = model.getSelectedItemPosition();
        String chosen = sel >= 0 && sel < modelIds.size() ? modelIds.get(sel) : null;
        connect.setEnabled(false);
        connectStatus.setText(R.string.ai_connecting);
        new Thread(() -> {
            String error = null;
            try {
                addAccount(p, in[0], in[1], chosen);
            } catch (AiClient.AiError e) {
                error = failure(p, e);
            } catch (Exception e) {
                Log.w(TAG, "collegamento non riuscito", e);
                error = getString(R.string.ai_verify_failed, String.valueOf(e.getMessage()));
            }
            String err = error;
            runOnUiThread(() -> {
                connect.setEnabled(true);
                if (err != null) {
                    connectStatus.setText(err);
                } else {
                    Toast.makeText(this, getString(R.string.ai_connected, p.name), Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }, "ai-connect").start();
    }

    /** Sceglie il modello se manca, prova una revisione d'esempio e salva l'account. */
    private void addAccount(AiProviders.Provider p, String k, String u, String chosen) throws Exception {
        String m = chosen;
        if (m == null) {
            AiClient.Models list = AiClient.listModels(p, k, u, 20000);
            m = list.recommended;
            if (m == null) throw new AiClient.AiError("model", getString(R.string.ai_no_models));
        }
        AiReview.test(p, k, u, m, AiStore.load(this).targetOrDefault(), 30000);
        AiStore.add(this, p.id, k, u, m);
        TranscriptionQueue.get(this).kick();
    }

    // --- OpenRouter: accesso con l'account -------------------------------------------------------

    private void startOAuth() {
        AiProviders.Provider p = current;
        closeOAuthServer();
        try {
            ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(OAUTH_TIMEOUT_MS);
            oauthServer = server;
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            String verifier = Base64.encodeToString(rnd, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String callback = "http://localhost:" + server.getLocalPort() + "/openrouter";
            Uri auth = Uri.parse("https://openrouter.ai/auth").buildUpon()
                    .appendQueryParameter("callback_url", callback)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .build();
            connectStatus.setText(R.string.ai_oauth_wait);
            form.setVisibility(View.VISIBLE);
            new Thread(() -> waitOAuth(p, server, verifier), "ai-oauth").start();
            startActivity(new Intent(Intent.ACTION_VIEW, auth));
        } catch (Exception e) {
            Log.w(TAG, "accesso a OpenRouter non avviato", e);
            connectStatus.setText(getString(R.string.ai_verify_failed, String.valueOf(e.getMessage())));
        }
    }

    private void waitOAuth(AiProviders.Provider p, ServerSocket server, String verifier) {
        String error = null;
        try {
            String code = null;
            while (code == null) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    code = codeFrom(line);
                    String body = code != null ? getString(R.string.ai_oauth_done) : "";
                    byte[] html = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                            + "<body style='font:18px system-ui;padding:32px'><p>" + body + "</p>").getBytes(StandardCharsets.UTF_8);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 " + (code != null ? "200 OK" : "404 Not Found")
                            + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + html.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(html);
                    out.flush();
                }
            }
            String k = AiClient.openRouterExchange(code, verifier);
            addAccount(p, k, null, null);
        } catch (AiClient.AiError e) {
            error = failure(p, e);
        } catch (Exception e) {
            if (oauthServer == server) {
                Log.w(TAG, "accesso a OpenRouter non riuscito", e);
                error = getString(R.string.ai_verify_failed, AiReview.errorText(this, "timeout"));
            }
        } finally {
            try {
                server.close();
            } catch (Exception ignored) {
                // Chiusura del server locale: nessun effetto sull'esito.
            }
        }
        if (oauthServer != server) return;
        oauthServer = null;
        String err = error;
        runOnUiThread(() -> {
            if (isFinishing()) return;
            if (err != null) {
                connectStatus.setText(err);
            } else {
                Toast.makeText(this, getString(R.string.ai_connected, p.name), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    /** Il parametro code dalla prima riga della richiesta, "GET /openrouter?code=… HTTP/1.1". */
    static String codeFrom(String requestLine) throws Exception {
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("/openrouter")) return null;
        int q = parts[1].indexOf('?');
        if (q < 0) return null;
        for (String kv : parts[1].substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("code")) return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
        }
        return null;
    }

    private void closeOAuthServer() {
        ServerSocket s = oauthServer;
        oauthServer = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // Il server può essere già chiuso.
            }
        }
    }

    /** Elenco dei fornitori: sigla colorata, nome e una riga con produttore e tipo di accesso. */
    private final class ProviderAdapter extends ArrayAdapter<AiProviders.Provider> {

        ProviderAdapter() {
            super(AiAddActivity.this, R.layout.item_ai_provider, AiProviders.ALL);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_ai_provider, parent, false);
            AiProviders.Provider p = getItem(position);
            AiSettings.logo(v.findViewById(R.id.prov_logo), p);
            ((TextView) v.findViewById(R.id.prov_name)).setText(p.name);
            String tag;
            if (p.oauth) tag = getString(R.string.ai_tag_oauth);
            else if (p.local) tag = getString(R.string.ai_tag_local);
            else if (p.free) tag = getString(R.string.ai_tag_free);
            else if (p.needsUrl) tag = getString(R.string.ai_tag_url_key);
            else tag = getString(R.string.ai_tag_key);
            String vendor = p.vendor != null ? p.vendor : getString(R.string.ai_custom_vendor);
            String sub = vendor.equals(p.name) || p.local ? tag : vendor + " · " + tag;
            ((TextView) v.findViewById(R.id.prov_sub)).setText(sub);
            return v;
        }
    }
}
