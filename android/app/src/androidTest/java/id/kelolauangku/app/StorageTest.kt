package id.kelolauangku.app

import android.test.AndroidTestCase
import android.content.pm.ApplicationInfo
import id.kelolauangku.app.data.Database
import id.kelolauangku.app.data.SQLiteStore
import mobile.Mobile
import org.json.JSONObject

@Suppress("DEPRECATION")
class StorageTest : AndroidTestCase() {
    fun testDatabaseIsPrivateAndBackupDisabled() {
        val name = "privacy-${System.nanoTime()}.db"
        val database = Database(context, name)
        try {
            val db = database.writableDatabase
            val dataDir = java.io.File(context.applicationInfo.dataDir).canonicalFile
            val dbFile = java.io.File(db.path).canonicalFile
            assertEquals(java.io.File(dataDir, "databases"), dbFile.parentFile)
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            db.rawQuery("PRAGMA foreign_keys", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            db.rawQuery("PRAGMA secure_delete", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    fun testBridgeSQLiteRollbackAndRetention() {
        val name = "test-${System.nanoTime()}.db"
        val store = SQLiteStore(context,name)
        val now="2026-10-16T12:00:00+07:00"
        fun call(op:String,body:String="{}") = JSONObject(Mobile.execute(store,op,body,now))
        assertTrue(call("seed").getBoolean("OK"))
        assertTrue(call("saveTransaction","""{"Input":{"CategoryID":1,"Type":"income","Amount":1200,"Date":"2025-10-14","Note":"uji"}}""").getBoolean("OK"))
        assertFalse(call("saveTransaction","""{"Input":{"CategoryID":1,"Type":"income","Amount":0,"Date":"2025-10-14"}}""").getBoolean("OK"))
        assertEquals(1,call("summary").getJSONObject("Data").getInt("Count"))
        // Crossing the retention boundary removes yesterday's oldest record.
        val purge=JSONObject(Mobile.execute(store,"purge","{}","2026-10-17T00:00:00+07:00"))
        assertTrue(purge.getBoolean("OK"));assertEquals(1,purge.getInt("Data"))
        assertEquals(0,call("summary").getJSONObject("Data").getInt("Count"))
        assertEquals(8,call("categories").getJSONArray("Data").length())
    }
}
