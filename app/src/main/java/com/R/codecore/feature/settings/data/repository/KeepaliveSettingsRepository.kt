package com.R.codecore.feature.settings.data.repository

import com.R.codecore.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 持久化「后台保活常驻通知」开关。默认关闭。 */
@Singleton
class KeepaliveSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val ENABLED_KEY = "keepalive_enabled"
    }

    val enabledFlow: Flow<Boolean> = kv.observeBool(NS, ENABLED_KEY).map { it ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        kv.putBool(NS, ENABLED_KEY, enabled)
    }

    suspend fun isEnabled(): Boolean = enabledFlow.first()

    suspend fun snapshot(): Boolean = enabledFlow.first()

    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
