package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 持久化「识图专用模型」选择。 */
@Singleton
class VisionModelSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val PROVIDER_ID_KEY = "vision_provider_id"
        const val MODEL_KEY = "vision_model"
    }

    val providerIdFlow: Flow<String> = kv.observeString(NS, PROVIDER_ID_KEY).map { it ?: "" }
    val modelFlow: Flow<String> = kv.observeString(NS, MODEL_KEY).map { it ?: "" }

    suspend fun setVisionModel(providerId: String, model: String) {
        kv.putString(NS, PROVIDER_ID_KEY, providerId)
        kv.putString(NS, MODEL_KEY, model)
    }

    suspend fun clear() {
        kv.delete(NS, PROVIDER_ID_KEY)
        kv.delete(NS, MODEL_KEY)
    }

    suspend fun getVisionProviderId(): String = providerIdFlow.first()
    suspend fun getVisionModel(): String = modelFlow.first()
}
