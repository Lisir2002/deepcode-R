package com.core.deepcode.feature.agent.domain.ext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件极简 manifest（对齐 Claude Code plugin.json + DSH B3 插件分发）。
 *
 * 文件形态：`plugins/<name>/plugin.json`，字段：
 * - [name]：插件名（目录名约束：字母/数字/中划线/下划线，防路径穿越）；
 * - [version]：版本（必填，如 "1.0.0"）；
 * - [description] / [author]：说明与作者（可选）；
 * - [provides]：本插件提供的扩展类别（可选，默认按目录自动识别）。
 *
 * 安全边界（对齐设计 B3）：Android 端插件**只能声明** commands / skills / hooks / agents，
 * **不能**注册任意原生代码（无运行时插件 API）。
 */
@Serializable
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    /** 本插件提供的扩展类别：commands / skills / hooks / agents（其余声明拒绝）。 */
    val provides: List<String> = emptyList()
) {
    companion object {
        const val MANIFEST_FILE = "plugin.json"

        /** 目录名/插件名合法字符（字母/数字/中划线/下划线，防路径穿越）。 */
        val VALID_NAME = Regex("^[a-zA-Z0-9_-]+$")

        /** 允许声明的扩展类别（安全边界，其余拒绝）。 */
        val VALID_PROVIDES = setOf("commands", "skills", "hooks", "agents")

        /** 解析 plugin.json 文本；格式非法返回 null。 */
        fun parse(content: String): PluginManifest? = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<PluginManifest>(content)
        } catch (e: Exception) {
            null
        }

        /** 名称是否合法（目录安全 + 非空）。 */
        fun isValidName(name: String): Boolean =
            name.isNotBlank() && name.length <= 64 && VALID_NAME.matches(name)
    }
}
