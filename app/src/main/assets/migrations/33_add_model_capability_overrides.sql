-- DB-SHIELD Phase A-1: 补回 RC62/RC63 缺失的 v32→v33 迁移
--  作用范围：FileMigration(version=33).startVersion=32, endVersion=33
--  幂等策略：所有 CREATE 全部带 IF NOT EXISTS，任何索引 CREATE INDEX IF NOT EXISTS
--  对应 Entity: ModelCapabilityOverrideEntity
-- RC91 SCHEMA 33 修正：与 @Entity(ModelCapabilityOverrideEntity) 完全对齐——
--   · 主键由单列 id 改为原生复合主键 PRIMARY KEY (providerType, modelId)（RC68 已改实体，迁移从未同步）；
--   · 删除 3 个 idx_* 索引（实体没有 @Index，Room TableInfo 校验会因多余索引失败）。
CREATE TABLE IF NOT EXISTS model_capability_overrides (
    id                  TEXT    NOT NULL,
    providerType        TEXT    NOT NULL,
    modelId             TEXT    NOT NULL,
    overrideVision      INTEGER,        -- Room Boolean? → SQLite INTEGER NULLABLE (1/0/NULL)
    overrideTools       INTEGER,
    overrideReasoning   INTEGER,
    updatedAtMs         INTEGER NOT NULL,
    PRIMARY KEY (providerType, modelId)
);

-- SCHEMA_VERSION 推进到 v33 （Room 在 Migration 结束后自动写 PRAGMA user_version=33）
