package com.R.codecore.feature.proxy.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.R.codecore.core.security.CredentialEncryptor
import com.R.codecore.feature.proxy.domain.ProxySubscription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.proxyDataStore by preferencesDataStore(name = "proxy_prefs")

/**
 * 网络代理（network_proxy）的持久化：启用开关、活跃 profile、已播种的订阅列表。
 *
 * 与 [com.R.codecore.feature.settings.data.repository.ContainerSettingsRepository] 同款
 * Preferences DataStore + 构造注入，无需 DI module。订阅列表 JSON 持久化、解析失败回退空列表。
 * 敏感内容（订阅 URL / 手动 YAML）在写入时经 [CredentialEncryptor.encrypt]，读取按需解密，
 * 全程不在 list 快照里回显明文。
 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialEncryptor: CredentialEncryptor,
) {
    private companion object {
        val PROXY_ENABLED_KEY = booleanPreferencesKey("proxy_enabled")
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val PROFILES_JSON_KEY = stringPreferencesKey("proxy_profiles_json")
        val AI_HOSTS_DIRECT_KEY = booleanPreferencesKey("ai_hosts_direct")
        val profileSerializer = ListSerializer(ProxySubscription.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /** 全局代理开关。默认关闭。 */
    val proxyEnabledFlow: Flow<Boolean> = context.proxyDataStore.data.map { prefs ->
        prefs[PROXY_ENABLED_KEY] ?: false
    }

    /** 网络层优化 C5：AI 接口直连分流开关。默认关（保持全走代理），可配置策略。 */
    val aiHostsDirectFlow: Flow<Boolean> = context.proxyDataStore.data.map { prefs ->
        prefs[AI_HOSTS_DIRECT_KEY] ?: false
    }

    /** 已播种的订阅列表（cipher 形式，不回显明文）。解析失败回退空列表。 */
    val subscriptionsFlow: Flow<List<ProxySubscription>> = context.proxyDataStore.data.map { prefs ->
        prefs[PROFILES_JSON_KEY]?.let { raw ->
            runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    /** 当前活跃 profile id；无则 null。 */
    val activeProfileIdFlow: Flow<String?> = context.proxyDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() }
    }

    suspend fun setProxyEnabled(enabled: Boolean) {
        context.proxyDataStore.edit { it[PROXY_ENABLED_KEY] = enabled }
    }

    suspend fun setAiHostsDirect(enabled: Boolean) {
        context.proxyDataStore.edit { it[AI_HOSTS_DIRECT_KEY] = enabled }
    }

    suspend fun isProxyEnabled(): Boolean = proxyEnabledFlow.first()

    suspend fun setActiveProfile(id: String?) {
        context.proxyDataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_PROFILE_ID_KEY)
            else prefs[ACTIVE_PROFILE_ID_KEY] = id
        }
    }

    /**
     * 新增或覆盖同名 id 的 profile。敏感内容 [plainSecret]（订阅 URL / YAML）在写入前加密。
     */
    suspend fun upsertSubscription(
        id: String,
        name: String,
        kind: String,
        plainSecret: String,
    ) {
        val cipher = credentialEncryptor.encrypt(plainSecret)
        context.proxyDataStore.edit { prefs ->
            val current = prefs[PROFILES_JSON_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val entry = ProxySubscription(id, name, kind, cipher, System.currentTimeMillis())
            val merged = current.filterNot { it.id == id } + entry
            prefs[PROFILES_JSON_KEY] = json.encodeToString(profileSerializer, merged)
        }
    }

    suspend fun deleteSubscription(id: String) {
        context.proxyDataStore.edit { prefs ->
            val current = prefs[PROFILES_JSON_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[PROFILES_JSON_KEY] =
                json.encodeToString(profileSerializer, current.filterNot { it.id == id })
        }
    }

    /**
     * 取回某 profile 的加密敏感内容并解密。找不到返回 null。
     */
    suspend fun revealSecret(id: String): String? {
        val profile = subscriptionsFlow.first().firstOrNull { it.id == id } ?: return null
        return credentialEncryptor.decrypt(profile.secretCipher)
    }
}