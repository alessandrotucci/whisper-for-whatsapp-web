#!/bin/sh
# Disinstalla il componente locale di Whisper for WhatsApp Web dell'utente corrente.
#
# Cancella le chiavi delle IA dal portachiavi del sistema, chiude i processi del
# componente, toglie i manifest di native messaging e la cartella del programma e,
# salvo --keep-data, la cartella dei dati (configurazione, log, librerie CUDA). I
# modelli scaricati stanno nella cache di Hugging Face (~/.cache/huggingface) e
# restano.
#
# Uso: uninstall.sh [--keep-data]

set -u

HOST_NAME=local.wa_whisper.host
APP=whisper-for-whatsapp-web
PREFIX=${WWT_PREFIX:-$HOME/.local/lib/$APP}
CONFIG=${XDG_CONFIG_HOME:-$HOME/.config}
DATA=${XDG_DATA_HOME:-$HOME/.local/share}/WhisperForWhatsAppWeb
KEEP_DATA=0
[ "${1:-}" = "--keep-data" ] && KEEP_DATA=1

EXE="$PREFIX/current/whisper_host/whisper_host"
if [ "$KEEP_DATA" = 0 ] && [ -x "$EXE" ]; then
    "$EXE" --forget-keys || true
fi

# Prima i manifest: da qui in poi il browser non può più avviare il componente.
if [ -f "$PREFIX/manifests.txt" ]; then
    while IFS= read -r manifest; do
        rm -f "$manifest"
    done < "$PREFIX/manifests.txt"
fi
for dir in "$CONFIG"/*/NativeMessagingHosts "$CONFIG"/*/*/NativeMessagingHosts \
           "$HOME/.mozilla/native-messaging-hosts" "$CONFIG/mozilla/native-messaging-hosts"; do
    if [ -f "$dir/$HOST_NAME.json" ] && grep -q "$PREFIX/" "$dir/$HOST_NAME.json"; then
        rm -f "$dir/$HOST_NAME.json"
    fi
done

# Processi del componente: quelli il cui eseguibile sta sotto PREFIX. Un processo già
# terminato e non ancora raccolto dal padre non ha più eseguibile e non compare.
component_pids() {
    for proc in /proc/[0-9]*; do
        exe=$(readlink "$proc/exe" 2>/dev/null) || continue
        case "$exe" in
            "$PREFIX"/*) echo "${proc#/proc/}" ;;
        esac
    done
}

# SIGTERM, poi fino a 5 s di attesa, poi SIGKILL a chi resta.
pids=$(component_pids)
if [ -n "$pids" ]; then
    kill $pids 2>/dev/null || true
    tries=0
    while [ -n "$(component_pids)" ] && [ "$tries" -lt 10 ]; do
        sleep 0.5
        tries=$((tries + 1))
    done
    pids=$(component_pids)
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
fi

# Socket del motore e relativi lock: in XDG_RUNTIME_DIR oppure nella cartella privata
# wwt-<uid> della cartella temporanea (posix_support.socket_dir).
for dir in ${XDG_RUNTIME_DIR:+"$XDG_RUNTIME_DIR"} "${TMPDIR:-/tmp}/wwt-$(id -u)"; do
    rm -f "$dir"/wwt-engine-*.sock "$dir"/wwt-engine-*.sock.lock
done
rmdir "${TMPDIR:-/tmp}/wwt-$(id -u)" 2>/dev/null || true

# Cartella di lavoro degli aggiornamenti (updater.work_dir).
rm -rf "${XDG_CACHE_HOME:-$HOME/.cache}/whisper-for-whatsapp-web-update"

rm -rf "$PREFIX"
if [ "$KEEP_DATA" = 0 ]; then
    rm -rf "$DATA"
fi
echo "Whisper for WhatsApp Web: componente locale disinstallato / local component uninstalled."
