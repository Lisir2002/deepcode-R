package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 持久化「压缩专用模型」选择。 */
@Singleton
class CompactionModelSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val PROVIDER_ID_KEY = "compaction_provider_id"
        const val MODEL_KEY = "compaction_model"
    }

    val providerIdFlow: Flow<String> = kv.observeString(NS, PROVIDER_ID_KEY).map { it ?: "" }
    val modelFlow: Flow<String> = kv.observeString(NS, MODEL_KEY).map { it ?: "" }

    suspend fun setCompactionModel(providerId: String, model: String) {
        kv.putString(NS, PROVIDER_ID_KEY, providerId)
        kv.putString(NS, MODEL_KEY, model)
    }

    suspend fun clear() {
        kv.delete(NS, PROVIDER_ID_KEY)
        kv.delete(NS, MODEL_KEY)
    }

    suspend fun getCompactionProviderId(): String = providerIdFlow.first()
    suspend fun getCompactionModel(): String = modelFlow.first()
}
