package com.deep.rcode.feature.terminal.data.repository

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.ContainerInstaller
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 终端功能包（Bundle）状态仓库。
 *
 * 职责：
 *   1. 把每个 bundle 的安装状态（NotInstalled / Installing / Installed / Failed / Uninstalling）
 *      通过 StateFlow 暴露给 UI。
 *   2. 负责把状态「落盘」：写入 `.bundle-<stableKey>-v<N>.done` 标记文件到 rootfs 目录；
 *      读磁盘标记来恢复冷启动后的状态。
 *   3. 负责「存量用户迁移」：如果检测到旧版 RC13/RC14 的 `.provisioned` 标记，
 *      自动把全部 7 个 bundle 标为 Installed（保证用户升级后不会要求重下）。
 *
 * 不负责真正的 apk add / apk del——那是 LinuxContainerEngine 的事。
 * Engine 在 provisionBundle/uninstallBundle 里调用本类的 [emitInstalling] / [markInstalled] / [markFailed] 方法。
 */
@Singleton
class TerminalBundleRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "TerminalBundleRepo"
        const val OLD_PROVISIONED_MARKER = ".provisioned"
        /** 旧版一次性 provision 的"内容为版本号字符串"标记。 */
        const val OLD_PROVISION_VERSION = "py3.12-pip-node-bash-curl-rg-gitcredhelper-v4"
    }

    /** 每个 bundle 的独立状态。key 稳定 = [TerminalBundleId.ordinal]，不会丢失。 */
    private val _states = MutableStateFlow<Map<TerminalBundleId, BundleInstallState>>(loadInitialStates())
    val states: StateFlow<Map<TerminalBundleId, BundleInstallState>> = _states.asStateFlow()

    /** 某个 bundle 已安装的"自定义 apk 包"列表（即不在 TerminalBundles.ALL.packages 里的 apk 世界包）。独立管理。 */
    private val _customPackages = MutableStateFlow<List<String>>(loadCustomPackages())
    val customPackages: StateFlow<List<String>> = _customPackages.asStateFlow()

    private val rootfsDir get() = containerInstaller.rootfsDir

    // ── 公共：读取 ────────────────────────────────────────────────────────

    /** 便捷：某个 bundle 的状态 Flow。UI 订阅单个卡片时用。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateOf(id: TerminalBundleId): Flow<BundleInstallState> =
        states.map { it[id] ?: BundleInstallState.NotInstalled }

    /** 便捷：某个 bundle 是否已安装（同步快照，避免协程）。 */
    fun isInstalledSnapshot(id: TerminalBundleId): Boolean =
        _states.value[id] is BundleInstallState.Installed

    /** AI 推荐组合的 5 个 bundle 是否全都已装完。用来显示"一键安装"按钮状态。 */
    fun aiRecommendedAllInstalledSnapshot(): Boolean =
        TerminalBundles.AI_RECOMMENDED_IDS.all { isInstalledSnapshot(it) }

    // ── 公共：Engine 调用的状态机驱动 API ──────────────────────────────────

    fun emitInstalling(id: TerminalBundleId, line: String? = null) {
        _states.update { it + (id to BundleInstallState.Installing(line)) }
    }

    fun emitUninstalling(id: TerminalBundleId) {
        _states.update { it + (id to BundleInstallState.Uninstalling) }
    }

    fun markInstalled(id: TerminalBundleId, bundle: TerminalBundle) {
        val marker = markerFileFor(id, bundle.version)
        runCatching {
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.writeText("ok")
            }
        }.onFailure { FileLogger.w(TAG, "写 bundle 标记失败 ${marker.name}", it) }
        _states.update { it + (id to BundleInstallState.Installed(bundle.version)) }
    }

    fun markFailed(id: TerminalBundleId, reason: String) {
        _states.update { it + (id to BundleInstallState.Failed(reason)) }
    }

    fun markUninstalled(id: TerminalBundleId) {
        // 清掉所有该 bundle 历史版本的标记
        for (v in 1..64) {
            val f = markerFileFor(id, v)
            if (f.exists()) runCatching { f.delete() }
        }
        _states.update { it + (id to BundleInstallState.NotInstalled) }
    }

    /** 卸载自定义包（UI「高级选项」里点卸载时调用）。之后 UI 端再刷新一次 [refreshCustomPackagesFromApk]。 */
    fun removeCustomSnapshot(pkg: String) {
        _customPackages.update { list -> list - pkg }
    }

    fun addCustomSnapshots(pkgs: List<String>) {
        _customPackages.update { (it + pkgs).distinct().sorted() }
    }

    /** 容器删除（重置）后，把所有状态重置成 NotInstalled。 */
    fun resetAllToNotInstalled() {
        _states.value = TerminalBundleId.entries.associateWith { BundleInstallState.NotInstalled }
        _customPackages.value = emptyList()
    }

    /**
     * 容器启动就绪后，调用一次本方法：
     *   - 如果检测到旧 `.provisioned` 标记（内容为 [OLD_PROVISION_VERSION]），自动打全部 7 个 bundle 为 Installed。
     *   - 然后刷新一次「自定义包」快照（从 apk list 里 diff 出来）。
     * 返回 true 表示完成了存量迁移。
     */
    fun migrateIfNeededAfterBoot(): Boolean {
        val old = File(rootfsDir, OLD_PROVISIONED_MARKER)
        val migrated: Boolean = if (old.exists()) {
            val content = runCatching { old.readText().trim() }.getOrDefault("")
            // 兼容：老版本 marker 内容是 PROVISION_VERSION 字符串；任何版本都视为全量 provision 过
            if (content.isNotBlank()) {
                FileLogger.i(TAG, "检测到旧版 .provisioned 标记（$content），自动把全部 7 个 bundle 标为已安装")
                for (b in TerminalBundles.ALL) {
                    // 只打 StateFlow 标记，物理 marker 不真正写——下次 rootfs 删除会自动重置，
                    // 保持 .provisioned 作为单一真源直到它被 rootfs 升级清掉。
                    _states.update { it + (b.id to BundleInstallState.Installed(b.version)) }
                }
                true
            } else false
        } else false

        // 自定义包快照刷新
        refreshCustomPackagesFromDiskMarker()
        return migrated
    }

    // ── 私有：磁盘 IO ──────────────────────────────────────────────────────

    /** 单个 bundle 标记文件：<rootfs>/.bundles/<stableKey>-v<version>.done。 */
    private fun markerFileFor(id: TerminalBundleId, version: Int): File =
        File(File(rootfsDir, ".bundles"), "${id.stableKey}-v${version}.done")

    private fun loadInitialStates(): Map<TerminalBundleId, BundleInstallState> {
        // 如果 rootfs 不存在 → 全 NotInstalled
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            return TerminalBundleId.entries.associateWith { BundleInstallState.NotInstalled }
        }
        val out = LinkedHashMap<TerminalBundleId, BundleInstallState>(TerminalBundleId.entries.size)
        for (b in TerminalBundles.ALL) {
            val marker = markerFileFor(b.id, b.version)
            out[b.id] = if (marker.exists()) {
                BundleInstallState.Installed(b.version)
            } else {
                BundleInstallState.NotInstalled
            }
        }
        return out
    }

    // 自定义包目前用标记文件兜底（因为冷启动时我们不能直接保证容器可用、apk list 能跑）。
    // 在 provision/卸载自定义包时由 Engine 调用 addCustomSnapshots / removeCustomSnapshot 来刷新；
    // 同时，在 Bundle UI 页面首次进入时会调用一次 refreshCustomPackagesFromApk()（通过 Engine 跑 apk list）
    // 让列表与实际 apk 世界一致。

    private fun customPackagesMarker(): File = File(File(rootfsDir, ".bundles"), "custom-packages.txt")

    private fun loadCustomPackages(): List<String> {
        val f = customPackagesMarker()
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines().map { it.trim() }.filter { it.isNotBlank() }.sorted() }
            .getOrDefault(emptyList())
    }

    private fun refreshCustomPackagesFromDiskMarker() {
        _customPackages.value = loadCustomPackages()
    }

    /** Engine 跑完 apk list（容器就绪后）更新自定义包清单 + 落盘。 */
    fun saveCustomPackagesSnapshot(pkgs: List<String>) {
        val sorted = pkgs.distinct().sorted()
        _customPackages.value = sorted
        runCatching {
            customPackagesMarker().parentFile?.mkdirs()
            customPackagesMarker().writeText(sorted.joinToString("\n"))
        }.onFailure { FileLogger.w(TAG, "写自定义包标记失败", it) }
    }
}
