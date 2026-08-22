#!/usr/bin/env python3
"""Simulate the Room migration chain against real SQLite to find failing migrations."""
import json, sqlite3, os, re, sys

MIG_DIR = "/workspace/app/src/main/assets/migrations"
SCHEMA_DIR = "/workspace/app/schemas/com.R.codecore.feature.agent.data.local.database.AgentDatabase"
PH = "${TABLE_NAME}"

def load_schema_entities(version):
    with open(f"{SCHEMA_DIR}/{version}.json") as f:
        root = json.load(f)
    ents = []
    for e in root["database"]["entities"]:
        d = {"tableName": e["tableName"], "createSql": e.get("createSql",""),
             "indices": [i.get("createSql","") for i in e.get("indices",[]) if i.get("createSql")]}
        ents.append(d)
    return ents

def build_db(path, version, seed_data=None):
    if os.path.exists(path): os.remove(path)
    db = sqlite3.connect(path)
    for e in load_schema_entities(version):
        sql = e["createSql"].replace(PH, e["tableName"])
        db.execute(sql)
        for idx in e["indices"]:
            db.execute(idx.replace(PH, e["tableName"]))
    db.execute(f"PRAGMA user_version={version}")
    if seed_data:
        cur = db.cursor()
        for sess in seed_data:
            cur.execute("INSERT INTO chat_sessions (id, title, workspacePath, createdAtMs, updatedAtMs) VALUES (?,?,?,?,?)", sess)
        cur.execute("INSERT INTO agent_messages (id, sessionId, taskId, role, content, timestamp, isError, isCompacted, isContextSummary, isCompactionMarker, inputTokens, outputTokens) VALUES (?,?,?,?,?,?,0,0,0,0,0,0)", ("m1","s1","","user","hello",1))
        db.commit()
    db.close()
    print(f"[build] v{version} DB @ {path}")

def read_migrations():
    files = sorted(os.listdir(MIG_DIR))
    out = {}
    for fn in files:
        if not fn.endswith(".sql"): continue
        v = int(fn.split("_")[0])
        with open(f"{MIG_DIR}/{fn}") as f:
            content = f.read()
        stmts = [s.strip() for s in content.split(";") if s.strip()]
        out[v] = (fn, stmts)
    return out

def apply_migration(db, version, stmts, label):
    print(f"--- applying migration v{version} ({label}) ---")
    try:
        for s in stmts:
            db.execute(s)
        db.commit()
        print(f"  OK: {len(stmts)} statements")
        return True
    except Exception as e:
        db.rollback()
        print(f"  FAIL: {e}")
        return False

def verify_schema(db, version):
    problems = []
    with open(f"{SCHEMA_DIR}/{version}.json") as f:
        root = json.load(f)
    for ent in root["database"]["entities"]:
        tn = ent["tableName"]
        rows = db.execute(f"PRAGMA table_info(`{tn}`)").fetchall()
        actual = {r[1]: {"type": (r[2] or "").upper(), "notnull": bool(r[3]), "pk": r[5]} for r in rows}
        exp = {}
        for fl in ent["fields"]:
            pkcols = [c for c in (ent.get("primaryKey") or {}).get("columnNames",[])]
            exp[fl["columnName"]] = {"type": fl.get("affinity","").upper(),
                                     "notnull": fl.get("notNull",False),
                                     "pk": 1 if fl["columnName"] in pkcols else 0}
        if set(actual.keys()) != set(exp.keys()):
            problems.append(f"[{tn}] cols mismatch actual={sorted(actual)} expected={sorted(exp)}")
            continue
        for cname in exp:
            if actual[cname]["type"] != exp[cname]["type"]:
                problems.append(f"[{tn}].{cname} type actual={actual[cname]['type']} expected={exp[cname]['type']}")
            if actual[cname]["notnull"] != exp[cname]["notnull"]:
                problems.append(f"[{tn}].{cname} notnull actual={actual[cname]['notnull']} expected={exp[cname]['notnull']}")
    return problems

def main():
    migs = read_migrations()
    print("migration versions:", sorted(migs.keys()))

    # ---- Scenario 1: upgrade 46 -> 47 (most common real update) ----
    print("\n===== Scenario 1: 46 -> 47 upgrade =====")
    db_path = "/workspace/sim_v46.db"
    build_db(db_path, 46, seed_data=[("s1","会话一","/data/user/0/com.R.codecore/files/projects/default",1,1),
                                      ("s2","会话二","/data/user/0/com.R.codecore/files/projects/default",2,2)])
    db = sqlite3.connect(db_path)
    ok = apply_migration(db, 47, migs[47][1], migs[47][0])
    if ok:
        db.execute("PRAGMA user_version=47"); db.commit()
        probs = verify_schema(db, 47)
        print("  verify v47 problems:", probs if probs else "NONE")
        n = db.execute("SELECT COUNT(*) FROM chat_sessions").fetchone()[0]
        print(f"  chat_sessions rows after migration: {n}")
    db.close()

    # ---- Scenario 2: 44 -> 47 (RobustMigration44 not needed; 44->45->46->47) ----
    print("\n===== Scenario 2: 44 -> 47 upgrade =====")
    db_path = "/workspace/sim_v44.db"
    build_db(db_path, 44, seed_data=[("s1","会话一","/data/user/0/com.R.codecore/files/projects/default",1,1)])
    db = sqlite3.connect(db_path)
    allok = True
    for v in (45,46,47):
        ok = apply_migration(db, v, migs[v][1], migs[v][0])
        allok = allok and ok
        if ok:
            db.execute(f"PRAGMA user_version={v}"); db.commit()
    if allok:
        probs = verify_schema(db, 47)
        print("  verify v47 problems:", probs if probs else "NONE")
        n = db.execute("SELECT COUNT(*) FROM chat_sessions").fetchone()[0]
        print(f"  chat_sessions rows after migration: {n}")
    db.close()

    # ---- Scenario 3: 43 -> 47 including RobustMigration44 ----
    print("\n===== Scenario 3: 43 -> 47 upgrade (with RobustMigration44) =====")
    db_path = "/workspace/sim_v43.db"
    build_db(db_path, 43, seed_data=[("s1","会话一","/data/user/0/com.R.codecore/files/projects/default",1,1)])
    db = sqlite3.connect(db_path)
    # RobustMigration44 logic is in Kotlin; simulate the 5 fixes via the 42/43 sql? 
    # Instead: apply 44 as no-op (we only test the SQL chain here); the programmatic migration is separate.
    print("  (RobustMigration44 is programmatic Kotlin; not simulated in SQL)")
    allok = True
    for v in (45,46,47):
        ok = apply_migration(db, v, migs[v][1], migs[v][0])
        allok = allok and ok
        if ok:
            db.execute(f"PRAGMA user_version={v}"); db.commit()
    if allok:
        probs = verify_schema(db, 47)
        print("  verify v47 problems:", probs if probs else "NONE")
    db.close()

if __name__ == "__main__":
    main()
