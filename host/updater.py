"""Aggiornamento del componente locale e dell'estensione dalle release su GitHub.

Si scarica solo da RELEASE_PREFIX, cioè dagli asset delle release del repository.

Componente locale su Windows: l'installer viene scaricato in una cartella
temporanea e avviato in modalità silenziosa, staccato da questo processo.
L'installer termina tutti i whisper_host.exe prima di copiare i file, questo
compreso; il browser avvia la versione nuova alla richiesta successiva.

Componente locale su Linux: si scaricano il tarball e SHA256SUMS della stessa
release, si verifica l'impronta, si estrae il tarball e si avvia staccato il suo
install.sh con --update, che alla fine cancella la cartella estratta (WWT_CLEAN_AFTER). Lo script copia la versione nuova accanto a quella in uso e
sposta il link "current" a cui puntano i manifest: i processi in corso continuano con
i file vecchi, il browser avvia la versione nuova alla richiesta successiva.

Estensione: le cartelle da aggiornare sono quelle da cui i browser Chromium
caricano l'estensione decompressa (campo "path" nelle preferenze di ogni profilo, con
location 4) e che contengono release.json, il marcatore presente solo negli zip
delle release. Una cartella senza marcatore, come quella del repository, non si
tocca mai. Lo zip sostituisce i file della cartella e rimuove quelli elencati nel
release.json precedente che la versione nuova non ha più.
"""

import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

RELEASE_PREFIX = "https://github.com/alessandrotucci/whisper-for-whatsapp-web/releases/download/"
USER_AGENT = "whisper-for-whatsapp-web-updater"
MARKER = "release.json"
UNPACKED_LOCATION = 4

# Cartelle dati dei browser Chromium: sotto %LOCALAPPDATA% su Windows, sotto
# XDG_CONFIG_HOME (~/.config) su Linux.
if os.name == "nt":
    BROWSER_DATA_DIRS = [
        ("chrome", os.path.join("Google", "Chrome", "User Data")),
        ("edge", os.path.join("Microsoft", "Edge", "User Data")),
    ]
else:
    BROWSER_DATA_DIRS = [
        ("chrome", "google-chrome"),
        ("chromium", "chromium"),
        ("edge", "microsoft-edge"),
        ("brave", os.path.join("BraveSoftware", "Brave-Browser")),
        ("vivaldi", "vivaldi"),
        ("opera", "opera"),
    ]

LINUX_PACKAGE_DIR = "whisper-for-whatsapp-web"
CHECKSUMS = "SHA256SUMS"


def check_url(url):
    if not isinstance(url, str) or not url.startswith(RELEASE_PREFIX):
        raise ValueError("indirizzo non ammesso: %s" % url)


def download(url, dest, on_progress=None):
    """Scarica url in dest; on_progress(scaricati, totale) al più ogni mezzo secondo."""
    check_url(url)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last = 0.0
    with urllib.request.urlopen(req, timeout=60) as resp, open(dest + ".part", "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0) or None
        done = 0
        while True:
            chunk = resp.read(256 * 1024)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            now = time.time()
            if on_progress and now - last >= 0.5:
                last = now
                on_progress(done, total)
    os.replace(dest + ".part", dest)
    if on_progress:
        on_progress(os.path.getsize(dest), total)
    return dest


def work_dir():
    """Cartella di lavoro: la temporanea di sistema su Windows, la cache dell'utente altrove.

    Fuori da Windows la cartella temporanea è condivisa tra utenti e un nome fisso
    lì si potrebbe occupare prima del componente.
    """
    if os.name == "nt":
        path = os.path.join(tempfile.gettempdir(), "whisper-for-whatsapp-web-update")
    else:
        cache = os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache")
        path = os.path.join(cache, "whisper-for-whatsapp-web-update")
    os.makedirs(path, mode=0o700, exist_ok=True)
    return path


def start_update(url, on_progress, data_dir):
    """Avvia l'aggiornamento del componente adatto al sistema; restituisce il pid."""
    if os.name == "nt":
        return start_installer(url, on_progress)
    if sys.platform.startswith("linux"):
        return start_tarball(url, on_progress, data_dir)
    raise RuntimeError("aggiornamento del componente non previsto su %s" % sys.platform)


def start_installer(url, on_progress=None):
    """Scarica l'installer e lo avvia staccato, senza finestre; restituisce il pid."""
    exe = download(url, os.path.join(work_dir(), "WhisperForWhatsAppWeb-Setup.exe"), on_progress)
    args = [exe, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-"]
    base = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    try:
        proc = subprocess.Popen(args, creationflags=base | subprocess.CREATE_BREAKAWAY_FROM_JOB, close_fds=True,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        # Un job che non ammette l'uscita: l'installer resta nel job del browser.
        proc = subprocess.Popen(args, creationflags=base, close_fds=True,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return proc.pid


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_sha256(sums_path, name):
    """Impronta di name nel file SHA256SUMS (righe "impronta  nome", formato di sha256sum)."""
    with open(sums_path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("*") == name:
                return parts[0].lower()
    raise RuntimeError("%s non è elencato in %s" % (name, CHECKSUMS))


def start_tarball(url, on_progress, data_dir):
    """Scarica e verifica il tarball, avvia staccato il suo install.sh --update; restituisce il pid."""
    import posix_support

    work = work_dir()
    name = url.rsplit("/", 1)[1]
    tgz = download(url, os.path.join(work, name), on_progress)
    sums = download(url.rsplit("/", 1)[0] + "/" + CHECKSUMS, os.path.join(work, CHECKSUMS))
    actual = sha256_of(tgz)
    if actual != expected_sha256(sums, name):
        raise RuntimeError("impronta di %s diversa da quella della release (%s)" % (name, actual[:16]))
    target = os.path.join(work, "unpacked")
    shutil.rmtree(target, ignore_errors=True)
    with tarfile.open(tgz) as t:
        t.extractall(target, filter="data")
    os.remove(tgz)
    script = os.path.join(target, LINUX_PACKAGE_DIR, "install.sh")
    if not os.path.isfile(script):
        raise RuntimeError("install.sh mancante nel tarball")
    os.makedirs(data_dir, exist_ok=True)
    with open(os.path.join(data_dir, "install.log"), "ab") as log:
        proc = subprocess.Popen(["/bin/sh", script, "--update"], stdin=subprocess.DEVNULL, stdout=log,
                                stderr=subprocess.STDOUT, close_fds=True, start_new_session=True,
                                cwd=target, env=dict(posix_support.clean_env(), WWT_CLEAN_AFTER=target))
    return proc.pid


def extension_dirs(ext_id):
    """Cartelle da cui i profili dei browser Chromium caricano l'estensione decompressa.

    Restituisce [(browser, profilo, cartella, ha_marcatore)], senza duplicati di cartella.
    """
    if os.name == "nt":
        base = os.environ.get("LOCALAPPDATA") or ""
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    seen = {}
    for browser, rel in BROWSER_DATA_DIRS:
        root = os.path.join(base, rel)
        for prefs in glob.glob(os.path.join(root, "*", "Secure Preferences")) + glob.glob(os.path.join(root, "*", "Preferences")):
            try:
                with open(prefs, encoding="utf-8") as f:
                    data = json.load(f)
            except (OSError, ValueError):
                continue
            entry = (((data.get("extensions") or {}).get("settings") or {}).get(ext_id)) or {}
            path = entry.get("path")
            if entry.get("location") != UNPACKED_LOCATION or not path or not os.path.isdir(path):
                continue
            key = os.path.normcase(os.path.abspath(path))
            if key not in seen:
                profile = os.path.basename(os.path.dirname(prefs))
                seen[key] = (browser, profile, path, os.path.isfile(os.path.join(path, MARKER)))
    return list(seen.values())


def _safe_target(root, name):
    target = os.path.abspath(os.path.join(root, name))
    if os.path.commonpath([target, os.path.abspath(root)]) != os.path.abspath(root):
        raise ValueError("percorso fuori dalla cartella: %s" % name)
    return target


def update_extension(url, ext_id, expect_version, on_progress=None):
    """Aggiorna ogni cartella con marcatore; restituisce (aggiornate, saltate)."""
    targets = extension_dirs(ext_id)
    updatable = [t for t in targets if t[3]]
    skipped = [t[2] for t in targets if not t[3]]
    if not updatable:
        raise RuntimeError("nessuna cartella dell'estensione con %s da aggiornare" % MARKER)
    zpath = download(url, os.path.join(work_dir(), "extension.zip"), on_progress)
    with zipfile.ZipFile(zpath) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        if "manifest.json" not in names or MARKER not in names:
            raise RuntimeError("zip senza manifest.json o %s" % MARKER)
        manifest = json.loads(z.read("manifest.json").decode("utf-8-sig"))
        if expect_version and manifest.get("version") != expect_version:
            raise RuntimeError("lo zip contiene la versione %s, attesa %s" % (manifest.get("version"), expect_version))
        new_files = set(json.loads(z.read(MARKER).decode("utf-8")).get("files") or names)
        done = []
        for _browser, _profile, root, _marker in updatable:
            try:
                with open(os.path.join(root, MARKER), encoding="utf-8") as f:
                    old_files = set(json.load(f).get("files") or [])
            except (OSError, ValueError):
                old_files = set()
            staging = os.path.join(work_dir(), "staging")
            shutil.rmtree(staging, ignore_errors=True)
            for n in names:
                z.extract(n, staging)
            for n in names:
                src = os.path.join(staging, n)
                dst = _safe_target(root, n)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(src, dst)
            for n in sorted(old_files - new_files):
                try:
                    os.remove(_safe_target(root, n))
                except OSError:
                    pass
            shutil.rmtree(staging, ignore_errors=True)
            done.append(root)
    return done, skipped
