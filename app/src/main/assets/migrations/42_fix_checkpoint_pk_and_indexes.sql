-- RC91 SCHEMA 42：修复 RC68/RC63 遗留的三张表 schema 与实体不一致。
-- 采用「CREATE NEW → INSERT OR IGNORE 回拷 → DROP IF EXISTS → RENAME」幂等四步法（与 38/41 同款）。
--
-- 1/3 session_checkpoints：旧迁移 28 建了 createdAt 列，实体/DAO 用 createdAtMs
--     → 重建为 createdAtMs 并保留数据（createdAt → createdAtMs）。
-- 2/3 model_capability_overrides：旧迁移 33 用单主键 id + 3 个 idx_* 索引，
--     实体是复合主键 (providerType, modelId) 且无索引
--     → 重建为复合主键、删除多余索引、保留数据（INSERT OR IGNORE 按新主键去重）。
-- 3/3 zth_hallucination_fuses：旧迁移 35 用单主键 id + UNIQUE(scope,scopeId) 索引，
--     实体是复合主键 (scope, scopeId) 且只有 state/updatedAtMs 索引
--     → 重建为复合主键、删除 UNIQUE 索引、保留数据（INSERT OR IGNORE 按新主键去重）。

-- ══════════════════════════════════════════════════════════
-- 1/3 session_checkpoints
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS session_checkpoints_new (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    userMessageId TEXT NOT NULL,
    promptSnippet TEXT NOT NULL,
    createdAtMs INTEGER NOT NULL
);

INSERT OR IGNORE INTO session_checkpoints_new (id, sessionId, userMessageId, promptSnippet, createdAtMs)
    SELECT id, sessionId, userMessageId, promptSnippet, createdAt FROM session_checkpoints;

DROP TABLE IF EXISTS session_checkpoints;
ALTER TABLE session_checkpoints_new RENAME TO session_checkpoints;

CREATE INDEX IF NOT EXISTS index_session_checkpoints_sessionId ON session_checkpoints (sessionId);

-- ══════════════════════════════════════════════════════════
-- 2/3 model_capability_overrides
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS model_capability_overrides_new (
    id TEXT NOT NULL,
    providerType TEXT NOT NULL,
    modelId TEXT NOT NULL,
    overrideVision INTEGER,
    overrideTools INTEGER,
    overrideReasoning INTEGER,
    updatedAtMs INTEGER NOT NULL,
    PRIMARY KEY (providerType, modelId)
);

INSERT OR IGNORE INTO model_capability_overrides_new (id, providerType, modelId, overrideVision, overrideTools, overrideReasoning, updatedAtMs)
    SELECT id, providerType, modelId, overrideVision, overrideTools, overrideReasoning, updatedAtMs FROM model_capability_overrides;

DROP TABLE IF EXISTS model_capability_overrides;
ALTER TABLE model_capability_overrides_new RENAME TO model_capability_overrides;

-- ══════════════════════════════════════════════════════════
-- 3/3 zth_hallucination_fuses
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS zth_hallucination_fuses_new (
    id TEXT NOT NULL,
    scope TEXT NOT NULL,
    scopeId TEXT NOT NULL,
    state TEXT NOT NULL,
    linkageVersion INTEGER NOT NULL,
    failureCount INTEGER NOT NULL,
    openSinceMs INTEGER NOT NULL,
    lastProbeAtMs INTEGER NOT NULL,
    killSwitch1Triggered INTEGER NOT NULL,
    killSwitch2SoftDisabled INTEGER NOT NULL,
    lastTripSubclass TEXT,
    updatedAtMs INTEGER NOT NULL,
    PRIMARY KEY (scope, scopeId)
);

INSERT OR IGNORE INTO zth_hallucination_fuses_new (id, scope, scopeId, state, linkageVersion, failureCount, openSinceMs, lastProbeAtMs, killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs)
    SELECT id, scope, scopeId, state, linkageVersion, failureCount, openSinceMs, lastProbeAtMs, killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs FROM zth_hallucination_fuses;

DROP TABLE IF EXISTS zth_hallucination_fuses;
ALTER TABLE zth_hallucination_fuses_new RENAME TO zth_hallucination_fuses;

CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_state ON zth_hallucination_fuses (state);
CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_updatedAtMs ON zth_hallucination_fuses (updatedAtMs);
