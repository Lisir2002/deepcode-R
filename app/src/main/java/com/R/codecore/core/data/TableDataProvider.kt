package com.R.codecore.core.data

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.R.codecore.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 通用 Room 表转储 Provider（数据注册表 TABLE 域实现）。
 *
 * 不依赖具体 DAO / DTO：用 SQLite 元数据（`PRAGMA table_info`）动态读取表结构与数据，
 * 逐行导出为 JSONL（每行一个 JSON 对象，列名→值），恢复时 `INSERT OR REPLACE`。
 * 适用于 v1 全新域库（表结构 = 实体类，列 1:1），对任何表通用，天然覆盖全量，
 * 新增表无需为备份编写专用 DTO。
 *
 * 序列化约定（跨整数/浮点/文本/BLOB 无损）：
 * - NULL → JSON null；INTEGER → JSON number（恢复写回 long）；
 * - FLOAT → JSON number；TEXT → JSON string；
 * - BLOB → base64 字符串（NO_WRAP），恢复时按列声明类型 BLOB 解码回字节。
 */
class TableDataProvider(
    override val key: String,
    private val database: RoomDatabase,
    private val table: String,
) : DataProvider {
    override val category: DataCategory = DataCategory.TABLE

    private data class Column(val name: String, val type: String)

    override suspend fun snapshot(): DataBlob = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        val columns = readColumns(db)
        if (columns.isEmpty()) return@withContext DataBlob(key, ByteArray(0))
        val colList = columns.joinToString(",") { "\"${it.name}\"" }
        val out = ByteArrayOutputStream()
        db.query("SELECT $colList FROM `$table`").use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (i in columns.indices) {
                    row.put(columns[i].name, valueToJson(cursor, i))
                }
                out.write(row.toString().toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
            }
        }
        DataBlob(key, out.toByteArray())
    }

    override suspend fun restore(blob: DataBlob) {
        if (blob.bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val db = database.openHelper.writableDatabase
            val columns = readColumns(db)
            if (columns.isEmpty()) return@withContext
            val colList = columns.joinToString(",") { "\"${it.name}\"" }
            val placeholders = columns.joinToString(",") { "?" }
            val insertSql = "INSERT OR REPLACE INTO `$table` ($colList) VALUES ($placeholders)"
            db.beginTransaction()
            try {
                val text = String(blob.bytes, Charsets.UTF_8)
                text.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val row = JSONObject(line)
                    val args = columns.map { jsonToValue(row.opt(it.name), it.type) }.toTypedArray()
                    db.execSQL(insertSql, args)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    // ── 表结构 / 行值序列化 ──────────────────────────────────────────

    private fun readColumns(db: SupportSQLiteDatabase): List<Column> {
        val cols = mutableListOf<Column>()
        runCatching {
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val typeIdx = c.getColumnIndexOrThrow("type")
                while (c.moveToNext()) {
                    cols.add(Column(c.getString(nameIdx), c.getString(typeIdx) ?: ""))
                }
            }
        }.onFailure {
            FileLogger.w(TAG, "读取表结构失败 $table", it)
        }
        return cols
    }

    private fun valueToJson(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(index))
        else -> cursor.getString(index)
    }

    private fun jsonToValue(value: Any?, type: String): Any? {
        if (value == null || value == JSONObject.NULL) return null
        return when {
            type.equals("BLOB", ignoreCase = true) ->
                runCatching { Base64.getDecoder().decode(value.toString()) }
                    .onFailure { FileLogger.w(TAG, "BLOB base64 解码失败 $table，落空字节", it) }
                    .getOrDefault(ByteArray(0))
            value is Boolean -> if (value) 1 else 0
            value is Int || value is Long -> value
            value is Double -> value
            value is String -> value
            else -> value.toString()
        }
    }

    private companion object {
        const val TAG = "TableDataProvider"
    }
}
