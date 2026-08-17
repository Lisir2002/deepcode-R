-- 工具系统优化 v46：新增两张表
--   file_edit_hunks（F-3 hunk 落库）：记录 readFile/writeFile/editFile 造成的文件变化快照，
--     为「撤销编辑」等能力提供数据基础。
--   mode_switch_history（G-1 切换历史）：记录 PLAN/BUILD 模式切换的 {from, to, reason, timestamp}，
--     供回溯与 G-3 频率限制判定。
CREATE TABLE IF NOT EXISTS file_edit_hunks (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL DEFAULT '',
    filePath TEXT NOT NULL DEFAULT '',
    operation TEXT NOT NULL DEFAULT '',
    hunk TEXT NOT NULL DEFAULT '',
    oldContent TEXT NOT NULL DEFAULT '',
    newContent TEXT NOT NULL DEFAULT '',
    createdAtMs INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS index_file_edit_hunks_sessionId_filePath ON file_edit_hunks(sessionId, filePath);
CREATE INDEX IF NOT EXISTS index_file_edit_hunks_createdAtMs ON file_edit_hunks(createdAtMs);

CREATE TABLE IF NOT EXISTS mode_switch_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL DEFAULT '',
    fromMode TEXT NOT NULL DEFAULT '',
    toMode TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    timestampMs INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS index_mode_switch_history_sessionId_timestampMs ON mode_switch_history(sessionId, timestampMs);
