-- ZTH v1.0 迁移 37：zth_telemetry_events（方案 B 自绘 Canvas 4 图表）
-- 主键 = Long AUTOINCREMENT；eventKind/eventSubKind 二级索引；BURIED-INV-1：永远不存在用户明文列

CREATE TABLE IF NOT EXISTS zth_telemetry_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
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

CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_eventKind_eventSubKind ON zth_telemetry_events(eventKind, eventSubKind);
CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_createdAtMs ON zth_telemetry_events(createdAtMs);
CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_severityTier ON zth_telemetry_events(severityTier);
