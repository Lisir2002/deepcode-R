-- RC69 SCHEMA 39：新增 T2I（Text-to-Image 文生图）三张表
-- 严格对齐 RC68 幂等约定：CREATE TABLE IF NOT EXISTS / INSERT OR IGNORE / DROP IF EXISTS
-- 所有布尔列 INTEGER + CHECK(IN (0,1))，时间列统一 createdAtMs/updatedAtMs 毫秒单位。
-- 新增表不涉及老数据回拷，直接建表即可（不需要 NEW/RENAME 四步法的全表重建）。

-- ══════════════════════════════════════════════════════════
-- 1/3 t2i_providers：文生图 Provider（独立于 ai_providers，因为 T2I 接口契约与 LLM 完全不同）
--   · 支持 OpenAI-compatible / Anthropic / Gemini / 第三方自建 Stable Diffusion 等 T2I 端点
--   · endpointMode = SYNC（响应体直接给图）| ASYNC（先返回 taskId，再轮询）| AUTO（先探测再缓存）
--   · isActive 互斥：全局最多 1 行 = 1（仓储级 invariant 由 T2IRepository 保证）
--   · priority 用于多 provider failover 排序（数字越大优先级越高）
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS t2i_providers (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    baseUrl TEXT NOT NULL,
    encryptedApiKey TEXT NOT NULL DEFAULT '',
    endpointMode TEXT NOT NULL DEFAULT 'AUTO',
    isActive INTEGER NOT NULL DEFAULT 0 CHECK (isActive IN (0,1)),
    priority INTEGER NOT NULL DEFAULT 0,
    isEnabled INTEGER NOT NULL DEFAULT 1 CHECK (isEnabled IN (0,1)),
    extraHeadersJson TEXT NOT NULL DEFAULT '',
    createdAtMs INTEGER NOT NULL DEFAULT 0,
    updatedAtMs INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS index_t2i_providers_isActive ON t2i_providers (isActive);
CREATE INDEX IF NOT EXISTS index_t2i_providers_priority ON t2i_providers (priority DESC);

-- ══════════════════════════════════════════════════════════
-- 2/3 t2i_provider_models：每个 T2I Provider 支持的模型清单 + 能力元数据
--   · 与 ModelMetadata（LLM 用）概念对齐，但能力维度换成 T2I 专用：
--     supportsHd / supportsInpaint / defaultWidth / defaultHeight / maxSteps
--   · 一个 provider 对应多条 model 行（1:N）
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS t2i_provider_models (
    id TEXT NOT NULL PRIMARY KEY,
    providerId TEXT NOT NULL,
    modelId TEXT NOT NULL,
    displayName TEXT NOT NULL DEFAULT '',
    supportsHd INTEGER NOT NULL DEFAULT 0 CHECK (supportsHd IN (0,1)),
    supportsInpaint INTEGER NOT NULL DEFAULT 0 CHECK (supportsInpaint IN (0,1)),
    defaultWidth INTEGER NOT NULL DEFAULT 1024,
    defaultHeight INTEGER NOT NULL DEFAULT 1024,
    maxSteps INTEGER NOT NULL DEFAULT 50,
    defaultSteps INTEGER NOT NULL DEFAULT 30,
    costPerImageTokens INTEGER NOT NULL DEFAULT 0,
    createdAtMs INTEGER NOT NULL DEFAULT 0,
    updatedAtMs INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS index_t2i_provider_models_providerId ON t2i_provider_models (providerId);
CREATE UNIQUE INDEX IF NOT EXISTS index_t2i_provider_models_providerId_modelId
    ON t2i_provider_models (providerId, modelId);

-- ══════════════════════════════════════════════════════════
-- 3/3 t2i_tasks：文生图任务（含同步/异步、重试、崩溃恢复状态机）
--   · status = PENDING | RUNNING | SUCCESS | FAILED | PENDING_RETRY | DANGLING
--   · 崩溃恢复：冷启动扫描 status IN (PENDING, RUNNING) 且 updatedAtMs < (now - 30min)
--     → 标记为 DANGLING 或尝试重试（由 T2ITaskRecoveryWorker 负责）
--   · imagePath 为本地持久化文件路径（filesDir/t2i_images/{taskId}.png）
--   · providerRef/endpointModeRef：创建任务时的快照，避免后续 provider 配置变更导致历史任务恢复时路由错
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS t2i_tasks (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    messageId TEXT NOT NULL DEFAULT '',
    prompt TEXT NOT NULL,
    negativePrompt TEXT NOT NULL DEFAULT '',
    width INTEGER NOT NULL DEFAULT 1024,
    height INTEGER NOT NULL DEFAULT 1024,
    steps INTEGER NOT NULL DEFAULT 30,
    seed INTEGER NOT NULL DEFAULT 0,
    hd INTEGER NOT NULL DEFAULT 0 CHECK (hd IN (0,1)),
    providerId TEXT NOT NULL,
    modelId TEXT NOT NULL,
    providerRef TEXT NOT NULL DEFAULT '',
    endpointModeRef TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'PENDING',
    imagePath TEXT NOT NULL DEFAULT '',
    thumbnailPath TEXT NOT NULL DEFAULT '',
    remoteTaskId TEXT NOT NULL DEFAULT '',
    progressPercent INTEGER NOT NULL DEFAULT 0,
    retryCount INTEGER NOT NULL DEFAULT 0,
    maxRetries INTEGER NOT NULL DEFAULT 3,
    errorCode TEXT NOT NULL DEFAULT '',
    errorMessage TEXT NOT NULL DEFAULT '',
    permissionDecision TEXT NOT NULL DEFAULT '',
    quotaDeductedTokens INTEGER NOT NULL DEFAULT 0,
    createdAtMs INTEGER NOT NULL DEFAULT 0,
    updatedAtMs INTEGER NOT NULL DEFAULT 0,
    completedAtMs INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS index_t2i_tasks_sessionId_createdAtMs
    ON t2i_tasks (sessionId, createdAtMs DESC);
CREATE INDEX IF NOT EXISTS index_t2i_tasks_status ON t2i_tasks (status);
CREATE INDEX IF NOT EXISTS index_t2i_tasks_messageId ON t2i_tasks (messageId);
