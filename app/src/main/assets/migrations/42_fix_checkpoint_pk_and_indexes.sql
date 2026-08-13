-- RC94 SCHEMA 42 修正：session_checkpoints 段改为「幂等 no-op」。
-- 背景：RC91 起迁移 28 已改为建 createdAtMs 列（与实体/DAO 对齐），但本迁移 42 仍固定引用
--   createdAt 列 → 新 schema 设备（RC91+ 全新安装）升级时 SQLite 在 prepare 阶段报
--   no such column: createdAt → Funnel 1 失败 → 连锁触发 Funnel 2/3/4 全崩（线上 CRASH）。
-- 修复：session_checkpoints 的重建（createdAt → createdAtMs）改由程序化迁移 RobustMigration44
--   （v43→v44）用 PRAGMA table_info 探测实际列名后幂等处理；此处仅保留索引创建（两 schema 通用）。
-- 注意：model_capability_overrides / zth_hallucination_fuses 两段不受列名影响，继续保留。

-- ══════════════════════════════════════════════════════════
-- 1/3 session_checkpoints（no-op：仅确保索引存在，重建逻辑见 RobustMigration44 v43→v44）
-- ══════════════════════════════════════════════════════════
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
