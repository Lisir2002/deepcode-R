-- ZTH v1.0 迁移 36：3 张 LINK-INV 审计表 + L0 还原日志
-- (1) sentinel_plan_rejection_audits (2) hard_constraint_delete_audits (3) l0_soft_compact_restore_logs

CREATE TABLE IF NOT EXISTS zth_sentinel_plan_rejection_audits (
    id TEXT PRIMARY KEY NOT NULL,
    sentinelId TEXT NOT NULL,
    rejectionType TEXT NOT NULL,
    s_reasonCiphertext TEXT,
    s_rejectedPlanSnapshotCiphertext TEXT NOT NULL,
    createdAtMs INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_zth_sentinel_plan_rejection_audits_sentinelId ON zth_sentinel_plan_rejection_audits(sentinelId);
CREATE INDEX IF NOT EXISTS index_zth_sentinel_plan_rejection_audits_createdAtMs ON zth_sentinel_plan_rejection_audits(createdAtMs);

CREATE TABLE IF NOT EXISTS zth_hard_constraint_delete_audits (
    id TEXT PRIMARY KEY NOT NULL,
    sessionId TEXT NOT NULL,
    affectedTableName TEXT NOT NULL,
    s_affectedKeysCiphertext TEXT NOT NULL,
    triggerSubClass TEXT NOT NULL,
    rollbackApplied INTEGER NOT NULL,
    createdAtMs INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_zth_hard_constraint_delete_audits_sessionId ON zth_hard_constraint_delete_audits(sessionId);
CREATE INDEX IF NOT EXISTS index_zth_hard_constraint_delete_audits_affectedTableName ON zth_hard_constraint_delete_audits(affectedTableName);
CREATE INDEX IF NOT EXISTS index_zth_hard_constraint_delete_audits_createdAtMs ON zth_hard_constraint_delete_audits(createdAtMs);

CREATE TABLE IF NOT EXISTS zth_l0_soft_compact_restore_logs (
    id TEXT PRIMARY KEY NOT NULL,
    sessionId TEXT NOT NULL,
    firstMessageId TEXT NOT NULL,
    lastMessageId TEXT NOT NULL,
    originalRowCount INTEGER NOT NULL,
    tokensBefore INTEGER NOT NULL,
    tokensAfter INTEGER NOT NULL,
    s_compactSourceDigestCiphertext TEXT NOT NULL,
    expireAtMs INTEGER NOT NULL,
    restoredFlag INTEGER NOT NULL,
    createdAtMs INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_zth_l0_soft_compact_restore_logs_sessionId ON zth_l0_soft_compact_restore_logs(sessionId);
CREATE INDEX IF NOT EXISTS index_zth_l0_soft_compact_restore_logs_expireAtMs ON zth_l0_soft_compact_restore_logs(expireAtMs);
CREATE INDEX IF NOT EXISTS index_zth_l0_soft_compact_restore_logs_createdAtMs ON zth_l0_soft_compact_restore_logs(createdAtMs);
