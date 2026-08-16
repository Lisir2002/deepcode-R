package com.deep.rcode.feature.agent.presentation

import android.content.Context
import com.deep.rcode.feature.agent.presentation.component.EnvironmentComponentState
import com.deep.rcode.feature.agent.presentation.component.EnvironmentStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 环境快照持久化：冷启动/进程被杀后恢复构建环境检测气泡。
 *
 * 根因：[EnvironmentSnapshot] 原本仅存 ViewModel 内存的 StateFlow，
 * 退出 App 后进程被杀即丢失 → 重新进入后气泡消失。
 *
 * 方案：以 `sessionId` 为粒度，将快照序列化为 JSON 存 SharedPreferences，
 * - 每次探测完成后写入；
 * - 切换会话/冷启动时从磁盘恢复到内存 StateFlow。
 *
 * 仅持久化已完成的探测结果（probing=false），探测中状态不落盘（避免转圈残留）。
 */
internal class EnvironmentSnapshotStore(
    private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(sessionId: String): Map<String, EnvironmentSnapshot> {
        val raw = prefs.getString(buildKey(sessionId), null) ?: return emptyMap()
        return runCatching {
            val list = json.decodeFromString<List<StoredSnapshot>>(raw)
            list.associate { it.key to it.toSnapshot() }
        }.getOrDefault(emptyMap())
    }

    fun save(sessionId: String, snapshots: Map<String, EnvironmentSnapshot>) {
        // 仅持久化已完成的探测结果，避免探测中状态残留
        val list = snapshots.values
            .filter { !it.probing && it.components.isNotEmpty() }
            .map { StoredSnapshot.from(it) }
        if (list.isEmpty()) {
            prefs.edit().remove(buildKey(sessionId)).apply()
        } else {
            prefs.edit().putString(buildKey(sessionId), json.encodeToString(list)).apply()
        }
    }

    fun clear(sessionId: String) {
        prefs.edit().remove(buildKey(sessionId)).apply()
    }

    companion object {
        private const val PREFS_NAME = "env_snapshots"
        private val json = Json { ignoreUnknownKeys = true }

        private fun buildKey(sessionId: String): String = "sess_$sessionId"
    }
}

/** 持久化用的环境快照（纯数据类，便于 JSON 序列化）。 */
@Serializable
private data class StoredSnapshot(
    val key: String,
    val components: List<StoredComponent>,
    val probedAt: Long
) {
    fun toSnapshot(): EnvironmentSnapshot = EnvironmentSnapshot(
        key = key,
        components = components.map { it.toState() },
        probedAt = probedAt,
        probing = false
    )

    companion object {
        fun from(snapshot: EnvironmentSnapshot): StoredSnapshot = StoredSnapshot(
            key = snapshot.key,
            components = snapshot.components.map { StoredComponent.from(it) },
            probedAt = snapshot.probedAt
        )
    }
}

@Serializable
private data class StoredComponent(
    val name: String,
    val status: String,
    val version: String? = null,
    val path: String? = null,
    val installPercent: Float? = null
) {
    fun toState(): EnvironmentComponentState = EnvironmentComponentState(
        name = name,
        status = when (status) {
            "installed" -> EnvironmentStatus.INSTALLED
            "installing" -> EnvironmentStatus.INSTALLING
            else -> EnvironmentStatus.MISSING
        },
        version = version,
        path = path,
        installPercent = installPercent
    )

    companion object {
        fun from(state: EnvironmentComponentState): StoredComponent = StoredComponent(
            name = state.name,
            status = when (state.status) {
                EnvironmentStatus.INSTALLED -> "installed"
                EnvironmentStatus.INSTALLING -> "installing"
                EnvironmentStatus.MISSING -> "missing"
            },
            version = state.version,
            path = state.path,
            installPercent = state.installPercent
        )
    }
}
