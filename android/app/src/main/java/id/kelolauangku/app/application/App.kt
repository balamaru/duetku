package id.kelolauangku.app.application

import android.app.Application
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        worker.execute {
            java.io.File(cacheDir,"reports").listFiles()?.filter { it.isFile && it.name.matches(Regex("laporan-[0-9a-f-]{36}\\.pdf")) }?.forEach { it.delete() }
        }
    }
    // Kept only in process memory across activity recreation; never persisted to cache.
    var pendingCSV: String? = null
    var pendingPDF: java.io.File? = null
    val worker = Executors.newSingleThreadExecutor()
    val security by lazy { Security(this) }
    val llm by lazy { LlmConfig(this, authorized={ security.authorized() }) }
    val profile by lazy { id.kelolauangku.app.data.ProfileStore(this) { security.authorized() } }
    val core by lazy { LocalCore(this) { security.authorized() } }
}
