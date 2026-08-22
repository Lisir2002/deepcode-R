#!/usr/bin/env python3
"""Full-chain upgrade simulation: build realistic old-version DBs, migrate to 47, check data retention."""
import json, sqlite3, os, sys

MIG_DIR = "/workspace/app/src/main/assets/migrations"
SCHEMA_DIR = "/workspace/app/schemas/com.R.codecore.feature.agent.data.local.database.AgentDatabase"
PH = "${TABLE_NAME}"

def load_schema(version):
    with open(f"{SCHEMA_DIR}/{version}.json") as f:
        return json.load(f)

def build_from_schema(path, version, seed=True):
    """Build a DB exactly as Room onCreate would (v46/v47)."""
    if os.path.exists(path): os.remove(path)
    db = sqlite3.connect(path)
    root = load_schema(version)
    for e in root["database"]["entities"]:
        tn = e["tableName"]
        db.execute(e["createSql"].replace(PH, tn))
        for idx in e.get("indices", []):
            sql = idx.get("createSql", "")
            if sql: db.execute(sql.replace(PH, tn))
    db.execute(f"PRAGMA user_version={version}")
    if seed:
        cur = db.cursor()
        for sess in [("s1","会话一","/data/user/0/com.R.codecore/files/projects/default",1,1),
                     ("s2","会话二","/data/user/0/com.R.codecore/files/projects/default",2,2),
                     ("s3","会话三","/data/user/0/com.R.codecore/files/projects/default",3,3)]:
            cur.execute("INSERT INTO chat_sessions (id,title,workspacePath,createdAtMs,updatedAtMs) VALUES (?,?,?,?,?)", sess)
        for i in range(10):
            cur.execute("INSERT INTO agent_messages (id,sessionId,role,content,timestamp,isError,isCompacted,isContextSummary,isCompactionMarker,inputTokens,outputTokens) VALUES (?,?,?,?,?,0,0,0,0,0,0)",
                        (f"m{i}", "s1", "user" if i%2==0 else "assistant", f"msg {i}", i))
        db.commit()
    db.close()

def downgrade_schema_from46(path):
    """Simulate a v45 DB: 46.json schema minus file_edit_hunks/mode_switch_history, agent_messages w/o taskId."""
    if os.path.exists(path): os.remove(path)
    db = sqlite3.connect(path)
    root = load_schema(46)
    for e in root["database"]["entities"]:
        tn = e["tableName"]
        if tn in ("file_edit_hunks", "mode_switch_history"): continue
        db.execute(e["createSql"].replace(PH, tn))
        for idx in e.get("indices", []):
            sql = idx.get("createSql", "")
            if sql and ("file_edit_hunks" not in sql and "mode_switch_history" not in sql):
                db.execute(sql.replace(PH, tn))
    # drop taskId column from agent_messages -> rebuild table without it
    db.execute("ALTER TABLE agent_messages RENAME TO agent_messages_old")
    db.execute("""CREATE TABLE agent_messages (
        id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, role TEXT NOT NULL,
        content TEXT NOT NULL, timestamp INTEGER NOT NULL, isError INTEGER NOT NULL DEFAULT 0,
        isCompacted INTEGER NOT NULL DEFAULT 0, isContextSummary INTEGER NOT NULL DEFAULT 0,
        isCompactionMarker INTEGER NOT NULL DEFAULT 0, reasoning TEXT, signature TEXT,
        attachmentsJson TEXT, toolCallsJson TEXT, toolCallId TEXT, toolName TEXT, toolArgs TEXT,
        inputTokens INTEGER NOT NULL DEFAULT 0, outputTokens INTEGER NOT NULL DEFAULT 0)""")
    db.execute("INSERT INTO agent_messages SELECT id,sessionId,role,content,timestamp,isError,isCompacted,isContextSummary,isCompactionMarker,reasoning,signature,attachmentsJson,toolCallsJson,toolCallId,toolName,toolArgs,inputTokens,outputTokens FROM agent_messages_old")
    db.execute("DROP TABLE agent_messages_old")
    db.execute("PRAGMA user_version=45")
    cur = db.cursor()
    for sess in [("s1","会话一","/data/user/0/com.R.codecore/files/projects/default",1,1),
                 ("s2","会话二","/data/user/0/com.R.codecore/files/projects/default",2,2)]:
        cur.execute("INSERT INTO chat_sessions (id,title,workspacePath,createdAtMs,updatedAtMs) VALUES (?,?,?,?,?)", sess)
    cur.execute("INSERT INTO agent_messages (id,sessionId,role,content,timestamp) VALUES ('m1','s1','user','hi',1)")
    db.commit(); db.close()

def read_migrations():
    files = sorted(os.listdir(MIG_DIR))
    out = {}
    for fn in files:
        if not fn.endswith(".sql"): continue
        v = int(fn.split("_")[0])
        with open(f"{MIG_DIR}/{fn}") as f:
            content = f.read()
        out[v] = [s.strip() for s in content.split(";") if s.strip()]
    return out

def apply_migration(db, version, stmts):
    label = f"{version}.sql"
    try:
        for s in stmts:
            db.execute(s)
        db.commit()
        print(f"  OK  v{version} ({label})")
        return True
    except Exception as e:
        db.rollback()
        print(f"  FAIL v{version} ({label}): {e}")
        return False

def verify(path, version):
    """Compare DB schema against schema JSON (cols/types/notnull/pk) + count chat_sessions."""
    db = sqlite3.connect(path)
    root = load_schema(version)
    problems = []
    for ent in root["database"]["entities"]:
        tn = ent["tableName"]
        rows = db.execute(f"PRAGMA table_info(`{tn}`)").fetchall()
        actual = {r[1]: {"type":(r[2] or "").upper(), "notnull":bool(r[3]), "pk":r[5]} for r in rows}
        pkcols = list((ent.get("primaryKey") or {}).get("columnNames", []))
        exp = {fl["columnName"]: {"type":fl.get("affinity","").upper(), "notnull":fl.get("notNull",False),
                "pk": 1 if fl["columnName"] in pkcols else 0} for fl in ent["fields"]}
        if set(actual) != set(exp):
            problems.append(f"[{tn}] cols mismatch actual={sorted(actual)} expected={sorted(exp)}")
        else:
            for c in exp:
                if actual[c]["type"] != exp[c]["type"]: problems.append(f"[{tn}].{c} type")
                if actual[c]["notnull"] != exp[c]["notnull"]: problems.append(f"[{tn}].{c} notnull")
                if actual[c]["pk"] != exp[c]["pk"]: problems.append(f"[{tn}].{c} pk")
    nsess = db.execute("SELECT COUNT(*) FROM chat_sessions").fetchone()[0]
    nmsg = db.execute("SELECT COUNT(*) FROM agent_messages").fetchone()[0]
    db.close()
    return problems, nsess, nmsg

def main():
    migs = read_migrations()
    print("versions:", sorted(migs.keys()))

    # Scenario A: realistic 46 -> 47
    print("\n===== A: 46 -> 47 =====")
    build_from_schema("/workspace/t_a46.db", 46)
    db = sqlite3.connect("/workspace/t_a46.db")
    ok = apply_migration(db, 47, migs[47])
    if ok:
        db.execute("PRAGMA user_version=47"); db.commit()
        p, s, m = verify("/workspace/t_a46.db", 47)
        print(f"  verify problems: {p if p else 'NONE'}; sessions={s} msgs={m}")
    db.close()

    # Scenario B: realistic 45 -> 46 -> 47 (v45 lacks the two new tables + taskId)
    print("\n===== B: 45 -> 46 -> 47 =====")
    downgrade_schema_from46("/workspace/t_b45.db")
    db = sqlite3.connect("/workspace/t_b45.db")
    ok = all(apply_migration(db, v, migs[v]) for v in (46, 47))
    if ok:
        db.execute("PRAGMA user_version=47"); db.commit()
        p, s, m = verify("/workspace/t_b45.db", 47)
        print(f"  verify problems: {p if p else 'NONE'}; sessions={s} msgs={m}")
    db.close()

    # Scenario C: simulate what a REAL device DB might look like (built by v46 chain with data) -> 47
    #  Also test: session_checkpoints with createdAt column (legacy) -> RobustMigration44 simulation is
    #  NOT in SQL; here just confirm the SQL chain 44->45->46->47 works with legacy table present.
    print("\n===== C: chain 44->45->46->47 (SQL only, RobustMigration44 separate) =====")
    # v44 = v46 schema minus the two new tables, agent_messages w/o taskId (same as v45 build but user_version=44)
    downgrade_schema_from46("/workspace/t_c44.db")
    db = sqlite3.connect("/workspace/t_c44.db")
    db.execute("PRAGMA user_version=44"); db.commit()
    ok = all(apply_migration(db, v, migs[v]) for v in (45, 46, 47))
    if ok:
        db.execute("PRAGMA user_version=47"); db.commit()
        p, s, m = verify("/workspace/t_c44.db", 47)
        print(f"  verify problems: {p if p else 'NONE'}; sessions={s} msgs={m}")
    db.close()

if __name__ == "__main__":
    main()
