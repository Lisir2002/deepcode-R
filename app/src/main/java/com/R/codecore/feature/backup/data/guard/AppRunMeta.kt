package com.R.codecore.feature.backup.data.guard

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appRunMetaDataStore by preferencesDataStore(name = "app_run_meta")

/** 一次运行的应用元数据：数据完整性哨兵（DataSentinel）判定「全新安装 / 正常升级 / 数据丢失」的依据。 */
data class RunMeta(
    /** 本包名下是否已建立过数据（首次运行置 true，此后恒 true）。 */
    val dataInitialized: Boolean = false,
    /** 上次正常运行的 versionCode。 */
    val lastVersionCode: Int = 0,
    /** 上次运行时的包名。与当前包名不一致说明包被改动过（运行时兜底检测）。 */
    val lastApplicationId: String? = null,
)

/**
 * 应用运行元数据持久化（DataStore）。
 *
 * 背景：包名（applicationId）变更在 Android 眼里是"全新安装"，数据目录随之隔离，历史对话会"消失"。
 * 哨兵用这里的元数据区分「本次是全新安装（静默）」还是「数据疑似丢失（提示用户）」。
 */
@Singleton
class AppRunMeta @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_INITIALIZED = booleanPreferencesKey("data_initialized")
        val KEY_LAST_VERSION_CODE = intPreferencesKey("last_version_code")
        val KEY_LAST_APPLICATION_ID = stringPreferencesKey("last_application_id")
    }

    val data: Flow<RunMeta> = context.appRunMetaDataStore.data.map { prefs ->
        RunMeta(
            dataInitialized = prefs[KEY_INITIALIZED] ?: false,
            lastVersionCode = prefs[KEY_LAST_VERSION_CODE] ?: 0,
            lastApplicationId = prefs[KEY_LAST_APPLICATION_ID],
        )
    }

    suspend fun snapshot(): RunMeta = data.first()

    /** 首次运行：记录已初始化 + 本次版本与包名。 */
    suspend fun markInitialized(versionCode: Int, applicationId: String) {
        context.appRunMetaDataStore.edit {
            it[KEY_INITIALIZED] = true
            it[KEY_LAST_VERSION_CODE] = versionCode
            it[KEY_LAST_APPLICATION_ID] = applicationId
        }
    }

    /** 正常运行后更新上次版本与包名。 */
    suspend fun updateLastRun(versionCode: Int, applicationId: String) {
        context.appRunMetaDataStore.edit {
            it[KEY_LAST_VERSION_CODE] = versionCode
            it[KEY_LAST_APPLICATION_ID] = applicationId
        }
    }
}
