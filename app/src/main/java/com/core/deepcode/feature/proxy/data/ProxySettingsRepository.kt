package com.core.deepcode.feature.proxy.data

import android.content.Context
import com.core.deepcode.core.security.CredentialEncryptor
import com.core.deepcode.datalayer.store.KVStore
import com.core.deepcode.feature.proxy.domain.ProxySubscription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

const val PROXY_NS = "proxy"
const val PROXY_ENABLED_KEY = "proxy_enabled"
const val ACTIVE_PROFILE_ID_KEY = "active_profile_id"
const val PROFILES_JSON_KEY = "proxy_profiles_json"
const val AI_HOSTS_DIRECT_KEY = "ai_hosts_direct"

/** 网络代理（network_proxy）的持久化：启用开关、活跃 profile、已播种的订阅列表。 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialEncryptor: CredentialEncryptor,
    private val kv: KVStore,
) {
    private companion object {
        val profileSerializer = ListSerializer(ProxySubscription.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    val proxyEnabledFlow: Flow<Boolean> = kv.observeBool(PROXY_NS, PROXY_ENABLED_KEY).map { it ?: false }
    val aiHostsDirectFlow: Flow<Boolean> = kv.observeBool(PROXY_NS, AI_HOSTS_DIRECT_KEY).map { it ?: false }

    val subscriptionsFlow: Flow<List<ProxySubscription>> = kv.observeString(PROXY_NS, PROFILES_JSON_KEY).map { raw ->
        raw?.let { runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull() } ?: emptyList()
    }

    val activeProfileIdFlow: Flow<String?> = kv.observeString(PROXY_NS, ACTIVE_PROFILE_ID_KEY).map { it?.takeIf { v -> v.isNotBlank() } }

    suspend fun setProxyEnabled(enabled: Boolean) { kv.putBool(PROXY_NS, PROXY_ENABLED_KEY, enabled) }
    suspend fun setAiHostsDirect(enabled: Boolean) { kv.putBool(PROXY_NS, AI_HOSTS_DIRECT_KEY, enabled) }
    suspend fun isProxyEnabled(): Boolean = proxyEnabledFlow.first()

    suspend fun setActiveProfile(id: String?) {
        if (id == null) kv.delete(PROXY_NS, ACTIVE_PROFILE_ID_KEY) else kv.putString(PROXY_NS, ACTIVE_PROFILE_ID_KEY, id)
    }

    suspend fun upsertSubscription(id: String, name: String, kind: String, plainSecret: String) {
        val cipher = credentialEncryptor.encrypt(plainSecret)
        val raw = kv.getString(PROXY_NS, PROFILES_JSON_KEY)
        val current = raw?.let { runCatching { json.decodeFromString(profileSerializer, it) }.getOrNull() } ?: emptyList()
        val entry = ProxySubscription(id, name, kind, cipher, System.currentTimeMillis())
        val merged = current.filterNot { it.id == id } + entry
        kv.putString(PROXY_NS, PROFILES_JSON_KEY, json.encodeToString(profileSerializer, merged))
    }

    suspend fun deleteSubscription(id: String) {
        val raw = kv.getString(PROXY_NS, PROFILES_JSON_KEY)
        val current = raw?.let { runCatching { json.decodeFromString(profileSerializer, it) }.getOrNull() } ?: emptyList()
        kv.putString(PROXY_NS, PROFILES_JSON_KEY, json.encodeToString(profileSerializer, current.filterNot { it.id == id }))
    }

    suspend fun revealSecret(id: String): String? {
        val profile = subscriptionsFlow.first().firstOrNull { it.id == id } ?: return null
        return credentialEncryptor.decrypt(profile.secretCipher)
    }
}
