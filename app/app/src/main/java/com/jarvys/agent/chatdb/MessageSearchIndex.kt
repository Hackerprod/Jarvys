package com.jarvys.agent.chatdb

internal object MessageSearchIndex {
    fun install(db: ChatSqlDatabase) {
        db.execSQL("""CREATE VIEW IF NOT EXISTS message_search_content AS
            SELECT p.rowid AS rowid,p.payload AS text,m.role,m.conversation_id
            FROM message_parts p JOIN messages m ON m.conversation_id=p.conversation_id AND m.message_id=p.message_id
            WHERE p.kind='text'""")
        val objects = listOf("message_fts", "trg_message_fts_insert", "trg_message_fts_delete", "trg_message_fts_update")
        if (objects.all { name -> db.query("SELECT 1 FROM sqlite_master WHERE name=?", arrayOf(name)).use { it.moveToFirst() } }) return
        drop(db)
        try {
            db.execSQL("CREATE VIRTUAL TABLE message_fts USING fts5(text,role UNINDEXED,conversation_id UNINDEXED,content='message_search_content',content_rowid='rowid',tokenize='unicode61 remove_diacritics 2')")
            db.execSQL("""CREATE TRIGGER trg_message_fts_insert AFTER INSERT ON message_parts WHEN NEW.kind='text' BEGIN
                INSERT INTO message_fts(rowid,text,role,conversation_id)
                SELECT NEW.rowid,NEW.payload,role,NEW.conversation_id FROM messages
                WHERE conversation_id=NEW.conversation_id AND message_id=NEW.message_id; END""")
            db.execSQL("""CREATE TRIGGER trg_message_fts_delete BEFORE DELETE ON message_parts WHEN OLD.kind='text' BEGIN
                INSERT INTO message_fts(message_fts,rowid,text,role,conversation_id)
                VALUES('delete',OLD.rowid,OLD.payload,NULL,OLD.conversation_id); END""")
            db.execSQL("""CREATE TRIGGER trg_message_fts_update AFTER UPDATE ON message_parts BEGIN
                INSERT INTO message_fts(message_fts,rowid,text,role,conversation_id)
                SELECT 'delete',OLD.rowid,OLD.payload,role,OLD.conversation_id FROM messages
                WHERE conversation_id=OLD.conversation_id AND message_id=OLD.message_id AND OLD.kind='text';
                INSERT INTO message_fts(rowid,text,role,conversation_id)
                SELECT NEW.rowid,NEW.payload,role,NEW.conversation_id FROM messages
                WHERE conversation_id=NEW.conversation_id AND message_id=NEW.message_id AND NEW.kind='text'; END""")
            db.execSQL("INSERT INTO message_fts(message_fts) VALUES('rebuild')")
        } catch (_: Exception) {
            drop(db)
        }
    }

    private fun drop(db: ChatSqlDatabase) {
        listOf("trg_message_fts_insert", "trg_message_fts_delete", "trg_message_fts_update").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("DROP TABLE IF EXISTS message_fts")
    }
}
