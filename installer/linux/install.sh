#!/bin/sh
# Installa il componente locale di Whisper for WhatsApp Web per il solo utente
# corrente, senza privilegi di root.
#
# Il programma va in ~/.local/lib/whisper-for-whatsapp-web/versions/<versione>, e
# il link "current" punta alla versione in uso; i manifest di native messaging
# puntano a current/whisper_host/whisper_host, quindi un aggiornamento sposta solo
# il link. Restano la versione nuova e quella precedente, le altre si cancellano.
# I processi in corso continuano con i file della versione che li ha avviati.
#
# I manifest vanno nelle cartelle NativeMessagingHosts dei browser Chromium e in
# ~/.mozilla/native-messaging-hosts per Firefox, solo per i browser di cui esiste la
# cartella dei dati; se non ne esiste nessuna, per Chrome e Firefox. I browser
# installati come Flatpak non leggono queste cartelle.
#
# Uso: ./install.sh            installazione o reinstallazione, con messaggi
#      ./install.sh --update   aggiornamento avviato dal componente, senza domande
#
# WWT_PREFIX cambia la cartella del programma. WWT_CLEAN_AFTER, se impostata, è una
# cartella da cancellare alla fine (quella in cui il componente ha estratto il
# tarball).

set -eu

HOST_NAME=local.wa_whisper.host
APP=whisper-for-whatsapp-web
MIN_GLIBC_MAJOR=2
MIN_GLIBC_MINOR=35

# Un id per ogni pubblicazione dell'estensione: GitHub, Chrome Web Store, Edge Add-ons,
# Opera Add-ons. Gli stessi di installer/setup.iss (tools/release_check.py li confronta).
CHROMIUM_ORIGINS='"chrome-extension://mmfdonlfmjnahpaebjhjlgkpplkfplop/", "chrome-extension://glolhnekllllekiicnekgekcghgpioma/", "chrome-extension://pdkbbmadgjfgbghdanfnahdkecalpgbb/", "chrome-extension://gippfnckahifomefkcjekddjphgdghkp/"'
FIREFOX_IDS='"wa-web-transcriber@alessandrotucci.github.io"'

UPDATE=0
[ "${1:-}" = "--update" ] && UPDATE=1

SRC=$(cd "$(dirname "$0")" && pwd)
VERSION=$(cat "$SRC/VERSION")
PREFIX=${WWT_PREFIX:-$HOME/.local/lib/$APP}
CONFIG=${XDG_CONFIG_HOME:-$HOME/.config}
DATA=${XDG_DATA_HOME:-$HOME/.local/share}/WhisperForWhatsAppWeb

case "${LANG:-}${LC_ALL:-}" in
    it*|*it_IT*) IT=1 ;;
    *) IT=0 ;;
esac

say() {
    if [ "$IT" = 1 ]; then printf '%s\n' "$1"; else printf '%s\n' "$2"; fi
}

fail() {
    say "ERRORE: $1" "ERROR: $2" >&2
    exit 1
}

[ "$UPDATE" = 1 ] && echo "$(date '+%Y-%m-%d %H:%M:%S') install.sh --update $VERSION"

# Controlli: sistema, architettura, glibc.
[ "$(uname -s)" = Linux ] || fail "questo pacchetto è per Linux." "this package is for Linux."
[ "$(uname -m)" = x86_64 ] || fail "serve un processore x86_64 (questo è $(uname -m))." "an x86_64 processor is required (this is $(uname -m))."
GLIBC=$(getconf GNU_LIBC_VERSION 2>/dev/null || true)
case "$GLIBC" in
    glibc\ *) ;;
    *) fail "serve la libreria C glibc (Ubuntu 22.04, Debian 12, Fedora 36 o successive)." "glibc is required (Ubuntu 22.04, Debian 12, Fedora 36 or later)." ;;
esac
GLIBC=${GLIBC#glibc }
G_MAJOR=${GLIBC%%.*}
G_MINOR=${GLIBC#*.}
G_MINOR=${G_MINOR%%.*}
if [ "$G_MAJOR" -lt "$MIN_GLIBC_MAJOR" ] || { [ "$G_MAJOR" -eq "$MIN_GLIBC_MAJOR" ] && [ "$G_MINOR" -lt "$MIN_GLIBC_MINOR" ]; }; then
    fail "glibc $GLIBC è troppo vecchia: serve la $MIN_GLIBC_MAJOR.$MIN_GLIBC_MINOR (Ubuntu 22.04, Debian 12, Fedora 36 o successive)." \
         "glibc $GLIBC is too old: $MIN_GLIBC_MAJOR.$MIN_GLIBC_MINOR is required (Ubuntu 22.04, Debian 12, Fedora 36 or later)."
fi

# Copia della versione nuova accanto alle altre, e prova dell'eseguibile prima di usarla.
mkdir -p "$PREFIX/versions"
mkdir -p "$DATA" && chmod 700 "$DATA"
PREVIOUS=
if [ -L "$PREFIX/current" ]; then
    PREVIOUS=$(basename "$(readlink "$PREFIX/current")")
fi
STAGE="$PREFIX/versions/.$VERSION.tmp"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$SRC/whisper_host" "$SRC/install.sh" "$SRC/uninstall.sh" "$SRC/VERSION" "$STAGE/"
chmod 755 "$STAGE/whisper_host/whisper_host" "$STAGE/install.sh" "$STAGE/uninstall.sh"
if ! "$STAGE/whisper_host/whisper_host" --info >/dev/null 2>"$STAGE/info-error.txt"; then
    cat "$STAGE/info-error.txt" >&2
    rm -rf "$STAGE"
    fail "il componente non parte su questo sistema (dettagli sopra)." "the component does not start on this system (details above)."
fi
rm -f "$STAGE/info-error.txt"
if [ "$PREVIOUS" = "$VERSION" ]; then
    # Reinstallazione della versione in uso: il link passa per un momento alla copia nuova.
    ln -sfn "versions/.$VERSION.tmp" "$PREFIX/current"
fi
rm -rf "$PREFIX/versions/$VERSION"
mv "$STAGE" "$PREFIX/versions/$VERSION"
ln -sfn "versions/$VERSION" "$PREFIX/current.new"
mv -T "$PREFIX/current.new" "$PREFIX/current" 2>/dev/null || {
    rm -f "$PREFIX/current"
    mv "$PREFIX/current.new" "$PREFIX/current"
}

EXE="$PREFIX/current/whisper_host/whisper_host"
EXE_JSON=$(printf '%s' "$EXE" | sed 's/\\/\\\\/g; s/"/\\"/g')

# Manifest di native messaging.
WRITTEN=0
: > "$PREFIX/manifests.txt"

write_manifest() {
    # $1 cartella, $2 campo degli id ammessi, $3 id
    mkdir -p "$1"
    cat > "$1/$HOST_NAME.json" <<EOF
{
  "name": "$HOST_NAME",
  "description": "Whisper for WhatsApp Web - trascrizione locale",
  "path": "$EXE_JSON",
  "type": "stdio",
  "$2": [$3]
}
EOF
    echo "$1/$HOST_NAME.json" >> "$PREFIX/manifests.txt"
    WRITTEN=$((WRITTEN + 1))
}

for browser in google-chrome google-chrome-beta google-chrome-unstable chromium microsoft-edge microsoft-edge-beta \
               microsoft-edge-dev BraveSoftware/Brave-Browser BraveSoftware/Brave-Browser-Beta vivaldi vivaldi-snapshot \
               opera opera-beta opera-developer; do
    if [ -d "$CONFIG/$browser" ]; then
        write_manifest "$CONFIG/$browser/NativeMessagingHosts" allowed_origins "$CHROMIUM_ORIGINS"
    fi
done
# Firefox, anche quando è la snap di Ubuntu: lì il manifest lo legge il portale di sistema.
if [ -d "$HOME/.mozilla" ] || [ -d "$HOME/snap/firefox" ] || command -v firefox >/dev/null 2>&1; then
    write_manifest "$HOME/.mozilla/native-messaging-hosts" allowed_extensions "$FIREFOX_IDS"
fi
if [ -d "$CONFIG/mozilla" ]; then
    write_manifest "$CONFIG/mozilla/native-messaging-hosts" allowed_extensions "$FIREFOX_IDS"
fi
if [ "$WRITTEN" = 0 ]; then
    write_manifest "$CONFIG/google-chrome/NativeMessagingHosts" allowed_origins "$CHROMIUM_ORIGINS"
    write_manifest "$HOME/.mozilla/native-messaging-hosts" allowed_extensions "$FIREFOX_IDS"
fi

# Restano la versione in uso e la precedente.
for dir in "$PREFIX"/versions/* "$PREFIX"/versions/.*.tmp; do
    [ -d "$dir" ] || continue
    name=$(basename "$dir")
    if [ "$name" != "$VERSION" ] && [ "$name" != "$PREVIOUS" ]; then
        rm -rf "$dir"
    fi
done

if [ -n "${WWT_CLEAN_AFTER:-}" ] && [ -d "$WWT_CLEAN_AFTER" ]; then
    rm -rf "$WWT_CLEAN_AFTER"
fi

if [ "$UPDATE" = 1 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') installata la versione $VERSION, manifest: $WRITTEN"
    exit 0
fi

say "" ""
say "Componente locale $VERSION installato in $PREFIX." "Local component $VERSION installed in $PREFIX."
say "Manifest scritti: $WRITTEN (elenco in $PREFIX/manifests.txt)." "Manifests written: $WRITTEN (list in $PREFIX/manifests.txt)."
say "Torna al browser: la pagina dell'estensione lo rileva da sola." "Go back to the browser: the extension page detects it automatically."
say "Per disinstallarlo: $PREFIX/current/uninstall.sh" "To uninstall it: $PREFIX/current/uninstall.sh"
