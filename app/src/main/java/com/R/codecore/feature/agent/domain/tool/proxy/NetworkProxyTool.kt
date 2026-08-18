package com.R.codecore.feature.agent.domain.tool.proxy

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import com.R.codecore.feature.proxy.domain.ClashProxyManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 网络代理工具（network_proxy）：让模型自助管理容器内的 mihomo 代理（VPN 形态）。
 *
 * 护栏（《网络代理设计 v1.0》§3 / §8）：
 *  - [ToolPermissionPolicy.ASK]：每次调用都弹确认卡，能力隔离 [ToolCapability.MODIFY_NETWORK]；
 *  - **播种后接管**：`on` 只能引用已播种 profile（[ProxySettingsRepository]）或用**临时 inline** YAML
 *    （仅本次会话、不新建长存订阅），模型不能凭空造新订阅；
 *  - 输出一律脱敏：订阅 URL / YAML / secret 不回显，只回 `id/name/kind` 与运行态。
 *
 * action ∈ { status, on, off, test, select, list_subscriptions, list_proxies, latency }
 *  - status / list_subscriptions：只读，返回脱敏概览；
 *  - on：`profile_id` 或 `inline_yaml`（临时）→ 合成配置；off：关；
 *  - test：给定 `url`（订阅）或 `yaml`（手动）做单次校验（拉取+统计，不落盘、不启用）；
 *  - select：`group` + `node` 切节点，或 `mode` 切运行模式；
 *  - list_proxies / latency：走 mihomo external-controller REST（未运行返回 PROXY_NOT_RUNNING）。
 */
class NetworkProxyTool @Inject constructor(
    private val manager: ClashProxyManager,
    private val repository: ProxySettingsRepository,
) : AgentTool() {

    private companion object {
        const val TAG = "NetworkProxyTool"
    }

    override val name = "network_proxy"
    override val description = "管理容器内网络代理（mihomo，VPN 形态）。action ∈ {status, on, off, test, select, list_subscriptions, list_proxies, latency}。on 用已播种 profile_id 或临时 inline_yaml 启用；select 切 select 分组节点或 mode。所有会改出口的操作都会请求用户确认。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_NETWORK, ToolCapability.NETWORK_READ)

    override val parameters = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "要执行的操作：status / on / off / test / select / list_subscriptions / list_proxies / latency",
            enum = listOf("status", "on", "off", "test", "select", "list_subscriptions", "list_proxies", "latency")
        ),
        "profile_id" to ToolParameter("profile_id", ParameterType.STRING, "on 时引用一个已播种的 profile id（list_subscriptions 可得）", required = false),
        "inline_yaml" to ToolParameter("inline_yaml", ParameterType.STRING, "临时代理配置 YAML（仅本次会话，不建成长期订阅）。on 时可用", required = false),
        "url" to ToolParameter("url", ParameterType.STRING, "test 单个订阅 URL", required = false),
        "yaml" to ToolParameter("yaml", ParameterType.STRING, "test 单个手动 YAML", required = false),
        "group" to ToolParameter("group", ParameterType.STRING, "select 目标分组名", required = false),
        "node" to ToolParameter("node", ParameterType.STRING, "select 目标节点名（选中分组内）", required = false),
        "mode" to ToolParameter("mode", ParameterType.STRING, "select 时切换运行模式：rule / global / direct", required = false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少 action", "MISSING_ACTION")
        return try {
            when (action) {
                "status" -> doStatus()
                "on" -> doOn(args)
                "off" -> doOff()
                "test" -> doTest(args)
                "select" -> doSelect(args)
                "list_subscriptions" -> doListSubscriptions()
                "list_proxies" -> doListProxies()
                "latency" -> doLatency(args)
                else -> ToolResult.Error("未知 action：$action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "network_proxy 失败: ${e.message}")
            ToolResult.Error("操作失败：${e.message}", "PROXY_FAILED")
        }
    }

    // ── 只读：status ──
    private suspend fun doStatus(): ToolResult {
        val s = manager.state.value
        val controllerCheck = if (s.enabled) manager.controllerRequest("GET", "/configs") else null
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "enabled" to JsonPrimitive(s.enabled),
                    "mode" to JsonPrimitive(s.mode),
                    "active_profile_id" to (s.activeProfileId?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
                    "mixed" to JsonPrimitive("${s.mixedHost}:${s.mixedPort}"),
                    "controller" to JsonPrimitive(manager.controllerAddress()),
                    "controller_reachable" to JsonPrimitive(controllerCheck != null),
                )
            )
        )
    }

    // ── 写：on / off ──
    private suspend fun doOn(args: Map<String, JsonElement>): ToolResult {
        val profileId = args["profile_id"]?.jsonPrimitive?.contentOrNull
        val inlineYaml = args["inline_yaml"]?.jsonPrimitive?.contentOrNull
        val result = manager.on(profileId, inlineYaml)
        if (result != "ok") return ToolResult.Error(result, "PROXY_ON_FAILED")
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "enabled" to JsonPrimitive(true),
                    "profile_id" to (profileId?.let { JsonPrimitive(it) } ?: JsonPrimitive("")),
                    "inline" to JsonPrimitive(inlineYaml != null),
                    "note" to JsonPrimitive("代理已启用：新容器进程生效；inline 仅本次会话、不入订阅列表"),
                )
            )
        )
    }

    private suspend fun doOff(): ToolResult {
        manager.off()
        return ToolResult.Success(
            JsonObject(
                mapOf("ok" to JsonPrimitive(true), "enabled" to JsonPrimitive(false))
            )
        )
    }

    // ── 只读：list_subscriptions（脱敏）──
    private suspend fun doListSubscriptions(): ToolResult {
        val list = repository.subscriptionsFlow.first()
        val items = JsonArray(
            list.map { s ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(s.id),
                        "name" to JsonPrimitive(s.name),
                        "kind" to JsonPrimitive(s.kind),
                    )
                )
            }
        )
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "count" to JsonPrimitive(items.size),
                    "subscriptions" to items
                )
            )
        )
    }

    // ── 校验：test（单次拉取/校验，不落盘不启用）──
    private suspend fun doTest(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
        val yaml = args["yaml"]?.jsonPrimitive?.contentOrNull
        val source = when {
            url != null -> manager.fetchSubscriptionYaml(url)
            yaml != null -> yaml
            else -> return ToolResult.Error("test 需要 url（订阅）或 yaml（手动）", "MISSING_ARGS")
        }
        if (source.isNullOrBlank()) return ToolResult.Error("拉取/解析失败（URL 不可达或内容为空）", "FETCH_FAILED")
        val config = manager.synthesizeConfig(source)
        val nodeCount = Regex("(?m)^\\s*-\\s+name:").findAll(config).count()
        val groupCount = Regex("(?m)^\\s*proxy-groups:\\s*$").findAll(config).count()
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "valid" to JsonPrimitive(true),
                    "node_count" to JsonPrimitive(nodeCount),
                    "group_count" to JsonPrimitive(groupCount),
                    "note" to JsonPrimitive("本次仅校验，未启用未落盘"),
                )
            )
        )
    }

    // ── 写：select 切节点 / 切 mode ──
    private suspend fun doSelect(args: Map<String, JsonElement>): ToolResult {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull
        val group = args["group"]?.jsonPrimitive?.contentOrNull
        val node = args["node"]?.jsonPrimitive?.contentOrNull
        if (mode != null) {
            val body = """{"mode":"$mode"}"""
            val resp = manager.controllerRequest("PATCH", "/configs", body)
            if (resp == null) return selOffline()
            return ToolResult.Success(
                JsonObject(mapOf("ok" to JsonPrimitive(true), "mode" to JsonPrimitive(mode)))
            )
        }
        if (group == null || node == null) {
            return ToolResult.Error("select 需要 group+node（切节点）或 mode（切模式）", "MISSING_ARGS")
        }
        val resp = manager.controllerRequest("PUT", "/proxies/${encoded(group)}", """{"name":"$node"}""")
        if (resp == null) return selOffline()
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "group" to JsonPrimitive(group),
                    "node" to JsonPrimitive(node)
                )
            )
        )
    }

    private fun selOffline(): ToolResult =
        ToolResult.Error("mihomo 未在运行（代理未启用或控制面不可达），无法 select", "PROXY_NOT_RUNNING")

    // ── 只读：list_proxies / latency（走 REST）──
    private suspend fun doListProxies(): ToolResult {
        val resp = manager.controllerRequest("GET", "/proxies")
            ?: return ToolResult.Error("mihomo 未在运行", "PROXY_NOT_RUNNING")
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject }.getOrNull()
        return ToolResult.Success(
            parsed ?: JsonObject(mapOf("ok" to JsonPrimitive(false), "raw" to JsonPrimitive(resp.take(2000))))
        )
    }

    private suspend fun doLatency(args: Map<String, JsonElement>): ToolResult {
        val node = args["node"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("latency 需要 node", "MISSING_NODE")
        val resp = manager.controllerRequest("GET", "/proxies/${encoded(node)}/delay?url=http://www.gstatic.com/generate_204&timeout=5000")
            ?: return ToolResult.Error("mihomo 未在运行或节点无效", "PROXY_NOT_RUNNING")
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject }.getOrNull()
        return ToolResult.Success(
            parsed ?: JsonObject(mapOf("raw" to JsonPrimitive(resp.take(2000))))
        )
    }

    private fun encoded(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}