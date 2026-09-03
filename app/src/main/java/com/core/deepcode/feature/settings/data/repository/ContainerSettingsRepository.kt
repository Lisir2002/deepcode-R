package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.core.environment.EnvironmentDetector
import com.core.deepcode.datalayer.store.KVStore
import com.core.deepcode.feature.agent.domain.container.ContainerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 持久化当前选中的容器 profile 与用户自定义 profile 列表。 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val ACTIVE_PROFILE_ID_KEY = "active_profile_id"
        const val CUSTOM_PROFILES_KEY = "custom_profiles_json"
        const val STORAGE_SHARE_ENABLED_KEY = "storage_share_enabled"
        val profileSerializer = ListSerializer(ContainerProfile.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    val storageShareEnabledFlow: Flow<Boolean> = kv.observeBool(NS, STORAGE_SHARE_ENABLED_KEY).map { it ?: false }

    suspend fun setStorageShareEnabled(enabled: Boolean) {
        kv.putBool(NS, STORAGE_SHARE_ENABLED_KEY, enabled)
    }

    suspend fun readStorageShareEnabled(): Boolean = storageShareEnabledFlow.first()

    val activeProfileIdFlow: Flow<String> = kv.observeString(NS, ACTIVE_PROFILE_ID_KEY).map { stored ->
        stored?.takeIf { it.isNotBlank() } ?: EnvironmentDetector.defaultProfileId()
    }

    val customProfilesFlow: Flow<List<ContainerProfile>> = kv.observeString(NS, CUSTOM_PROFILES_KEY).map { raw ->
        raw?.let { runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull() } ?: emptyList()
    }

    suspend fun setActiveProfile(id: String) {
        kv.putString(NS, ACTIVE_PROFILE_ID_KEY, id)
    }

    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        val raw = kv.getString(NS, CUSTOM_PROFILES_KEY)
        val current = raw?.let { runCatching { json.decodeFromString(profileSerializer, it) }.getOrNull() } ?: emptyList()
        val merged = (current.filterNot { it.id == profile.id } + profile)
        kv.putString(NS, CUSTOM_PROFILES_KEY, json.encodeToString(profileSerializer, merged))
    }

    suspend fun deleteCustomProfile(id: String) {
        val raw = kv.getString(NS, CUSTOM_PROFILES_KEY)
        val current = raw?.let { runCatching { json.decodeFromString(profileSerializer, it) }.getOrNull() } ?: emptyList()
        kv.putString(NS, CUSTOM_PROFILES_KEY, json.encodeToString(profileSerializer, current.filterNot { it.id == id }))
    }
}
