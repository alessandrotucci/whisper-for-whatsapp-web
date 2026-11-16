"""Host di native messaging che trascrive audio con faster-whisper.

Due ruoli, nello stesso eseguibile.

Il processo che il browser avvia (relay) parla con il browser su stdin/stdout
con il protocollo di native messaging: ogni messaggio è un intero a 32 bit
little-endian con la lunghezza, seguito da JSON in UTF-8. Resta vivo finché la
porta del browser è aperta. Gestisce da sé configurazione, modelli, download e
aggiornamenti; le trascrizioni le inoltra al motore.

Il motore ("--engine") è un processo unico per utente e cartella dati, avviato
dal primo relay che ne ha bisogno e raggiunto su una named pipe con chiave di
autenticazione. Tiene il modello in memoria e la coda delle trascrizioni di
tutti i browser: una trascrizione per volta, le richieste manuali prima di
quelle automatiche, in ordine di arrivo. A ogni cambio della coda manda a ogni
relay lo stato della coda con le posizioni dei suoi lavori. Si chiude quando
non ha relay collegati né lavori da ENGINE_IDLE_EXIT_S secondi. Se il motore si
chiude con lavori in sospeso, il relay ne avvia un altro e li reinvia, al più
ENGINE_RESUBMIT volte per lavoro.

Richieste del browser (il campo "rid", se presente, torna nella risposta):
  {"type": "ping"}                                   -> pong
  {"type": "models"}                                 -> models
  {"type": "set_config", "config": {...}}            -> pong
  {"type": "download_model", "name": "large-v3"}     -> download_progress..., download_done | error
  {"type": "cuda_install"}                           -> cuda_progress..., cuda_done | error
  {"type": "pick_folder"}                            -> folder_picked
  {"type": "transcribe", "id": "...", "audio": "<base64>", "language": "pt"|null,
   "initial_prompt": "...", "beam_size": 5, "vad_filter": true, "manual": true}
  {"type": "cancel", "id": "..."}
  {"type": "queue"}                                  -> queue
  {"type": "update_host", "url": "..."}              -> update_progress..., update_host_started
  {"type": "update_extension", "url": "...", "ext_id": "...", "expect": "1.3.0"}
                                                     -> update_progress..., update_extension_done
  {"type": "extension_dirs", "ext_id": "..."}        -> extension_dirs

Revisione e traduzione con l'IA (modulo ai_review; le chiavi non tornano mai al browser):
  {"type": "ai_status"}                              -> ai_status
  {"type": "ai_models", "provider": "...", "key": "...", "base_url": "...", "account": "..."}
                                                     -> ai_models | ai_error
  {"type": "ai_add", "provider": "...", "key": "...", "base_url": "...", "model": "...",
   "target_lang": "it"}                              -> ai_added | ai_error
  {"type": "ai_update", "account": "...", "model": "...", "base_url": "..."} -> ai_status
  {"type": "ai_remove" | "ai_move", "account": "...", "delta": -1|1}        -> ai_status
  {"type": "ai_settings", "settings": {...}}         -> ai_status
  {"type": "ai_test", "account": "..."}              -> ai_test | ai_error
  {"type": "ai_oauth", "provider": "openrouter", "target_lang": "it"}
                                                     -> ai_oauth_url, poi ai_added | ai_error
  {"type": "review", "id": "...", "text": "...", "language": "pt", "hint": "...", "context": "..."}
                                                     -> review

Dopo una trascrizione, se c'è almeno un account e serve una revisione, il
risultato porta "review": {"state": "running", "provider": "..."} e segue
  {"type": "review", "id": "...", "state": "done", "review": {...}}
  {"type": "review", "id": "...", "state": "error", "errors": [...]}

Eventi di una trascrizione (campo "id"):
  status (waiting_lock | loading_model | transcribing), partial, result, error

Stato della coda, in risposta a "queue" e spontaneo a ogni cambio (senza "rid"):
  {"type": "queue", "length": lavori in coda e in corso di tutti i browser,
   "waiting": lavori in coda, "others": lavori degli altri browser,
   "running": null | {"mine": bool, "id": id se è di questo browser},
   "jobs": [{"id": "...", "position": 1}, ...]}   posizione 1 = il prossimo

Un lock su file impedisce anche a trascrizioni da riga di comando di sovrapporsi
a quelle del motore.

I dati (config.json, log, lock, chiave del motore, librerie CUDA) stanno in
DATA_DIR: la variabile d'ambiente WWT_DATA_DIR, altrimenti
%LOCALAPPDATA%\\WhisperForWhatsAppWeb per l'eseguibile impacchettato e la
cartella dello script quando gira da sorgente.

Da riga di comando: "--info" stampa lo stato, "--selftest" carica il modello e
trascrive un secondo di silenzio, "--file PERCORSO [--lang xx]" trascrive un file,
"--cuda-install" scarica le librerie CUDA, "--download MODELLO" scarica un modello;
"--device cpu|cuda|auto", "--compute TIPO" e "--model MODELLO" valgono per quella
sola esecuzione, senza toccare config.json.
"""

import base64
import hashlib
import io
import json
import os
import queue
import re
import struct
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from multiprocessing.connection import Client, Listener

import ai_review
import cuda_libs

HOST_VERSION = "1.5.0"
FROZEN = getattr(sys, "frozen", False)


def _data_dir():
    env = os.environ.get("WWT_DATA_DIR")
    if env:
        return env
    if FROZEN:
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        return os.path.join(base, "WhisperForWhatsAppWeb")
    return os.path.dirname(os.path.abspath(__file__))


DATA_DIR = _data_dir()
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
LOG_PATH = os.path.join(DATA_DIR, "whisper_host.log")
LOCK_PATH = os.path.join(DATA_DIR, "transcribe.lock")
ENGINE_KEY_PATH = os.path.join(DATA_DIR, "engine.key")
ENGINE_IDLE_EXIT_S = 30
ENGINE_START_TIMEOUT_S = 30
ENGINE_RESUBMIT = 2

DEFAULT_CONFIG = {
    "model": "large-v3",
    "device": "cpu",
    "compute_type": "int8",
    "cpu_threads": 0,
    "beam_size": 5,
    "vad_filter": True,
}

# Modelli proposti nell'interfaccia, con la dimensione approssimativa del download.
MODEL_CHOICES = [
    {"name": "large-v3", "size_gb": 3.1, "note": "il più accurato"},
    {"name": "large-v3-turbo", "size_gb": 1.6, "note": "molto più veloce, accuratezza vicina"},
    {"name": "medium", "size_gb": 1.5, "note": ""},
    {"name": "small", "size_gb": 0.5, "note": "leggero, meno accurato"},
]

MODEL_FILES = ["config.json", "preprocessor_config.json", "model.bin", "tokenizer.json", "vocabulary.*"]


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, encoding="utf-8-sig") as f:
            cfg.update(json.load(f))
    except FileNotFoundError:
        pass
    return cfg


def save_config(cfg):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CONFIG_PATH)


def repo_for(name):
    from faster_whisper.utils import _MODELS

    return _MODELS.get(name, name if "/" in name else None)


def is_model_dir(path):
    return bool(path) and os.path.isfile(os.path.join(path, "model.bin")) and os.path.isfile(os.path.join(path, "config.json"))


def local_model_path(name):
    """Cartella locale del modello (nome noto o cartella), oppure None se non è sul disco."""
    if not name:
        return None
    if os.path.isdir(name):
        return name if is_model_dir(name) else None
    repo = repo_for(name)
    if not repo:
        return None
    try:
        from huggingface_hub import snapshot_download

        path = snapshot_download(repo, allow_patterns=MODEL_FILES, local_files_only=True)
        return path if is_model_dir(path) else None
    except Exception:  # noqa: BLE001
        return None


def dir_size(path):
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


class ModelMissing(RuntimeError):
    """Il modello configurato non è sul disco; il messaggio è il codice che l'estensione traduce."""


class FileLock:
    """Lock esclusivo tra processi su un file, con msvcrt su Windows e fcntl altrove."""

    def __init__(self, path):
        self.path = path
        self.fh = None

    def acquire(self, on_wait=None):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.fh = open(self.path, "a+b")
        notified = False
        while True:
            try:
                if os.name == "nt":
                    import msvcrt

                    self.fh.seek(0)
                    msvcrt.locking(self.fh.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(self.fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                return
            except OSError:
                if not notified and on_wait:
                    on_wait()
                    notified = True
                time.sleep(0.5)

    def release(self):
        if not self.fh:
            return
        try:
            if os.name == "nt":
                import msvcrt

                self.fh.seek(0)
                msvcrt.locking(self.fh.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(self.fh.fileno(), fcntl.LOCK_UN)
        finally:
            self.fh.close()
            self.fh = None


class Transcriber:
    def __init__(self, log):
        self.log = log
        self.model = None
        self.model_key = None
        self.cfg = load_config()
        self.cuda_failed = False

    def describe(self):
        info = {
            "host_version": HOST_VERSION,
            "frozen": FROZEN,
            "executable": sys.executable,
            "python_version": sys.version.split()[0],
            "data_dir": DATA_DIR,
            "config": self.cfg,
            "model_loaded": self.model is not None,
            "model_path": local_model_path(self.cfg.get("model")),
        }
        try:
            import faster_whisper

            info["faster_whisper"] = getattr(faster_whisper, "__version__", "?")
        except Exception as e:  # noqa: BLE001
            info["faster_whisper_error"] = str(e)
        try:
            import ctranslate2

            info["ctranslate2"] = ctranslate2.__version__
            info["cuda_devices"] = ctranslate2.get_cuda_device_count()
        except Exception:  # noqa: BLE001
            pass
        info["cuda"] = cuda_libs.status(DATA_DIR)
        return info

    def models(self):
        current = self.cfg.get("model")
        out = []
        for choice in MODEL_CHOICES:
            out.append(dict(choice, path=local_model_path(choice["name"]), current=choice["name"] == current))
        if current and not any(c["name"] == current for c in MODEL_CHOICES):
            out.append({"name": current, "size_gb": None, "note": "cartella", "path": local_model_path(current), "current": True})
        return out

    def device(self):
        """Dispositivo effettivo: "auto" diventa cuda solo con driver e librerie pronti."""
        wanted = self.cfg.get("device") or "cpu"
        if wanted == "cpu" or self.cuda_failed:
            return "cpu"
        if wanted == "auto":
            return "cuda" if cuda_libs.status(DATA_DIR)["usable"] else "cpu"
        return "cuda"

    def model_key_for(self, device):
        cfg = self.cfg
        return (cfg["model"], device, cfg["compute_type"], cfg.get("cpu_threads", 0))

    def model_ready(self):
        return self.model is not None and self.model_key == self.model_key_for(self.device())

    def ensure_model(self):
        cfg = self.cfg
        device = self.device()
        key = self.model_key_for(device)
        if self.model is not None and self.model_key == key:
            return
        path = local_model_path(cfg["model"])
        if path is None:
            raise ModelMissing("E:model_missing:%s" % cfg["model"])
        from faster_whisper import WhisperModel

        if device == "cuda":
            cuda_libs.activate(DATA_DIR)
        self.model = None
        t0 = time.time()
        try:
            self.model = WhisperModel(
                path,
                device=device,
                compute_type=cfg["compute_type"],
                cpu_threads=int(cfg.get("cpu_threads") or 0),
            )
        except Exception as e:  # noqa: BLE001
            if device != "cuda":
                raise
            self.fail_cuda(e)
            return self.ensure_model()
        self.model_key = key
        self.log("modello caricato da %s su %s (%s) in %.1f s" % (path, device, cfg["compute_type"], time.time() - t0))

    def fail_cuda(self, error):
        """La GPU non funziona: si torna alla CPU fino al prossimo cambio di configurazione."""
        message = "%s: %s" % (type(error).__name__, error)
        self.log("GPU non utilizzabile, uso la CPU: %s" % message)
        cuda_libs.set_last_error(DATA_DIR, message)
        self.cuda_failed = True
        self.model = None
        self.model_key = None

    def reload_config(self):
        cfg = load_config()
        if cfg != self.cfg:
            self.cuda_failed = False
        self.cfg = cfg

    def transcribe(self, audio_bytes, req, on_partial, cancelled):
        cfg = self.cfg
        segments, info = self.model.transcribe(
            io.BytesIO(audio_bytes),
            language=req.get("language") or None,
            beam_size=int(req.get("beam_size") or cfg["beam_size"]),
            vad_filter=bool(req.get("vad_filter", cfg["vad_filter"])),
            initial_prompt=req.get("initial_prompt") or None,
        )
        parts = []
        duration = info.duration or 0
        for seg in segments:
            if cancelled():
                raise InterruptedError("annullata")
            parts.append(seg.text.strip())
            progress = min(1.0, seg.end / duration) if duration else None
            on_partial(" ".join(p for p in parts if p), progress)
        return {
            "text": " ".join(p for p in parts if p),
            "language": info.language,
            "language_probability": round(info.language_probability or 0, 3),
            "duration": round(duration, 2),
        }

    def download(self, name, on_progress):
        """Scarica il modello nella cache di Hugging Face; l'avanzamento si misura sulla cartella della cache."""
        repo = repo_for(name)
        if not repo:
            raise ValueError("modello sconosciuto: %s" % name)
        from huggingface_hub import HfApi, constants, snapshot_download

        total = None
        try:
            info = HfApi().model_info(repo, files_metadata=True)
            import fnmatch

            total = sum(
                (s.size or 0)
                for s in info.siblings
                if any(fnmatch.fnmatch(s.rfilename, p) for p in MODEL_FILES)
            ) or None
        except Exception as e:  # noqa: BLE001
            self.log("dimensione del modello non disponibile: %s" % e)
        cache = os.path.join(constants.HF_HUB_CACHE, "models--" + repo.replace("/", "--"))
        start = dir_size(cache) if os.path.isdir(cache) else 0
        done = threading.Event()

        def watch():
            while not done.wait(1.0):
                now = dir_size(cache) if os.path.isdir(cache) else 0
                on_progress(max(0, now - start), total)

        t = threading.Thread(target=watch, daemon=True)
        t.start()
        try:
            path = snapshot_download(repo, allow_patterns=MODEL_FILES)
        finally:
            done.set()
            t.join(timeout=2)
        if not is_model_dir(path):
            raise RuntimeError("download incompleto in %s" % path)
        return path


def pick_folder():
    """Apre la finestra di Windows per scegliere una cartella; restituisce il percorso o None."""
    if os.name != "nt":
        return None
    script = (
        "Add-Type -AssemblyName System.Windows.Forms;"
        "$o = New-Object System.Windows.Forms.Form -Property @{TopMost=$true};"
        "$d = New-Object System.Windows.Forms.FolderBrowserDialog;"
        "$d.Description = 'Cartella del modello faster-whisper (contiene model.bin)';"
        "$d.UseDescriptionForTitle = $true;"
        "if ($d.ShowDialog($o) -eq 'OK') { [Console]::OutputEncoding = [Text.Encoding]::UTF8; $d.SelectedPath }"
    )
    res = subprocess.run(
        ["powershell.exe", "-NoProfile", "-STA", "-Command", script],
        capture_output=True,
        stdin=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    path = res.stdout.decode("utf-8", "replace").strip()
    return path or None


class PipeReader:
    """Legge stdin senza lasciare un thread fermo in ReadFile sulla pipe del browser.

    Su Windows, finché un thread ha una ReadFile sincrona in sospeso su una pipe,
    GetFileType sulla stessa pipe si blocca fino alla fine della lettura; alcune DLL
    (OpenBLAS di numpy, per esempio) lo chiamano sugli handle standard mentre si
    caricano. Il caricamento di una DLL nel thread di lavoro resterebbe quindi fermo
    fino al messaggio successivo del browser. Qui ReadFile parte solo quando
    PeekNamedPipe segnala dati disponibili, e completa subito. Se l'handle non è una
    pipe, la lettura è quella bloccante normale.
    """

    POLL_S = 0.05
    ERROR_BROKEN_PIPE = 109

    def __init__(self, fd):
        self.fd = fd
        self.peek = None
        if os.name == "nt":
            import ctypes
            import msvcrt
            from ctypes import wintypes

            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            peek_fn = k32.PeekNamedPipe
            peek_fn.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p,
                                ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p]
            peek_fn.restype = wintypes.BOOL
            handle = msvcrt.get_osfhandle(fd)

            def peek():
                avail = wintypes.DWORD(0)
                if peek_fn(handle, None, 0, None, ctypes.byref(avail), None):
                    return avail.value
                return -1 if ctypes.get_last_error() == self.ERROR_BROKEN_PIPE else None

            if peek() is not None:
                self.peek = peek

    def read(self, n):
        """Fino a n byte; b"" a pipe chiusa."""
        if self.peek is None:
            return os.read(self.fd, n)
        while True:
            avail = self.peek()
            if avail is None:
                return os.read(self.fd, n)
            if avail < 0:
                return b""
            if avail > 0:
                return os.read(self.fd, min(n, avail))
            time.sleep(self.POLL_S)

    def read_exact(self, n):
        """Esattamente n byte, oppure None se la pipe si chiude prima."""
        out = bytearray()
        while len(out) < n:
            chunk = self.read(n - len(out))
            if not chunk:
                return None
            out += chunk
        return bytes(out)


def engine_address():
    """Indirizzo del motore: unico per utente e cartella dati."""
    tag = hashlib.sha256(os.path.abspath(DATA_DIR).lower().encode("utf-8")).hexdigest()[:10]
    if os.name == "nt":
        user = re.sub(r"[^A-Za-z0-9_.-]", "_", os.environ.get("USERNAME") or "user")
        return r"\\.\pipe\WhisperForWhatsAppWeb-%s-%s" % (user, tag), "AF_PIPE"
    return os.path.join(DATA_DIR, "engine-%s.sock" % tag), "AF_UNIX"


def engine_key():
    """Chiave di autenticazione condivisa da motore e relay, creata dal primo che la chiede."""
    os.makedirs(DATA_DIR, exist_ok=True)
    try:
        fd = os.open(ENGINE_KEY_PATH, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "wb") as f:
            f.write(os.urandom(32))
    for _ in range(50):
        with open(ENGINE_KEY_PATH, "rb") as f:
            key = f.read()
        if len(key) == 32:
            return key
        time.sleep(0.05)
    raise RuntimeError("chiave del motore non valida in %s" % ENGINE_KEY_PATH)


def send_json(conn, lock, msg):
    data = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    with lock:
        conn.send_bytes(data)


def spawn_engine(log):
    """Avvia il motore staccato da questo processo e dal job del browser, quando possibile."""
    args = [sys.executable, "--engine"] if FROZEN else [sys.executable, os.path.abspath(__file__), "--engine"]
    kwargs = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True, cwd=DATA_DIR)
    if os.name != "nt":
        return subprocess.Popen(args, start_new_session=True, **kwargs).pid
    base = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    try:
        proc = subprocess.Popen(args, creationflags=base | subprocess.CREATE_BREAKAWAY_FROM_JOB, **kwargs)
    except OSError:
        proc = subprocess.Popen(args, creationflags=base, **kwargs)
    log("motore avviato, pid %s" % proc.pid)
    return proc.pid


class EngineJob:
    def __init__(self, cid, msg):
        self.cid = cid
        self.id = msg.get("id")
        self.msg = msg
        self.manual = bool(msg.get("manual"))
        self.cancelled = False


class EngineClient:
    def __init__(self, cid, conn):
        self.cid = cid
        self.conn = conn
        self.lock = threading.Lock()
        self.label = ""


class Engine:
    """Coda unica delle trascrizioni e modello condiviso da tutti i relay."""

    def __init__(self, log):
        self.log = log
        self.tr = Transcriber(log)
        self.cond = threading.Condition()
        self.clients = {}
        self.waiting = []
        self.running = None
        self.seq = 0
        self.idle_since = time.time()

    # ---------- collegamenti ----------

    def serve(self, listener):
        while True:
            try:
                conn = listener.accept()
            except Exception as e:  # noqa: BLE001
                self.log("motore: collegamento rifiutato: %s" % e)
                continue
            with self.cond:
                self.seq += 1
                client = EngineClient(self.seq, conn)
                self.clients[client.cid] = client
                self.idle_since = time.time()
            threading.Thread(target=self.client_loop, args=(client,), daemon=True).start()

    def client_loop(self, client):
        try:
            while True:
                msg = json.loads(client.conn.recv_bytes().decode("utf-8"))
                self.handle(client, msg)
        except (EOFError, OSError, ValueError):
            pass
        finally:
            self.drop(client)

    def drop(self, client):
        with self.cond:
            self.clients.pop(client.cid, None)
            self.waiting = [j for j in self.waiting if j.cid != client.cid]
            if self.running is not None and self.running.cid == client.cid:
                self.running.cancelled = True
            self.idle_since = time.time()
            self.cond.notify_all()
        try:
            client.conn.close()
        except OSError:
            pass
        self.broadcast_queue()

    def send(self, client, msg):
        try:
            send_json(client.conn, client.lock, msg)
        except (OSError, ValueError):
            pass

    def to_owner(self, job, msg):
        client = self.clients.get(job.cid)
        if client is not None:
            self.send(client, msg)

    # ---------- coda ----------

    def handle(self, client, msg):
        kind = msg.get("type")
        if kind == "hello":
            client.label = str(msg.get("browser") or "")
        elif kind == "transcribe":
            job = EngineJob(client.cid, msg)
            with self.cond:
                if job.manual:
                    i = 0
                    while i < len(self.waiting) and self.waiting[i].manual:
                        i += 1
                    self.waiting.insert(i, job)
                else:
                    self.waiting.append(job)
                self.cond.notify_all()
            self.broadcast_queue()
        elif kind == "cancel":
            removed = None
            with self.cond:
                for j in self.waiting:
                    if j.cid == client.cid and j.id == msg.get("id"):
                        removed = j
                        break
                if removed is not None:
                    self.waiting.remove(removed)
                elif self.running is not None and self.running.cid == client.cid and self.running.id == msg.get("id"):
                    self.running.cancelled = True
            if removed is not None:
                self.send(client, {"type": "error", "id": removed.id, "message": "annullata", "cancelled": True})
                self.broadcast_queue()
        elif kind == "queue":
            snap = self.snapshot(client.cid)
            if msg.get("rid") is not None:
                snap["rid"] = msg["rid"]
            self.send(client, snap)
        else:
            self.send(client, {"type": "error", "id": msg.get("id"), "rid": msg.get("rid"), "message": "tipo sconosciuto: %s" % kind})

    def snapshot(self, cid):
        with self.cond:
            run = self.running
            return {
                "type": "queue",
                "length": len(self.waiting) + (1 if run is not None else 0),
                "waiting": len(self.waiting),
                "others": sum(1 for j in self.waiting if j.cid != cid) + (1 if run is not None and run.cid != cid else 0),
                "running": None if run is None else {"mine": run.cid == cid, "id": run.id if run.cid == cid else None},
                "jobs": [{"id": j.id, "position": i + 1} for i, j in enumerate(self.waiting) if j.cid == cid],
            }

    def broadcast_queue(self):
        for client in list(self.clients.values()):
            self.send(client, self.snapshot(client.cid))

    def worker(self):
        lock = FileLock(LOCK_PATH)
        while True:
            with self.cond:
                while not self.waiting:
                    self.cond.wait()
                job = self.waiting.pop(0)
                self.running = job
            self.broadcast_queue()
            try:
                self.run_job(job, lock)
            finally:
                with self.cond:
                    self.running = None
                    self.idle_since = time.time()
                self.broadcast_queue()

    def run_job(self, job, lock):
        jid = job.id
        if job.cancelled:
            self.to_owner(job, {"type": "error", "id": jid, "message": "annullata", "cancelled": True})
            return
        t0 = time.time()
        try:
            audio = base64.b64decode(job.msg["audio"])
            lock.acquire(on_wait=lambda: self.to_owner(job, {"type": "status", "id": jid, "state": "waiting_lock"}))
            try:
                res = self.transcribe(job, audio)
            finally:
                lock.release()
            res.update(type="result", id=jid, elapsed=round(time.time() - t0, 2))
            client = self.clients.get(job.cid)
            self.log("trascritto %s%s: %.1f s audio in %.1f s su %s" % (
                jid, " (%s)" % client.label if client is not None and client.label else "",
                res["duration"], res["elapsed"], res["device"]))
            self.to_owner(job, res)
        except InterruptedError:
            self.to_owner(job, {"type": "error", "id": jid, "message": "annullata", "cancelled": True})
        except Exception as e:  # noqa: BLE001
            self.log(traceback.format_exc())
            self.to_owner(job, {"type": "error", "id": jid, "message": "%s: %s" % (type(e).__name__, e)})

    def transcribe(self, job, audio):
        """Trascrive sul dispositivo configurato; un errore sulla GPU ripete il lavoro sulla CPU."""
        jid = job.id
        while True:
            self.tr.reload_config()
            if not self.tr.model_ready():
                self.to_owner(job, {"type": "status", "id": jid, "state": "loading_model"})
            self.tr.ensure_model()
            device = self.tr.model_key[1]
            self.to_owner(job, {"type": "status", "id": jid, "state": "transcribing"})
            try:
                res = self.tr.transcribe(
                    audio,
                    job.msg,
                    lambda text, p: self.to_owner(job, {"type": "partial", "id": jid, "text": text, "progress": p}),
                    lambda: job.cancelled,
                )
            except InterruptedError:
                raise
            except Exception as e:  # noqa: BLE001
                if device != "cuda":
                    raise
                self.tr.fail_cuda(e)
                continue
            if device == "cuda":
                cuda_libs.set_last_error(DATA_DIR, None)
            res["device"] = device
            return res

    def idle_watch(self):
        while True:
            time.sleep(5)
            with self.cond:
                idle = not self.clients and not self.waiting and self.running is None
                since = self.idle_since
            if idle and time.time() - since >= ENGINE_IDLE_EXIT_S:
                self.log("motore: nessun collegamento da %d s, uscita" % ENGINE_IDLE_EXIT_S)
                os._exit(0)


class EngineLink:
    """Collegamento del relay al motore: invia i lavori, inoltra al browser ciò che torna."""

    def __init__(self, host):
        self.host = host
        self.log = host.log
        self.conn = None
        self.send_lock = threading.Lock()
        self.state_lock = threading.RLock()
        self.submitted = {}
        self.attempts = {}

    def connect(self, spawn=True):
        with self.state_lock:
            if self.conn is not None:
                return self.conn
            address, family = engine_address()
            key = engine_key()
            deadline = time.time() + ENGINE_START_TIMEOUT_S
            spawned = False
            while True:
                try:
                    conn = Client(address, family=family, authkey=key)
                    break
                except OSError:
                    if not spawn:
                        return None
                    if not spawned:
                        spawn_engine(self.log)
                        spawned = True
                    if time.time() > deadline:
                        raise RuntimeError("E:engine_unavailable")
                    time.sleep(0.2)
            self.conn = conn
            send_json(conn, self.send_lock, {"type": "hello", "browser": self.host.browser})
            threading.Thread(target=self.reader, args=(conn,), daemon=True).start()
            return conn

    def send(self, msg, spawn=True):
        conn = self.connect(spawn)
        if conn is None:
            return False
        send_json(conn, self.send_lock, msg)
        return True

    def reader(self, conn):
        try:
            while True:
                msg = json.loads(conn.recv_bytes().decode("utf-8"))
                req = None
                if msg.get("type") in ("result", "error") and msg.get("id") in self.submitted:
                    req = self.submitted.pop(msg["id"], None)
                    self.attempts.pop(msg["id"], None)
                if msg.get("type") == "result" and req is not None:
                    self.host.on_result(msg, req)
                else:
                    self.host.send(msg)
        except (EOFError, OSError, ValueError):
            pass
        with self.state_lock:
            if self.conn is conn:
                self.conn = None
        if not self.host.closing:
            self.host.engine_q.put(("resubmit", None))

    def submit(self, msg):
        jid = msg.get("id")
        self.submitted[jid] = msg
        self.attempts[jid] = 0
        try:
            self.send(msg)
        except Exception as e:  # noqa: BLE001
            self.fail(jid, e)

    def fail(self, jid, error):
        self.submitted.pop(jid, None)
        self.attempts.pop(jid, None)
        text = str(error) if str(error).startswith("E:") else "%s: %s" % (type(error).__name__, error)
        self.host.send({"type": "error", "id": jid, "message": text})

    def resubmit(self):
        """Il motore si è chiuso: i lavori senza risposta ripartono su un motore nuovo."""
        pending = list(self.submitted.values())
        if not pending:
            return
        self.log("motore perso con %d lavori in sospeso" % len(pending))
        for msg in pending:
            jid = msg.get("id")
            self.attempts[jid] = self.attempts.get(jid, 0) + 1
            if self.attempts[jid] > ENGINE_RESUBMIT:
                self.fail(jid, RuntimeError("E:engine_crashed"))
                continue
            try:
                self.send(msg)
            except Exception as e:  # noqa: BLE001
                self.fail(jid, e)

    def cancel(self, msg):
        if msg.get("id") in self.submitted and self.conn is not None:
            try:
                self.send(msg, spawn=False)
            except Exception:  # noqa: BLE001
                pass

    def queue(self, msg):
        try:
            if self.send({"type": "queue", "rid": msg.get("rid")}, spawn=False):
                return
        except Exception:  # noqa: BLE001
            pass
        self.host.reply(msg, {"type": "queue", "length": 0, "waiting": 0, "others": 0, "running": None, "jobs": []})


class Host:
    """Relay: protocollo del browser, lavori locali, inoltro delle trascrizioni al motore."""

    def __init__(self, out, log, browser=""):
        self.out = out
        self.log = log
        self.browser = browser
        self.closing = False
        self.write_lock = threading.Lock()
        self.jobs = queue.Queue()
        self.engine_q = queue.Queue()
        self.tr = Transcriber(log)
        self.engine = EngineLink(self)
        self.ai = ai_review.Store(DATA_DIR)

    def send(self, msg):
        data = json.dumps(msg, ensure_ascii=False).encode("utf-8")
        with self.write_lock:
            self.out.write(struct.pack("<I", len(data)))
            self.out.write(data)
            self.out.flush()

    def reply(self, req, msg):
        if req.get("rid") is not None:
            msg["rid"] = req["rid"]
        self.send(msg)

    def reader(self, inp):
        while True:
            header = inp.read_exact(4)
            if header is None:
                break
            (length,) = struct.unpack("<I", header)
            body = inp.read_exact(length)
            if body is None:
                break
            try:
                msg = json.loads(body.decode("utf-8"))
            except ValueError as e:
                self.send({"type": "error", "message": "JSON non valido: %s" % e})
                continue
            self.dispatch(msg)
        self.closing = True
        self.jobs.put(None)
        self.engine_q.put(None)

    def dispatch(self, msg):
        kind = msg.get("type")
        if kind in ("ping", "models"):
            # config.json può cambiare da un altro relay o a mano: si rilegge a ogni richiesta.
            self.tr.cfg = load_config()
        if kind == "ping":
            self.reply(msg, dict(type="pong", **self.tr.describe()))
        elif kind == "models":
            self.reply(msg, {"type": "models", "models": self.tr.models(), "config": self.tr.cfg})
        elif kind == "pick_folder":
            threading.Thread(target=self.do_pick_folder, args=(msg,), daemon=True).start()
        elif kind == "extension_dirs":
            import updater

            dirs = [{"browser": b, "profile": p, "path": d, "release": m} for b, p, d, m in updater.extension_dirs(msg.get("ext_id"))]
            self.reply(msg, {"type": "extension_dirs", "dirs": dirs})
        elif kind in ("transcribe", "cancel", "queue"):
            self.engine_q.put((kind, msg))
        elif kind in ("set_config", "download_model", "cuda_install", "update_host", "update_extension"):
            self.jobs.put(msg)
        elif kind.startswith("ai_") or kind == "review":
            threading.Thread(target=self.do_ai, args=(msg,), daemon=True).start()
        else:
            self.reply(msg, {"type": "error", "id": msg.get("id"), "message": "tipo sconosciuto: %s" % kind})

    def engine_worker(self):
        """Comunicazioni con il motore in ordine di arrivo, fuori dal thread che legge il browser."""
        while True:
            item = self.engine_q.get()
            if item is None:
                return
            kind, msg = item
            try:
                if kind == "transcribe":
                    self.engine.submit(msg)
                elif kind == "cancel":
                    self.engine.cancel(msg)
                elif kind == "queue":
                    self.engine.queue(msg)
                elif kind == "resubmit":
                    self.engine.resubmit()
            except Exception:  # noqa: BLE001
                self.log(traceback.format_exc())

    def on_result(self, msg, req):
        """Inoltra un risultato del motore e, se serve, avvia la revisione con l'IA."""
        data = ai_review.active(self.ai) if (msg.get("text") or "").strip() else None
        if not ai_review.needs_review(data, msg.get("language")):
            self.send(msg)
            return
        first = ai_review.public_account(data["accounts"][0])
        msg["review"] = {"state": "running", "provider": first["name"]}
        self.send(msg)
        threading.Thread(target=self.run_review, daemon=True, args=({
            "type": "review", "id": msg.get("id"), "text": msg["text"], "language": msg.get("language"),
            "hint": req.get("initial_prompt"), "context": req.get("context"),
        },)).start()

    def run_review(self, msg):
        jid = msg.get("id")

        def attempt(acc):
            self.reply(msg, {"type": "review", "id": jid, "state": "running", "provider": acc["name"]})

        try:
            res = ai_review.review(self.ai, msg.get("text") or "", msg.get("language"), msg.get("hint"),
                                   msg.get("context"), attempt)
        except ai_review.AIError as e:
            errors = getattr(e, "errors", None) or [{"code": e.code, "detail": e.detail}]
            self.log("revisione %s non riuscita: %s" % (jid, "; ".join(
                "%s %s" % (x.get("name", ""), x.get("code")) for x in errors)))
            self.reply(msg, {"type": "review", "id": jid, "state": "error", "errors": errors})
            return
        if res is None:
            self.reply(msg, {"type": "review", "id": jid, "state": "skipped"})
            return
        self.log("revisione %s con %s %s in %d ms%s" % (
            jid, res["provider"], res["model"], res["ms"], ", correzione scartata" if res["rejected"] else ""))
        self.reply(msg, {"type": "review", "id": jid, "state": "done", "review": res})

    def ai_status(self):
        return dict(type="ai_status", **ai_review.status(self.ai))

    def do_ai(self, msg):
        kind = msg.get("type")
        try:
            if kind == "review":
                self.run_review(msg)
            elif kind == "ai_status":
                self.reply(msg, self.ai_status())
            elif kind == "ai_models":
                key, base_url = msg.get("key"), msg.get("base_url")
                provider = msg.get("provider")
                if msg.get("account"):
                    acc = ai_review.find_account(self.ai, msg["account"])
                    provider, key = acc["provider"], ai_review.unseal(acc.get("key"))
                    base_url = base_url or acc.get("base_url")
                models, best = ai_review.list_models(provider, key, base_url)
                self.reply(msg, {"type": "ai_models", "provider": provider, "models": models, "recommended": best})
            elif kind == "ai_add":
                acc, ms = ai_review.add_account(self.ai, msg.get("provider"), msg.get("key"), msg.get("base_url"),
                                                msg.get("model"), msg.get("target_lang"))
                self.log("IA: collegato %s (%s), prova in %d ms" % (acc["name"], acc["model"], ms))
                self.reply(msg, dict(self.ai_status(), type="ai_added", account=acc["id"], ms=ms))
            elif kind == "ai_update":
                ai_review.update_account(self.ai, msg.get("account"), msg.get("model"), msg.get("base_url"))
                self.reply(msg, self.ai_status())
            elif kind == "ai_remove":
                ai_review.remove_account(self.ai, msg.get("account"))
                self.reply(msg, self.ai_status())
            elif kind == "ai_move":
                ai_review.move_account(self.ai, msg.get("account"), msg.get("delta") or 0)
                self.reply(msg, self.ai_status())
            elif kind == "ai_settings":
                ai_review.set_settings(self.ai, msg.get("settings"))
                self.reply(msg, self.ai_status())
            elif kind == "ai_test":
                acc = ai_review.find_account(self.ai, msg.get("account"))
                target = self.ai.load()["settings"].get("target_lang")
                try:
                    ms, parsed = ai_review.test_account(acc["provider"], ai_review.unseal(acc.get("key")),
                                                        acc.get("base_url"), acc.get("model"), target)
                except ai_review.AIError as e:
                    ai_review.mark(self.ai, acc["id"], error=e.code)
                    raise
                ai_review.mark(self.ai, acc["id"], ok=True)
                self.reply(msg, {"type": "ai_test", "account": acc["id"], "ms": ms, "text": parsed.get("text"),
                                 "translation": parsed.get("translation")})
            elif kind == "ai_oauth":
                self.do_ai_oauth(msg)
            else:
                self.reply(msg, {"type": "error", "message": "tipo sconosciuto: %s" % kind})
        except ai_review.AIError as e:
            self.reply(msg, {"type": "ai_error", "code": e.code, "detail": e.detail})
        except Exception as e:  # noqa: BLE001
            self.log(traceback.format_exc())
            self.reply(msg, {"type": "ai_error", "code": "http", "detail": "%s: %s" % (type(e).__name__, e)})

    def do_ai_oauth(self, msg):
        """Accesso a OpenRouter: pagina di ritorno su localhost, scambio del codice, account collegato."""
        from http.server import BaseHTTPRequestHandler, HTTPServer

        verifier, challenge = ai_review.pkce_pair()
        result = {}
        done = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                code = (q.get("code") or [None])[0]
                if not code:
                    self.send_response(404)
                    self.end_headers()
                    return
                result["code"] = code
                body = ("<!doctype html><meta charset=utf-8><title>Whisper for WhatsApp Web</title>"
                        "<body style='font:16px system-ui;padding:40px'><p>OpenRouter collegato. "
                        "Puoi chiudere questa scheda.</p><p>OpenRouter connected. You can close this tab.</p>")
                data = body.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                done.set()

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = ai_review.openrouter_auth_url("http://localhost:%d/openrouter" % port, challenge)
            self.reply(msg, {"type": "ai_oauth_url", "url": url})
            if not done.wait(600):
                raise ai_review.AIError("timeout", "accesso a OpenRouter non completato")
        finally:
            server.shutdown()
            server.server_close()
        key = ai_review.openrouter_exchange(result["code"], verifier)
        acc, ms = ai_review.add_account(self.ai, "openrouter", key, None, None, msg.get("target_lang"))
        self.log("IA: collegato OpenRouter (%s) con l'accesso all'account" % acc["model"])
        self.reply(msg, dict(self.ai_status(), type="ai_added", account=acc["id"], ms=ms))

    def do_pick_folder(self, msg):
        try:
            path = pick_folder()
            self.reply(msg, {"type": "folder_picked", "path": path, "valid": is_model_dir(path)})
        except Exception as e:  # noqa: BLE001
            self.reply(msg, {"type": "error", "message": "selezione cartella: %s" % e})

    def worker(self):
        while True:
            msg = self.jobs.get()
            if msg is None:
                return
            kind = msg.get("type")
            try:
                if kind == "set_config":
                    self.apply_config(msg)
                elif kind == "download_model":
                    self.do_download(msg)
                elif kind == "cuda_install":
                    self.do_cuda_install(msg)
                elif kind == "update_host":
                    self.do_update_host(msg)
                elif kind == "update_extension":
                    self.do_update_extension(msg)
            except Exception as e:  # noqa: BLE001
                self.log(traceback.format_exc())
                self.reply(msg, {"type": "error", "id": msg.get("id"), "message": "%s: %s" % (type(e).__name__, e)})

    def update_progress(self, msg, stage):
        def progress(done, total):
            self.reply(msg, {"type": "update_progress", "stage": stage, "downloaded": done, "total": total})

        return progress

    def do_update_host(self, msg):
        import updater

        self.log("aggiornamento del componente da %s" % msg.get("url"))
        pid = updater.start_installer(msg.get("url"), self.update_progress(msg, "host"))
        self.log("installer avviato, pid %s" % pid)
        self.reply(msg, {"type": "update_host_started", "pid": pid})

    def do_update_extension(self, msg):
        import updater

        self.log("aggiornamento dell'estensione da %s" % msg.get("url"))
        done, skipped = updater.update_extension(
            msg.get("url"), msg.get("ext_id"), msg.get("expect"), self.update_progress(msg, "extension"))
        self.log("estensione aggiornata in %s; saltate senza marcatore: %s" % (done, skipped))
        self.reply(msg, {"type": "update_extension_done", "updated": done, "skipped": skipped})

    def do_download(self, msg):
        name = msg.get("name")
        self.log("download del modello %s" % name)
        last = [0.0]

        def progress(done, total):
            now = time.time()
            if now - last[0] >= 0.5:
                last[0] = now
                self.reply(msg, {"type": "download_progress", "name": name, "downloaded": done, "total": total})

        path = self.tr.download(name, progress)
        self.log("modello %s scaricato in %s" % (name, path))
        if msg.get("select", True):
            self.set_config({"model": name})
        self.reply(msg, {"type": "download_done", "name": name, "path": path, "models": self.tr.models()})

    def do_cuda_install(self, msg):
        last = [0.0]

        def progress(stage, done, total):
            now = time.time()
            if stage != "download" or now - last[0] >= 0.5:
                last[0] = now
                self.reply(msg, {"type": "cuda_progress", "stage": stage, "downloaded": done, "total": total})

        cuda_libs.install(DATA_DIR, progress, self.log)
        self.reply(msg, {"type": "cuda_done", "cuda": cuda_libs.status(DATA_DIR)})

    def set_config(self, values):
        cfg = load_config()
        for k, v in (values or {}).items():
            if k in DEFAULT_CONFIG:
                cfg[k] = v
        save_config(cfg)
        self.tr.cfg = cfg

    def apply_config(self, msg):
        self.set_config(msg.get("config"))
        self.reply(msg, dict(type="pong", **self.tr.describe()))


def open_log(prefix=""):
    os.makedirs(DATA_DIR, exist_ok=True)
    fh = open(LOG_PATH, "a", encoding="utf-8", buffering=1)
    tag = "[%s] " % prefix if prefix else ""

    def log(text):
        fh.write("%s %s%s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), tag, text))

    return fh, log


def run_native():
    """Isola lo stdout del protocollo: fd 1 e 2 vanno sul log, il protocollo usa una copia privata di fd 1."""
    if os.name == "nt":
        import msvcrt

        msvcrt.setmode(0, os.O_BINARY)
        msvcrt.setmode(1, os.O_BINARY)
    proto_fd = os.dup(1)
    if os.name == "nt":
        msvcrt.setmode(proto_fd, os.O_BINARY)
    proto_out = os.fdopen(proto_fd, "wb", buffering=0)
    log_fh, log = open_log()
    os.dup2(log_fh.fileno(), 1)
    os.dup2(log_fh.fileno(), 2)
    sys.stdout = log_fh
    sys.stderr = log_fh
    log("avvio host %s, %s, argv %s" % (HOST_VERSION, sys.executable, sys.argv[1:]))
    host = Host(proto_out, log, parent_browser())
    for target in (host.worker, host.engine_worker):
        threading.Thread(target=target, daemon=True).start()
    host.reader(PipeReader(0))
    log("stdin chiuso, uscita")


def parent_browser():
    """Nome del browser che ha avviato il relay (chrome, msedge...), dal processo padre."""
    if os.name != "nt":
        return ""
    try:
        import ctypes
        from ctypes import wintypes

        k32 = ctypes.WinDLL("kernel32", use_last_error=True)

        class PROCESSENTRY32W(ctypes.Structure):
            _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                        ("th32DefaultHeapID", ctypes.c_void_p), ("th32ModuleID", wintypes.DWORD),
                        ("cntThreads", wintypes.DWORD), ("th32ParentProcessID", wintypes.DWORD),
                        ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD), ("szExeFile", ctypes.c_wchar * 260)]

        k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        snap = k32.CreateToolhelp32Snapshot(0x2, 0)
        entries = {}
        e = PROCESSENTRY32W()
        e.dwSize = ctypes.sizeof(e)
        ok = k32.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            entries[e.th32ProcessID] = (e.th32ParentProcessID, e.szExeFile)
            ok = k32.Process32NextW(snap, ctypes.byref(e))
        k32.CloseHandle(snap)
        pid = os.getpid()
        for _ in range(4):
            parent = entries.get(pid)
            if not parent:
                return ""
            pid = parent[0]
            name = entries.get(pid, (0, ""))[1].lower()
            if name and name not in ("cmd.exe", "conhost.exe"):
                return name.rsplit(".", 1)[0]
        return ""
    except Exception:  # noqa: BLE001
        return ""


def run_engine():
    log_fh, log = open_log("motore")
    if os.name == "nt":
        os.dup2(log_fh.fileno(), 1)
        os.dup2(log_fh.fileno(), 2)
    sys.stdout = log_fh
    sys.stderr = log_fh
    address, family = engine_address()
    try:
        listener = Listener(address, family=family, authkey=engine_key())
    except OSError as e:
        log("motore già attivo (%s)" % e)
        return
    log("avvio motore %s, %s" % (HOST_VERSION, sys.executable))
    engine = Engine(log)
    for target in (engine.worker, engine.idle_watch):
        threading.Thread(target=target, daemon=True).start()
    engine.serve(listener)


def run_cli(argv):
    # Rediretti su file o pipe, stdout e stderr userebbero la codepage ANSI: il JSON esce in UTF-8.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass

    def log(text):
        print(text, file=sys.stderr)

    tr = Transcriber(log)
    for opt, key in (("--device", "device"), ("--compute", "compute_type"), ("--model", "model")):
        if opt in argv:
            tr.cfg[key] = argv[argv.index(opt) + 1]
    if "--download" in argv:
        name = argv[argv.index("--download") + 1]
        last = [0.0]

        def dl_progress(done, total):
            if time.time() - last[0] >= 5:
                last[0] = time.time()
                log("modello %s: %d / %s MB" % (name, done // 1000000, total // 1000000 if total else "?"))

        log("modello %s in %s" % (name, tr.download(name, dl_progress)))
    if "--cuda-install" in argv:
        last = [0.0]

        def progress(stage, done, total):
            if stage != "download" or time.time() - last[0] >= 5:
                last[0] = time.time()
                log("CUDA %s: %d / %d MB" % (stage, done // 1000000, total // 1000000))

        cuda_libs.install(DATA_DIR, progress, log)
    print(json.dumps(tr.describe(), indent=2, ensure_ascii=False))
    if "--selftest" in argv:
        import wave

        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(16000)
            w.writeframes(b"\x00\x00" * 16000)
        t0 = time.time()
        tr.ensure_model()
        print("modello caricato su %s in %.1f s" % (tr.model_key[1], time.time() - t0))
        res = tr.transcribe(buf.getvalue(), {"vad_filter": False}, lambda *_: None, lambda: False)
        print("selftest ok:", json.dumps(res, ensure_ascii=False))
    if "--file" in argv:
        path = argv[argv.index("--file") + 1]
        lang = argv[argv.index("--lang") + 1] if "--lang" in argv else None
        tr.ensure_model()
        t0 = time.time()
        with open(path, "rb") as f:
            res = tr.transcribe(f.read(), {"language": lang}, lambda *_: None, lambda: False)
        res.update(device=tr.model_key[1], elapsed=round(time.time() - t0, 2))
        print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if "--engine" in sys.argv:
        run_engine()
    elif any(a in sys.argv for a in ("--selftest", "--file", "--info", "--cuda-install", "--download")):
        run_cli(sys.argv[1:])
    else:
        run_native()
