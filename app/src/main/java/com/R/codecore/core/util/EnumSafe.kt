package com.R.codecore.core.util

/**
 * DB-SHIELD-RC68 P1-5：全局 Enum.valueOf() 安全壳。
 * 所有 `Enum.valueOf(str)` 调用应统一走本 helper，禁止直接 `MessageRole.valueOf(role)` ——
 * 老数据/备份导入/未来 Enum 重构改名时会直接抛 IllegalArgumentException（UI 线程=崩）。
 */
object EnumSafe {

    inline fun <reified T : Enum<T>> valueOf(
        value: String?,
        default: T,
        tag: String = "EnumSafe",
        onUnknown: ((String) -> Unit) = { unknown ->
            FileLogger.w(tag, "未知枚举值「$unknown」映射到默认=$default；" +
                    "若这是近期重构改名导致，请补一条老值→新值的迁移映射（MigrationLoader 或 EnumAliases）。")
        }
    ): T {
        if (value == null) return default
        val constants: Array<T> = enumValues<T>()
        val hit = constants.firstOrNull { it.name == value }
        if (hit != null) return hit
        val ci = constants.firstOrNull { it.name.equals(value, ignoreCase = true) }
        if (ci != null) return ci
        onUnknown(value)
        return default
    }

    inline fun <reified T : Enum<T>> valueOfOrNull(value: String?): T? {
        if (value == null) return null
        val constants = enumValues<T>()
        return constants.firstOrNull { it.name == value }
            ?: constants.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
