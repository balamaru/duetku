package id.kelolauangku.app.application

import android.content.Context
import id.kelolauangku.app.data.SQLiteStore
import mobile.Mobile
import org.json.JSONObject
import java.time.OffsetDateTime

class CoreFailure(val code: String, override val message: String) : Exception(message)

class LocalCore(context: Context, private val authorized: () -> Boolean) {
    private val store = SQLiteStore(context)
    @Synchronized fun call(operation: String, payload: JSONObject = JSONObject()): Any? {
        check(authorized()) { "Buka kunci aplikasi terlebih dahulu." }
        return execute(operation,payload)
    }
    private fun execute(operation: String, payload: JSONObject = JSONObject()): Any? {
        val result = JSONObject(Mobile.execute(store, operation, payload.toString(), OffsetDateTime.now().toString()))
        if (!result.getBoolean("OK")) {
            val error = result.getJSONObject("Error")
            throw CoreFailure(error.getString("Code"), error.getString("Message"))
        }
        return result.opt("Data")
    }
    @Synchronized fun initialize() {
        if (store.needsSeed()) { execute("seed"); store.markSeeded() }
        execute("purge")
    }
}
