-- RC91 SCHEMA 28 修正：session_checkpoints.createdAt → createdAtMs（与 @Entity(CheckpointEntity) 字段名、CheckpointDao 查询对齐）。
--   · 实体字段为 createdAtMs（RC68 统一 Ms 后缀），DAO 的 ORDER BY createdAtMs / WHERE createdAtMs < 都引用 createdAtMs；
--   · 旧迁移建 createdAt 导致：① Room TableInfo 校验失败（列名不匹配）；② 即使校验通过，DAO 查询也会报 no such column: createdAtMs。
-- checkpoint_file_snapshots 的 createdAt 与实体 CheckpointFileSnapshotEntity.createdAt 一致，保持不变。
CREATE TABLE IF NOT EXISTS session_checkpoints (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    userMessageId TEXT NOT NULL,
    promptSnippet TEXT NOT NULL,
    createdAtMs INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_session_checkpoints_sessionId ON session_checkpoints(sessionId);

CREATE TABLE IF NOT EXISTS checkpoint_file_snapshots (
    id TEXT NOT NULL PRIMARY KEY,
    checkpointId TEXT NOT NULL,
    filePath TEXT NOT NULL,
    snapshotRelativePath TEXT NOT NULL,
    changeType TEXT NOT NULL,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_checkpoint_file_snapshots_checkpointId ON checkpoint_file_snapshots(checkpointId);
