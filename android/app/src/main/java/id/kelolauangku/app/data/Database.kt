package id.kelolauangku.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Database(context: Context, name: String = "keuangan.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // This PRAGMA returns a row. Consume it through a cursor: execSQL rejects
        // result-producing statements on Android SQLite implementations.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "Pengamanan penghapusan SQLite gagal diaktifkan."
            }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL COLLATE NOCASE CHECK(length(trim(name)) BETWEEN 1 AND 50),
            type TEXT NOT NULL CHECK(type IN ('income','expense')),
            is_active INTEGER NOT NULL CHECK(is_active IN (0,1)),
            is_default INTEGER NOT NULL CHECK(is_default IN (0,1)),
            UNIQUE(type,name))""")
        db.execSQL("""CREATE TABLE transactions (
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),
            category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
            type TEXT NOT NULL CHECK(type IN ('income','expense')),
            amount_minor INTEGER NOT NULL CHECK(amount_minor BETWEEN 1 AND 9999999999999),
            transaction_date TEXT NOT NULL CHECK(length(transaction_date)=10),
            note TEXT NOT NULL CHECK(length(note)<=255),
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX idx_tx_date ON transactions(transaction_date DESC,id DESC)")
        db.execSQL("CREATE INDEX idx_tx_type_date ON transactions(type,transaction_date)")
        db.execSQL("CREATE INDEX idx_tx_category ON transactions(category_id)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata(key,value) VALUES('seeded',0)")
        createSettings(db)
    }
    private fun createSettings(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE app_profile (id INTEGER PRIMARY KEY CHECK(id=1),
            display_name TEXT NOT NULL DEFAULT '', avatar_color INTEGER NOT NULL DEFAULT 0 CHECK(avatar_color BETWEEN 0 AND 7),
            include_name_in_pdf INTEGER NOT NULL DEFAULT 1, default_dashboard_period TEXT NOT NULL DEFAULT 'month',
            default_report_period TEXT NOT NULL DEFAULT 'month', created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
        db.execSQL("INSERT INTO app_profile(id,created_at,updated_at) VALUES(1,datetime('now'),datetime('now'))")
        db.execSQL("""CREATE TABLE app_security (id INTEGER PRIMARY KEY CHECK(id=1),
            pin_hash BLOB NOT NULL, pin_salt BLOB NOT NULL, kdf_iterations INTEGER NOT NULL CHECK(kdf_iterations>=100000),
            recovery_hash BLOB, recovery_salt BLOB, biometric_enabled INTEGER NOT NULL DEFAULT 0,
            biometric_token BLOB, biometric_iv BLOB, auto_lock_seconds INTEGER NOT NULL DEFAULT 60 CHECK(auto_lock_seconds IN (0,30,60,300)),
            allow_screenshots INTEGER NOT NULL DEFAULT 0, failed_attempts INTEGER NOT NULL DEFAULT 0,
            locked_until_elapsed INTEGER NOT NULL DEFAULT 0, boot_marker INTEGER NOT NULL DEFAULT 0,
            wipe_after_failures INTEGER NOT NULL DEFAULT 0 CHECK(wipe_after_failures IN (0,15)))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Migrasi database belum tersedia." }
        // SQLiteOpenHelper wraps onUpgrade and user_version in one transaction.
        db.execSQL("ALTER TABLE transactions RENAME TO transactions_v1")
        db.execSQL("""CREATE TABLE transactions (
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),
            category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
            type TEXT NOT NULL CHECK(type IN ('income','expense')),
            amount_minor INTEGER NOT NULL CHECK(amount_minor BETWEEN 1 AND 9999999999999),
            transaction_date TEXT NOT NULL CHECK(length(transaction_date)=10),
            note TEXT NOT NULL CHECK(length(note)<=255), created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
        db.rawQuery("SELECT category_id,type,amount_minor,transaction_date,note,created_at,updated_at FROM transactions_v1 ORDER BY id", null).use { c ->
            while (c.moveToNext()) db.execSQL("INSERT INTO transactions VALUES(?,?,?,?,?,?,?,?)", arrayOf(
                java.util.UUID.randomUUID().toString(), c.getLong(0), c.getString(1), c.getLong(2),
                c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
        }
        db.execSQL("DROP TABLE transactions_v1")
        db.execSQL("CREATE INDEX idx_tx_date ON transactions(transaction_date DESC,created_at DESC,id DESC)")
        db.execSQL("CREATE INDEX idx_tx_type_date ON transactions(type,transaction_date)")
        db.execSQL("CREATE INDEX idx_tx_category ON transactions(category_id)")
        createSettings(db)
    }
}
