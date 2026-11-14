"""Genera docs/ai-keys/README.md (inglese) e README.it.md (italiano) da extension/ai-guides.json.

Le guide stanno in un solo file, ai-guides.json, letto anche dalla finestra di
collegamento dell'estensione; le pagine del repository ne sono la versione da
leggere a schermo intero. Un'immagine compare solo se il file esiste in
docs/ai-keys/img.

Uso: python tools/ai_guides.py
"""
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, os.path.join(ROOT, "host"))
import ai_review  # noqa: E402

TEXT = {
    "it": {
        "title": "Come ottenere una chiave API",
        "intro": ("Per la revisione e la traduzione dei vocali l'estensione usa un account di IA scelto da te. "
                  "Gli abbonamenti alle chat (Claude Pro, ChatGPT Plus, Gemini Advanced, SuperGrok) non valgono per le "
                  "applicazioni esterne: serve una chiave API dello stesso fornitore, che si paga a consumo e a parte. "
                  "Un vocale costa una frazione di centesimo. Alcuni fornitori hanno un livello gratuito; Ollama e "
                  "LM Studio girano sul tuo computer, senza account."),
        "where": "La chiave si incolla in Configurazione e guida → Revisione e traduzione con l'IA → Aggiungi un account.",
        "minutes": "circa %d minuti",
        "card": "serve una carta di credito",
        "nocard": "non serve una carta",
        "local": "nessun account",
        "free": "gratuito con limiti",
        "other": "English version",
        "other_file": "README.md",
    },
    "en": {
        "title": "How to get an API key",
        "intro": ("For reviewing and translating voice messages the extension uses an AI account of your choice. "
                  "Chat subscriptions (Claude Pro, ChatGPT Plus, Gemini Advanced, SuperGrok) do not apply to external "
                  "apps: you need an API key from the same provider, billed by usage and separately. A voice message "
                  "costs a fraction of a cent. Some providers have a free tier; Ollama and LM Studio run on your "
                  "computer, no account needed."),
        "where": "Paste the key in Setup and guide → AI review and translation → Add an account.",
        "minutes": "about %d minutes",
        "card": "a credit card is needed",
        "nocard": "no card needed",
        "local": "no account",
        "free": "free with limits",
        "other": "Versione italiana",
        "other_file": "README.it.md",
    },
}


def main():
    with open(os.path.join(ROOT, "extension", "ai-guides.json"), encoding="utf-8") as f:
        guides = json.load(f)
    img_dir = os.path.join(ROOT, "docs", "ai-keys", "img")
    for lang, names in (("en", "README.md"), ("it", "README.it.md")):
        T = TEXT[lang]
        out = ["# " + T["title"], "", "[%s](%s)" % (T["other"], T["other_file"]), "", T["intro"], "", T["where"], ""]
        out.append(" · ".join("[%s](#%s)" % (p["name"] if p["id"] != "custom" else ("Altro" if lang == "it" else "Other"), p["id"])
                              for p in ai_review.PROVIDERS if p["id"] in guides["providers"]))
        out.append("")
        for p in ai_review.PROVIDERS:
            g = guides["providers"].get(p["id"])
            if not g:
                continue
            name = p["name"] if p["id"] != "custom" else ("Altro servizio compatibile OpenAI" if lang == "it" else "Other OpenAI-compatible service")
            vendor = "" if p["id"] == "custom" else " · " + p["vendor"]
            out.append('<a id="%s"></a>' % p["id"])
            out.append("")
            out.append("## %s%s" % (name, vendor))
            out.append("")
            meta = [T["minutes"] % g.get("minutes", 3)]
            meta.append(T["card"] if g.get("card") else T["local"] if p.get("local") else T["nocard"])
            if p.get("free"):
                meta.append(T["free"])
            out.append("*" + " · ".join(meta) + "*")
            out.append("")
            for i, s in enumerate(g["steps"], 1):
                title = s["title"].get(lang) or s["title"]["en"]
                text = s["text"].get(lang) or s["text"]["en"]
                out.append("%d. **%s**  " % (i, title))
                line = "   " + text
                if s.get("url"):
                    line += " <%s>" % s["url"]
                out.append(line)
                if s.get("img") and os.path.isfile(os.path.join(img_dir, s["img"])):
                    out.append("")
                    out.append("   ![](img/%s)" % s["img"])
                out.append("")
        path = os.path.join(ROOT, "docs", "ai-keys", names)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(out).rstrip() + "\n")
        print("scritto", os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
