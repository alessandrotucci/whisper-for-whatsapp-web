"""Tarball del componente locale per Linux, dall'output di PyInstaller.

Il tarball contiene una sola cartella, whisper-for-whatsapp-web/, con:
  whisper_host/   l'output onedir di PyInstaller (build/dist/whisper_host);
  install.sh, uninstall.sh   da installer/linux;
  VERSION         la versione del componente (version.json, campo "host").

I permessi si fissano qui e non dipendono dal file system di partenza: 755 per gli
eseguibili (il programma, gli script, le librerie .so), 644 per il resto. Proprietario
e date sono neutri, così il contenuto dipende solo dai file.

Uso: python tools/package_linux.py [--dist build/dist/whisper_host] [--out build/installer/whisper-for-whatsapp-web-linux-x86_64.tar.gz]
"""
import argparse
import io
import json
import os
import tarfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOP = "whisper-for-whatsapp-web"
ASSET = "whisper-for-whatsapp-web-linux-x86_64.tar.gz"


def is_executable(rel):
    name = os.path.basename(rel)
    return rel == "whisper_host/whisper_host" or name.endswith(".sh") or ".so" in name


def add_file(tar, src, arcname, mode):
    info = tar.gettarinfo(src, arcname)
    info.mode = mode
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.mtime = 0
    if info.isreg():
        with open(src, "rb") as f:
            tar.addfile(info, f)
    else:
        tar.addfile(info)


def add_dir(tar, arcname):
    info = tarfile.TarInfo(arcname)
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    tar.addfile(info)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist", default=os.path.join(ROOT, "build", "dist", "whisper_host"))
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "installer", ASSET))
    a = ap.parse_args()

    version = json.load(open(os.path.join(ROOT, "version.json"), encoding="utf-8"))["host"]
    exe = os.path.join(a.dist, "whisper_host")
    if not os.path.isfile(exe):
        raise SystemExit("manca %s: va prima costruito con PyInstaller su Linux" % exe)

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with tarfile.open(a.out, "w:gz", format=tarfile.PAX_FORMAT) as tar:
        add_dir(tar, TOP)
        add_dir(tar, TOP + "/whisper_host")
        for base, dirs, names in os.walk(a.dist):
            dirs.sort()
            rel_base = os.path.relpath(base, a.dist).replace(os.sep, "/")
            for d in dirs:
                rel = d if rel_base == "." else rel_base + "/" + d
                path = os.path.join(base, d)
                if os.path.islink(path):
                    add_file(tar, path, TOP + "/whisper_host/" + rel, 0o777)
                else:
                    add_dir(tar, TOP + "/whisper_host/" + rel)
            for n in sorted(names):
                rel = n if rel_base == "." else rel_base + "/" + n
                full_rel = "whisper_host/" + rel
                add_file(tar, os.path.join(base, n), TOP + "/" + full_rel, 0o755 if is_executable(full_rel) else 0o644)
        for script in ("install.sh", "uninstall.sh"):
            add_file(tar, os.path.join(ROOT, "installer", "linux", script), TOP + "/" + script, 0o755)
        data = (version + "\n").encode("utf-8")
        info = tarfile.TarInfo(TOP + "/VERSION")
        info.size = len(data)
        info.mode = 0o644
        tar.addfile(info, io.BytesIO(data))
    print("%s (%d MB), componente %s" % (a.out, os.path.getsize(a.out) // 1000000, version))


if __name__ == "__main__":
    main()
