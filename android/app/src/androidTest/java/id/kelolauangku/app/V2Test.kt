package id.kelolauangku.app

import android.test.AndroidTestCase
import android.database.sqlite.SQLiteDatabase
import id.kelolauangku.app.data.Database
import id.kelolauangku.app.application.Security
import id.kelolauangku.app.application.LocalCore
import id.kelolauangku.app.application.PdfReport
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class V2Test:AndroidTestCase() {
    fun testLlmKeyEncryptionAndAuthorization() {
        val name="llm-test-"+System.nanoTime()
        var authorized=true
        val store=id.kelolauangku.app.application.LlmConfig(context,{authorized},name,name)
        val secret="test-credential-not-a-real-api-key"
        try {
            store.save("gemini","test-model",secret)
            assertEquals(secret,store.load()!!.key)
            assertFalse(context.getSharedPreferences(name,0).all.toString().contains(secret))
            store.save("gemini","another-model","")
            assertEquals(secret,store.load()!!.key)
            assertTrue(runCatching{store.save("claude","test-model","")}.isFailure)
            authorized=false
            assertTrue(runCatching{store.load()}.isFailure)
            authorized=true
            store.clear();assertNull(store.load())
        }finally{authorized=true;store.clear();context.deleteSharedPreferences(name)}
    }
    fun testV1MigrationPreservesDataAndUUIDs() {
        val name="migration-${System.nanoTime()}.db"
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,type TEXT,is_active INTEGER,is_default INTEGER)")
            db.execSQL("INSERT INTO categories VALUES(1,'Gaji','income',1,1)")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value INTEGER)")
            db.execSQL("INSERT INTO metadata VALUES('seeded',1)")
            db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,category_id INTEGER REFERENCES categories(id),type TEXT,amount_minor INTEGER,transaction_date TEXT,note TEXT,created_at TEXT,updated_at TEXT)")
            db.execSQL("CREATE INDEX idx_tx_date ON transactions(transaction_date DESC,id DESC)")
            db.execSQL("CREATE INDEX idx_tx_type_date ON transactions(type,transaction_date)")
            db.execSQL("CREATE INDEX idx_tx_category ON transactions(category_id)")
            for(id in 1..3)db.execSQL("INSERT INTO transactions VALUES(?,1,'income',100,'2026-10-18','catatan','2026-10-18T00:00:00Z','2026-10-18T00:00:00Z')",arrayOf(id))
            db.version=1
        }
        val migrated=Database(context,name)
        val identifiers=mutableSetOf<String>()
        try {
            val db=migrated.writableDatabase
            assertEquals(2,db.version)
            db.rawQuery("SELECT id,amount_minor,note,category_id FROM transactions",null).use{c->while(c.moveToNext()){
                assertEquals(4,java.util.UUID.fromString(c.getString(0)).version());identifiers.add(c.getString(0))
                assertEquals(100L,c.getLong(1));assertEquals("catatan",c.getString(2));assertEquals(1L,c.getLong(3))
            }}
            assertEquals(3,identifiers.size)
            db.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
            db.rawQuery("SELECT COUNT(*) FROM app_profile",null).use{assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        }finally{migrated.close()}
        val reopened=Database(context,name)
        try{reopened.readableDatabase.rawQuery("SELECT id FROM transactions",null).use{while(it.moveToNext())assertTrue(identifiers.contains(it.getString(0)))}}
        finally{reopened.close();context.deleteDatabase(name)}
    }
    fun testLockoutRecoveryAndMonotonicAutoLock() {
        val name="security-${System.nanoTime()}.db";val prefix="test-${System.nanoTime()}-"
        var elapsed=1000L;var boot=1
        var security=Security(context,name,prefix,{elapsed},{boot})
        try {
            security.load();security.setPin("394827".toCharArray());val recovery=security.recoveryKey()
            repeat(5){assertTrue(runCatching{security.verify("000000".toCharArray())}.isFailure)}
            assertEquals(30L,security.remainingLockSeconds())
            security.close();security=Security(context,name,prefix,{elapsed},{boot});security.load()
            assertEquals(30L,security.remainingLockSeconds())
            elapsed+=15000;assertEquals(15L,security.remainingLockSeconds())
            boot++;elapsed=10;assertEquals(30L,security.remainingLockSeconds())
            elapsed+=30000;security.verify(recovery.toCharArray(),true);security.setPin("738492".toCharArray())
            security.verify("738492".toCharArray());security.verify(recovery.toCharArray(),true)
            security.unlock();security.background();elapsed+=59000;security.foreground();assertTrue(security.unlocked)
            security.background();elapsed+=60000;security.foreground();assertFalse(security.unlocked)
            assertTrue(runCatching{LocalCore(context){false}.call("summary")}.isFailure)
        } finally {security.resetKeys();security.close();context.deleteDatabase(name)}
    }
    fun testPDFPaginationAndCancellation() {
        val dataset=JSONObject("""{"SchemaVersion":1,"Period":{"Start":"2026-10-01","End":"2026-10-18"},"Retention":{"Start":"2025-10-16","End":"2026-10-18"},"Generated":"2026-10-18","Summary":{"Income":1000,"Expense":250,"Balance":750,"Count":2},"SavingRatio":"75,0","DailyAverage":"13,9","Categories":[],"Trends":[],"ComparisonAvailable":false,"Top":[],"Notes":["Uji"],"Transactions":[]}""")
        val report=JSONObject().put("Dataset",dataset).put("Narrative",org.json.JSONArray())
        val file=java.io.File(context.cacheDir,"test-report.pdf")
        try{
            file.outputStream().use{PdfReport.write(report,"Nama uji",it,AtomicBoolean(false))}
            android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use{fd->android.graphics.pdf.PdfRenderer(fd).use{assertTrue(it.pageCount>=4)}}
            assertTrue(runCatching{PdfReport.write(report,"",java.io.ByteArrayOutputStream(),AtomicBoolean(true))}.isFailure)
        }finally{file.delete()}
    }
}
