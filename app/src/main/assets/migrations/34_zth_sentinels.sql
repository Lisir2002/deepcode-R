-- ZTH v1.0 迁移 34：zth_user_confirmed_sentinels（ZTH-0 铁律主表）
-- 列名 camelCase；无 SQL DEFAULT（Entity Kotlin 字段默认值由 Room INSERT 填，避免 Room 2.7 TableInfo.dflt_value 校验不一致）
-- 索引命名与迁移 32 一致：index_{table}_{camelCaseColumn}

CREATE TABLE IF NOT EXISTS zth_user_confirmed_sentinels (
    id TEXT PRIMARY KEY NOT NULL,
    sessionId TEXT NOT NULL,
    linkageVersion INTEGER NOT NULL,
    chainId TEXT NOT NULL,
    chainIndex INTEGER NOT NULL,
    cardTemplateId TEXT NOT NULL,
    triggerSubClass TEXT NOT NULL,
    s_planPayloadCiphertext TEXT NOT NULL,
    s_userTextCiphertext TEXT,
    s_cardPayloadCiphertext TEXT NOT NULL,
    userChoice TEXT NOT NULL,
    swipeVerified INTEGER NOT NULL,
    s_modifiedPlanCiphertext TEXT,
    expireAtMs INTEGER NOT NULL,
    rollbackFlag INTEGER NOT NULL,
    createdAtMs INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_zth_user_confirmed_sentinels_sessionId ON zth_user_confirmed_sentinels(sessionId);
CREATE INDEX IF NOT EXISTS index_zth_user_confirmed_sentinels_chainId ON zth_user_confirmed_sentinels(chainId);
CREATE INDEX IF NOT EXISTS index_zth_user_confirmed_sentinels_createdAtMs ON zth_user_confirmed_sentinels(createdAtMs);
