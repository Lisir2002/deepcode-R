package com.R.codecore.feature.proxy.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import com.R.codecore.feature.proxy.domain.ClashProxyManager
import com.R.codecore.feature.proxy.domain.ProxyRuntimeState
import com.R.codecore.feature.proxy.domain.ProxySubscription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
            val config = manager.synthesizeConfig(resolved)
            val nodeCount = Regex("(?m)^\\s*-\\s+name:").findAll(config).count()
            val groupCount = Regex("(?m)^\\s*proxy-groups:\\s*$").findAll(config).count()
            val warnings = runCatching { dangerScan(resolved) }.getOrDefault(emptyList())
            _preview.value = ProxyPreview(
                ok = true,
                summary = "解析 OK · 节点 $nodeCount · 分组 $groupCount · mode rule",
                nodeCount = nodeCount,
                groupCount = groupCount,
                warnings = warnings,
                resolvedYaml = resolved
            )
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