-- RC93 SCHEMA 43：修复两张 AUTOINCREMENT 主键表缺 NOT NULL 的遗留隐患。
-- 背景：迁移 32（remote_audit_logs）与迁移 37（zth_telemetry_events）建表时写
--   `id INTEGER PRIMARY KEY AUTOINCREMENT`（无 NOT NULL），
-- 而 Room 对 @PrimaryKey(autoGenerate=true) 的 Long 主键期望 notNull=true
-- （Room 生成的 CREATE TABLE 为 `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`）。
-- 已部署设备（RC61/RC74 起）上这两张表缺 NOT NULL → Room TableInfo 校验失败
-- （Migration didn't properly handle: xxx），触发 Funnel 2/3 抢救甚至删表丢数据。
--
-- 修复：采用「CREATE NEW → INSERT OR IGNORE 回拷 → DROP IF EXISTS → RENAME」幂等四步法
-- （与 38/41/42 同款），重建为带 NOT NULL 的表并保留数据。
-- 所有语句保持 IF NOT EXISTS / INSERT OR IGNORE / DROP IF EXISTS，防止：
--   ① 迁移中途被 SIGKILL → 重跑时不崩到 Funnel 2；
--   ② 新安装用户 onCreate 已拿 RC93 新 schema → 迁移里 CREATE TABLE IF NOT EXISTS 不报错。

-- ══════════════════════════════════════════════════════════
-- 1/2 remote_audit_logs
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS remote_audit_logs_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    category TEXT NOT NULL,
    action TEXT NOT NULL,
    connectionId TEXT,
    connectionName TEXT,
    remoteHost TEXT,
    success INTEGER NOT NULL,
    message TEXT,
    sourceIp TEXT,
    createdAt INTEGER NOT NULL
);

INSERT OR IGNORE INTO remote_audit_logs_new (id, category, action, connectionId, connectionName, remoteHost, success, message, sourceIp, createdAt)
    SELECT id, category, action, connectionId, connectionName, remoteHost, success, message, sourceIp, createdAt FROM remote_audit_logs;

DROP TABLE IF EXISTS remote_audit_logs;
ALTER TABLE remote_audit_logs_new RENAME TO remote_audit_logs;

CREATE INDEX IF NOT EXISTS index_remote_audit_logs_createdAt ON remote_audit_logs(createdAt);
CREATE INDEX IF NOT EXISTS index_remote_audit_logs_category ON remote_audit_logs(category);
CREATE INDEX IF NOT EXISTS index_remote_audit_logs_connectionId ON remote_audit_logs(connectionId);

-- ══════════════════════════════════════════════════════════
-- 2/2 zth_telemetry_events
-- ══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS zth_telemetry_events_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    eventKind TEXT NOT NULL,
    eventSubKind TEXT NOT NULL,
    severityTier INTEGER NOT NULL,
    sessionSha256Prefix TEXT,
    latencyMs INTEGER,
    flagA INTEGER,
    flagB INTEGER,
    metricA INTEGER,
    metricB INTEGER,
    createdAtMs INTEGER NOT NULL
);

INSERT OR IGNORE INTO zth_telemetry_events_new (id, eventKind, eventSubKind, severityTier, sessionSha256Prefix, latencyMs, flagA, flagB, metricA, metricB, createdAtMs)
    SELECT id, eventKind, eventSubKind, severityTier, sessionSha256Prefix, latencyMs, flagA, flagB, metricA, metricB, createdAtMs FROM zth_telemetry_events;

DROP TABLE IF EXISTS zth_telemetry_events;
ALTER TABLE zth_telemetry_events_new RENAME TO zth_telemetry_events;

CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_eventKind_eventSubKind ON zth_telemetry_events(eventKind, eventSubKind);
CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_createdAtMs ON zth_telemetry_events(createdAtMs);
CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_severityTier ON zth_telemetry_events(severityTier);
