"""Pacchetti dell'estensione per i diversi browser, a partire dalla cartella extension/.

La cartella extension/ è il pacchetto per Chrome ed Edge così com'è (si carica anche
non impacchettata durante lo sviluppo). Per Firefox il manifest cambia così:
lo script di sfondo è una pagina di eventi (background.scripts) invece di un service
worker; la pagina delle opzioni si dichiara con options_ui; l'id stabile sta in
browser_specific_settings.gecko al posto della chiave pubblica "key"; spariscono le
voci che Firefox non conosce. channel.js dice "store": Firefox installa solo
estensioni firmate da Mozilla, che le aggiorna, quindi il componente locale non
sostituisce i file dell'estensione.

Il pacchetto "store" è quello per Chrome Web Store, Edge Add-ons e Opera Add-ons: lo stesso
codice di extension/, senza la chiave pubblica (gli store assegnano il proprio id) e con
channel.js "store". In tutti e due i pacchetti per gli store il nome è STORE_NAME.

Uso:
  python tools/build_extension.py firefox|store [--out build/ext/<bersaglio>] [--zip build/installer/x.zip]
"""
import argparse
import json
import os
import shutil
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "extension")

GECKO_ID = "wa-web-transcriber@alessandrotucci.github.io"
STORE_NAME = "WA Web Transcriber, Translator and Reviewer"
GECKO_MIN_VERSION = "142.0"


# In Firefox le API con promesse stanno su browser.*; lo script le mette su chrome.*, che
# il resto del codice usa, prima di ogni altro script dell'estensione.
COMPAT_JS = """// Firefox: le API dell'estensione si usano da chrome.*, con le promesse di browser.*.
if (typeof browser !== 'undefined' && browser.runtime && globalThis.chrome !== browser) {
  try {
    globalThis.chrome = browser;
  } catch (_) {
    /* chrome resta quello di Firefox */
  }
}
"""


def firefox_manifest(m):
    m = dict(m)
    m.pop("key", None)
    m.pop("minimum_chrome_version", None)
    m["background"] = {"scripts": ["compat.js", "channel.js", "background.js", "update.js"]}
    for cs in m.get("content_scripts", []):
        if cs.get("world") != "MAIN":
            cs["js"] = ["compat.js"] + cs["js"]
    if "options_page" in m:
        m["options_ui"] = {"page": m.pop("options_page"), "open_in_tab": True}
    m["browser_specific_settings"] = {
        "gecko": {
            "id": GECKO_ID,
            "strict_min_version": GECKO_MIN_VERSION,
            "data_collection_permissions": {"required": ["none"]},
        }
    }
    return m


def store_manifest(m):
    m = dict(m)
    m.pop("key", None)
    return m


def set_store_name(out):
    """Il nome dell'estensione, in ogni lingua, diventa STORE_NAME."""
    loc = os.path.join(out, "_locales")
    for lang in os.listdir(loc):
        path = os.path.join(loc, lang, "messages.json")
        with open(path, encoding="utf-8") as f:
            msgs = json.load(f)
        msgs["extName"]["message"] = STORE_NAME
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(msgs, f, ensure_ascii=False, indent=2)
            f.write("\n")


def write_channel(out, store_label):
    with open(os.path.join(out, "channel.js"), "w", encoding="utf-8", newline="\n") as f:
        f.write("// Canale di aggiornamento dell'estensione: \"store\", la aggiorna %s.\n"
                "const UPDATE_CHANNEL = 'store';\n" % store_label)


def build(target, out):
    if target not in ("firefox", "store"):
        raise SystemExit("bersaglio sconosciuto: %s" % target)
    if os.path.isdir(out):
        shutil.rmtree(out)
    shutil.copytree(SRC, out, ignore=shutil.ignore_patterns("README.txt"))
    with open(os.path.join(SRC, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    manifest.pop("short_name", None)
    set_store_name(out)
    if target == "store":
        with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(store_manifest(manifest), f, indent=2, ensure_ascii=False)
            f.write("\n")
        write_channel(out, "lo store del browser")
        return out
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(firefox_manifest(manifest), f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(out, "compat.js"), "w", encoding="utf-8", newline="\n") as f:
        f.write(COMPAT_JS)
    for page in ("options.html", "popup.html"):
        path = os.path.join(out, page)
        with open(path, encoding="utf-8") as f:
            html = f.read()
        first = html.index("<script src=")
        html = html[:first] + '<script src="compat.js"></script>\n' + html[first:]
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(html)
    write_channel(out, "Mozilla")
    return out


def make_zip(folder, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(folder):
            for name in sorted(files):
                full = os.path.join(base, name)
                z.write(full, os.path.relpath(full, folder).replace(os.sep, "/"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", choices=["firefox", "store"])
    ap.add_argument("--out")
    ap.add_argument("--zip")
    a = ap.parse_args()
    out = a.out or os.path.join(ROOT, "build", "ext", a.target)
    build(a.target, out)
    print("scritto", os.path.relpath(out, ROOT))
    if a.zip:
        make_zip(out, a.zip)
        print("scritto", a.zip)


if __name__ == "__main__":
    main()
