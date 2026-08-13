-- RC90 SCHEMA 41：修复 RC74 迁移 40 遗留的 skill_state 表（多余 DEFAULT 与索引）。
--
-- 背景：RC74（v0.1.0-rc89）的 40_add_skill_state.sql 建表时带了
--   · 列 DEFAULT（enabled=1 / version='0.0.0' / source='LOCAL' / installedAtMs=0）
--   · 额外索引 index_skill_state_source
-- 而 @Entity(SkillStateEntity) 既没有 @ColumnInfo(defaultValue) 也没有 @Index，
-- 导致 Room TableInfo 校验失败（Migration didn't properly handle: skill_state）。
--
-- 本迁移把 skill_state 重建为与实体完全一致的表（无 DEFAULT、无索引），
-- 并保留已有数据（enabled 等运行时状态）。对已由 Funnel 兜底重建为正确表的设备，
-- 本迁移等价于无操作（重建后结构一致、数据保留）。
CREATE TABLE IF NOT EXISTS skill_state_new (
    id TEXT NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    installedAtMs INTEGER NOT NULL
);

INSERT INTO skill_state_new (id, enabled, version, source, installedAtMs)
    SELECT id, enabled, version, source, installedAtMs FROM skill_state;

-- RC91：DROP 加 IF EXISTS，防止 skill_state 已被 Funnel 3 破坏性重建删掉时连锁报 no such table。
DROP TABLE IF EXISTS skill_state;

ALTER TABLE skill_state_new RENAME TO skill_state;
