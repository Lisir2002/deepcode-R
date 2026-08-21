-- 技能作用域 v2 SCHEMA 47：
--   1) skill_state 新增 scope_override / agent_type_override（用户对作用域的覆盖，NULL=跟随 frontmatter 声明）。
--   2) 新增 skill_conversation_state 表：记录技能在特定对话中的启用/禁用状态（对话级双向控制）。
--
-- 注意：列定义与 Room @Entity 导出 schema 完全一致（含 NOT NULL、无 DEFAULT 的字段不写 DEFAULT），
-- 否则 MigrationSchemaConsistencyTest 闸门会 fail（Room TableInfo 校验失败 → 触发 Funnel 抢救）。
--
-- skill_state 采用 _new 四步法重建（参考迁移 41）：Room 不允许直接 ALTER 加列后让 CI 测试
-- 的「最终建表」判定通过（41 已用 _new 重建，测试以最后一次 _new 建表为最终结构）。
-- INSERT SELECT 只引用旧表既有列，新增的 scope_override / agent_type_override 在 _new 表默认为 NULL，
-- 保证从任意旧版本升级时 SELECT 不引用尚不存在的列。
CREATE TABLE IF NOT EXISTS skill_state_new (
    id TEXT NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    installedAtMs INTEGER NOT NULL,
    scope_override TEXT,
    agent_type_override TEXT
);
INSERT INTO skill_state_new (id, enabled, version, source, installedAtMs)
    SELECT id, enabled, version, source, installedAtMs FROM skill_state;
DROP TABLE IF EXISTS skill_state;
ALTER TABLE skill_state_new RENAME TO skill_state;
CREATE TABLE IF NOT EXISTS skill_conversation_state (
    skill_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    PRIMARY KEY(skill_id, session_id)
);
