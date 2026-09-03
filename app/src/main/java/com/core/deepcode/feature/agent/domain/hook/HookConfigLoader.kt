package com.core.deepcode.feature.agent.domain.hook

import android.content.Context
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 声明式 hooks.json 加载器（方向 B2 声明式扩展生态）。
 *
 * 配置来源（覆盖合并，对齐 ExtensionLoader 同名覆盖语义）：
 * - 内置：`assets/ext/hooks/hooks.json`（打包只读，随 App 升级更新）；
 * - 用户：`<deepcode>/ext/hooks/hooks.json`（同名 id 覆盖内置，热加载由调用方在每次
 *   分发前重读本类实现——本类 [load] 每次现读，天然支持用户改动即时生效）。
 *
 * [load] 每次调用重新解析文件，把声明式配置转换为 [HookHandler] 列表（供 [HookDispatcher]
 * 合并进各事件分组）。解析失败静默降级为内置配置，绝不影响主流程。
 */
@Singleton
class HookConfigLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    /** 插件分发管理器：合并已安装插件的 hooks.json（B3，用户插件覆盖内置）。 */
    private val pluginManager: com.core.deepcode.feature.agent.domain.ext.PluginManager
) {
    private companion object {
        const val TAG = "HookConfigLoader"
        const val ASSET_PATH = "ext/hooks/hooks.json"
    }

    /** 用户 hooks.json 路径 `<deepcode>/ext/hooks/hooks.json`。 */
    val userHooksFile: File by lazy {
        File(File(containerInstaller.deepcodeDir, "ext/hooks"), "hooks.json")
    }

    /**
     * 读取并合并声明式 hook 配置，转换为 [HookHandler] 列表。
     * 每次调用现读：内置（失败回退空） → 插件（按 id 覆盖内置） → 用户（最高优先，按 id 覆盖）。
     */
    fun load(): List<HookHandler> {
        val builtin = parse(readAsset())
        var merged = builtin ?: DeclarativeHookConfig()
        for (config in readPluginConfigs()) {
            merged = mergeById(merged, config)
        }
        val user = parse(readUserFile())
        merged = mergeById(merged, user)
        val handlers = merged.toHandlers()
        if (handlers.isNotEmpty()) {
            FileLogger.d(TAG, "加载声明式 hooks ${handlers.size} 个（用户覆盖 ${user?.hooks?.values?.sumOf { it.size } ?: 0} 条）")
        }
        return handlers
    }

    /** 读取并解析所有已安装插件的 hooks.json（解析失败静默跳过单个插件）。 */
    private fun readPluginConfigs(): List<DeclarativeHookConfig> =
        pluginManager.hookConfigs().mapNotNull { parse(it) }

    /** 条目级合并：user 中的条目按 id 覆盖 builtin 同名条目；未匹配的 user 条目追加。 */
    private fun mergeById(
        builtin: DeclarativeHookConfig?,
        user: DeclarativeHookConfig?
    ): DeclarativeHookConfig {
        val base = builtin ?: DeclarativeHookConfig()
        val override = user ?: return base
        val result = linkedMapOf<String, List<DeclarativeHookEntry>>()
        for ((eventName, entries) in base.hooks) {
            val userEntries = override.hooks[eventName].orEmpty()
            val userById = userEntries.associateBy { it.id }
            result[eventName] = entries.map { userById[it.id] ?: it } +
                userEntries.filter { ue -> entries.none { it.id == ue.id } }
        }
        // 内置未声明的事件（仅用户声明）：追加。
        for ((eventName, entries) in override.hooks) {
            if (eventName !in result) result[eventName] = entries
        }
        return DeclarativeHookConfig(result)
    }

    private fun readAsset(): String? = try {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        FileLogger.w(TAG, "读取内置 hooks.json 失败: ${e.message}", e)
        null
    }

    private fun readUserFile(): String? {
        if (!userHooksFile.isFile) return null
        return try {
            userHooksFile.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取用户 hooks.json 失败: ${e.message}", e)
            null
        }
    }

    private fun parse(raw: String?): DeclarativeHookConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString(DeclarativeHookConfig.serializer(), raw)
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 hooks.json 失败（忽略该配置源）: ${e.message}", e)
            null
        }
    }
}
