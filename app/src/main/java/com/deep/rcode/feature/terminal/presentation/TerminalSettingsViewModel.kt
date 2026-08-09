package com.deep.rcode.feature.terminal.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.feature.agent.domain.container.LinuxContainerEngine
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundles
import com.deep.rcode.feature.terminal.data.repository.SshHeartbeatSeconds
import com.deep.rcode.feature.terminal.data.repository.TerminalBundleRepository
import com.deep.rcode.feature.terminal.data.repository.TerminalSettingsRepository
import com.deep.rcode.feature.terminal.data.repository.TerminalTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 终端设置页的 ViewModel。
 *
 * 职责：
 *  - 外观/键盘/行为/SSH 四分组开关：直接把 TerminalSettingsRepository 的 Flow 暴露成 StateFlow。
 *  - 容器环境大卡片状态：读取 LinuxContainerEngine.initProgress 与 isContainerInstalled 快照。
 *  - Bundle 卡片状态：读取 TerminalBundleRepository 的 Map StateFlow。
 *  - 执行具体动作：初始化容器 / 重置 / 换源 / 安装 bundle / 卸载 bundle / 自定义包。
 *  - 通用 toast 错误：一个 MutableStateFlow<String?>（UI 在终端设置页里用 Snackbar 承接）。
 */
@HiltViewModel
class TerminalSettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepo: TerminalSettingsRepository,
    private val bundleRepo: TerminalBundleRepository,
    private val containerEngine: LinuxContainerEngine
) : AndroidViewModel(application) {

    private val appContext get() = getApplication<Application>().applicationContext

    private companion object { const val TAG = "TerminalSettingsVM" }

    // ── 错误 toast（与 TerminalViewModel 同语义，UI 承接 Snackbar） ─────
    private val _errorToast = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val errorToast: StateFlow<String?> = _errorToast

    fun consumeErrorToast() { _errorToast.value = null }
    private fun postError(msg: String) { _errorToast.value = msg }

    // ── G1 外观 ───────────────────────────────────────────────────
    val fontSizeSp: StateFlow<Int> = settingsRepo.fontSizeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 12)

    val terminalTheme: StateFlow<TerminalTheme> = settingsRepo.themeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalTheme.SYSTEM)

    val showTabBar: StateFlow<Boolean> = settingsRepo.showTabBarFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── G2 键盘 & 交互 ────────────────────────────────────────────
    val fullExtraKeys: StateFlow<Boolean> = settingsRepo.fullExtraKeysFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val scaleGesturePersists: StateFlow<Boolean> = settingsRepo.scaleGesturePersistsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoPopImeOnSwitch: StateFlow<Boolean> = settingsRepo.autoPopImeOnSwitchFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── G3 行为 ───────────────────────────────────────────────────
    val newOutputIndicator: StateFlow<Boolean> = settingsRepo.newOutputIndicatorFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoNewTabOnCloseLast: StateFlow<Boolean> = settingsRepo.autoNewTabOnCloseLastFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val keepSessionWhenLeave: StateFlow<Boolean> = settingsRepo.keepSessionWhenLeaveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val pasteAsPlainText: StateFlow<Boolean> = settingsRepo.pasteAsPlainTextFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── G4 SSH 常用 ───────────────────────────────────────────────
    val sshAutoReconnect: StateFlow<Boolean> = settingsRepo.sshAutoReconnectFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sshHeartbeatSeconds: StateFlow<Int> = settingsRepo.sshHeartbeatSecondsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SshHeartbeatSeconds.S60.seconds)

    val sshKeepalive: StateFlow<Boolean> = settingsRepo.sshKeepaliveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── 容器环境卡片 ──────────────────────────────────────────────
    /** 容器初始化进度（与终端页 AppLoadingState 同源）。 */
    val containerInit: StateFlow<com.deep.rcode.feature.agent.domain.container.ContainerInitState> =
        containerEngine.initProgress.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            com.deep.rcode.feature.agent.domain.container.ContainerInitState.Idle
        )

    /** 容器是否物理安装好（rootfs+proot）。方便 UI 直接判断状态。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val containerInstalled: StateFlow<Boolean> = containerInit
        .map { state ->
            when (state) {
                is com.deep.rcode.feature.agent.domain.container.ContainerInitState.Ready -> true
                is com.deep.rcode.feature.agent.domain.container.ContainerInitState.BundleInstalling,
                is com.deep.rcode.feature.agent.domain.container.ContainerInitState.BundleUninstalling -> true
                is com.deep.rcode.feature.agent.domain.container.ContainerInitState.ExtractingRootfs,
                com.deep.rcode.feature.agent.domain.container.ContainerInitState.DeployingProot,
                com.deep.rcode.feature.agent.domain.container.ContainerInitState.Idle,
                is com.deep.rcode.feature.agent.domain.container.ContainerInitState.Failed -> false
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 容器占用（MB）：rootfs 目录大小 /data/data/<pkg>/files/rootfs。懒读 + 状态变更触发刷新。 */
    private val _storageUsedMb = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val storageUsedMb: StateFlow<Long> = _storageUsedMb

    private suspend fun computeRootfsSizeMb(): Long {
        // ContainerInstaller.rootfsDir = File(context.filesDir, "rootfs")（ContainerInstaller.kt#L142-L143 约定）
        val rootfsDir = File(appContext.filesDir, "rootfs")
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return 0L
        // 用 walkTopDown().sumOf 统计目录总字节（递归，符号链接按自身条目算，与「du」的语义近似）
        // 结果 MB（1024×1024），与卡片文案「150 MB」量级一致。
        val bytes = runCatching {
            var acc = 0L
            rootfsDir.walkTopDown().forEach { f ->
                if (!f.isDirectory) acc += runCatching { f.length() }.getOrDefault(0L)
            }
            acc
        }.getOrDefault(0L)
        return bytes / (1024L * 1024L)
    }

    init {
        // 容器初始化进度切换到 Ready 后触发一次统计；
        // 另外任何容器安装状态 true→false / false→true 的跳变都再补一次。
        viewModelScope.launch {
            containerInit.collect { state ->
                val readyish = state is com.deep.rcode.feature.agent.domain.container.ContainerInitState.Ready
                    || state is com.deep.rcode.feature.agent.domain.container.ContainerInitState.BundleInstalling
                    || state is com.deep.rcode.feature.agent.domain.container.ContainerInitState.BundleUninstalling
                if (readyish) {
                    _storageUsedMb.value = computeRootfsSizeMb()
                }
            }
        }
        // 启动时先填一次（如果 rootfs 早已就位），避免冷启动卡片永远 0M，直到下一次状态跳变。
        // 注：containerEngine.containerInstaller / currentProfile 都是 private，这里用「rootfs 目录存在」
        // 作为容器物理已安装的代理判定——与 ContainerInstaller.isInstalledFor 的核心条件一致。
        viewModelScope.launch {
            val containerInstalledCached = containerInstalled.replayCache.firstOrNull()
                ?: File(appContext.filesDir, "rootfs").isDirectory
            if (containerInstalledCached) {
                _storageUsedMb.value = computeRootfsSizeMb()
            }
        }
    }

    // ── Bundle 列表 & 状态 ────────────────────────────────────────
    val bundleStates: StateFlow<Map<TerminalBundleId, BundleInstallState>> = bundleRepo.states

    val customPackages: StateFlow<List<String>> = bundleRepo.customPackages

    // AI 推荐组合是否已全部安装（按钮状态）：
    @OptIn(ExperimentalCoroutinesApi::class)
    val aiRecommendedAllInstalled: StateFlow<Boolean> = bundleStates
        .map { states ->
            TerminalBundles.AI_RECOMMENDED_IDS.all { id ->
                states[id] is BundleInstallState.Installed
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // 第一次进入页面时刷新一下自定义包列表（需要容器已就绪 → 未就绪时 Engine 内部已兜底为空列表）
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.refreshCustomPackagesSnapshot() }
            refreshStorageUsed()
        }
    }

    /** 刷新 rootfs 占用（MB）。UI "刷新"按钮触发。走 computeRootfsSizeMb，不反射访问 private 字段 */
    fun refreshStorageUsed() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _storageUsedMb.value = computeRootfsSizeMb()
        }
    }

    // ─────────── 动作：容器区 ──────────────────────────────────────

    fun ensureContainerInstalled() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.ensureInstalled() }
                .onFailure { postError(it.message ?: "初始化容器失败") }
            refreshStorageUsed()
        }
    }

    fun resetContainer(onConfirmSuccess: () -> Unit = {}) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.resetContainer() }
                .onFailure { postError(it.message ?: "重置容器失败") }
                .onSuccess { onConfirmSuccess() }
            refreshStorageUsed()
        }
    }

    fun setMirrorAndRefresh(mirror: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { containerEngine.setApkMirrorAndUpdate(mirror) }
                .getOrDefault(false)
            if (!ok) postError("换源失败，请确认网络后重试")
        }
    }

    // ─────────── 动作：Bundle 区 ──────────────────────────────────

    fun installBundle(id: TerminalBundleId) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.installBundle(id) }
                .onFailure { postError(it.message ?: "安装失败") }
            refreshStorageUsed()
        }
    }

    fun uninstallBundle(id: TerminalBundleId) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.uninstallBundle(id) }
                .onFailure { postError(it.message ?: "卸载失败") }
            refreshStorageUsed()
        }
    }

    /** 一键安装 AI 推荐组合（1+3+4+5+6）。 */
    fun installAiRecommended() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 先确保容器已初始化
                if (!containerInstalled.value) containerEngine.ensureInstalled()
                containerEngine.installBundlesOrdered(TerminalBundles.AI_RECOMMENDED_IDS)
            }.onFailure { postError(it.message ?: "安装推荐组合失败") }
            refreshStorageUsed()
        }
    }

    // ─────────── 动作：自定义包区 ──────────────────────────────────

    fun installCustom(pkgString: String) {
        val pkgs = pkgString.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (pkgs.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val failed = runCatching { containerEngine.installCustomPackages(pkgs) }
                .getOrElse { pkgs } // 异常=全部失败
            if (failed.isNotEmpty()) postError("安装失败：${failed.joinToString(" ")}")
            refreshStorageUsed()
        }
    }

    fun uninstallCustom(pkg: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { containerEngine.uninstallCustomPackage(pkg) }.getOrDefault(false)
            if (!ok) postError("卸载失败：$pkg")
            refreshStorageUsed()
        }
    }

    fun refreshCustom() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { containerEngine.refreshCustomPackagesSnapshot() }
        }
    }

    // ─────────── 动作：G1 外观 ────────────────────────────────────

    fun setFontSizeSp(sp: Int) { viewModelScope.launch { settingsRepo.saveFontSize(sp) } }
    fun setTheme(theme: TerminalTheme) { viewModelScope.launch { settingsRepo.saveTheme(theme) } }
    fun setShowTabBar(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveShowTabBar(enabled) } }

    // ─────────── 动作：G2 键盘 & 交互 ─────────────────────────────

    fun setFullExtraKeys(full: Boolean) { viewModelScope.launch { settingsRepo.saveFullExtraKeys(full) } }
    fun setScaleGesturePersists(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveScaleGesturePersists(enabled) } }
    fun setAutoPopImeOnSwitch(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveAutoPopImeOnSwitch(enabled) } }
    fun resetCtrlHint() { viewModelScope.launch { settingsRepo.resetCtrlHint() } }

    // ─────────── 动作：G3 行为 ────────────────────────────────────

    fun setNewOutputIndicator(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveNewOutputIndicator(enabled) } }
    fun setAutoNewTabOnCloseLast(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveAutoNewTabOnCloseLast(enabled) } }
    fun setKeepSessionWhenLeave(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveKeepSessionWhenLeave(enabled) } }
    fun setPasteAsPlainText(enabled: Boolean) { viewModelScope.launch { settingsRepo.savePasteAsPlainText(enabled) } }

    // ─────────── 动作：G4 SSH 常用 ────────────────────────────────

    fun setSshAutoReconnect(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveSshAutoReconnect(enabled) } }
    fun setSshHeartbeatSeconds(seconds: Int) { viewModelScope.launch { settingsRepo.saveSshHeartbeatSeconds(seconds) } }
    fun setSshKeepalive(enabled: Boolean) { viewModelScope.launch { settingsRepo.saveSshKeepalive(enabled) } }

    // ─────────── 辅助：Bundle 元数据暴露给 UI ─────────────────────

    fun bundles(): List<TerminalBundle> = TerminalBundles.ALL
    fun bundle(id: TerminalBundleId): TerminalBundle? = TerminalBundles.byId(id)
}
