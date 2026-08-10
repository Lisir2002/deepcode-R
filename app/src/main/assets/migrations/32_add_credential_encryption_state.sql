CREATE TABLE IF NOT EXISTS credential_encryption_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    master_key_fingerprint TEXT NOT NULL,
    dek_ciphertext TEXT NOT NULL,
    enc_scheme TEXT NOT NULL DEFAULT 'V2',
    last_rotated_at INTEGER NOT NULL DEFAULT 0,
    rotation_counter INTEGER NOT NULL DEFAULT 0,
    biometric_required INTEGER NOT NULL DEFAULT 0,
    migrated_from_v1 INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS remote_audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category TEXT NOT NULL,
    action TEXT NOT NULL,
    connection_id TEXT,
    connection_name TEXT,
    remote_host TEXT,
    success INTEGER NOT NULL,
    message TEXT,
    source_ip TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_remote_audit_logs_created_at ON remote_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_remote_audit_logs_category ON remote_audit_logs(category);
CREATE INDEX IF NOT EXISTS idx_remote_audit_logs_connection_id ON remote_audit_logs(connection_id);