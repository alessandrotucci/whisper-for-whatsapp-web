"""Controlli di coerenza delle versioni prima di una release, e release.json per lo zip.

version.json nella radice del repository è la fonte che l'estensione legge da GitHub
per decidere cosa aggiornare: "extension" è la versione dell'estensione, "host"
quella del componente locale (host/ e installer/ insieme, perché il componente
arriva sempre con il suo installer).

Vincoli verificati:
  - il tag vale "v" + version.json.extension, ed è uguale alla versione di manifest.json;
  - version.json.host è uguale a HOST_VERSION in host/whisper_host.py;
  - se host/ o installer/ sono cambiati dal tag precedente, la versione del componente
    è cambiata anch'essa: altrimenti chi ha il componente vecchio non lo aggiornerebbe.

Con --release-json PERCORSO scrive il marcatore incluso nello zip dell'estensione:
versione ed elenco dei file. Il componente aggiorna solo cartelle che lo contengono.

Uso: python tools/release_check.py --tag v1.3.0 [--release-json build/release.json]
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)


def host_version_from(text):
    m = re.search(r'^HOST_VERSION = "([^"]+)"', text, re.M)
    return m.group(1) if m else None


def fail(msg):
    print("ERRORE: " + msg)
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--release-json")
    a = ap.parse_args()

    versions = json.load(open(os.path.join(ROOT, "version.json"), encoding="utf-8"))
    manifest = json.load(open(os.path.join(ROOT, "extension", "manifest.json"), encoding="utf-8"))
    host_src = open(os.path.join(ROOT, "host", "whisper_host.py"), encoding="utf-8").read()
    host_v = host_version_from(host_src)

    if a.tag.startswith("v"):
        if a.tag != "v" + versions["extension"]:
            fail("tag %s diverso da version.json.extension %s" % (a.tag, versions["extension"]))
    if manifest["version"] != versions["extension"]:
        fail("manifest.json %s diverso da version.json.extension %s" % (manifest["version"], versions["extension"]))
    if host_v != versions["host"]:
        fail("HOST_VERSION %s diverso da version.json.host %s" % (host_v, versions["host"]))

    prev = git("describe", "--tags", "--abbrev=0", "--match", "v*", a.tag + "^").stdout.strip()
    if prev:
        changed = git("diff", "--name-only", prev, a.tag, "--", "host", "installer").stdout.split()
        changed = [f for f in changed if not f.endswith(".md")]
        prev_host = None
        prev_versions = git("show", prev + ":version.json")
        if prev_versions.returncode == 0:
            prev_host = json.loads(prev_versions.stdout).get("host")
        else:
            prev_host = host_version_from(git("show", prev + ":host/whisper_host.py").stdout)
        if changed and prev_host == versions["host"]:
            fail("dal tag %s sono cambiati %s ma la versione del componente resta %s" % (prev, ", ".join(changed), prev_host))
        print("tag precedente %s: componente %s -> %s, file cambiati: %d" % (prev, prev_host, versions["host"], len(changed)))

    if a.release_json:
        ext_dir = os.path.join(ROOT, "extension")
        files = []
        for base, _dirs, names in os.walk(ext_dir):
            for n in names:
                files.append(os.path.relpath(os.path.join(base, n), ext_dir).replace(os.sep, "/"))
        os.makedirs(os.path.dirname(os.path.abspath(a.release_json)), exist_ok=True)
        with open(a.release_json, "w", encoding="utf-8") as f:
            json.dump({"version": versions["extension"], "files": sorted(files + ["release.json"])}, f, indent=2)
        print("scritto %s (%d file)" % (a.release_json, len(files) + 1))
    print("versioni coerenti: estensione %s, componente %s" % (versions["extension"], versions["host"]))


if __name__ == "__main__":
    main()
