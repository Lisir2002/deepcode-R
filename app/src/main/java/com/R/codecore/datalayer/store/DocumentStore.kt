package com.R.codecore.datalayer.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.R.codecore.datalayer.sqldelight.InfraDb

/**
 * 一等 DocumentStore（设计 §6.2）：JSON 文档存储，首版即上 FTS5 全文检索。
 *
 * FTS5 虚表 doc_fts + trigger 在初始化时经 driver.execute 建立（设计备注：
 * 避免 SQLDelight 对虚拟表/trigger 的解析问题；基表 doc_store 由 SQLDelight schema 管理）。
 * 写入时 trigger 自动同步索引；检索走 FTS5 MATCH。
 */
data class DocEntry(
    val id: Long,
    val collection: String,
    val key: String,
    val docJson: String,
    val version: Long,
    val updatedAt: Long,
)

class DocumentStore(private val db: InfraDb, private val driver: SqlDriver) {

    private val queries get() = db.docQueries

    init {
        ensureFts()
    }

    private fun ensureFts() {
        driver.execute(
            null,
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(
              title, body, content='doc_store', content_rowid='id'
            );
            """.trimIndent(),
            0,
        )
        driver.execute(null, """
            CREATE TRIGGER IF NOT EXISTS doc_ai AFTER INSERT ON doc_store BEGIN
              INSERT INTO doc_fts(rowid, title, body) VALUES (new.id, new.collection || '/' || new.key, new.doc_json);
            END;
        """.trimIndent(), 0)
        driver.execute(null, """
            CREATE TRIGGER IF NOT EXISTS doc_ad AFTER DELETE ON doc_store BEGIN
              INSERT INTO doc_fts(doc_fts, rowid, title, body) VALUES('delete', old.id, old.collection || '/' || old.key, old.doc_json);
            END;
        """.trimIndent(), 0)
        driver.execute(null, """
            CREATE TRIGGER IF NOT EXISTS doc_au AFTER UPDATE ON doc_store BEGIN
              INSERT INTO doc_fts(doc_fts, rowid, title, body) VALUES('delete', old.id, old.collection || '/' || old.key, old.doc_json);
              INSERT INTO doc_fts(rowid, title, body) VALUES (new.id, new.collection || '/' || new.key, new.doc_json);
            END;
        """.trimIndent(), 0)
    }

    fun put(collection: String, key: String, docJson: String, version: Long = 1) {
        queries.upsertDoc(collection, key, docJson, version, System.currentTimeMillis())
    }

    fun get(collection: String, key: String): DocEntry? =
        queries.selectDoc(collection, key).executeAsOneOrNull()?.toEntry()

    fun getAll(collection: String): List<DocEntry> =
        queries.selectDocByCollection(collection).executeAsList().map { it.toEntry() }

    fun delete(collection: String, key: String) =
        queries.tombstoneDoc(System.currentTimeMillis(), collection, key)

    /** FTS5 全文检索：跨 collection 匹配标题/正文（collection/key 作为 title）。 */
    fun search(match: String): List<DocEntry> {
        val sql = """
            SELECT d.id, d.collection, d.key, d.doc_json, d.version, d.updated_at
            FROM doc_store d JOIN doc_fts f ON d.id = f.rowid
            WHERE doc_fts MATCH ? AND d.deleted = 0
            ORDER BY rank
        """.trimIndent()
        return driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<DocEntry>()
            while (cursor.next().value) {
                out.add(
                    DocEntry(
                        id = cursor.getLong(0) ?: 0,
                        collection = cursor.getString(1) ?: "",
                        key = cursor.getString(2) ?: "",
                        docJson = cursor.getString(3) ?: "",
                        version = cursor.getLong(4) ?: 1,
                        updatedAt = cursor.getLong(5) ?: 0,
                    ),
                )
            }
            QueryResult.Value(out)
        }, 1) { bindString(0, match) }.value
    }

    private fun com.R.codecore.datalayer.sqldelight.infra.Doc_store.toEntry() = DocEntry(
        id = id,
        collection = collection,
        key = key,
        docJson = doc_json,
        version = version.toLong(),
        updatedAt = updated_at,
    )
}
