package com.R.codecore.feature.agent.domain.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-3 运行时依赖探针（重写「技能调用工具」后的受控预检组件）。
 *
 * 负责在技能执行前探测 `requires_runtime` 求值树（[RuntimeProbeExpr]）声明的运行时条件是否满足，
 * 由 [RunSkillScriptTool]（手动执行）与自动触发路径（[SkillRuntimeProbe] 统一接入）共用。
 *
 * **安全性（相比旧版裸 `command -v $cmd`）**：所有目标名/模块名/包名/路径在拼入 shell 前
 * 一律过字符白名单校验（[SAFE_NAME]），非法直接判失败、不执行任何命令；版本约束同样
 * 白名单校验（[SAFE_VERSION]）。探测命令整体为固定模板 + 已校验参数，杜绝注入。
 * 布尔组合（AND/OR/NOT）只做纯逻辑判断、**绝不 eval 任何字符串**。
 *
 * 支持的探针类型（[RuntimeProbe]）：
 * - cmd：PATH 可执行命令（`command -v`），可选最低/最高版本约束（解析 `--version` 做语义化比较）。
 * - mod：Python 3 模块（`python3 -c "import <mod>"`）。
 * - npmpkg：npm 全局包（`npm ls -g --depth=0 <pkg>`），可选最低/最高版本约束。
 * - dpkg：deb 包（`dpkg -s <pkg>`）。
 * - file：文件/路径存在性（`test -e <path>`）。
 *
 * 线程安全：所有方法为 suspend，调用方需保证在协程内调用。
 */
@Singleton
class SkillRuntimeProbe @Inject constructor(
    private val commandEngine: CommandEngine
) {
    private companion object {
        const val TAG = "SkillRuntimeProbe"
        const val PROBE_TIMEOUT_MS = 20_000L

        // 命令/模块/包/路径白名单：字母数字、点、下划线、短横线、@（npm scope）、/（路径）、+。
        // 不含空格与任何 shell 元字符（; | & $ ` " ' \ ( ) < >），杜绝拼接注入。
        val SAFE_NAME = Regex("^[A-Za-z0-9._@/+-]+$")

        // 版本约束白名单：纯数字与点，如 "18" / "3.9" / "1.2.3"。
        val SAFE_VERSION = Regex("^[0-9]+(\\.[0-9]+){0,3}$")

        // 从命令输出中提取首个版本号（如 "v18.20.1" → "18.20.1"）。
        val VERSION_PATTERN = Regex("(\\d+(?:\\.\\d+){0,3})")
    }

    /**
     * 单条失败描述（供上层拼错误信息展示给模型/用户）。
     * [probe] 为 null 表示复合条件失败（如 NOT 反转命中），此时 [reason] 自带说明。
     */
    data class ProbeFailure(val probe: RuntimeProbe?, val reason: String)

    /**
     * 探测求值树，返回所有不满足的失败项（空列表 = 全部满足）。
     *
     * 求值语义（短路 + 失败原因沿树汇总）：
     * - [RuntimeProbeExpr.And]：全过才过；失败时**汇总所有失败子项的原因**；
     * - [RuntimeProbeExpr.Or]：任一过即过；全败时**列出各分支原因**；
     * - [RuntimeProbeExpr.Not]：内层失败 → 过；内层过 → 败（"期望不满足但实际满足"）。
     *
     * 即使容器未初始化/探测异常，也按「不满足」返回（宁可不执行，不放过）。
     */
    suspend fun probe(expr: RuntimeProbeExpr?): List<ProbeFailure> {
        if (expr == null) return emptyList()
        return when (expr) {
            is RuntimeProbeExpr.Leaf -> probeLeaf(expr.probe)
            is RuntimeProbeExpr.And -> {
                val all = mutableListOf<ProbeFailure>()
                for (child in expr.children) all += probe(child)
                all
            }
            is RuntimeProbeExpr.Or -> {
                val all = mutableListOf<ProbeFailure>()
                var anyPassed = false
                for (child in expr.children) {
                    val failures = probe(child)
                    if (failures.isEmpty()) anyPassed = true
                    all += failures
                }
                if (anyPassed) emptyList() else all
            }
            is RuntimeProbeExpr.Not -> {
                val inner = probe(expr.child)
                if (inner.isNotEmpty()) emptyList()
                else listOf(
                    ProbeFailure(null, "期望「不满足」的条件实际已满足（${summarize(expr.child)}）")
                )
            }
        }
    }

    /** 叶子探测：失败时附带 [RuntimeProbe.installHint] 安装建议。 */
    private suspend fun probeLeaf(p: RuntimeProbe): List<ProbeFailure> {
        val reason = probeOne(p) ?: return emptyList()
        val hint = p.installHint?.trim()?.takeIf { it.isNotBlank() }
        val full = if (hint != null) "$reason（安装建议：$hint）" else reason
        return listOf(ProbeFailure(p, full))
    }

    private suspend fun probeOne(p: RuntimeProbe): String? {
        val name = p.name.trim()
        if (!SAFE_NAME.matches(name)) {
            return "目标名「${p.name}」含非法字符（仅允许字母数字 . _ @ / + -），已拒绝探测"
        }
        val minVersion = p.minVersion?.trim()?.takeIf { it.isNotBlank() }
        val maxVersion = p.maxVersion?.trim()?.takeIf { it.isNotBlank() }
        if (minVersion != null && !SAFE_VERSION.matches(minVersion)) {
            return "版本下界「${p.minVersion}」格式非法（仅允许如 18 / 3.9 / 1.2.3）"
        }
        if (maxVersion != null && !SAFE_VERSION.matches(maxVersion)) {
            return "版本上界「${p.maxVersion}」格式非法（仅允许如 18 / 3.9 / 1.2.3）"
        }
        return when (p.check) {
            RuntimeProbe.CHECK_CMD -> probeCmd(name, minVersion, maxVersion)
            RuntimeProbe.CHECK_MOD -> probeMod(name)
            RuntimeProbe.CHECK_NPM -> probeNpm(name, minVersion, maxVersion)
            RuntimeProbe.CHECK_DPKG -> probeDpkg(name)
            RuntimeProbe.CHECK_FILE -> probeFile(name)
            else -> "未知探针类型「${p.check}」"
        }
    }

    private suspend fun probeCmd(name: String, minVersion: String?, maxVersion: String?): String? {
        if (!run("command -v -- $name >/dev/null 2>&1")) return "容器内未找到命令 $name"
        if (minVersion == null && maxVersion == null) return null
        val output = capture("$name --version 2>/dev/null | head -n 1") ?: return "无法读取 $name 版本"
        val actual = extractVersion(output) ?: return "无法解析 $name 版本（输出：${output.trim()}）"
        if (minVersion != null && compareVersions(actual, minVersion) < 0) {
            return "命令 $name 版本 $actual，低于要求的最低版本 $minVersion"
        }
        if (maxVersion != null && compareVersions(actual, maxVersion) > 0) {
            return "命令 $name 版本 $actual，高于允许的最高版本 $maxVersion"
        }
        return null
    }

    private suspend fun probeMod(name: String): String? {
        val ok = run("python3 -c \"import $name\" 2>/dev/null")
        return if (ok) null else "Python 3 缺少模块 $name（或 python3 未安装）"
    }

    private suspend fun probeNpm(name: String, minVersion: String?, maxVersion: String?): String? {
        if (!run("npm ls -g --depth=0 $name >/dev/null 2>&1")) return "npm 全局包未安装 $name"
        if (minVersion == null && maxVersion == null) return null
        val output = capture("npm ls -g --depth=0 $name 2>/dev/null") ?: return null
        val actual = extractVersion(output) ?: return null // 解析不到版本则按满足处理，避免误伤
        if (minVersion != null && compareVersions(actual, minVersion) < 0) {
            return "npm 包 $name 版本 $actual，低于要求的最低版本 $minVersion"
        }
        if (maxVersion != null && compareVersions(actual, maxVersion) > 0) {
            return "npm 包 $name 版本 $actual，高于允许的最高版本 $maxVersion"
        }
        return null
    }

    private suspend fun probeDpkg(name: String): String? {
        val ok = run("dpkg -s $name >/dev/null 2>&1")
        return if (ok) null else "缺少 deb 包 $name（未安装，或容器非 Debian 系）"
    }

    private suspend fun probeFile(name: String): String? {
        val ok = run("test -e \"$name\"")
        return if (ok) null else "容器内不存在路径 $name"
    }

    /** 执行固定模板命令，以退出码判成败；异常/超时视为失败。 */
    private suspend fun run(command: String): Boolean = try {
        commandEngine.runCommandSyncWithExit(
            command = command,
            projectPath = null,
            timeoutMs = PROBE_TIMEOUT_MS
        ).exitCode == 0
    } catch (e: Exception) {
        FileLogger.w(TAG, "探针执行失败: $command - ${e.message}")
        false
    }

    /** 执行并捕获标准输出；异常/超时返回 null。 */
    private suspend fun capture(command: String): String? = try {
        commandEngine.runCommandSync(
            command = command,
            projectPath = null,
            timeoutMs = PROBE_TIMEOUT_MS
        ).trim().ifEmpty { null }
    } catch (e: Exception) {
        FileLogger.w(TAG, "探针捕获失败: $command - ${e.message}")
        null
    }

    /** 从命令输出中提取首个语义化版本号（如 "v18.20.1" → "18.20.1"）。 */
    private fun extractVersion(output: String): String? {
        val m = VERSION_PATTERN.find(output) ?: return null
        return m.groupValues[1]
    }

    /**
     * 语义化版本比较（段数不齐按缺位补 0）：v1 > v2 返回正数；相等返回 0；否则负数。
     * 仅处理数字段，忽略预发布/构建元数据后缀（"1.2.3-rc1" 按 "1.2.3" 比较）。
     */
    internal fun compareVersions(v1: String, v2: String): Int {
        val a = v1.split('-')[0].split('.').mapNotNull { it.toIntOrNull() }
        val b = v2.split('-')[0].split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 复合条件的人类可读摘要（NOT 失败时说明「实际满足了什么」）。 */
    private fun summarize(e: RuntimeProbeExpr): String = when (e) {
        is RuntimeProbeExpr.Leaf -> "${e.probe.check}:${e.probe.name}"
        is RuntimeProbeExpr.And -> e.children.joinToString(" && ") { summarize(it) }
        is RuntimeProbeExpr.Or -> e.children.joinToString(" || ") { summarize(it) }
        is RuntimeProbeExpr.Not -> "!(${summarize(e.child)})"
    }
}
