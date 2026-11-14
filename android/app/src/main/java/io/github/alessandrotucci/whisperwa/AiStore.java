package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Impostazioni della revisione con l'IA e account collegati, in ordine di preferenza, nelle
 * SharedPreferences "ai". I campi coincidono con ai.json del componente desktop.
 *
 * <p>Le chiavi API sono cifrate con AES-GCM da una chiave dell'Android Keystore, che non esce
 * dal dispositivo: nelle preferenze resta solo "ks:" seguito da IV e testo cifrato in base64.
 * Le letture e le scritture passano tutte da questa classe, sotto il suo lock.
 */
final class AiStore {

    static final int DEFAULT_TIMEOUT_S = 20;
    private static final String FILE = "ai";
    private static final String KEY_ALIAS = "whisperwa-ai-keys";

    /** Un account collegato. key è la chiave in chiaro, presente solo dopo {@link #load}. */
    static final class Account {
        String id;
        String provider;
        String model;
        String baseUrl;
        String sealedKey;
        long added;
        long lastOk;
        String lastErrorCode;
        long lastErrorAt;

        AiProviders.Provider provider() {
            return AiProviders.byId(provider);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject()
                    .put("id", id)
                    .put("provider", provider)
                    .put("model", model)
                    .put("key", sealedKey == null ? "" : sealedKey)
                    .put("added", added)
                    .put("last_ok", lastOk);
            if (baseUrl != null) o.put("base_url", baseUrl);
            if (lastErrorCode != null) {
                o.put("last_error", new JSONObject().put("code", lastErrorCode).put("at", lastErrorAt));
            }
            return o;
        }

        static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id");
            a.provider = o.optString("provider");
            a.model = o.optString("model", null);
            a.baseUrl = o.isNull("base_url") ? null : o.optString("base_url", null);
            a.sealedKey = o.optString("key", "");
            a.added = o.optLong("added");
            a.lastOk = o.optLong("last_ok");
            JSONObject err = o.optJSONObject("last_error");
            if (err != null) {
                a.lastErrorCode = err.optString("code", null);
                a.lastErrorAt = err.optLong("at");
            }
            return a;
        }
    }

    /** Impostazioni e account; target è la lingua della traduzione, null finché non scelta. */
    static final class Data {
        boolean revise = true;
        boolean translate = true;
        String target;
        int timeoutS = DEFAULT_TIMEOUT_S;
        boolean context;
        final List<Account> accounts = new ArrayList<>();

        /** La lingua della traduzione: quella scelta, oppure quella del telefono. */
        String targetOrDefault() {
            return target != null && !target.isEmpty() ? target : Locale.getDefault().getLanguage();
        }

        /** Vero se c'è almeno un account e almeno una delle due funzioni è attiva. */
        boolean active() {
            return !accounts.isEmpty() && (revise || translate);
        }

        /** Vero se una trascrizione in language richiede una chiamata (needs_review del desktop). */
        boolean needsReview(String language) {
            if (!active()) return false;
            if (revise) return true;
            return translate && !AiProviders.baseLang(language).equals(AiProviders.baseLang(targetOrDefault()));
        }
    }

    private static final Object LOCK = new Object();

    private AiStore() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Data load(Context context) {
        synchronized (LOCK) {
            Data d = new Data();
            String raw = sp(context).getString("data", null);
            if (raw == null) return d;
            try {
                JSONObject o = new JSONObject(raw);
                JSONObject s = o.optJSONObject("settings");
                if (s != null) {
                    d.revise = s.optBoolean("revise", true);
                    d.translate = s.optBoolean("translate", true);
                    d.target = s.isNull("target_lang") ? null : s.optString("target_lang", null);
                    d.timeoutS = s.optInt("timeout_s", DEFAULT_TIMEOUT_S);
                    d.context = s.optBoolean("context", false);
                }
                JSONArray accs = o.optJSONArray("accounts");
                for (int i = 0; accs != null && i < accs.length(); i++) {
                    JSONObject a = accs.optJSONObject(i);
                    if (a != null) d.accounts.add(Account.fromJson(a));
                }
            } catch (JSONException ignored) {
                // Dati illeggibili: si riparte da zero.
            }
            return d;
        }
    }

    static void save(Context context, Data d) {
        synchronized (LOCK) {
            try {
                JSONObject s = new JSONObject()
                        .put("revise", d.revise)
                        .put("translate", d.translate)
                        .put("target_lang", d.target == null ? JSONObject.NULL : d.target)
                        .put("timeout_s", d.timeoutS)
                        .put("context", d.context);
                JSONArray accs = new JSONArray();
                for (Account a : d.accounts) accs.put(a.toJson());
                sp(context).edit().putString("data", new JSONObject().put("settings", s).put("accounts", accs).toString())
                        .apply();
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Modifica atomica: legge, applica fn e salva. */
    static void update(Context context, java.util.function.Consumer<Data> fn) {
        synchronized (LOCK) {
            Data d = load(context);
            fn.accept(d);
            save(context, d);
        }
    }

    /** Aggiunge un account in fondo; il primo account accende revisione e traduzione. */
    static Account add(Context context, String providerId, String key, String baseUrl, String model) throws Exception {
        Account a = new Account();
        byte[] rnd = new byte[6];
        new SecureRandom().nextBytes(rnd);
        StringBuilder id = new StringBuilder();
        for (byte b : rnd) id.append(String.format(Locale.ROOT, "%02x", b));
        a.id = id.toString();
        a.provider = providerId;
        a.model = model;
        a.baseUrl = baseUrl == null || baseUrl.trim().isEmpty() ? null : baseUrl.trim();
        a.sealedKey = seal(key);
        a.added = System.currentTimeMillis() / 1000;
        a.lastOk = a.added;
        update(context, d -> {
            if (d.accounts.isEmpty()) {
                d.revise = true;
                d.translate = true;
            }
            if (d.target == null) d.target = Locale.getDefault().getLanguage();
            d.accounts.add(a);
        });
        return a;
    }

    static void remove(Context context, String accountId) {
        update(context, d -> d.accounts.removeIf(a -> a.id.equals(accountId)));
    }

    static void move(Context context, String accountId, int delta) {
        update(context, d -> {
            for (int i = 0; i < d.accounts.size(); i++) {
                if (d.accounts.get(i).id.equals(accountId)) {
                    int j = Math.max(0, Math.min(d.accounts.size() - 1, i + delta));
                    d.accounts.add(j, d.accounts.remove(i));
                    return;
                }
            }
        });
    }

    static void setModel(Context context, String accountId, String model) {
        update(context, d -> {
            for (Account a : d.accounts) if (a.id.equals(accountId)) a.model = model;
        });
    }

    static void mark(Context context, String accountId, String errorCode) {
        update(context, d -> {
            for (Account a : d.accounts) {
                if (!a.id.equals(accountId)) continue;
                long now = System.currentTimeMillis() / 1000;
                if (errorCode == null) {
                    a.lastOk = now;
                    a.lastErrorCode = null;
                    a.lastErrorAt = 0;
                } else {
                    a.lastErrorCode = errorCode;
                    a.lastErrorAt = now;
                }
            }
        });
    }

    // --- cifratura delle chiavi -----------------------------------------------------------------

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    static String seal(String secret) throws Exception {
        if (secret == null || secret.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[1 + iv.length + ct.length];
        all[0] = (byte) iv.length;
        System.arraycopy(iv, 0, all, 1, iv.length);
        System.arraycopy(ct, 0, all, 1 + iv.length, ct.length);
        return "ks:" + Base64.encodeToString(all, Base64.NO_WRAP);
    }

    static String unseal(String sealed) throws Exception {
        if (sealed == null || sealed.isEmpty()) return "";
        if (!sealed.startsWith("ks:")) throw new IllegalArgumentException("formato della chiave sconosciuto");
        byte[] all = Base64.decode(sealed.substring(3), Base64.NO_WRAP);
        int ivLen = all[0];
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, all, 1, ivLen));
        byte[] pt = c.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
