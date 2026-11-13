"""Esecuzione su GPU NVIDIA: driver, librerie CUDA, attivazione.

Per girare su GPU ctranslate2 ha bisogno di tre cose oltre a sé stesso:

il driver NVIDIA, cioè nvcuda.dll di sistema, con supporto per CUDA 12;
cuBLAS 12 (cublas64_12.dll e cublasLt64_12.dll), che ctranslate2 carica per nome
quando un modello gira su GPU;
le sotto-librerie di cuDNN 9 (cudnn_*64_9.dll), della stessa versione di
cudnn64_9.dll distribuito con ctranslate2: cuDNN rifiuta sotto-librerie di
versione diversa dal caricatore.

Le librerie non sono nell'installer: si scaricano su richiesta dai wheel NVIDIA
pubblicati su PyPI, con indirizzo, dimensione e SHA-256 fissati in WHEELS, e se ne
estraggono le DLL nella cartella "cuda" dei dati del componente. Un file
installed.json, scritto per ultimo, segna l'installazione completa.

Il driver non si scarica: è un driver di sistema, dipende dalla scheda e richiede
privilegi di amministratore. Senza driver la GPU non è utilizzabile.

activate() rende le DLL raggiungibili da ctranslate2 (os.add_dll_directory e PATH) e
va chiamata prima di creare un modello su GPU.
"""

import ctypes
import hashlib
import json
import os
import shutil
import urllib.request
import zipfile

CUDNN_VERSION = "9.10.2.21"
CUBLAS_VERSION = "12.8.4.1"

WHEELS = [
    {
        "name": "cublas",
        "version": CUBLAS_VERSION,
        "url": "https://files.pythonhosted.org/packages/70/61/7d7b3c70186fb651d0fbd35b01dbfc8e755f69fd58f817f3d0f642df20c3/"
               "nvidia_cublas_cu12-12.8.4.1-py3-none-win_amd64.whl",
        "size": 567544208,
        "sha256": "47e9b82132fa8d2b4944e708049229601448aaad7e6f296f630f2d1a32de35af",
        "files": ["cublas64_12.dll", "cublasLt64_12.dll"],
    },
    {
        "name": "cudnn",
        "version": CUDNN_VERSION,
        "url": "https://files.pythonhosted.org/packages/3d/90/0bd6e586701b3a890fd38aa71c387dab4883d619d6e5ad912ccbd05bfd67/"
               "nvidia_cudnn_cu12-9.10.2.21-py3-none-win_amd64.whl",
        "size": 692992268,
        "sha256": "c6288de7d63e6cf62988f0923f96dc339cea362decb1bf5b3141883392a7d65e",
        "files": None,
    },
]

DOWNLOAD_SIZE = sum(w["size"] for w in WHEELS)

# cuDriverGetVersion restituisce 1000 * major + 10 * minor: 12000 è CUDA 12.0.
MIN_DRIVER_VERSION = 12000
MARKER = "installed.json"
LAST_ERROR = "cuda_error.txt"
USER_AGENT = "whisper-for-whatsapp-web-cuda"

_driver_cache = None
_dll_dir_handle = None


def cuda_dir(data_dir):
    return os.path.join(data_dir, "cuda")


def driver_info():
    """Stato del driver NVIDIA: presenza, versione CUDA supportata, schede."""
    global _driver_cache
    if _driver_cache is not None:
        return _driver_cache
    info = {"present": False, "usable": False}
    if os.name != "nt":
        _driver_cache = info
        return info
    try:
        nv = ctypes.WinDLL("nvcuda.dll")
    except OSError:
        _driver_cache = info
        return info
    info["present"] = True
    try:
        rc = nv.cuInit(0)
        if rc != 0:
            info["error"] = "cuInit %d" % rc
            _driver_cache = info
            return info
        ver = ctypes.c_int(0)
        nv.cuDriverGetVersion(ctypes.byref(ver))
        info["version"] = ver.value
        info["version_str"] = "%d.%d" % (ver.value // 1000, (ver.value % 1000) // 10)
        count = ctypes.c_int(0)
        nv.cuDeviceGetCount(ctypes.byref(count))
        names = []
        for i in range(count.value):
            dev = ctypes.c_int(0)
            if nv.cuDeviceGet(ctypes.byref(dev), i) != 0:
                continue
            buf = ctypes.create_string_buffer(256)
            if nv.cuDeviceGetName(buf, 256, dev) == 0:
                names.append(buf.value.decode("utf-8", "replace"))
        info["devices"] = count.value
        info["names"] = names
        info["usable"] = ver.value >= MIN_DRIVER_VERSION and count.value > 0
    except Exception as e:  # noqa: BLE001
        info["error"] = str(e)
    _driver_cache = info
    return info


def libs_info(data_dir):
    """Stato delle librerie scaricate: installate se c'è il marcatore e ci sono tutti i file."""
    d = cuda_dir(data_dir)
    try:
        with open(os.path.join(d, MARKER), encoding="utf-8") as f:
            marker = json.load(f)
    except (OSError, ValueError):
        return {"installed": False, "dir": d, "download_size": DOWNLOAD_SIZE}
    missing = [n for n in marker.get("files", []) if not os.path.isfile(os.path.join(d, n))]
    return {
        "installed": not missing,
        "dir": d,
        "versions": marker.get("versions", {}),
        "missing": missing,
        "download_size": DOWNLOAD_SIZE,
    }


def last_error(data_dir):
    try:
        with open(os.path.join(cuda_dir(data_dir), LAST_ERROR), encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def set_last_error(data_dir, message):
    path = os.path.join(cuda_dir(data_dir), LAST_ERROR)
    if message:
        os.makedirs(cuda_dir(data_dir), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(message)
    else:
        try:
            os.remove(path)
        except OSError:
            pass


def status(data_dir):
    drv = driver_info()
    libs = libs_info(data_dir)
    return {
        "driver": drv,
        "libs": libs,
        "usable": bool(drv.get("usable") and libs.get("installed")),
        "last_error": last_error(data_dir),
    }


def activate(data_dir):
    """Rende le DLL della cartella cuda raggiungibili dal caricamento per nome di ctranslate2."""
    global _dll_dir_handle
    d = cuda_dir(data_dir)
    if not os.path.isdir(d):
        return False
    if _dll_dir_handle is None and hasattr(os, "add_dll_directory"):
        _dll_dir_handle = os.add_dll_directory(d)
    path = os.environ.get("PATH", "")
    if d.lower() not in [p.lower() for p in path.split(os.pathsep)]:
        os.environ["PATH"] = d + os.pathsep + path
    return True


def _download(wheel, dest, on_progress, base):
    """Scarica un wheel verificandone dimensione e SHA-256; on_progress(byte totali scaricati)."""
    req = urllib.request.Request(wheel["url"], headers={"User-Agent": USER_AGENT})
    h = hashlib.sha256()
    done = 0
    with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
        while True:
            chunk = resp.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
            h.update(chunk)
            done += len(chunk)
            on_progress(base + done)
    if done != wheel["size"] or h.hexdigest() != wheel["sha256"]:
        raise RuntimeError("download di %s non valido (%d byte, sha256 %s)" % (wheel["name"], done, h.hexdigest()[:16]))


def _extract(wheel, whl_path, target):
    """Estrae le DLL della cartella bin del wheel in target; restituisce i nomi estratti."""
    names = []
    with zipfile.ZipFile(whl_path) as z:
        for member in z.infolist():
            parts = member.filename.split("/")
            if len(parts) < 2 or parts[-2] != "bin" or not parts[-1].lower().endswith(".dll"):
                continue
            name = parts[-1]
            if wheel["files"] is not None and name not in wheel["files"]:
                continue
            with z.open(member) as src, open(os.path.join(target, name), "wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
            names.append(name)
    if wheel["files"] is not None and sorted(names) != sorted(wheel["files"]):
        raise RuntimeError("file mancanti nel wheel %s" % wheel["name"])
    if not names:
        raise RuntimeError("nessuna DLL nel wheel %s" % wheel["name"])
    return names


def install(data_dir, on_progress, log):
    """Scarica ed estrae cuBLAS e cuDNN; on_progress(fase, fatti, totale) con fase download o extract."""
    d = cuda_dir(data_dir)
    os.makedirs(d, exist_ok=True)
    try:
        os.remove(os.path.join(d, MARKER))
    except OSError:
        pass
    files = []
    base = 0
    for wheel in WHEELS:
        whl = os.path.join(d, wheel["name"] + ".whl.part")
        log("CUDA: scarico %s %s" % (wheel["name"], wheel["version"]))
        try:
            _download(wheel, whl, lambda n: on_progress("download", n, DOWNLOAD_SIZE), base)
            on_progress("extract", base + wheel["size"], DOWNLOAD_SIZE)
            files += _extract(wheel, whl, d)
        finally:
            try:
                os.remove(whl)
            except OSError:
                pass
        base += wheel["size"]
    marker = {"versions": {w["name"]: w["version"] for w in WHEELS}, "files": sorted(files)}
    with open(os.path.join(d, MARKER), "w", encoding="utf-8") as f:
        json.dump(marker, f, indent=2)
    set_last_error(data_dir, None)
    log("CUDA: librerie installate in %s (%d file)" % (d, len(files)))
    return libs_info(data_dir)
