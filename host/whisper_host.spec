# Spec di PyInstaller: whisper_host.exe in modalità cartella (onedir).
# La modalità cartella evita l'estrazione in una cartella temporanea a ogni avvio
# dell'host, che con ctranslate2 e onnxruntime richiederebbe secondi.
#
# Uso: pyinstaller --noconfirm --distpath build/dist --workpath build/work host/whisper_host.spec

from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs

datas = collect_data_files("faster_whisper")  # assets: modello VAD silero
binaries = collect_dynamic_libs("ctranslate2")

a = Analysis(
    ["whisper_host.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=["huggingface_hub", "tokenizers", "updater", "cuda_libs", "multiprocessing.connection"],
    excludes=["tkinter", "matplotlib", "IPython", "pytest", "torch"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="whisper_host",
    console=True,
    upx=False,
)
coll = COLLECT(exe, a.binaries, a.datas, name="whisper_host", upx=False)
