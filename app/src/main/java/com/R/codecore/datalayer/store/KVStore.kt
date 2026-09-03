package com.R.codecore.datalayer.store

import app.cash.sqldelight.coroutines.asFlow
import com.R.codecore.datalayer.sqldelight.InfraDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 一等 KVStore（设计 §6.1）：替代散落的 DataStore。
 * 标量按类型拆列（string/int/bool/json），写入层宽松、读取层解析。
 */
data class KvEntry(
    val namespace: String,
    val key: String,
    val type: String,
    val stringVal: String?,
    val intVal: Long?,
    val boolVal: Long?,
    val jsonVal: String?,
    val updatedAt: Long,
)

class KVStore(private val db: InfraDb) {

    private val queries get() = db.kvQueries

    fun putString(namespace: String, key: String, value: String) =
        queries.upsertKv(namespace, key, "string", value, null, null, null, now())

    fun putInt(namespace: String, key: String, value: Long) =
        queries.upsertKv(namespace, key, "int", null, value, null, null, now())

    fun putBool(namespace: String, key: String, value: Boolean) =
        queries.upsertKv(namespace, key, "bool", null, null, if (value) 1L else 0L, null, now())

    fun putJson(namespace: String, key: String, value: String) =
        queries.upsertKv(namespace, key, "json", null, null, null, value, now())

    fun get(namespace: String, key: String): KvEntry? =
        queries.selectKv(namespace, key).executeAsOneOrNull()?.toEntry()

    fun getAll(namespace: String): List<KvEntry> =
        queries.selectKvByNamespace(namespace).executeAsList().map { it.toEntry() }

    /** 类型化 get 便捷方法：比 get().xVal?.let 更简洁。 */
    fun getString(namespace: String, key: String): String? = get(namespace, key)?.stringVal
    fun getInt(namespace: String, key: String): Long? = get(namespace, key)?.intVal
    fun getBool(namespace: String, key: String): Boolean? = get(namespace, key)?.boolVal?.let { it != 0L }

    /** 响应式观察（替代 DataStore.data）。 */
    fun observe(namespace: String, key: String): Flow<KvEntry?> =
        queries.selectKv(namespace, key).asFlow().map { it.executeAsOneOrNull()?.toEntry() }

    /** 类型化 observe 便捷方法：直接 Flow<String?> / Flow<Long?> / Flow<Boolean?>。 */
    fun observeString(namespace: String, key: String): Flow<String?> =
        observe(namespace, key).map { it?.stringVal }
    fun observeInt(namespace: String, key: String): Flow<Long?> =
        observe(namespace, key).map { it?.intVal }
    fun observeBool(namespace: String, key: String): Flow<Boolean?> =
        observe(namespace, key).map { it?.boolVal?.let { v -> v != 0L } }

    fun delete(namespace: String, key: String) =
        queries.tombstoneKv(now(), namespace, key)

    private fun now() = System.currentTimeMillis()

    private fun com.R.codecore.datalayer.sqldelight.infra.Kv_store.toEntry() = KvEntry(
        namespace = namespace,
        key = key,
        type = type,
        stringVal = string_val,
        intVal = int_val,
        boolVal = bool_val,
        jsonVal = json_val,
        updatedAt = updated_at,
    )
}
