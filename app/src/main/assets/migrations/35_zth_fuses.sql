-- ZTH v1.0 迁移 35：zth_hallucination_fuses（熔断状态机：CLOSED/HALF_OPEN/OPEN/TRANSITIONING）
-- scope+scopeId 组合唯一索引；state 单独索引用于横幅过滤

CREATE TABLE IF NOT EXISTS zth_hallucination_fuses (
    id TEXT PRIMARY KEY NOT NULL,
    scope TEXT NOT NULL,
    scopeId TEXT NOT NULL,
    state TEXT NOT NULL,
    linkageVersion INTEGER NOT NULL,
    failureCount INTEGER NOT NULL,
    openSinceMs INTEGER NOT NULL,
    lastProbeAtMs INTEGER NOT NULL,
    killSwitch1Triggered INTEGER NOT NULL,
    killSwitch2SoftDisabled INTEGER NOT NULL,
    lastTripSubclass TEXT,
    updatedAtMs INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS index_zth_hallucination_fuses_scope_scopeId ON zth_hallucination_fuses(scope, scopeId);
CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_state ON zth_hallucination_fuses(state);
CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_updatedAtMs ON zth_hallucination_fuses(updatedAtMs);
