#!/usr/bin/env python3
"""Critical test: does migration 38 crash on a pre-RC68 chat_sessions (no workspacePath column)?"""
import sqlite3, os

MIG_DIR = "/workspace/app/src/main/assets/migrations"

def read_migration(v):
    fn = [f for f in os.listdir(MIG_DIR) if f.startswith(f"{v}_")][0]
    with open(f"{MIG_DIR}/{fn}") as f:
        content = f.read()
    return fn, [s.strip() for s in content.split(";") if s.strip()]

def build_pre38_db(path):
    """chat_sessions as it existed pre-RC68: NO workspacePath column."""
    if os.path.exists(path): os.remove(path)
    db = sqlite3.connect(path)
    db.execute("""CREATE TABLE chat_sessions (
        id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL,
        mode TEXT NOT NULL DEFAULT 'BUILD',
        reasoningEffort TEXT NOT NULL DEFAULT 'MEDIUM',
        providerId TEXT, model TEXT,
        totalInputTokens INTEGER NOT NULL DEFAULT 0,
        totalOutputTokens INTEGER NOT NULL DEFAULT 0,
        lastInputTokens INTEGER NOT NULL DEFAULT 0)""")
    db.execute("""CREATE TABLE agent_messages (
        id TEXT NOT NULL PRIMARY KEY,
        sessionId TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        isError INTEGER NOT NULL DEFAULT 0,
        isCompacted INTEGER NOT NULL DEFAULT 0,
        isContextSummary INTEGER NOT NULL DEFAULT 0,
        isCompactionMarker INTEGER NOT NULL DEFAULT 0)""")
    cur = db.cursor()
    for sid, title in [("s1","会话一"),("s2","会话二"),("s3","会话三")]:
        cur.execute("INSERT INTO chat_sessions (id,title,createdAt,updatedAt,mode) VALUES (?,?,?,?,?)",
                    (sid, title, 1, 3 if sid=="s3" else 1, "BUILD"))
    cur.execute("INSERT INTO agent_messages (id,sessionId,role,content,timestamp) VALUES ('m1','s1','user','hello',1)")
    db.execute("PRAGMA user_version=37")
    db.commit(); db.close()
    print(f"[build] pre-38 DB @ {path}")

def main():
    fn, stmts = read_migration(38)
    print(f"migration: {fn}, {len(stmts)} statements")
    build_pre38_db("/workspace/t_pre38.db")
    db = sqlite3.connect("/workspace/t_pre38.db")
    print("--- applying migration 38 ---")
    for i, s in enumerate(stmts):
        try:
            db.execute(s)
        except Exception as e:
            db.rollback()
            print(f"  FAIL statement #{i}: {e}")
            print(f"    SQL: {s[:120]}")
            return
    db.commit()
    n = db.execute("SELECT COUNT(*) FROM chat_sessions").fetchone()[0]
    print(f"  migration 38 applied OK; chat_sessions rows = {n}")
    rows = db.execute("SELECT id, title, workspacePath FROM chat_sessions").fetchall()
    print("  sessions:", rows)

if __name__ == "__main__":
    main()
