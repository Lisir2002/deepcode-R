# 长输出分块消息方案设计

> 评审状态：📝 草案

## 背景

模型输出超长内容时偶发「无声闪退」（无 Java 日志，疑似 SQLite 原生崩溃 / OOM 被杀）。
根因之一是单条消息 content 过长：超大数据行经 CursorWindow 读取会触发
`SQLiteBlobTooBigException`（约 2MB/行 上限）导致原生崩溃；同时超长字符串在
内存中被多次持有也易触发 OOM。

此前 `sanitizeContent` 以 `MAX_CONTENT_CHARS=200_000` 截断兜底，但截断会丢内容，
体验差。本方案改为**分块存储**：超长消息按块拆分为多行落库，读取时按序拼接，
既保证单行不超 CursorWindow 限制，又**不限制最终消息长度**。

## 目标

1. 单条消息内容不设长度上限，不再截断。
2. 任何单行 content ≤ CHUNK_SIZE（100k 字符，UTF-8 约 ≤300KB），杜绝 CursorWindow 崩溃。
3. 读取侧（UI 流、buildHistory）按 `chunk_index` 序拼接，用户无感知。
4. 兼容备份/恢复、上下文压缩、任务分组等既有链路。

## 非目标

- 不处理流式阶段内存（已有 `capStreamingText` 400k 显示护栏）。
- 不做文件外置存储（分块行全部留在 `agent_messages`，保持备份/搜索语义）。

## 设计

### Schema v48 → v49

`agent_messages` 增加两列（列名用 camelCase，与实体字段名一致——Room 未显式指定列名时默认以字段名作列名）：

```sql
ALTER TABLE agent_messages ADD COLUMN chunkGroupId TEXT NOT NULL DEFAULT '';
ALTER TABLE agent_messages ADD COLUMN chunkIndex INTEGER NOT NULL DEFAULT 0;
```

- `chunkGroupId`：分块组 id（= 主消息 id）。非分块消息为空串。
- `chunkIndex`：块序号，0 起。主行（含全部元数据）为 0。

### 实体

`AgentMessageEntity` 追加 `chunkGroupId: String = ""`、`chunkIndex: Int = 0`（默认值，
既有位置构造调用不破坏）。

### 写入（MessagePersistenceUseCase.persist）

1. `stripInlineImages(content)`：仅剥离内嵌 base64 图片（不截断）。
2. 若长度 ≤ CHUNK_SIZE：单行落库（`chunkGroupId=""`），行为同现状。
3. 若超长：按 `CHUNK_SIZE` 切成多块，**共享同一 timestamp**（保证查询邻接），
   主行（chunk 0）携带全部元数据（toolCallsJson / reasoning / signature / attachmentsJson /
   taskId 等），续块行（chunk 1..N-1）仅携带 content + 会话/角色/timestamp + chunk 字段，
   id 用 `主id + "#c" + i`。整组 `insertAll` 原子写入。
4. reasoning 维持 `sanitizeContent`（200k 截断）单行兜底，不做分块（优先级低于正文）。

### 读取拼接（MessagePersistenceUseCase.mergeChunks）

按 `chunkGroupId` 分组 → 组内按 `chunkIndex` 升序 → content 与 reasoning 拼接 →
以 chunk 0（主行）为基底产出单条实体（清空 chunk 字段）。遍历原顺序输出，保序。

接入点：
- `buildHistory`：`getMessagesBySessionOnce` → `mergeChunks` → 过滤 isCompacted → 配对逻辑。
- UI 流（AIAgentViewModel.messagesState）：`getMessagesBySessionPaged` → `mergeChunks` →
  既有 filter → `toUIMessage()`。

分页边界说明：单条消息跨页被截断（>30 块、>3M 字符的病态场景）时按已有块渲染，
loadMore 拉全后 mergeChunks 自动补齐。

### 备份兼容

`AgentMessageDto` 追加 `chunkGroupId` / `chunkIndex`（默认值），`toDto`/`toEntity`
透传，保证备份/恢复后分块组结构不变。

## 常量

| 常量 | 值 | 说明 |
|---|---|---|
| `CHUNK_SIZE` | 100_000 | 单块字符数（UTF-8 ≤ ~300KB，远低于 2MB） |
| `MAX_CONTENT_CHARS` | 200_000 | 保留为 reasoning 等非分块字段的单行兜底 |

## 影响面

- `AgentDatabase.kt`：version 48 → 49。
- `app/src/main/assets/migrations/49_add_message_chunking.sql`：新增迁移。
- `AgentMessageEntity.kt`：+2 字段。
- `MessagePersistenceUseCase.kt`：分块写入 + mergeChunks + buildHistory 接入。
- `AIAgentViewModel.kt`：messagesState 接入 mergeChunks。
- `BackupSnapshot.kt` / `BackupManagerImpl.kt`：DTO + 转换透传。
- `app/schemas/.../49.json`：KSP 构建自动导出。

## 验证

1. `./gradlew :app:assembleDebug` 编译通过。
2. `./gradlew :app:testReleaseUnitTest` 全绿（含既有 sanitizeContent 单测、迁移一致性闸门）。
3. 真机：长输出 > 100k 字符 → 落库分块、UI 完整展示、buildHistory 完整回放、备份/恢复后完整。
