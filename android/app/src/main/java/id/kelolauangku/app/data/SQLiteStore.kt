package id.kelolauangku.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import mobile.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

// Go callbacks may use different native threads: every DB action is pinned here.
class SQLiteStore(context: Context, name: String = "keuangan.db") : Store {
    private val database = Database(context.applicationContext, name)
    private val worker = Executors.newSingleThreadExecutor()
    private var openTransaction = false
    private var writable = false
    private fun <T> db(action: (SQLiteDatabase) -> T): T {
        try {
            return worker.submit<T> {
                try { action(database.writableDatabase) }
                catch (cause: Exception) {
                    // Keep the platform cause so startup diagnostics can identify SQLite/storage failures.
                    // Do not log this exception: database paths and user data must stay private.
                    throw IllegalStateException("Penyimpanan lokal gagal.", cause)
                }
            }.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    override fun begin(write: Boolean): String = db { connection ->
        check(!openTransaction)
        connection.beginTransaction()
        openTransaction = true
        writable = write
        try {
            val categories = JSONArray()
            connection.rawQuery("SELECT id,name,type,is_active,is_default FROM categories ORDER BY id", null).use { c ->
                while (c.moveToNext()) categories.put(JSONObject().put("ID", c.getLong(0))
                    .put("Name", c.getString(1)).put("Type", c.getString(2))
                    .put("Active", c.getInt(3) == 1).put("Default", c.getInt(4) == 1))
            }
            val transactions = JSONArray()
            connection.rawQuery("SELECT id,category_id,type,amount_minor,transaction_date,note,created_at,updated_at FROM transactions", null).use { c ->
                while (c.moveToNext()) transactions.put(JSONObject().put("ID", c.getString(0))
                    .put("CategoryID", c.getLong(1)).put("Type", c.getString(2)).put("Amount", c.getLong(3))
                    .put("Date", c.getString(4)).put("Note", c.getString(5))
                    .put("CreatedAt", c.getString(6)).put("UpdatedAt", c.getString(7)))
            }
            val lastID = connection.rawQuery("SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence", null).use { it.moveToFirst(); it.getLong(0) }
            JSONObject().put("Categories", categories).put("Transactions", transactions).put("LastID", lastID).toString()
        } catch (e: Exception) {
            connection.endTransaction(); openTransaction = false; throw e
        }
    }
    override fun finish(changes: String) { db { connection ->
        check(openTransaction)
        try {
            val batch = JSONArray(changes)
            check(writable || batch.length() == 0)
            for (index in 0 until batch.length()) {
                val change = batch.getJSONObject(index)
                when (change.getString("Kind")) {
                    "saveCategory" -> {
                        val c = change.getJSONObject("Category")
                        val values = ContentValues().apply {
                            put("id", c.getLong("ID")); put("name", c.getString("Name")); put("type", c.getString("Type"))
                            put("is_active", if (c.getBoolean("Active")) 1 else 0)
                            put("is_default", if (c.getBoolean("Default")) 1 else 0)
                        }
                        upsert(connection, "categories", c.getLong("ID").toString(), values)
                    }
                    "saveTransaction" -> {
                        val t = change.getJSONObject("Transaction")
                        val values = ContentValues().apply {
                            put("id", t.getString("ID")); put("category_id", t.getLong("CategoryID")); put("type", t.getString("Type"))
                            put("amount_minor", t.getLong("Amount")); put("transaction_date", t.getString("Date"))
                            put("note", t.getString("Note")); put("created_at", t.getString("CreatedAt")); put("updated_at", t.getString("UpdatedAt"))
                        }
                        upsert(connection, "transactions", t.getString("ID"), values)
                    }
                    "deleteCategory" -> connection.delete("categories", "id=?", arrayOf(change.getLong("ID").toString()))
                    "deleteTransaction" -> connection.delete("transactions", "id=?", arrayOf(change.getString("TransactionID")))
                    else -> error("Operasi penyimpanan tidak sah.")
                }
            }
            connection.setTransactionSuccessful()
        } finally { connection.endTransaction(); openTransaction = false }
    } }
    private fun upsert(db: SQLiteDatabase, table: String, id: String, values: ContentValues) {
        if (db.update(table, values, "id=?", arrayOf(id.toString())) == 0) db.insertOrThrow(table, null, values)
    }
    override fun abort() { db { if (openTransaction) { it.endTransaction(); openTransaction = false } } }
    fun needsSeed(): Boolean = db { c -> c.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf("seeded")).use { it.moveToFirst(); it.getInt(0) == 0 } }
    fun markSeeded() { db { it.execSQL("UPDATE metadata SET value=1 WHERE key=?", arrayOf("seeded")) } }
}
