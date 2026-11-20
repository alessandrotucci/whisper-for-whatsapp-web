"""Chiavi delle IA nel portachiavi del sistema su Linux, tramite l'API Secret Service.

Secret Service è l'interfaccia D-Bus di GNOME Keyring, KWallet e KeePassXC. Ogni
chiave è un elemento della raccolta predefinita, riconosciuto dagli attributi
APP_ATTR e "id"; in ai.json resta solo l'id.

La sessione D-Bus usa l'algoritmo "plain": il segreto viaggia in chiaro sul bus
della sessione, che è locale e dell'utente. Una raccolta bloccata o un'operazione
che chiede una conferma all'utente (prompt) valgono come portachiavi non
disponibile: il componente gira senza finestre e non può rispondere a un prompt.

Le connessioni durano una sola operazione. jeepney è Python puro e parla D-Bus
direttamente sul socket della sessione.
"""

import secrets

APP_ATTR = {"application": "whisper-for-whatsapp-web"}
LABEL = "Whisper for WhatsApp Web: chiave di un fornitore di IA"
TIMEOUT_S = 5

BUS = "org.freedesktop.secrets"
SERVICE_PATH = "/org/freedesktop/secrets"
SERVICE_IFACE = "org.freedesktop.Secret.Service"
COLLECTION_IFACE = "org.freedesktop.Secret.Collection"
ITEM_IFACE = "org.freedesktop.Secret.Item"
PROPS_IFACE = "org.freedesktop.DBus.Properties"
NO_PROMPT = "/"


class Unavailable(RuntimeError):
    """Portachiavi assente, bloccato o non raggiungibile."""


class _Session:
    def __init__(self):
        try:
            from jeepney.io.blocking import open_dbus_connection

            self.conn = open_dbus_connection(bus="SESSION")
        except Exception as e:  # noqa: BLE001
            raise Unavailable("bus della sessione: %s" % e) from e
        try:
            _output, self.path = self.call(SERVICE_PATH, SERVICE_IFACE, "OpenSession", "sv", ("plain", ("s", "")))
        except Exception:
            self.conn.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        self.conn.close()

    def call(self, path, iface, method, signature=None, body=()):
        from jeepney import DBusAddress, HeaderFields, MessageType, new_method_call

        msg = new_method_call(DBusAddress(path, bus_name=BUS, interface=iface), method, signature, body)
        try:
            reply = self.conn.send_and_get_reply(msg, timeout=TIMEOUT_S)
        except Exception as e:  # noqa: BLE001
            raise Unavailable("%s: %s" % (method, e)) from e
        if reply.header.message_type == MessageType.error:
            raise Unavailable("%s: %s %s" % (method, reply.header.fields.get(HeaderFields.error_name, ""), reply.body))
        return reply.body

    def default_collection(self):
        (path,) = self.call(SERVICE_PATH, SERVICE_IFACE, "ReadAlias", "s", ("default",))
        if path == NO_PROMPT:
            raise Unavailable("nessuna raccolta predefinita")
        (locked,) = self.call(path, PROPS_IFACE, "Get", "ss", (COLLECTION_IFACE, "Locked"))
        if locked[1]:
            raise Unavailable("raccolta predefinita bloccata")
        return path

    def find(self, key_id):
        attrs = dict(APP_ATTR, id=key_id)
        unlocked, locked = self.call(SERVICE_PATH, SERVICE_IFACE, "SearchItems", "a{ss}", (attrs,))
        if not unlocked and locked:
            raise Unavailable("chiave in una raccolta bloccata")
        return unlocked


def available():
    """Vero se il portachiavi c'è, ha una raccolta predefinita ed è sbloccato."""
    try:
        with _Session() as s:
            s.default_collection()
        return True
    except Unavailable:
        return False


def store(secret):
    """Salva secret nel portachiavi; restituisce l'id con cui ritrovarlo."""
    key_id = secrets.token_hex(8)
    with _Session() as s:
        collection = s.default_collection()
        props = {
            ITEM_IFACE + ".Label": ("s", LABEL),
            ITEM_IFACE + ".Attributes": ("a{ss}", dict(APP_ATTR, id=key_id)),
        }
        value = (s.path, b"", secret.encode("utf-8"), "text/plain; charset=utf8")
        item, prompt = s.call(collection, COLLECTION_IFACE, "CreateItem", "a{sv}(oayays)b", (props, value, True))
        if prompt != NO_PROMPT or item == NO_PROMPT:
            raise Unavailable("il portachiavi chiede una conferma")
    return key_id


def load(key_id):
    with _Session() as s:
        items = s.find(key_id)
        if not items:
            raise KeyError("chiave %s non trovata nel portachiavi" % key_id)
        ((_session, _params, value, _ctype),) = s.call(items[0], ITEM_IFACE, "GetSecret", "o", (s.path,))
    return bytes(value).decode("utf-8")


def delete(key_id):
    """Cancella la chiave key_id; vero se c'era."""
    with _Session() as s:
        items = s.find(key_id)
        for item in items:
            s.call(item, ITEM_IFACE, "Delete")
    return bool(items)


def delete_all():
    """Cancella tutte le chiavi del componente; restituisce quante erano."""
    with _Session() as s:
        unlocked, _locked = s.call(SERVICE_PATH, SERVICE_IFACE, "SearchItems", "a{ss}", (APP_ATTR,))
        for item in unlocked:
            s.call(item, ITEM_IFACE, "Delete")
    return len(unlocked)
