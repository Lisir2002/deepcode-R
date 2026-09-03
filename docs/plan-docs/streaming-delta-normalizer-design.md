# 流式增量语义归一化设计（预防"字段语义假设"类 bug）

> 评审状态：✅ 已实施

## 背景

### 本次问题（触发点）

用户在 deepseek-v4-flash（OpenAI 兼容协议）下发了一张截图，思考过程（reasoning）气泡出现**同一段 base64 被完整重复几百行**。

追踪完整链路后定位根因链：

1. **上游行为**：DeepSeek 兼容生态流式返回 `delta.reasoning_content` 时，每个 chunk 是**完整思考内容（全量）**，而非增量 delta——`reasoning_content` 字段没有像 `content` 那样统一增量规范。
2. **adapter**：`OpenAIAdapter` 把每行原样 `emit(AIStreamChunk.ReasoningDelta(r))`，假设是增量（[OpenAIAdapter.kt](../../app/src/main/java/com/core/deepcode/feature/agent/domain/provider/OpenAIAdapter.kt)）。
3. **workflow（真正的放大点）**：`reasoningAcc.append(chunk.text)` 把"全量"当"增量"累积，全量串 A 出现 n 次 → 累积 = A 重复 n 次（[StatefulAgentWorkflow.kt](../../app/src/main/java/com/core/deepcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt)）。线性放大，与模型无关。
4. **防御失效**：`capStreamingText` 只截流式显示（100k）、`sanitizeContent` 只剥带 `data:image/` 前缀的 base64（[MessagePersistenceUseCase.kt](../../app/src/main/java/com/core/deepcode/feature/agent/domain/session/MessagePersistenceUseCase.kt)）——本案例模型输出的是**无前缀裸 base64**，恰好绕过，且累积本身无上限。

### 本质

不是"一个字段坏了"，而是**"流式字段的增量语义假设"这一整类风险**：代码对每个流式字段隐含假设"上游必为增量"，但跨 provider 生态里该假设不成立。这类隐患同时埋在**正文 text、工具参数 arguments** 等所有裸 `StringBuilder.append` 处。

## 目标

1. 让流式累积对"上游增量/全量语义差异"完全免疫，从源头消除放大。
2. 消除"字段语义假设"这一整类 bug 的再发可能（不止 reasoning）。
3. 累积层自带护栏（长度上限 / 重复折叠 / 异常数据识别），不再依赖下游显示/落库截断。
4. 每个流式字段有明确语义契约与对应单测夹具，防回归。

## 非目标

- 不改变 provider 协议适配（仍由各 adapter 做字段抽取）。
- 不做 UI 改造（ReasoningBubble 折叠/渲染维持现状，折叠只是兜底）。
- 不改变落库分块/截断策略（作为二道兜底保留）。

## 设计原则

1. **workflow 层零语义假设**：任何流式字段的累积一律走统一归一化器，禁止裸 `append`。
2. **语义由 adapter 声明，归一化层消费**：契约显式化，不赌上游行为。
3. **护栏在累积层**：累积器内置上限/去重/异常识别，不依赖下游截断兜底。
4. **异常数据识别前移**：裸 base64 / 病态重复在累积层处理，而非落库层。
5. **语义假设必须配三夹具单测**（增量 / 全量 / 混合），新增 provider 流式字段强制补齐。

## 核心设计：StreamDeltaNormalizer

在 workflow 的累积点（text / reasoning / tool arguments）之前插入统一归一化器，替代裸 `StringBuilder.append`。

### 1. 适配层：语义声明

各 adapter 声明其流式字段语义，归一化器据此选择策略：

| Provider | 字段 | 实际语义 | 声明 |
|---|---|---|---|
| Anthropic | `delta.thinking` | 规范增量 | `INCREMENTAL` |
| Gemini | `parts[].thought` | 单次完整 thought | `FULL_SNAPSHOT` |
| OpenAI | `reasoning_content` | 兼容网关常全量重发 | `AUTO_DETECT` |
| OpenAI | `delta.content` | 规范增量 | `INCREMENTAL` |
| OpenAI | `tool_calls[].arguments` | 增量片段 | `INCREMENTAL` |
| Anthropic | `input_json_delta` | 增量片段 | `INCREMENTAL` |
| Gemini | `functionCall` | 全量 JSON | `FULL_SNAPSHOT` |

> 注意：`FULL_SNAPSHOT`（Gemini thought / functionCall）同样被现有"裸 append"错误累积——本轮未触发，属同类隐患。

### 2. 算法层：全量/增量自动判别（AUTO_DETECT）

**"上次完整快照"判别法**，不依赖全局比对：

```
维护: acc(已累积), lastFull(上次收到的完整内容)

新 chunk c:
  c == lastFull            → 全量重发，跳过（去重！）
  c.startsWith(lastFull)   → 增量，append c 的新增尾巴
  lastFull.startsWith(c)   → 全量重发且变短，用 c 替换 acc（罕见）
  否则                     → 真增量，append；更新 lastFull = acc
```

对本次场景：每 chunk 是同一段 base64 → `c == lastFull` → **全部去重，只留一份**。正常增量流不受影响。

### 3. 护栏层：累积级三道防线

1. **长度上限**：`MAX_ACC_CHARS = 200_000`（与落库对齐），超限截断 + 置 `truncated` 标记；每次 send 只发截断后的串，杜绝"先累积几十 MB 再拷贝"。
2. **周期性重复折叠**：滚动窗口检测同一子串重复 ≥3 次 → 折叠为单份 + 标注 `[重复内容已折叠 ×N]`。兜住判别算法未识别的模型病态输出。
3. **异常 base64 折叠**：识别裸 base64（长度 > 阈值 && 匹配 `^[A-Za-z0-9+/]{64,}={0,2}$` && 无 `data:image/` 前缀）→ 替换为 `[图片数据已省略]`。补齐 `sanitizeContent` 只剥 `data:image/` 前缀的盲区。

### 4. 输出契约

归一化器输出两种语义，下游零歧义：

```kotlin
sealed class NormalizedDelta {
    data class Append(val text: String)   // 真增量，直接累积
    data class Duplicate(val count: Int)  // 全量重发/重复，已去重，仅统计
}
```

workflow 只消费 `Append`，UI 只渲染 `Append` 累积。放大在源头消除；显示/落库截断降级为二道兜底。

## 约束：多轮回传字段保留

归一化/折叠/截断**不得破坏回传所需字段**：

- Anthropic 要求上一轮 thinking + signature 原样回传，否则 400（[AnthropicAdapter.kt](../../app/src/main/java/com/core/deepcode/feature/agent/domain/provider/AnthropicAdapter.kt)）。
- DeepSeek 思考模式要求 assistant 消息的 `reasoning_content` 字段必须存在（即使空串），否则工具轮回调 400（[OpenAIAdapter.kt](../../app/src/main/java/com/core/deepcode/feature/agent/domain/provider/OpenAIAdapter.kt)）。

→ 归一化后的累积结果仍作为 reasoning 落库并在下一轮回传（与现状一致），折叠/截断只影响展示，不得清空回传字段。截断时应保留头部（思考语义在开头）。

## 可观测性与测试

### 可观测性

- 归一化器埋点：检测到全量重发 / 重复折叠 / 截断时，用 `AILogger` 打 warn（带 sessionId、provider、放大倍数/折叠次数）。
- 预留指标：reasoning 原始接收字节 vs 归一化后字节的比率，超阈值可观测到"异常放大上游"。

### 测试（防回归）

`DeltaAccumulator` 纯算法单测，覆盖：

- 增量流（`INCREMENTAL`）：逐 chunk 追加，输出 = 拼接。
- 全量重发流（`FULL_SNAPSHOT` / 自动判别命中）：只保留一份，`Duplicate` 计数正确。
- 混合流：先增量后全量 / 全量变短等边界。
- 护栏：超长截断置位、重复折叠标注、裸 base64 折叠、空 chunk / 空白 chunk。
- 回传约束：截断/折叠后 reasoning 非空且保留头部。

## 落地路径（分阶段，防扩散）

| 阶段 | 内容 | 风险 |
|---|---|---|
| P0 | 新建 `DeltaAccumulator` + 三夹具单测（纯算法，不动调用方） | 零 |
| P1 | reasoning 累积接入归一化器（修本次 bug）+ base64 折叠 + 长度护栏 | 低 |
| P2 | text / tool arguments 累积接入（消除另两处同类隐患） | 中 |
| P3 | 可观测埋点 + 放大比率日志 + 模块文档（agent.md）同步 | 低 |

### 落地情况

- P0：`core/network/DeltaAccumulator.kt`（AUTO_DETECT / INCREMENTAL / FULL_SNAPSHOT 三语义 + 去重 / 折叠 / 截断三护栏 + `rawCharsReceived` 观测指标）；`core/network/DeltaAccumulatorTest.kt` 14 条单测全绿。
- P1：`StatefulAgentWorkflow` 主路径与 vision 降级路径的 `reasoningAcc`/`reasoning2` 由 `StringBuilder` 换为 `DeltaAccumulator()`。
- P2：`StatefulAgentWorkflow` 正文 `acc`/`acc2` 接入 `DeltaAccumulator()`（AUTO_DETECT）；`OpenAIAdapter.OpenAIToolAcc.args`、`AnthropicAdapter.ToolBlockAcc.args` 接入 `DeltaAccumulator(INCREMENTAL)`（工具参数增量）。
- P3：`StatefulAgentWorkflow.logNormalizerGuardrails` 在护栏触发时打 warn（sessionId / model / 去重次数 / 截断标记 / 放大比率）；设计文档本页 + `docs/modules/agent.md` §3.1 同步。

## 关联文档

- 网络层优化设计（本问题上游上下文）：`network-layer-optimization-design.md`
- agent 模块文档：[docs/modules/agent.md](../modules/agent.md)
- 长输出分块方案（落库侧兜底）：`chunked-message-design.md`
