package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.R.codecore.core.environment.EnvironmentDetector
import com.R.codecore.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化当前选中的容器 profile 与用户自定义 profile 列表。
 *
 * DataStore 用法与 [ThemeSettingsRepository] 一致（构造注入即可，无需 DI module）。
 * 无 [ACTIVE_PROFILE_ID_KEY] 时返回 [ContainerProfile.BUILTIN_ID]，等同改动前——默认内置 Alpine。
 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CUSTOM_PROFILES_KEY = stringPreferencesKey("custom_profiles_json")
        val STORAGE_SHARE_ENABLED_KEY = booleanPreferencesKey("storage_share_enabled")
        val profileSerializer = ListSerializer(ContainerProfile.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * 是否把设备存储绑定进容器（/root/storage/shared → 设备存储根）。默认关闭；
     * 开启后在 `buildBaseProotArgv` 动态追加 `-b <外存>:<容器路径>`。
     * 因 proot 的 `-b` 是 per-process 视图，新命令/新终端即生效，已运行的 shell 需重开。
     */
    val storageShareEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[STORAGE_SHARE_ENABLED_KEY] ?: false
    }

    suspend fun setStorageShareEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[STORAGE_SHARE_ENABLED_KEY] = enabled }
    }

    suspend fun readStorageShareEnabled(): Boolean = storageShareEnabledFlow.first()

    /** 当前选中的 profile id；无值时按宿主架构取内置容器（x86_64 宿主 → x86_64 内置，其余 → arm64 内置）。 */
    val activeProfileIdFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() }
            ?: EnvironmentDetector.defaultProfileId()
    }

    /** 用户自定义 profile 列表（不含内置）。解析失败回退空列表。 */
    val customProfilesFlow: Flow<List<ContainerProfile>> = context.settingsDataStore.data.map { prefs ->
        prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setActiveProfile(id: String) {
        context.settingsDataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    /** 新增或覆盖同名 id 的自定义 profile。 */
    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val merged = (current.filterNot { it.id == profile.id } + profile)
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(profileSerializer, merged)
        }
    }

    suspend fun deleteCustomProfile(id: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[CUSTOM_PROFILES_KEY] =
                json.encodeToString(profileSerializer, current.filterNot { it.id == id })
        }
    }
}
