"""Host di native messaging che trascrive audio con faster-whisper.

Il browser avvia il processo e gli parla su stdin/stdout con il protocollo di
native messaging: ogni messaggio è un intero a 32 bit little-endian con la
lunghezza, seguito da JSON in UTF-8. Il processo resta vivo finché la porta del
browser è aperta e tiene il modello in memoria tra una richiesta e l'altra.

Richieste (il campo "rid", se presente, torna nella risposta):
  {"type": "ping"}                                   -> pong
  {"type": "models"}                                 -> models
  {"type": "set_config", "config": {...}}            -> pong
  {"type": "download_model", "name": "large-v3"}     -> download_progress..., download_done | error
  {"type": "pick_folder"}                            -> folder_picked
  {"type": "transcribe", "id": "...", "audio": "<base64>", "language": "pt"|null,
   "initial_prompt": "...", "beam_size": 5, "vad_filter": true}
  {"type": "cancel", "id": "..."}
  {"type": "update_host", "url": "..."}              -> update_progress..., update_host_started
  {"type": "update_extension", "url": "...", "ext_id": "...", "expect": "1.3.0"}
                                                     -> update_progress..., update_extension_done
  {"type": "extension_dirs", "ext_id": "..."}        -> extension_dirs

Eventi di una trascrizione (campo "id"):
  status (waiting_lock | loading_model | transcribing), partial, result, error

Lavori lunghi (trascrizioni, download, cambi di configurazione) passano da un
solo thread, in ordine. Un lock su file condiviso tra processi impedisce due
trascrizioni contemporanee anche quando Chrome ed Edge avviano ciascuno il
proprio host.

I dati (config.json, log, lock) stanno in DATA_DIR: la variabile d'ambiente
WWT_DATA_DIR, altrimenti %LOCALAPPDATA%\\WhisperForWhatsAppWeb per l'eseguibile
impacchettato e la cartella dello script quando gira da sorgente.

Da riga di comando: "--info" stampa lo stato, "--selftest" carica il modello e
trascrive un secondo di silenzio, "--file PERCORSO [--lang xx]" trascrive un file.
"""

import base64
import io
import json
import os
import queue
import struct
import subprocess
import sys
import threading
import time
import traceback

HOST_VERSION = "1.3.1"
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
        return info

    def models(self):
        current = self.cfg.get("model")
        out = []
        for choice in MODEL_CHOICES:
            out.append(dict(choice, path=local_model_path(choice["name"]), current=choice["name"] == current))
        if current and not any(c["name"] == current for c in MODEL_CHOICES):
            out.append({"name": current, "size_gb": None, "note": "cartella", "path": local_model_path(current), "current": True})
        return out

    def ensure_model(self):
        cfg = self.cfg
        key = (cfg["model"], cfg["device"], cfg["compute_type"], cfg.get("cpu_threads", 0))
        if self.model is not None and self.model_key == key:
            return
        path = local_model_path(cfg["model"])
        if path is None:
            raise ModelMissing("E:model_missing:%s" % cfg["model"])
        from faster_whisper import WhisperModel

        self.model = None
        t0 = time.time()
        self.model = WhisperModel(
            path,
            device=cfg["device"],
            compute_type=cfg["compute_type"],
            cpu_threads=int(cfg.get("cpu_threads") or 0),
        )
        self.model_key = key
        self.log("modello caricato da %s in %.1f s" % (path, time.time() - t0))

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


class Host:
    def __init__(self, out, log):
        self.out = out
        self.log = log
        self.write_lock = threading.Lock()
        self.jobs = queue.Queue()
        self.cancelled = set()
        self.tr = Transcriber(log)

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
        self.jobs.put(None)

    def dispatch(self, msg):
        kind = msg.get("type")
        if kind == "ping":
            self.reply(msg, dict(type="pong", **self.tr.describe()))
        elif kind == "models":
            self.reply(msg, {"type": "models", "models": self.tr.models(), "config": self.tr.cfg})
        elif kind == "cancel":
            self.cancelled.add(msg.get("id"))
        elif kind == "pick_folder":
            threading.Thread(target=self.do_pick_folder, args=(msg,), daemon=True).start()
        elif kind == "extension_dirs":
            import updater

            dirs = [{"browser": b, "profile": p, "path": d, "release": m} for b, p, d, m in updater.extension_dirs(msg.get("ext_id"))]
            self.reply(msg, {"type": "extension_dirs", "dirs": dirs})
        elif kind in ("transcribe", "set_config", "download_model", "update_host", "update_extension"):
            self.jobs.put(msg)
        else:
            self.reply(msg, {"type": "error", "id": msg.get("id"), "message": "tipo sconosciuto: %s" % kind})

    def do_pick_folder(self, msg):
        try:
            path = pick_folder()
            self.reply(msg, {"type": "folder_picked", "path": path, "valid": is_model_dir(path)})
        except Exception as e:  # noqa: BLE001
            self.reply(msg, {"type": "error", "message": "selezione cartella: %s" % e})

    def worker(self):
        lock = FileLock(LOCK_PATH)
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
                elif kind == "update_host":
                    self.do_update_host(msg)
                elif kind == "update_extension":
                    self.do_update_extension(msg)
                else:
                    self.do_transcribe(msg, lock)
            except Exception as e:  # noqa: BLE001
                self.log(traceback.format_exc())
                self.reply(msg, {"type": "error", "id": msg.get("id"), "message": "%s: %s" % (type(e).__name__, e)})

    def do_transcribe(self, msg, lock):
        job_id = msg.get("id")
        if job_id in self.cancelled:
            self.cancelled.discard(job_id)
            self.send({"type": "error", "id": job_id, "message": "annullata", "cancelled": True})
            return
        t0 = time.time()
        try:
            audio = base64.b64decode(msg["audio"])
            lock.acquire(on_wait=lambda: self.send({"type": "status", "id": job_id, "state": "waiting_lock"}))
            try:
                if self.tr.model is None:
                    self.send({"type": "status", "id": job_id, "state": "loading_model"})
                self.tr.ensure_model()
                self.send({"type": "status", "id": job_id, "state": "transcribing"})
                res = self.tr.transcribe(
                    audio,
                    msg,
                    lambda text, p: self.send({"type": "partial", "id": job_id, "text": text, "progress": p}),
                    lambda: job_id in self.cancelled,
                )
            finally:
                lock.release()
            res.update(type="result", id=job_id, elapsed=round(time.time() - t0, 2))
            self.log("trascritto %s: %.1f s audio in %.1f s" % (job_id, res["duration"], res["elapsed"]))
            self.send(res)
        except InterruptedError:
            self.send({"type": "error", "id": job_id, "message": "annullata", "cancelled": True})
        except Exception as e:  # noqa: BLE001
            self.log(traceback.format_exc())
            self.send({"type": "error", "id": job_id, "message": "%s: %s" % (type(e).__name__, e)})
        finally:
            self.cancelled.discard(job_id)

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


def open_log():
    os.makedirs(DATA_DIR, exist_ok=True)
    fh = open(LOG_PATH, "a", encoding="utf-8", buffering=1)

    def log(text):
        fh.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), text))

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
    host = Host(proto_out, log)
    t = threading.Thread(target=host.worker, daemon=True)
    t.start()
    host.reader(PipeReader(0))
    log("stdin chiuso, uscita")
    t.join(timeout=2)


def run_cli(argv):
    def log(text):
        print(text, file=sys.stderr)

    tr = Transcriber(log)
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
        print("modello caricato in %.1f s" % (time.time() - t0))
        res = tr.transcribe(buf.getvalue(), {"vad_filter": False}, lambda *_: None, lambda: False)
        print("selftest ok:", json.dumps(res, ensure_ascii=False))
    if "--file" in argv:
        path = argv[argv.index("--file") + 1]
        lang = argv[argv.index("--lang") + 1] if "--lang" in argv else None
        tr.ensure_model()
        with open(path, "rb") as f:
            res = tr.transcribe(f.read(), {"language": lang}, lambda *_: None, lambda: False)
        print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if any(a in sys.argv for a in ("--selftest", "--file", "--info")):
        run_cli(sys.argv[1:])
    else:
        run_native()
