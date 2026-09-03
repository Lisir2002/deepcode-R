package com.core.deepcode.feature.backup.data.guard

import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

const val APP_RUN_META_NS = "app_run_meta"
const val KEY_INITIALIZED = "data_initialized"
const val KEY_LAST_VERSION_CODE = "last_version_code"
const val KEY_LAST_APPLICATION_ID = "last_application_id"

data class RunMeta(
    val dataInitialized: Boolean = false,
    val lastVersionCode: Int = 0,
    val lastApplicationId: String? = null,
)

/** 应用运行元数据持久化。 */
@Singleton
class AppRunMeta @Inject constructor(
    private val kv: KVStore,
) {
    val data: Flow<RunMeta> = kv.observeBool(APP_RUN_META_NS, KEY_INITIALIZED).map { init ->
        RunMeta(
            dataInitialized = init ?: false,
            lastVersionCode = (kv.getInt(APP_RUN_META_NS, KEY_LAST_VERSION_CODE) ?: 0L).toInt(),
            lastApplicationId = kv.getString(APP_RUN_META_NS, KEY_LAST_APPLICATION_ID),
        )
    }

    suspend fun snapshot(): RunMeta = data.first()

    suspend fun markInitialized(versionCode: Int, applicationId: String) {
        kv.putBool(APP_RUN_META_NS, KEY_INITIALIZED, true)
        kv.putInt(APP_RUN_META_NS, KEY_LAST_VERSION_CODE, versionCode.toLong())
        kv.putString(APP_RUN_META_NS, KEY_LAST_APPLICATION_ID, applicationId)
    }

    suspend fun updateLastRun(versionCode: Int, applicationId: String) {
        kv.putInt(APP_RUN_META_NS, KEY_LAST_VERSION_CODE, versionCode.toLong())
        kv.putString(APP_RUN_META_NS, KEY_LAST_APPLICATION_ID, applicationId)
    }
}
