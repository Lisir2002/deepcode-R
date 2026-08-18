package com.R.codecore.feature.proxy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import com.R.codecore.feature.proxy.domain.ClashConfigSummary
import com.R.codecore.feature.proxy.domain.ClashProxiesSnapshot
import com.R.codecore.feature.proxy.domain.ClashProxyManager
import com.R.codecore.feature.proxy.domain.ProxyRuntimeState
import com.R.codecore.feature.proxy.domain.ProxySubscription
import com.R.codecore.feature.proxy.domain.ProxyTraffic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 导入配置的预检结果（仅展示，不落盘）。[resolvedYaml] 供保存时作为来源。 */
data class ProxyPreview(
    val ok: Boolean,
    val summary: String,
    val nodeCount: Int = 0,
    val groupCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val resolvedYaml: String = "",
)

/**
 * 某 profile 展开后的节点列表视图（对齐 Clash 的「节点列表 + 测速状态」）。
 * [latencies]：节点名 → 毫秒延迟；值为 null 表示已测但超时/失败；不含该 key 表示未测。
 */
data class ProfileNodesView(
    val profileId: String,
    val profileName: String,
    val summary: ClashConfigSummary,
    val latencies: Map<String, Long?> = emptyMap(),
    val loading: Boolean = false,
    val testing: Boolean = false,
    val error: String? = null,
)

/**
 * 展开区「分组 · 流量」视图：分组树 + 选中项 + 实时流量。
 * 与 `network_proxy list_proxies / flow` 共用 manager 的 REST(/proxies) 与 WS(/traffic) 数据源。
 */
data class ProxyGroupsView(
    val profileId: String,
    val snapshot: ClashProxiesSnapshot? = null,
    val traffic: ProxyTraffic? = null,
    val running: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 网络代理配置页的 ViewModel：对接 [ProxySettingsRepository] 与 [ClashProxyManager]。
 *
 * 只做 App 侧「播种 + 开关」的 UI 交互；日常切换/测速/监控仍由模型驱动 `network_proxy` 工具完成
 * （《网络代理 v1.0》§3 / §11）。页面与工具落到同一运行链路（manager/repository），避免两套实现。
 */
@HiltViewModel
class ProxyViewModel @Inject constructor(
    private val repository: ProxySettingsRepository,
    private val manager: ClashProxyManager,
) : ViewModel() {

    /** 已播种的订阅/manual/list（脱敏，cipher 不解密返回）。 */
    val profiles: StateFlow<List<ProxySubscription>> = repository.subscriptionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全局代理开关。 */
    val enabled: StateFlow<Boolean> = repository.proxyEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 当前活跃 profile id。 */
    val activeProfileId: StateFlow<String?> = repository.activeProfileIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 运行态（含 mode / controller 可达性）。 */
    val runtime: StateFlow<ProxyRuntimeState> = manager.state

    /** 最近一次预检结果。 */
    private val _preview = MutableStateFlow<ProxyPreview?>(null)
    val preview: StateFlow<ProxyPreview?> = _preview.asStateFlow()

    /** 当前展开 profile 的节点列表视图（对齐 Clash：节点列表 + 测速状态）。 */
    private val _profileNodes = MutableStateFlow<ProfileNodesView?>(null)
    val profileNodes: StateFlow<ProfileNodesView?> = _profileNodes.asStateFlow()

    /** 当前展开 profile 的「分组 · 流量」视图（对齐 Clash：分组树 + 选中项 + 实时流量）。 */
    private val _groups = MutableStateFlow<ProxyGroupsView?>(null)
    val groups: StateFlow<ProxyGroupsView?> = _groups.asStateFlow()

    /** /traffic WS 订阅协程（打开分组视图时启动，关闭时取消）。 */
    private var trafficJob: Job? = null

    /** 一次性提示事件（Snackbar）。 */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 切换全局开关：开需至少有 1 个已播种 profile。 */
    fun toggleEnabled(desired: Boolean) {
        viewModelScope.launch {
            if (!desired) {
                manager.off()
                _events.send("代理已关闭")
                return@launch
            }
            val list = profiles.value
            if (list.isEmpty()) {
                _events.send("请先导入配置")
                return@launch
            }
            val id = activeProfileId.value ?: list.first().id
            val result = manager.on(id, null)
            if (result == "ok") _events.send("代理已启用")
            else _events.send(result)
        }
    }

    /** 把某 profile 设为活跃（并以其启用环境注入）。 */
    fun activate(id: String) {
        viewModelScope.launch {
            val result = manager.on(id, null)
            _events.send(if (result == "ok") "已切换活跃配置" else result)
        }
    }

    /** 删除某 profile；若它正是活跃则一并清空活跃标记（不自动关开关）。 */
    fun delete(id: String) {
        viewModelScope.launch {
            repository.deleteSubscription(id)
            if (activeProfileId.value == id) repository.setActiveProfile(null)
            _events.send("已删除")
        }
    }

    /** 预检：订阅 URL 或手动 YAML → 解析概览（不落盘不启用）。 */
    fun runPreview(url: String?, yaml: String?) {
        viewModelScope.launch {
            _preview.value = null
            val resolved: String? = when {
                !url.isNullOrBlank() -> {
                    withContext(Dispatchers.IO) { manager.fetchSubscriptionYaml(url.trim()) }
                }
                !yaml.isNullOrBlank() -> yaml
                else -> null
            }
            if (resolved.isNullOrBlank()) {
                _preview.value = ProxyPreview(false, "预检失败：无法读取配置内容", warnings = emptyList())
                return@launch
            }
            val summary = manager.parseClashConfig(resolved)
            if (!summary.ok) {
                _preview.value = ProxyPreview(
                    ok = false,
                    summary = "预检失败：${summary.error ?: "YAML 解析失败"}",
                    warnings = runCatching { dangerScan(resolved) }.getOrDefault(emptyList())
                )
                return@launch
            }
            val nodeCount = summary.nodes.size
            val groupCount = summary.groups.size
            val providerNote = if (summary.providerCount > 0) " · provider ${summary.providerCount}" else ""
            val warnings = runCatching { dangerScan(resolved) }.getOrDefault(emptyList()) +
                if (summary.providerCount > 0) {
                    listOf("含 ${summary.providerCount} 个 proxy-provider：节点由内核动态加载，此处仅统计内联节点")
                } else {
                    emptyList()
                }
            _preview.value = ProxyPreview(
                ok = true,
                summary = "解析 OK · 节点 $nodeCount · 分组 $groupCount$providerNote · mode rule",
                nodeCount = nodeCount,
                groupCount = groupCount,
                warnings = warnings,
                resolvedYaml = resolved
            )
        }
    }

    /** 收起展开的节点列表。 */
    fun closeInspect() {
        _profileNodes.value = null
    }

    /**
     * 展开某 profile 并加载其节点列表（对齐 Clash 配置详情）：
     * 订阅型拉最新 YAML，手动型用已存 YAML；不落盘不启用。
     */
    fun inspectProfile(id: String) {
        viewModelScope.launch {
            val current = _profileNodes.value
            if (current != null && current.profileId == id && current.loading) return@launch
            val profile = profiles.value.firstOrNull { it.id == id }
            val name = profile?.name?.ifBlank { profile.id } ?: id
            _profileNodes.value = ProfileNodesView(id, name, ClashConfigSummary(ok = true), loading = true)
            val revealed = repository.revealSecret(id)
            if (revealed == null) {
                _profileNodes.value = ProfileNodesView(
                    id, name, ClashConfigSummary(ok = false),
                    error = "无法读取该配置（凭据解密失败）"
                )
                return@launch
            }
            val trimmed = revealed.trim()
            val source = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                withContext(Dispatchers.IO) { manager.fetchSubscriptionYaml(trimmed) }
            } else {
                trimmed
            }
            if (source.isNullOrBlank()) {
                _profileNodes.value = ProfileNodesView(
                    id, name, ClashConfigSummary(ok = false),
                    error = "订阅拉取失败或内容为空"
                )
                return@launch
            }
            val summary = manager.parseClashConfig(source)
            _profileNodes.value = ProfileNodesView(id, name, summary)
        }
    }

    /** 对展开 profile 的所有节点测速（并发受限），逐节点回填延迟/超时状态。 */
    fun testProfileLatency(id: String) {
        val current = _profileNodes.value ?: return
        if (current.profileId != id || current.testing) return
        _profileNodes.value = current.copy(testing = true, latencies = emptyMap())
        viewModelScope.launch {
            val nodes = current.summary.nodes
            // 仅当该 profile 正是当前运行的活跃配置时走 mihomo REST（真实出口测速），否则直连近似
            val useController = manager.state.value.enabled &&
                manager.state.value.activeProfileId == id
            val semaphore = Semaphore(6)
            coroutineScope {
                nodes.forEach { node ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val d = manager.testNodeLatency(node, useController)
                            _profileNodes.update { v ->
                                if (v == null || v.profileId != id) v
                                else v.copy(latencies = v.latencies + (node.name to d))
                            }
                        }
                    }
                }
            }
            _profileNodes.update { v ->
                if (v == null || v.profileId != id) v else v.copy(testing = false)
            }
        }
    }

    /** 打开「分组 · 流量」视图：读 /proxies 快照并订阅 /traffic WS 实时流（需代理运行中）。 */
    fun openGroups(id: String) {
        val current = _groups.value
        if (current?.profileId == id && !current.loading) return
        trafficJob?.cancel()
        _groups.value = ProxyGroupsView(id, loading = true)
        viewModelScope.launch {
            if (!manager.state.value.enabled) {
                _groups.value = ProxyGroupsView(id, running = false)
                return@launch
            }
            val snap = manager.fetchProxiesSnapshot()
            _groups.value = ProxyGroupsView(
                id,
                snapshot = snap,
                running = true,
                error = if (snap == null) "无法读取分组（控制器不可达）" else null
            )
            trafficJob = viewModelScope.launch {
                manager.trafficFlow().collect { t ->
                    _groups.update { v -> if (v?.profileId == id) v.copy(traffic = t) else v }
                }
            }
        }
    }

    /** 关闭「分组 · 流量」视图并停止 /traffic WS 订阅。 */
    fun closeGroups() {
        trafficJob?.cancel()
        trafficJob = null
        _groups.value = null
    }

    /** 切换某分组内选中节点（用户 UI 直点，与 Clash 一致；走同一 REST）。 */
    fun selectGroupNode(group: String, node: String) {
        viewModelScope.launch {
            val ok = manager.selectProxyNode(group, node)
            if (ok) {
                val snap = manager.fetchProxiesSnapshot()
                _groups.update { v -> if (v != null) v.copy(snapshot = snap ?: v.snapshot) else v }
                _events.send("已切换 $group → $node")
            } else {
                _events.send("切换失败（节点无效或控制器不可达）")
            }
        }
    }

    /** 保存一个 profile（播种）。[kind] 取 [ProxySubscription.KIND_SUBSCRIPTION]/[KIND_MANUAL]。 */
    fun commitProfile(name: String, kind: String, secret: String, enableNow: Boolean) {
        viewModelScope.launch {
            if (name.isBlank() || secret.isBlank()) {
                _events.send("名称/内容不能为空")
                return@launch
            }
            val id = generateId()
            repository.upsertSubscription(id, name.trim(), kind, secret)
            _events.send("已保存「${name.trim()}」")
            if (enableNow) {
                val result = manager.on(id, null)
                _events.send(if (result == "ok") "已启用「${name.trim()}」" else result)
            }
        }
    }

    private fun generateId(): String =
        "prof-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** 危险/覆盖键扫描：提示哪些字段将从配置中剥离。 */
    private fun dangerScan(yaml: String): List<String> {
        val danger = ClashProxyManager.OVERRIDDEN_KEYS + listOf("dns", "script")
        return yaml.lines().map { it.trimStart() }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { line ->
                val key = danger.firstOrNull { line.startsWith("$it:") || line.startsWith("$it :") }
                key?.let { "将剥离顶层字段：$it" }
            }
    }
}