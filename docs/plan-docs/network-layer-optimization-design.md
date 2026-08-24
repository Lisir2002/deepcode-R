# 网络层性能优化设计

> 状态：📝 草案（仅方案讨论，本次不落地）
> 日期：2026-08-24
> 范围：模型接口（AI Provider：OpenAI / Anthropic / Gemini）的「接受（TTFT）与返回（TOKT）速度」优化

## 1. 背景与目标

当前 AI 对话链路的网络层（Retrofit + OkHttp + Gson，SSE 流式）存在可量化的性能瓶颈。本文按「接受速度（首字延迟 TTFT）」与「返回速度（token 吞吐 TOKT）」两条主链路，提出 L1 连接/传输、L2 解析/序列化、L3 流式通道三层优化方案，并给出收益/成本/风险与落地顺序。

目标：**不动 API 语义、不改对外行为**的前提下，降低首字延迟与解析开销，提升流式吞吐的 CPU/GC 效率。

## 2. 现状诊断

### 2.1 网络栈

- 单一共享 `OkHttpClient`：connect/read/write 均 120s，注入 `ProxyRouteHolder` 路由选择器（代理走 mihomo mixed-port）；连接池/Dispatcher 均默认值。
- 3 个 Retrofit（OpenAI / Anthropic / Gemini）均用 `GsonConverterFactory`；DTO 为 Gson 注解 data class。
- 三家 provider 接口同构：非流式返回 DTO/`JsonObject`，流式 `@Streaming` 返回 `ResponseBody`。

### 2.2 流式关键路径（三家同构）

```
ResponseBody
 → charStream().buffered().readLine()                       // 字符流逐行（多一层解码+拷贝）
 → "data:" 过滤 → JsonParser.parseString(data).asJsonObject  // ★ 每行建整棵 Gson JSON tree（CPU 大头）
 → when(type) 分发 → emit(AIStreamChunk.TextDelta) 逐 token
 → flowOn(Dispatchers.IO) + 首字节 watchdog(60s) + 指数退避重试(6 次)
```

### 2.3 瓶颈定位

| 维度 | 瓶颈 | 严重度 |
|---|---|---|
| TTFT | 无连接预热；默认连接池 5 连接/5min 复用率低；代理链路多一跳；DNS 走系统解析无缓存 | 中 |
| TOKT | 每行 SSE 用 Gson `JsonParser` 建整树（慢+高分配）；`charStream` 比 Okio 字节读慢；双 JSON 库混用（Gson 行解析 + kotlinx 工具参数转换 `jsonElementToMap/mapToJson` 反复倒腾） | 高 |
| 请求体 | Gson 反射序列化 DTO（系统 prompt + 历史 + 工具 schema，体积大时慢） | 中低 |

## 3. 优化方案

### L2 解析/序列化层（TOKT 主攻，性价比最高）

#### P1 · SSE 行流式解析（换掉 Gson 整树）

- 读：`rb.source()` → Okio `readUtf8Line()`（字节直读，省字符解码+拷贝）。
- 解析：对 SSE 行做**定点字段抽取**，只分配 token 字符串、不建整树：
  - OpenAI：`{"choices":[{"delta":{"content":"xxx"}}]}` → 取 `content`
  - Anthropic：`{"type":"content_block_delta","delta":{"type":"text_delta","text":"xxx"}}` → 取 `text`
  - Gemini：`{"candidates":[{"content":{"parts":[{"text":"xxx"}]}}]}` → 取 `text`
- 实现载体：Gson `com.google.gson.stream.JsonReader` 或 Moshi `JsonReader` 流式定点遍历（语义清晰、转义可靠）；手写最小提取器最快但易错。
- 注意点：`\n / \" / \uXXXX` 转义正确处理；Anthropic 工具参数（`input_json_delta` 的 `partial_json`）仍需完整累积 JSON，保留完整解析路径。
- 收益：解析 CPU 降 50%+，GC 压力大减。

#### P2 · 统一 JSON 栈（去双库）

- 全链路统一 `kotlinx.serialization`（codegen 编译期序列化器、无反射）+ `ignoreUnknownKeys=true`。
- 三家 DTO 全量 `@Serializable`；SSE 行仍用 JsonReader 定点抽（kotlinx 无流式 Reader）。
- 删除 `jsonElementToMap` / `mapToJson` 等 Gson↔kotlinx 转换桥接。
- 收益：无反射序列化更快、类型安全、代码更简。
- 成本：DTO 改造面最大，需回归三家 provider 兼容边界（null 处理、未知字段）。

#### P3 · 请求体序列化（随 P2）

- codegen 序列化 + 裁剪冗余字段 → 请求体更小 → 服务端读请求更快 → 对 TTFT 亦有贡献。

### L1 连接/传输层（TTFT 主攻）

| 项 | 做法 | 收益 | 成本/风险 |
|---|---|---|---|
| C1 连接预热 | 启动/切 provider 时对目标 host 后台发最小请求预建连接（留在池中） | 省 DNS+TCP+TLS+HTTP2 握手 1~3 RTT（弱网数百 ms~1s+） | 误预热白费流量；实现几十行 |
| C2 连接池调优 | `ConnectionPool(8, 15min)`（默认 5/5min） | 长连接复用率↑，TTFT↓ | 一行配置；注意 fd 上限 |
| C3 异步 DNS 缓存 | `OkHttp dns{}` 覆盖 + 短 TTL 缓存（或 DoH） | 避免每次系统 DNS（几十~几百 ms） | 缓存失效→连接失败，需可回退 |
| C4 HTTP/3 | OkHttp 原生支持有限，完整需引 Cronet | 弱网/高丢包 TTFT 显著（0-RTT/连接迁移） | 体积 +数 MB；与代理 mihomo 的 UDP 转发兼容性未知；适配大 → 后置 |
| C5 直连/代理分流 | 已知 AI host 直连、其余走 mihomo（做成可配置策略） | 去代理一跳延迟 | 直连不通地区需回退代理，需可配置 |

### L3 流式通道层

- S1 delta 聚合：每 token `emit` 已足够；聚合引入渲染延迟，对工具调用场景不利 → 建议保持逐 token。
- S2 微调：`AILogger.logResponseStream` 的 rawSse 落盘在流式热路径上（StringBuilder 追加 + 写盘），可异步/降频；取消传播已完善，保持。

## 4. 方案取舍与推荐路径

```
P1+P2 (L2) → C1+C2+C3 (L1 小项) → S2 (L3) → C5 → C4 (Cronet，单独评估)
```

- P1 是纯本地可测、收益最直接的单项；P2 与 P1 同层可合并实施。
- C1/C2/C3 改动小、TTFT 收益直接，可优先。
- C5 依赖实际代理使用场景（若用户普遍依赖代理，直连分流需谨慎）。
- C4（HTTP/3 + Cronet）收益大但成本/风险最高，后置单独评估。

## 5. 验证方案

- 基准：流式 TOKT 基准测试（固定 prompt + 固定模型，对比优化前后解析耗时/GC/首字延迟）。
- 回归：三家 provider 各跑 非流式 + 流式 + 工具调用 三条链路。
- 兼容：未知字段/异常 SSE 行/转义字符的容错测试保持通过。
- 弱网：网络限速/丢包模拟验证 C1~C3 收益与回退。

## 6. 本次范围

仅方案讨论与设计沉淀，**不落地改码**。落地需用户另行明确指令，并按「分支与改动工作流」在 `feat/network-opt` 分支实施，同步更新 `docs/modules/agent.md`（或新增网络层模块文档）。
