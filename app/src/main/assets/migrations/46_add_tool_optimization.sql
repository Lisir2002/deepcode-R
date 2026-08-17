-- 工具系统优化 v46：新增两张表
--   file_edit_hunks（F-3 hunk 落库）：记录 readFile/writeFile/editFile 造成的文件变化快照，
--     为「撤销编辑」等能力提供数据基础。
--   mode_switch_history（G-1 切换历史）：记录 PLAN/BUILD 模式切换的 {from, to, reason, timestamp}，
--     供回溯与 G-3 频率限制判定。
--
-- 注意：列定义与 Room @Entity 导出 schema 完全一致（含 NOT NULL、无 DEFAULT 的字段不写 DEFAULT），
-- 否则 MigrationSchemaConsistencyTest 闸门会 fail（Room TableInfo 校验失败 → 触发 Funnel 抢救）。
CREATE TABLE IF NOT EXISTS file_edit_hunks (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    filePath TEXT NOT NULL,
    operation TEXT NOT NULL,
    hunk TEXT NOT NULL,
    oldContent TEXT NOT NULL,
    newContent TEXT NOT NULL,
    createdAtMs INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_file_edit_hunks_sessionId_filePath ON file_edit_hunks(sessionId, filePath);
CREATE INDEX IF NOT EXISTS index_file_edit_hunks_createdAtMs ON file_edit_hunks(createdAtMs);

CREATE TABLE IF NOT EXISTS mode_switch_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    fromMode TEXT NOT NULL,
    toMode TEXT NOT NULL,
    reason TEXT NOT NULL,
    timestampMs INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_mode_switch_history_sessionId_timestampMs ON mode_switch_history(sessionId, timestampMs);
