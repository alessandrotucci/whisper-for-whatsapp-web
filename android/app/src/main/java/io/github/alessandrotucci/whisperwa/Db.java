package io.github.alessandrotucci.whisperwa;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Archivio dei vocali e delle loro trascrizioni, che fa anche da coda persistente: una riga
 * PENDING è un vocale da trascrivere, e sopravvive alla chiusura del processo.
 *
 * <p>source identifica l'audio ed è unico: l'URI del documento nella cartella di WhatsApp, oppure
 * "share:" seguito da un UUID per i file condivisi, copiati in local_path.
 *
 * <p>text è sempre il testo di Whisper. La revisione con l'IA ha uno stato suo (review_state):
 * una riga trascritta con revisione in attesa è anch'essa lavoro per la coda. review_text e
 * review_segments sono il testo corretto e i suoi pezzi [testo, testo di Whisper o null] in
 * JSON; translation è la traduzione in target_lang. show_whisper sceglie quale testo mostrare.
 */
public final class Db extends SQLiteOpenHelper {

    public static final int PENDING = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int ERROR = 3;

    public static final int REVIEW_NONE = 0;
    public static final int REVIEW_PENDING = 1;
    public static final int REVIEW_RUNNING = 2;
    public static final int REVIEW_DONE = 3;
    public static final int REVIEW_FAILED = 4;

    /** Una riga della tabella notes. */
    public static final class Note {
        public long id;
        public String pkg;
        public String chat;
        public String sender;
        public String source;
        public String name;
        public String localPath;
        public long received;
        public int status;
        public String text;
        public String language;
        public long audioMs;
        public long workMs;
        public String model;
        public String error;
        public int attempts;
        public int reviewState;
        public String reviewText;
        public String reviewSegments;
        public String translation;
        public String targetLang;
        public String reviewProvider;
        public String reviewError;
        public boolean showWhisper;

        /** Vero se c'è un testo rivisto diverso da mostrare al posto di quello di Whisper. */
        public boolean hasRevision() {
            return reviewState == REVIEW_DONE && reviewText != null;
        }

        /** Il testo da mostrare e copiare: quello rivisto, salvo scelta contraria. */
        public String shownText() {
            return hasRevision() && !showWhisper ? reviewText : text;
        }

        /** Mittente e chat in una riga, oppure il nome del file se non si conoscono. */
        public String title() {
            if (sender != null && chat != null && !chat.equals(sender)) return sender + " · " + chat;
            if (sender != null) return sender;
            if (chat != null) return chat;
            return name;
        }
    }

    private static Db instance;

    public static synchronized Db get(Context context) {
        if (instance == null) instance = new Db(context.getApplicationContext());
        return instance;
    }

    private Db(Context context) {
        super(context, "notes.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "pkg TEXT,"
                + "chat TEXT,"
                + "sender TEXT,"
                + "source TEXT NOT NULL UNIQUE,"
                + "name TEXT,"
                + "local_path TEXT,"
                + "received INTEGER NOT NULL,"
                + "status INTEGER NOT NULL,"
                + "text TEXT,"
                + "language TEXT,"
                + "audio_ms INTEGER,"
                + "work_ms INTEGER,"
                + "model TEXT,"
                + "error TEXT,"
                + "attempts INTEGER NOT NULL DEFAULT 0,"
                + REVIEW_COLUMNS + ")");
        db.execSQL("CREATE INDEX notes_status ON notes(status, received)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE notes ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 3) {
            for (String col : REVIEW_COLUMNS.split(",")) db.execSQL("ALTER TABLE notes ADD COLUMN " + col);
        }
    }

    private static final String REVIEW_COLUMNS = "review_state INTEGER NOT NULL DEFAULT 0,"
            + "review_text TEXT,"
            + "review_segments TEXT,"
            + "translation TEXT,"
            + "target_lang TEXT,"
            + "review_provider TEXT,"
            + "review_model TEXT,"
            + "review_error TEXT,"
            + "show_whisper INTEGER NOT NULL DEFAULT 0";

    /** La riga trascritta con revisione in attesa da più tempo, oppure null. */
    public Note nextReviewPending() {
        List<Note> l = query("status = " + DONE + " AND review_state = " + REVIEW_PENDING, null,
                "received ASC, id ASC", "1");
        return l.isEmpty() ? null : l.get(0);
    }

    public void setReviewState(long id, int state) {
        ContentValues v = new ContentValues();
        v.put("review_state", state);
        if (state == REVIEW_PENDING) v.putNull("review_error");
        update(id, v);
    }

    public void markReviewDone(long id, String text, String segments, String translation, String targetLang,
                               String provider, String model) {
        ContentValues v = new ContentValues();
        v.put("review_state", REVIEW_DONE);
        v.put("review_text", text);
        v.put("review_segments", segments);
        v.put("translation", translation);
        v.put("target_lang", targetLang);
        v.put("review_provider", provider);
        v.put("review_model", model);
        v.putNull("review_error");
        update(id, v);
    }

    public void markReviewFailed(long id, String error) {
        ContentValues v = new ContentValues();
        v.put("review_state", REVIEW_FAILED);
        v.put("review_error", error);
        update(id, v);
    }

    public void setShowWhisper(long id, boolean show) {
        ContentValues v = new ContentValues();
        v.put("show_whisper", show ? 1 : 0);
        update(id, v);
    }

    /** Inserisce un vocale in coda; restituisce l'id, oppure -1 se source è già presente. */
    public long insertPending(String pkg, String chat, String sender, String source, String name,
                              String localPath, long received) {
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("chat", chat);
        v.put("sender", sender);
        v.put("source", source);
        v.put("name", name);
        v.put("local_path", localPath);
        v.put("received", received);
        v.put("status", PENDING);
        return getWritableDatabase().insertWithOnConflict("notes", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public boolean hasSource(String source) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM notes WHERE source = ? LIMIT 1", new String[]{source})) {
            return c.moveToFirst();
        }
    }

    /** Il vocale in coda da più tempo, oppure null. */
    public Note nextPending() {
        List<Note> l = query("status = " + PENDING, null, "received ASC, id ASC", "1");
        return l.isEmpty() ? null : l.get(0);
    }

    public Note byId(long id) {
        List<Note> l = query("id = ?", new String[]{Long.toString(id)}, null, "1");
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Note> recent(int limit) {
        return query(null, null, "received DESC, id DESC", Integer.toString(limit));
    }

    public List<Note> lastDone(int limit) {
        return query("status = " + DONE, null, "received DESC, id DESC", Integer.toString(limit));
    }

    public int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM notes WHERE status IN (" + PENDING + "," + RUNNING + ")", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Segna la riga in corso e conta il tentativo: un processo ucciso a metà lascia il conto. */
    public void markRunning(long id) {
        getWritableDatabase().execSQL("UPDATE notes SET status = " + RUNNING
                + ", error = NULL, attempts = attempts + 1 WHERE id = ?", new Object[]{id});
    }

    public void markDone(long id, String text, String language, long audioMs, long workMs, String model) {
        ContentValues v = new ContentValues();
        v.put("status", DONE);
        v.put("text", text);
        v.put("language", language);
        v.put("audio_ms", audioMs);
        v.put("work_ms", workMs);
        v.put("model", model);
        v.putNull("error");
        v.putNull("local_path");
        v.put("review_state", REVIEW_NONE);
        v.putNull("review_text");
        v.putNull("review_segments");
        v.putNull("translation");
        v.putNull("review_provider");
        v.putNull("review_error");
        v.put("show_whisper", 0);
        update(id, v);
    }

    public void markError(long id, String error) {
        ContentValues v = new ContentValues();
        v.put("status", ERROR);
        v.put("error", error);
        update(id, v);
    }

    /** Rimette in coda la riga in corso senza contare il tentativo appena interrotto. */
    public void requeueUncounted(long id) {
        getWritableDatabase().execSQL("UPDATE notes SET status = " + PENDING
                + ", attempts = MAX(attempts - 1, 0) WHERE id = ?", new Object[]{id});
    }

    public void requeue(long id) {
        ContentValues v = new ContentValues();
        v.put("status", PENDING);
        v.putNull("error");
        v.put("attempts", 0);
        update(id, v);
    }

    /** Dopo un riavvio del processo, ciò che risultava in corso torna in coda. */
    public void resetRunning() {
        ContentValues v = new ContentValues();
        v.put("status", PENDING);
        getWritableDatabase().update("notes", v, "status = " + RUNNING, null);
        ContentValues r = new ContentValues();
        r.put("review_state", REVIEW_PENDING);
        getWritableDatabase().update("notes", r, "review_state = " + REVIEW_RUNNING, null);
    }

    public void delete(long id) {
        getWritableDatabase().delete("notes", "id = ?", new String[]{Long.toString(id)});
    }

    public void deleteAll() {
        getWritableDatabase().delete("notes", null, null);
    }

    private void update(long id, ContentValues v) {
        getWritableDatabase().update("notes", v, "id = ?", new String[]{Long.toString(id)});
    }

    private List<Note> query(String where, String[] args, String order, String limit) {
        List<Note> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("notes", null, where, args, null, null, order, limit)) {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
                n.chat = c.getString(c.getColumnIndexOrThrow("chat"));
                n.sender = c.getString(c.getColumnIndexOrThrow("sender"));
                n.source = c.getString(c.getColumnIndexOrThrow("source"));
                n.name = c.getString(c.getColumnIndexOrThrow("name"));
                n.localPath = c.getString(c.getColumnIndexOrThrow("local_path"));
                n.received = c.getLong(c.getColumnIndexOrThrow("received"));
                n.status = c.getInt(c.getColumnIndexOrThrow("status"));
                n.text = c.getString(c.getColumnIndexOrThrow("text"));
                n.language = c.getString(c.getColumnIndexOrThrow("language"));
                n.audioMs = c.getLong(c.getColumnIndexOrThrow("audio_ms"));
                n.workMs = c.getLong(c.getColumnIndexOrThrow("work_ms"));
                n.model = c.getString(c.getColumnIndexOrThrow("model"));
                n.error = c.getString(c.getColumnIndexOrThrow("error"));
                n.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
                n.reviewState = c.getInt(c.getColumnIndexOrThrow("review_state"));
                n.reviewText = c.getString(c.getColumnIndexOrThrow("review_text"));
                n.reviewSegments = c.getString(c.getColumnIndexOrThrow("review_segments"));
                n.translation = c.getString(c.getColumnIndexOrThrow("translation"));
                n.targetLang = c.getString(c.getColumnIndexOrThrow("target_lang"));
                n.reviewProvider = c.getString(c.getColumnIndexOrThrow("review_provider"));
                n.reviewError = c.getString(c.getColumnIndexOrThrow("review_error"));
                n.showWhisper = c.getInt(c.getColumnIndexOrThrow("show_whisper")) != 0;
                out.add(n);
            }
        }
        return out;
    }
}
