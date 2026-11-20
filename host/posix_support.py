"""Funzioni di sistema del componente su Linux e macOS.

Il componente gira come utente normale, avviato dal browser. Qui stanno le parti
che su Windows passano da API proprie: dove tenere dati e socket, quale browser ha
avviato il relay, la finestra per scegliere una cartella, l'ambiente dei processi
esterni.

Un eseguibile di PyInstaller su Linux imposta LD_LIBRARY_PATH sulla propria
cartella e conserva il valore originale in LD_LIBRARY_PATH_ORIG; i programmi di
sistema avviati dal componente ricevono l'ambiente originale (clean_env), perché le
librerie del pacchetto non sono quelle con cui sono stati compilati.
"""

import os
import shutil
import subprocess
import sys
import tempfile

APP_DIR = "WhisperForWhatsAppWeb"

# sun_path vale 108 byte su Linux e 104 su macOS, terminatore compreso.
SOCKET_PATH_MAX = 100

# Processi che stanno tra il browser e il relay senza esserne il browser.
NOT_BROWSERS = {"sh", "bash", "dash", "zsh", "env", "flatpak-spawn", "whisper_host"}


def data_dir():
    """Cartella dati dell'utente: XDG su Linux, Application Support su macOS."""
    if sys.platform == "darwin":
        return os.path.join(os.path.expanduser("~/Library/Application Support"), APP_DIR)
    base = os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share")
    return os.path.join(base, APP_DIR)


def _private_dir(path):
    """Vero se path è una cartella dell'utente corrente, senza permessi per gli altri."""
    try:
        st = os.stat(path)
    except OSError:
        return False
    return os.path.isdir(path) and st.st_uid == os.getuid() and not st.st_mode & 0o077


def socket_dir(fallback):
    """Cartella privata dell'utente con un percorso corto, per il socket del motore.

    Su Linux è XDG_RUNTIME_DIR (tmpfs per utente, svuotato al logout); su macOS TMPDIR,
    che è già per utente. Altrimenti una cartella wwt-<uid> nella cartella temporanea,
    creata con permessi 0700 e usata solo se appartiene all'utente; in ultima istanza
    fallback.
    """
    run = os.environ.get("XDG_RUNTIME_DIR")
    if run and _private_dir(run):
        return run
    tmp = tempfile.gettempdir()
    if sys.platform == "darwin" and _private_dir(tmp):
        return tmp
    own = os.path.join(tmp, "wwt-%d" % os.getuid())
    try:
        os.mkdir(own, 0o700)
    except FileExistsError:
        pass
    except OSError:
        return fallback
    return own if _private_dir(own) else fallback


def socket_path(fallback_dir, name):
    path = os.path.join(socket_dir(fallback_dir), name)
    if len(path.encode("utf-8")) > SOCKET_PATH_MAX:
        path = os.path.join(fallback_dir, name)
    return path


def clean_env():
    """Ambiente per i programmi di sistema: LD_LIBRARY_PATH com'era prima del pacchetto."""
    env = dict(os.environ)
    if getattr(sys, "frozen", False):
        orig = env.pop("LD_LIBRARY_PATH_ORIG", None)
        if orig is not None:
            env["LD_LIBRARY_PATH"] = orig
        else:
            env.pop("LD_LIBRARY_PATH", None)
    return env


def _linux_parent(pid):
    with open("/proc/%d/stat" % pid, encoding="utf-8", errors="replace") as f:
        stat = f.read()
    # Il nome del processo sta tra parentesi e può contenere spazi: i campi seguono l'ultima ")".
    return int(stat.rsplit(")", 1)[1].split()[1])


def _linux_name(pid):
    try:
        return os.path.basename(os.readlink("/proc/%d/exe" % pid))
    except OSError:
        with open("/proc/%d/comm" % pid, encoding="utf-8", errors="replace") as f:
            return f.read().strip()


def _mac_parent_and_name(pid):
    out = subprocess.run(["ps", "-o", "ppid=", "-o", "comm=", "-p", str(pid)], capture_output=True,
                         text=True, env=clean_env(), timeout=5).stdout.strip()
    ppid, _, comm = out.partition(" ")
    return int(ppid), os.path.basename(comm.strip())


def browser_name(name):
    """Nome del browser come su Windows (chrome, msedge, firefox...), dal nome del processo."""
    name = name.lower()
    if name.startswith("xdg-desktop-portal"):
        return "firefox"
    for suffix in ("-bin", "-browser", "-stable", "-esr"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
    aliases = {"google chrome": "chrome", "microsoft edge": "msedge", "brave browser": "brave"}
    return aliases.get(name, name)


def parent_browser():
    """Nome del browser che ha avviato il relay, risalendo al più quattro processi."""
    try:
        pid = os.getpid()
        for _ in range(4):
            if sys.platform == "darwin":
                pid, _name = _mac_parent_and_name(pid)
                name = _mac_parent_and_name(pid)[1] if pid > 1 else ""
            else:
                pid = _linux_parent(pid)
                name = _linux_name(pid) if pid > 1 else ""
            if pid <= 1 or not name:
                return ""
            if name.lower() not in NOT_BROWSERS:
                return browser_name(name)
        return ""
    except Exception:  # noqa: BLE001
        return ""


class FolderDialogMissing(RuntimeError):
    """Sul sistema non c'è un programma che mostri la finestra di scelta di una cartella."""


def pick_folder(title):
    """Finestra di sistema per scegliere una cartella; il percorso, oppure None se annullata."""
    if sys.platform == "darwin":
        script = 'POSIX path of (choose folder with prompt "%s")' % title.replace('"', "'")
        res = subprocess.run(["osascript", "-e", script], capture_output=True, stdin=subprocess.DEVNULL,
                             env=clean_env())
        path = res.stdout.decode("utf-8", "replace").strip().rstrip("/")
        return path if res.returncode == 0 and path else None
    home = os.path.expanduser("~")
    commands = [
        ["zenity", "--file-selection", "--directory", "--title", title, "--filename", home + "/"],
        ["kdialog", "--getexistingdirectory", home, "--title", title],
        ["yad", "--file", "--directory", "--title", title, "--filename", home + "/"],
    ]
    for cmd in commands:
        if shutil.which(cmd[0], path=clean_env().get("PATH")):
            res = subprocess.run(cmd, capture_output=True, stdin=subprocess.DEVNULL, env=clean_env())
            path = res.stdout.decode("utf-8", "replace").strip()
            return path if res.returncode == 0 and path else None
    raise FolderDialogMissing("nessuno tra zenity, kdialog e yad")
