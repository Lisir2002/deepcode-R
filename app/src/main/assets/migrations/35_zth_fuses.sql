-- ZTH v1.0 迁移 35：zth_hallucination_fuses（熔断状态机：CLOSED/HALF_OPEN/OPEN/TRANSITIONING）
-- scope+scopeId 组合唯一索引；state 单独索引用于横幅过滤
-- RC91 SCHEMA 35 修正：与 @Entity(HallucinationFuseEntity) 完全对齐——
--   · 主键由单列 id 改为原生复合主键 PRIMARY KEY (scope, scopeId)（RC68 已改实体，迁移从未同步）；
--   · 删除 UNIQUE index_zth_hallucination_fuses_scope_scopeId（复合主键已保证唯一，实体没有该 @Index，
--     Room TableInfo 校验会因多余索引失败）；
--   · 保留 state / updatedAtMs 两个索引（实体有对应 @Index）。

CREATE TABLE IF NOT EXISTS zth_hallucination_fuses (
    id TEXT NOT NULL,
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
    updatedAtMs INTEGER NOT NULL,
    PRIMARY KEY (scope, scopeId)
);

CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_state ON zth_hallucination_fuses(state);
CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_updatedAtMs ON zth_hallucination_fuses(updatedAtMs);
