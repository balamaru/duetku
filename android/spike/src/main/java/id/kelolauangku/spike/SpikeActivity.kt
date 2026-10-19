package id.kelolauangku.spike

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import java.util.concurrent.Executors

class SpikeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        val started = SystemClock.elapsedRealtimeNanos()
        super.onCreate(state)
        val label = TextView(this).apply { text="Menyiapkan spike…"; textSize=22f; setPadding(48,120,48,48) }
        setContentView(label)
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val result = runCatching {
                openOrCreateDatabase("spike.db",MODE_PRIVATE,null).use { db ->
                    db.setForeignKeyConstraintsEnabled(true)
                    db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY)")
                    db.rawQuery("SELECT COUNT(*) FROM probe",null).use { check(it.moveToFirst()) }
                }
                check(Engine.ping()=="ready")
            }
            runOnUiThread {
                label.text=if(result.isSuccess)"Spike siap · ${Engine.name}" else "Spike gagal"
                if(result.isSuccess) {
                    reportFullyDrawn()
                    // Only synthetic timing metadata; no financial data.
                    Log.i("KelolaSpike","ready_ns="+(SystemClock.elapsedRealtimeNanos()-started))
                }
            }
            worker.shutdown()
        }
    }
}
