-- RC61 迁移 32（v0.1.0-rc61b 修正版）：
-- 列名严格遵循全项目约定 = camelCase（与迁移 8/9/28 一致），与 Entity 字段逐字吻合。
-- 不写 SQL DEFAULT：Kotlin 字段默认值（encScheme="V2"、lastRotatedAt=0、…）由 Room 在 INSERT 时填列，
--   避免与 Room TableInfo.dflt_value 校验不一致（Room 2.7 会严格比较 PRAGMA table_info 的 dflt_value）。
-- 索引命名与 Room 自动生成保持一致：index_{table}_{camelCaseColumn}。

-- 单行配置表：MasterKey 指纹 + wrap 后的 DEK + 轮换/生物识别标志
CREATE TABLE IF NOT EXISTS credential_encryption_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    masterKeyFingerprint TEXT NOT NULL,
    dekCiphertext TEXT NOT NULL,
    encScheme TEXT NOT NULL,
    lastRotatedAt INTEGER NOT NULL,
    rotationCounter INTEGER NOT NULL,
    biometricRequired INTEGER NOT NULL,
    migratedFromV1 INTEGER NOT NULL
);

-- SSH 连接 / 凭据操作 / 备份导入导出 / SFTP 审计日志
-- RC93 修复：id 补 NOT NULL。Room 对 autoGenerate 的 INTEGER 主键期望 notNull=true
-- （Room 生成的 CREATE TABLE 为 `id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`），
-- 缺 NOT NULL 会导致 TableInfo 校验失败（Migration didn't properly handle: remote_audit_logs）。
CREATE TABLE IF NOT EXISTS remote_audit_logs (
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

-- 与 Entity indices = [Index("createdAt"), Index("category"), Index("connectionId")] 完全对齐
CREATE INDEX IF NOT EXISTS index_remote_audit_logs_createdAt ON remote_audit_logs(createdAt);
CREATE INDEX IF NOT EXISTS index_remote_audit_logs_category ON remote_audit_logs(category);
CREATE INDEX IF NOT EXISTS index_remote_audit_logs_connectionId ON remote_audit_logs(connectionId);
