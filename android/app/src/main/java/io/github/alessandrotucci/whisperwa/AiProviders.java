package io.github.alessandrotucci.whisperwa;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fornitori di IA per la revisione e la traduzione delle trascrizioni: indirizzi, autenticazione,
 * modello consigliato. L'elenco e i suoi campi coincidono con PROVIDERS di host/ai_review.py,
 * così app ed estensione offrono gli stessi fornitori con le stesse regole.
 *
 * <p>Due dialetti: "openai" (chat/completions, usato da quasi tutti) e "anthropic" (messages).
 * prefer sono espressioni regolari, cercate senza distinzione di maiuscole, che scelgono il
 * modello consigliato tra quelli offerti dal fornitore; models, quando c'è, è l'elenco fisso dei
 * modelli per i fornitori che non ne pubblicano uno.
 */
public final class AiProviders {

    public static final class Provider {
        public final String id;
        public final String name;
        public final String vendor;
        public final String dialect;
        public final String baseUrl;
        public final String modelsUrl;
        public final String[] prefer;
        public final String[] models;
        public final int color;
        public final String mark;
        public boolean free;
        public boolean oauth;
        public boolean local;
        public boolean noKey;
        public boolean needsUrl;
        public boolean apiKeyHeader;
        public String urlHint;

        Provider(String id, String name, String vendor, String dialect, String baseUrl, String color, String mark,
                 String[] prefer, String[] models, String modelsUrl) {
            this.id = id;
            this.name = name;
            this.vendor = vendor;
            this.dialect = dialect;
            this.baseUrl = baseUrl;
            this.color = (int) Long.parseLong("ff" + color.substring(1), 16);
            this.mark = mark;
            this.prefer = prefer;
            this.models = models;
            this.modelsUrl = modelsUrl;
        }

        Provider free() {
            free = true;
            return this;
        }

        Provider oauth() {
            oauth = true;
            return this;
        }

        Provider local() {
            local = true;
            noKey = true;
            return this;
        }

        Provider needsUrl(String hint) {
            needsUrl = true;
            urlHint = hint;
            return this;
        }

        Provider apiKeyHeader() {
            apiKeyHeader = true;
            return this;
        }
    }

    private static String[] p(String... patterns) {
        return patterns;
    }

    public static final Provider[] ALL = {
            new Provider("anthropic", "Claude", "Anthropic", "anthropic", "https://api.anthropic.com/v1",
                    "#d97757", "C", p("haiku", "sonnet"), null, null),
            new Provider("openai", "ChatGPT", "OpenAI", "openai", "https://api.openai.com/v1",
                    "#10a37f", "O", p("^gpt-[\\d.]+-mini$", "^gpt-.*-mini$", "mini"), null, null),
            new Provider("gemini", "Gemini", "Google", "openai", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "#4285f4", "G", p("^gemini-[\\d.]+-flash$", "flash(?!.*(lite|image|tts|live))", "flash"), null, null).free(),
            new Provider("xai", "Grok", "xAI", "openai", "https://api.x.ai/v1",
                    "#111111", "X", p("grok.*fast", "grok.*mini", "grok"), null, null),
            new Provider("mistral", "Mistral", "Mistral AI", "openai", "https://api.mistral.ai/v1",
                    "#fa520f", "M", p("^mistral-small-latest$", "mistral-small", "mistral-medium"), null, null).free(),
            new Provider("deepseek", "DeepSeek", "DeepSeek", "openai", "https://api.deepseek.com/v1",
                    "#4d6bfe", "D", p("^deepseek-chat$"), null, null),
            new Provider("qwen", "Qwen", "Alibaba Cloud", "openai", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "#615ced", "Q", p("^qwen-flash$", "^qwen-plus$", "qwen.*(flash|plus|turbo)"), null, null),
            new Provider("moonshot", "Kimi", "Moonshot AI", "openai", "https://api.moonshot.ai/v1",
                    "#000000", "K", p("kimi.*turbo", "kimi-k2", "moonshot"), null, null),
            new Provider("zai", "GLM", "Z.ai", "openai", "https://api.z.ai/api/paas/v4",
                    "#3859ff", "Z", p("glm.*flash", "glm.*air", "glm"),
                    new String[]{"glm-4.5-flash", "glm-4.5-air", "glm-4.6"}, null),
            new Provider("perplexity", "Perplexity", "Perplexity", "openai", "https://api.perplexity.ai",
                    "#20808d", "P", p("^sonar$"), new String[]{"sonar", "sonar-pro"}, null),
            new Provider("cohere", "Cohere", "Cohere", "openai", "https://api.cohere.ai/compatibility/v1",
                    "#39594d", "Co", p("command-a", "command-r"),
                    new String[]{"command-a-03-2025", "command-r7b-12-2024"}, null),
            new Provider("groq", "Groq", "Groq", "openai", "https://api.groq.com/openai/v1",
                    "#f55036", "Gq", p("llama-3\\.3-70b", "gpt-oss-120b", "70b"), null, null).free(),
            new Provider("cerebras", "Cerebras", "Cerebras", "openai", "https://api.cerebras.ai/v1",
                    "#f05a28", "Ce", p("gpt-oss-120b", "llama-3\\.3-70b", "llama"), null, null).free(),
            new Provider("github", "GitHub Models", "GitHub", "openai", "https://models.github.ai/inference",
                    "#24292f", "GH", p("gpt-[\\d.]+-mini$", "mini"), null, "https://models.github.ai/catalog/models").free(),
            new Provider("openrouter", "OpenRouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1",
                    "#6467f2", "OR", p("^google/gemini-[\\d.]+-flash$", "gemini.*flash", "haiku", "mini"), null, null).oauth(),
            new Provider("together", "Together AI", "Together AI", "openai", "https://api.together.xyz/v1",
                    "#0f6fff", "T", p("Llama-3\\.3-70B-Instruct-Turbo", "70B.*Instruct"), null, null),
            new Provider("fireworks", "Fireworks", "Fireworks AI", "openai", "https://api.fireworks.ai/inference/v1",
                    "#6720ff", "F", p("llama-v3p3-70b-instruct", "gpt-oss-120b", "instruct"), null, null),
            new Provider("azure", "Azure OpenAI", "Microsoft", "openai", "",
                    "#0078d4", "Az", p("mini"), null, null)
                    .apiKeyHeader().needsUrl("https://<risorsa>.openai.azure.com/openai/v1"),
            new Provider("ollama", "Ollama", "Ollama", "openai", "http://192.168.1.10:11434/v1",
                    "#333333", "Ol", p("qwen", "llama", "gemma", "mistral"), null, null).local(),
            new Provider("lmstudio", "LM Studio", "LM Studio", "openai", "http://192.168.1.10:1234/v1",
                    "#4338ca", "LM", p("qwen", "llama", "gemma", "mistral"), null, null).local(),
            new Provider("custom", "Altro", null, "openai", "",
                    "#59636e", "…", p(), null, null).needsUrl("https://…/v1"),
    };

    /** Modelli che non generano testo: esclusi dagli elenchi. */
    static final Pattern NOT_CHAT = Pattern.compile("embed|tts|whisper|dall-?e|image|moderation|audio|realtime|transcri|"
            + "rerank|guard|computer-use|sora|veo|imagen|ocr|search-preview|codex|live", Pattern.CASE_INSENSITIVE);

    /** Nomi inglesi delle lingue, usati nel prompt. */
    static final Map<String, String> LANG_NAMES;

    static {
        Map<String, String> m = new HashMap<>();
        String[] pairs = {"it", "Italian", "en", "English", "pt", "Portuguese", "es", "Spanish", "fr", "French",
                "de", "German", "nl", "Dutch", "ro", "Romanian", "pl", "Polish", "ru", "Russian", "uk", "Ukrainian",
                "el", "Greek", "tr", "Turkish", "ar", "Arabic", "he", "Hebrew", "hi", "Hindi", "zh", "Chinese",
                "ja", "Japanese", "ko", "Korean", "sq", "Albanian", "sr", "Serbian", "hr", "Croatian", "cs", "Czech",
                "hu", "Hungarian", "sv", "Swedish", "da", "Danish", "no", "Norwegian", "fi", "Finnish",
                "ca", "Catalan", "id", "Indonesian", "vi", "Vietnamese", "th", "Thai", "fa", "Persian",
                "bn", "Bengali", "ur", "Urdu", "tl", "Tagalog"};
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        LANG_NAMES = Collections.unmodifiableMap(m);
    }

    private AiProviders() {
    }

    public static Provider byId(String id) {
        for (Provider p : ALL) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** Il codice di lingua senza regione e in minuscolo: "pt-BR" diventa "pt". */
    static String baseLang(String code) {
        if (code == null) return "";
        int dash = code.indexOf('-');
        return (dash >= 0 ? code.substring(0, dash) : code).toLowerCase(java.util.Locale.ROOT);
    }

    static String langName(String code) {
        String name = LANG_NAMES.get(baseLang(code));
        return name != null ? name : (code == null ? "" : code);
    }
}
