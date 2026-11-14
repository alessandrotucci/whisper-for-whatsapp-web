package io.github.alessandrotucci.whisperwa;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Richieste HTTP ai fornitori di IA: elenco dei modelli e una richiesta di testo, nei dialetti
 * "openai" e "anthropic". Ogni chiamata blocca il thread che la fa e solleva {@link AiError}.
 */
final class AiClient {

    static final String USER_AGENT = "whisper-for-whatsapp-android";

    /** Errore di un fornitore; code è uno tra key, rate, timeout, network, model, http, format. */
    static final class AiError extends Exception {
        final String code;
        final String detail;

        AiError(String code, String detail) {
            super(code + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** Elenco dei modelli di testo, con il consigliato per primo; recommended può essere null. */
    static final class Models {
        final List<String> ids;
        final String recommended;

        Models(List<String> ids, String recommended) {
            this.ids = ids;
            this.recommended = recommended;
        }
    }

    private AiClient() {
    }

    private static Object http(String method, String url, Map<String, String> headers, JSONObject body, int timeoutMs)
            throws AiError {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                String text = read(c.getErrorStream());
                if (code == 401 || code == 403) throw new AiError("key", "HTTP " + code);
                if (code == 429) throw new AiError("rate", "HTTP 429");
                if (code == 404) throw new AiError("model", "HTTP 404 " + shortError(text));
                throw new AiError("http", "HTTP " + code + " " + shortError(text));
            }
            String text = read(c.getInputStream());
            try {
                String t = text.trim();
                return t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
            } catch (JSONException e) {
                throw new AiError("format", "risposta non JSON");
            }
        } catch (SocketTimeoutException e) {
            throw new AiError("timeout", null);
        } catch (IOException e) {
            throw new AiError("network", String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }

    private static String shortError(String text) {
        String t = text == null ? "" : text.trim();
        try {
            Object j = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
            if (j instanceof JSONArray && ((JSONArray) j).length() > 0) j = ((JSONArray) j).opt(0);
            if (j instanceof JSONObject) {
                Object err = ((JSONObject) j).opt("error");
                if (err instanceof JSONObject) {
                    String msg = ((JSONObject) err).optString("message", err.toString());
                    return cut(msg, 200);
                }
                if (err != null) return cut(err.toString(), 200);
            }
            return cut(j.toString(), 200);
        } catch (JSONException e) {
            return cut(t, 200);
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, String> authHeaders(AiProviders.Provider p, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        if (p.noKey && (key == null || key.isEmpty())) return h;
        if ("anthropic".equals(p.dialect)) {
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
            return h;
        }
        if (p.apiKeyHeader) {
            h.put("api-key", key);
            return h;
        }
        h.put("Authorization", "Bearer " + key);
        if ("openrouter".equals(p.id)) {
            h.put("HTTP-Referer", "https://github.com/alessandrotucci/whisper-for-whatsapp-web");
            h.put("X-Title", "Whisper for WhatsApp");
        }
        return h;
    }

    private static String base(AiProviders.Provider p, String baseUrl) throws AiError {
        String url = baseUrl != null && !baseUrl.trim().isEmpty() ? baseUrl.trim() : p.baseUrl;
        if (url == null) url = "";
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isEmpty()) throw new AiError("http", "indirizzo mancante");
        return url;
    }

    /** Modelli di testo del fornitore, ordinati come in list_models del componente desktop. */
    static Models listModels(AiProviders.Provider p, String key, String baseUrl, int timeoutMs) throws AiError {
        List<String> ids = new ArrayList<>();
        if (p.models != null && p.modelsUrl == null) {
            Collections.addAll(ids, p.models);
        } else {
            String url = p.modelsUrl != null ? p.modelsUrl : base(p, baseUrl) + "/models";
            Object data = http("GET", url, authHeaders(p, key), null, timeoutMs);
            JSONArray items = null;
            if (data instanceof JSONObject) {
                items = ((JSONObject) data).optJSONArray("data");
                if (items == null) items = ((JSONObject) data).optJSONArray("models");
            } else if (data instanceof JSONArray) {
                items = (JSONArray) data;
            }
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                Object it = items.opt(i);
                String mid;
                if (it instanceof JSONObject) {
                    JSONObject o = (JSONObject) it;
                    mid = o.optString("id", "");
                    if (mid.isEmpty()) mid = o.optString("name", "");
                } else {
                    mid = String.valueOf(it);
                }
                if (mid.isEmpty()) continue;
                if (mid.startsWith("models/")) mid = mid.substring("models/".length());
                if (AiProviders.NOT_CHAT.matcher(mid).find()) continue;
                set.add(mid);
            }
            ids.addAll(set);
            ids.sort((x, y) -> y.toLowerCase(Locale.ROOT).compareTo(x.toLowerCase(Locale.ROOT)));
        }
        String best = recommended(p, ids);
        if (best != null) {
            ids.remove(best);
            ids.add(0, best);
        }
        return new Models(ids, best);
    }

    static String recommended(AiProviders.Provider p, List<String> ids) {
        for (String pattern : p.prefer) {
            Pattern rx = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            List<String> hits = new ArrayList<>();
            for (String m : ids) {
                if (rx.matcher(m).find() && !m.contains("preview") && !m.contains("exp")) hits.add(m);
            }
            if (hits.isEmpty()) {
                for (String m : ids) if (rx.matcher(m).find()) hits.add(m);
            }
            if (!hits.isEmpty()) {
                // Senza data di creazione il nome più corto è di solito l'alias stabile.
                hits.sort((x, y) -> x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y));
                return hits.get(0);
            }
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Una richiesta di testo con prompt di sistema e messaggio utente; restituisce la risposta. */
    static String chat(AiProviders.Provider p, String key, String baseUrl, String model, String system, String user,
                       int timeoutMs) throws AiError {
        Map<String, String> headers = authHeaders(p, key);
        try {
            if ("anthropic".equals(p.dialect)) {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("max_tokens", 4096)
                        .put("temperature", 0)
                        .put("system", system)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
                Object data = http("POST", base(p, baseUrl) + "/messages", headers, body, timeoutMs);
                StringBuilder out = new StringBuilder();
                JSONArray content = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("content") : null;
                for (int i = 0; content != null && i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c != null && "text".equals(c.optString("type"))) out.append(c.optString("text"));
                }
                return out.toString();
            }
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", 0);
            String url = base(p, baseUrl) + "/chat/completions";
            Object data;
            try {
                data = http("POST", url, headers, body, timeoutMs);
            } catch (AiError e) {
                // Alcuni modelli di ragionamento accettano solo la temperatura predefinita.
                if (!"http".equals(e.code) || !e.detail.toLowerCase(Locale.ROOT).contains("temperature")) throw e;
                body.remove("temperature");
                data = http("POST", url, headers, body, timeoutMs);
            }
            try {
                JSONObject msg = ((JSONObject) data).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                return msg.isNull("content") ? "" : msg.optString("content", "");
            } catch (JSONException | ClassCastException e) {
                throw new AiError("format", "risposta senza testo");
            }
        } catch (JSONException e) {
            throw new AiError("format", String.valueOf(e.getMessage()));
        }
    }

    /** Scambia il codice di accesso di OpenRouter (PKCE) con una chiave. */
    static String openRouterExchange(String code, String verifier) throws AiError {
        try {
            JSONObject body = new JSONObject()
                    .put("code", code)
                    .put("code_verifier", verifier)
                    .put("code_challenge_method", "S256");
            Object data = http("POST", "https://openrouter.ai/api/v1/auth/keys", new LinkedHashMap<>(), body, 30000);
            String key = data instanceof JSONObject ? ((JSONObject) data).optString("key", "") : "";
            if (key.isEmpty()) throw new AiError("key", "OpenRouter non ha restituito una chiave");
            return key;
        } catch (JSONException e) {
            throw new AiError("format", String.valueOf(e.getMessage()));
        }
    }
}
