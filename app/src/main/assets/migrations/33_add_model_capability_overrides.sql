-- DB-SHIELD Phase A-1: 补回 RC62/RC63 缺失的 v32→v33 迁移
--  作用范围：FileMigration(version=33).startVersion=32, endVersion=33
--  幂等策略：所有 CREATE 全部带 IF NOT EXISTS，任何索引 CREATE INDEX IF NOT EXISTS
--  对应 Entity: ModelCapabilityOverrideEntity
CREATE TABLE IF NOT EXISTS model_capability_overrides (
    id                  TEXT    NOT NULL PRIMARY KEY,
    providerType        TEXT    NOT NULL,
    modelId             TEXT    NOT NULL,
    overrideVision      INTEGER,        -- Room Boolean? → SQLite INTEGER NULLABLE (1/0/NULL)
    overrideTools       INTEGER,
    overrideReasoning   INTEGER,
    updatedAtMs         INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_model_capability_overrides_provider_type
    ON model_capability_overrides(providerType);

CREATE INDEX IF NOT EXISTS idx_model_capability_overrides_model_id
    ON model_capability_overrides(modelId);

CREATE INDEX IF NOT EXISTS idx_model_capability_overrides_updated_at_ms
    ON model_capability_overrides(updatedAtMs);

-- SCHEMA_VERSION 推进到 v33 （Room 在 Migration 结束后自动写 PRAGMA user_version=33）
