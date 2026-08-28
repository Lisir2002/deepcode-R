package com.R.codecore.datalayer.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.R.codecore.core.data.DataBlob
import com.R.codecore.core.data.DataCategory
import com.R.codecore.core.data.DataProvider
import com.R.codecore.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 新数据层（SQLDelight 6 库）的通用表转储 Provider（DataRegistry TABLE 域实现）。
 *
 * 对齐现有 [com.R.codecore.core.data.TableDataProvider] 的模式（数据保护契约：任何新存储必须注册进
 * DataRegistry，否则备份漏数据）：用 SQLite 元数据 `PRAGMA table_info` 动态读取表结构与数据，
 * 逐行导出 JSONL（列名→值），恢复时 `INSERT OR REPLACE`。对任何表通用，新增表无需专用 DTO。
 *
 * 与 Room 版的差异：底层是 SQLDelight [SqlDriver]（SqlCursor），列读取按 `PRAGMA table_info` 的
 * 声明类型选择 getLong/getDouble/getBytes/getString；BLOB 一律 base64（NO_WRAP）无损往返；
 * 恢复不包事务（SqlDriver 2.2.1 无公开事务扩展，失败由 DataRegistry 兜底记录，与 TableDataProvider 语义一致）。
 */
class SqlDelightDataProvider(
    override val key: String,
    private val driver: SqlDriver,
    private val table: String,
) : DataProvider {
    override val category: DataCategory = DataCategory.TABLE

    private data class Column(val name: String, val type: String)

    override suspend fun snapshot(): DataBlob = withContext(Dispatchers.IO) {
        val columns = readColumns()
        if (columns.isEmpty()) return@withContext DataBlob(key, ByteArray(0))
        val colList = columns.joinToString(",") { "\"${it.name}\"" }
        val out = ByteArrayOutputStream()
        driver.executeQuery(
            null,
            "SELECT $colList FROM `$table`",
            { cursor ->
                while (cursor.next().value) {
                    val row = JSONObject()
                    for (i in columns.indices) {
                        row.put(columns[i].name, valueToJson(cursor, i, columns[i].type))
                    }
                    out.write(row.toString().toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
                QueryResult.Value(Unit)
            },
            0,
            {},
        )
        DataBlob(key, out.toByteArray())
    }

    override suspend fun restore(blob: DataBlob) {
        if (blob.bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            val columns = readColumns()
            if (columns.isEmpty()) return@withContext
            val colList = columns.joinToString(",") { "\"${it.name}\"" }
            val placeholders = columns.joinToString(",") { "?" }
            val insertSql = "INSERT OR REPLACE INTO `$table` ($colList) VALUES ($placeholders)"
            val text = String(blob.bytes, Charsets.UTF_8)
            // 逐行 INSERT OR REPLACE；未绑定的参数即 NULL（SqlPreparedStatement 无 bindNull），故 null 值跳过 bind。
            text.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val row = JSONObject(line)
                driver.execute(null, insertSql, columns.size) {
                    columns.forEachIndexed { i, col ->
                        val v = jsonToValue(row.opt(col.name), col.type)
                        when (v) {
                            null -> Unit // 未绑定 → NULL
                            is ByteArray -> bindBytes(i, v)
                            is Double -> bindDouble(i, v)
                            is Long -> bindLong(i, v)
                            is Int -> bindLong(i, v.toLong())
                            else -> bindString(i, v.toString())
                        }
                    }
                }
            }
        }
    }

    // ── 表结构 / 行值序列化（对齐 DataRegistry 的约定）─────────────

    private fun readColumns(): List<Column> {
        val cols = mutableListOf<Column>()
        runCatching {
            driver.executeQuery(
                null,
                "PRAGMA table_info(`$table`)",
                { cursor ->
                    while (cursor.next().value) {
                        cols.add(Column(cursor.getString(1) ?: "", cursor.getString(2) ?: ""))
                    }
                    QueryResult.Value(Unit)
                },
                0,
                {},
            )
        }.onFailure {
            FileLogger.w(TAG, "读取表结构失败 $table", it)
        }
        return cols
    }

    private fun valueToJson(cursor: app.cash.sqldelight.db.SqlCursor, index: Int, type: String): Any? {
        val upper = type.uppercase()
        return when {
            "INT" in upper -> cursor.getLong(index)
            "REAL" in upper || "FLOA" in upper || "DOUB" in upper || "NUM" in upper -> cursor.getDouble(index)
            "BLOB" in upper -> cursor.getBytes(index)?.let { Base64.getEncoder().encodeToString(it) }
            else -> cursor.getString(index)
        }
    }

    private fun jsonToValue(value: Any?, type: String): Any? {
        if (value == null || value == JSONObject.NULL) return null
        return when {
            type.equals("BLOB", ignoreCase = true) ->
                runCatching { Base64.getDecoder().decode(value.toString()) }
                    .onFailure { FileLogger.w(TAG, "BLOB base64 解码失败 $table，落空字节", it) }
                    .getOrDefault(ByteArray(0))
            value is Boolean -> if (value) 1L else 0L
            value is Int -> value.toLong()
            value is Long -> value
            value is Double -> value
            else -> value.toString()
        }
    }

    private companion object {
        const val TAG = "SqlDelightDataProvider"
    }
}
