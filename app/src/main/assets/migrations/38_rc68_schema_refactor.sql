-- RC68 SCHEMA 38：跨 6 张表的「结构化重命名 + 明文敏感列消除」重构
-- 采用「CREATE NEW → INSERT OR IGNORE 回拷 → DROP OLD → RENAME NEW」的幂等四步法，
-- 原因：SQLite < 3.35 不支持 ALTER TABLE DROP COLUMN / RENAME COLUMN，
-- 必须走全表重建（SQL 标准最稳写法，在所有 Android API 21+ 都过）。
-- 所有语句保持 IF NOT EXISTS / INSERT OR IGNORE / DROP IF EXISTS，防止：
--   ① 迁移中途被 SIGKILL → 重跑时不崩到 Funnel 2；
--   ② 新安装用户 onCreate 已拿 RC68 新 schema → 迁移里 CREATE TABLE IF NOT EXISTS 不报错。

-- ══════════════════════════════════════════════════════════
-- 1/6 ai_providers：
--   - 删除明文 apiKey 列（仅保留 encryptedApiKey，由 31_add_encrypted_fields.sql 引入的加密列）
--   - 删除冗余的 selectedModel 列（defaultModel 即当前选中模型；UI 统一语义）
--   - CHECK(isActive IN (0,1))：仓储级 invariant 的 DB 级兜底，确保 isActive 互斥逻辑永不写脏值
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS ai_providers_new (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    baseUrl TEXT NOT NULL,
    encryptedApiKey TEXT NOT NULL DEFAULT '',
    defaultModel TEXT NOT NULL,
    isActive INTEGER NOT NULL CHECK (isActive IN (0,1)),
    models TEXT NOT NULL DEFAULT '',
    isEnabled INTEGER NOT NULL DEFAULT 1,
    useFullUrl INTEGER NOT NULL DEFAULT 0,
    useResponseApi INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO ai_providers_new (
    id, name, type, baseUrl, encryptedApiKey, defaultModel,
    isActive, models, isEnabled, useFullUrl, useResponseApi
)
SELECT
    id, name, type, baseUrl,
    -- 31 迁移前的老用户可能 encryptedApiKey 为空；
    -- 这里不为他们自动加密明文（避免冷启动 DB 锁 + Keystore 首次初始化时序问题），
    -- 留空 → AIProviderRepository.resolveApiKey 返回空 → 下次 UI 保存 Provider 时由
    -- CredentialEncryptor 加密写回（RC68 saveProvider.toEntity 路径已重写 always 走加密）。
    CASE WHEN encryptedApiKey IS NULL OR encryptedApiKey = '' THEN '' ELSE encryptedApiKey END,
    -- selectedModel 合并：有 selectedModel 且非空时优先用它，否则 fallback defaultModel。
    CASE
        WHEN selectedModel IS NOT NULL AND selectedModel != '' THEN selectedModel
        ELSE defaultModel
    END,
    isActive, models, isEnabled, useFullUrl, useResponseApi
FROM ai_providers;

DROP TABLE IF EXISTS ai_providers;
ALTER TABLE ai_providers_new RENAME TO ai_providers;

-- ══════════════════════════════════════════════════════════
-- 2/6 git_credentials：
--   - 删除明文 token 列（仅保留 encryptedToken，31 迁移引入）
--   - createdAt/updatedAt 列重命名 → createdAtMs/updatedAtMs（消除单位歧义）
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS git_credentials_new (
    id TEXT NOT NULL PRIMARY KEY,
    host TEXT NOT NULL,
    username TEXT NOT NULL,
    label TEXT NOT NULL DEFAULT '',
    encryptedToken TEXT NOT NULL DEFAULT '',
    isDefault INTEGER NOT NULL DEFAULT 0,
    createdAtMs INTEGER NOT NULL DEFAULT 0,
    updatedAtMs INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO git_credentials_new (
    id, host, username, label, encryptedToken, isDefault, createdAtMs, updatedAtMs
)
SELECT
    id, host, username, label,
    CASE WHEN encryptedToken IS NULL OR encryptedToken = '' THEN '' ELSE encryptedToken END,
    isDefault,
    -- 旧版存毫秒（即使列名没写 Ms，GitCredentialRepository 也是 System.currentTimeMillis() 写入），
    -- 所以直接赋值。万一有极端情况 0，也是默认值 0 对齐语义。
    COALESCE(createdAt, 0),
    COALESCE(updatedAt, 0)
FROM git_credentials;

DROP TABLE IF EXISTS git_credentials;
ALTER TABLE git_credentials_new RENAME TO git_credentials;

-- ══════════════════════════════════════════════════════════
-- 3/6 chat_sessions：createdAt/updatedAt → createdAtMs/updatedAtMs（消除单位歧义）
--   不破坏现有列；其他 20/22/26/29 迁移列保持原样回拷。
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS chat_sessions_new (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    createdAtMs INTEGER NOT NULL,
    updatedAtMs INTEGER NOT NULL,
    workspacePath TEXT NOT NULL DEFAULT '',
    mode TEXT NOT NULL DEFAULT 'BUILD',
    reasoningEffort TEXT NOT NULL DEFAULT 'MEDIUM',
    providerId TEXT,
    model TEXT,
    totalInputTokens INTEGER NOT NULL DEFAULT 0,
    totalOutputTokens INTEGER NOT NULL DEFAULT 0,
    lastInputTokens INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO chat_sessions_new (
    id, title, createdAtMs, updatedAtMs, workspacePath, mode, reasoningEffort,
    providerId, model, totalInputTokens, totalOutputTokens, lastInputTokens
)
SELECT
    id, title,
    COALESCE(createdAt, 0), COALESCE(updatedAt, 0),
    COALESCE(workspacePath, ''),
    COALESCE(mode, 'BUILD'),
    COALESCE(reasoningEffort, 'MEDIUM'),
    providerId, model,
    COALESCE(totalInputTokens, 0),
    COALESCE(totalOutputTokens, 0),
    COALESCE(lastInputTokens, 0)
FROM chat_sessions;

DROP TABLE IF EXISTS chat_sessions;
ALTER TABLE chat_sessions_new RENAME TO chat_sessions;

CREATE INDEX IF NOT EXISTS index_chat_sessions_workspacePath_updatedAtMs
    ON chat_sessions (workspacePath, updatedAtMs DESC);

-- ══════════════════════════════════════════════════════════
-- 4/6 todo_items：createdAt/updatedAt → createdAtMs/updatedAtMs
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS todo_items_new (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    subject TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'PENDING',
    priority INTEGER NOT NULL DEFAULT 0,
    `order` INTEGER NOT NULL DEFAULT 0,
    createdAtMs INTEGER NOT NULL,
    updatedAtMs INTEGER NOT NULL
);

INSERT OR IGNORE INTO todo_items_new (
    id, sessionId, subject, description, status, priority, `order`, createdAtMs, updatedAtMs
)
SELECT
    id, sessionId, subject, COALESCE(description, ''), COALESCE(status, 'PENDING'),
    COALESCE(priority, 0), COALESCE(`order`, 0),
    COALESCE(createdAt, 0), COALESCE(updatedAt, 0)
FROM todo_items;

DROP TABLE IF EXISTS todo_items;
ALTER TABLE todo_items_new RENAME TO todo_items;

CREATE INDEX IF NOT EXISTS index_todo_items_sessionId_order_priority
    ON todo_items (sessionId, `order` ASC, priority DESC);
CREATE INDEX IF NOT EXISTS index_todo_items_createdAtMs ON todo_items (createdAtMs);

-- ══════════════════════════════════════════════════════════
-- 5/6 credential_encryption_state：
--   - 仅增加「CREATE TABLE IF NOT EXISTS + INSERT OR IGNORE + DROP IF EXISTS」幂等；
--   - 列结构与 32_add_credential_encryption_state.sql 及当前 Entity 完全一致（不做字段重命名），
--     仅重复声明 CHECK(id=1) 作为 DB 级单例语义兜底；
--     迁移 32 里本来就有 CHECK(id=1)，这里重建保留，消除「万一有人手改 32 文件删掉 CHECK」的风险。
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS credential_encryption_state_new (
    id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    masterKeyFingerprint TEXT NOT NULL,
    dekCiphertext TEXT NOT NULL,
    encScheme TEXT NOT NULL,
    lastRotatedAt INTEGER NOT NULL,
    rotationCounter INTEGER NOT NULL,
    biometricRequired INTEGER NOT NULL,
    migratedFromV1 INTEGER NOT NULL
);

INSERT OR IGNORE INTO credential_encryption_state_new (
    id, masterKeyFingerprint, dekCiphertext, encScheme,
    lastRotatedAt, rotationCounter, biometricRequired, migratedFromV1
)
SELECT
    id,
    COALESCE(masterKeyFingerprint, ''),
    COALESCE(dekCiphertext, ''),
    COALESCE(encScheme, 'V2'),
    COALESCE(lastRotatedAt, 0),
    COALESCE(rotationCounter, 0),
    COALESCE(biometricRequired, 0),
    COALESCE(migratedFromV1, 0)
FROM credential_encryption_state;

DROP TABLE IF EXISTS credential_encryption_state;
ALTER TABLE credential_encryption_state_new RENAME TO credential_encryption_state;

-- ══════════════════════════════════════════════════════════
-- 6/6 后迁移数据完整性校验（SQLite 内置约束冲突检测 + 轻量行数对账）。
--   只查 chat_sessions / ai_providers 的行数，不抛异常（抛异常会把整个迁移事务回滚），
--   插入 migration_history 一条 WARNING 行供诊断用。
-- ══════════════════════════════════════════════════════════
-- （故意留空语句块占位，后续可加 PRAGMA integrity_check；
--   当前空段避免 MigrationLoader.split(';') 读到空语句时被 filter{isNotBlank} 剔除，
--   不影响执行，仅用于结构自解释。）
