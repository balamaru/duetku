"""Source-level checks only; these are not Android build or device-test evidence."""
import pathlib
import re
import sqlite3
import uuid
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
for file in (ROOT / "android").rglob("*.xml"):
    if "build" in file.parts or ".gradle" in file.parts:
        continue  # Generated build outputs are checked by lint and verify-apk.py.
    tree = ET.parse(file)
    if file.name == "AndroidManifest.xml":
        assert all(p.get(ANDROID + "name") in ("android.permission.USE_BIOMETRIC", "android.permission.INTERNET") for p in tree.findall("uses-permission")), file
        app = tree.find("application")
        assert app.get(ANDROID + "allowBackup") == "false", file

source = (ROOT / "android/app/src/main/java/id/kelolauangku/app/data/Database.kt").read_text(encoding="utf-8")
source = source.split("override fun onUpgrade")[0]
statements = re.findall(r'db\.execSQL\("""(.*?)"""\)', source, re.S)
statements += re.findall(r'db\.execSQL\("([^"\n]+)"\)', source)
with sqlite3.connect(":memory:") as db:
    db.execute("PRAGMA foreign_keys=ON")
    for sql in statements:
        db.execute(sql)
    db.execute("INSERT INTO categories VALUES(1,'Uji','income',1,0)")
    values = ("00000000-0000-4000-8000-000000000001", 1, "income", 100, "2026-10-16", "", "now", "now")
    db.execute("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?)", values)
    for sql, parameters in [
        ("DELETE FROM categories WHERE id=?", (1,)),
        ("UPDATE transactions SET amount_minor=?", (0,)),
        ("UPDATE transactions SET category_id=?", (999,)),
    ]:
        try:
            db.execute(sql, parameters)
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError("Expected constraint failure")
    db.commit()
    db.execute("BEGIN")
    db.execute("DELETE FROM transactions")
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0] == 1
print("PASS: XML, source manifests, SQLite DDL constraints and rollback (host SQLite only).")

# Exercise the actual schema DDL from onUpgrade. UUID generation here mirrors
# java.util.UUID.randomUUID; Android lifecycle/Keystore still require V2Test.
full_source = (ROOT / "android/app/src/main/java/id/kelolauangku/app/data/Database.kt").read_text(encoding="utf-8")
upgrade = full_source.split("override fun onUpgrade")[1]
new_table = re.search(r'db\.execSQL\("""(CREATE TABLE transactions.*?)"""\)', upgrade, re.S).group(1)
legacy = """CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,
category_id INTEGER REFERENCES categories(id),type TEXT,amount_minor INTEGER,
transaction_date TEXT,note TEXT,created_at TEXT,updated_at TEXT)"""
for fail_midway in (False, True):
    with sqlite3.connect(":memory:") as db:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY)")
        db.execute("INSERT INTO categories VALUES(1)")
        db.execute(legacy)
        originals = [(i, 1, "income", 100 + i, "2026-10-18", "catatan", "created", "updated") for i in range(1, 4)]
        db.executemany("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?)", originals)
        db.commit()
        db.execute("BEGIN")
        db.execute("ALTER TABLE transactions RENAME TO transactions_v1")
        db.execute(new_table)
        for row in db.execute("SELECT * FROM transactions_v1 ORDER BY id").fetchall():
            db.execute("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?)", (str(uuid.uuid4()), *row[1:]))
        db.execute("DROP TABLE transactions_v1")
        if fail_midway:
            db.rollback()
            assert db.execute("SELECT * FROM transactions ORDER BY id").fetchall() == originals
        else:
            db.commit()
            rows = db.execute("SELECT * FROM transactions ORDER BY amount_minor").fetchall()
            assert [r[1:] for r in rows] == [r[1:] for r in originals]
            assert len({r[0] for r in rows}) == 3
            assert all(uuid.UUID(r[0]).version == 4 for r in rows)
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
print("PASS: UUID migration DDL preserves financial columns/FK and rolls back atomically (host SQLite).")
