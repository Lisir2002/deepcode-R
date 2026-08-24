package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化「压缩专用模型」选择（providerId + model 两字符串）。
 *
 * 上下文压缩默认跟随当前聊天模型；当用户在此指定一个专用模型后，
 * 压缩轮会临时切换到该专用模型发送摘要请求，发完恢复聊天模型。
 * providerId 为空（未配置）即视为「跟随当前聊天模型」。
 */
@Singleton
class CompactionModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val PROVIDER_ID_KEY = stringPreferencesKey("compaction_provider_id")
        val MODEL_KEY = stringPreferencesKey("compaction_model")
    }

    val providerIdFlow: Flow<String> = context.settingsDataStore.data.map { it[PROVIDER_ID_KEY] ?: "" }

    val modelFlow: Flow<String> = context.settingsDataStore.data.map { it[MODEL_KEY] ?: "" }

    suspend fun setCompactionModel(providerId: String, model: String) {
        context.settingsDataStore.edit {
            it[PROVIDER_ID_KEY] = providerId
            it[MODEL_KEY] = model
        }
    }

    suspend fun clear() {
        context.settingsDataStore.edit {
            it.remove(PROVIDER_ID_KEY)
            it.remove(MODEL_KEY)
        }
    }

    suspend fun getCompactionProviderId(): String = providerIdFlow.first()

    suspend fun getCompactionModel(): String = modelFlow.first()
}
