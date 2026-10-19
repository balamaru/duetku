package id.kelolauangku.app.data

import android.content.Context
import android.content.ContentValues
import org.json.JSONObject

class ProfileStore(context:Context, private val authorized:()->Boolean) {
    private val database=Database(context)
    fun load():JSONObject {
        check(authorized()) {"Buka kunci aplikasi terlebih dahulu."}
        return database.readableDatabase.rawQuery("SELECT display_name,avatar_color,include_name_in_pdf,default_dashboard_period,default_report_period FROM app_profile WHERE id=1",null).use {
        check(it.moveToFirst())
        JSONObject().put("Name",it.getString(0)).put("Color",it.getInt(1)).put("IncludeName",it.getInt(2)==1).put("DashboardPeriod",it.getString(3)).put("ReportPeriod",it.getString(4))
    }
    }
    fun save(name:String,color:Int,include:Boolean,dashboard:String,report:String) {
        check(authorized()) {"Buka kunci aplikasi terlebih dahulu."}
        val clean=name.trim()
        require(clean.codePointCount(0,clean.length)<=50 && clean.none{it.isISOControl()}) {"Nama maksimal 50 karakter tanpa karakter kontrol."}
        require(color in 0..7 && dashboard in listOf("day","week","month","year12") && report in listOf("week","month","lastMonth","quarter","year12"))
        database.writableDatabase.update("app_profile",ContentValues().apply {
            put("display_name",clean);put("avatar_color",color);put("include_name_in_pdf",if(include)1 else 0)
            put("default_dashboard_period",dashboard);put("default_report_period",report);put("updated_at",java.time.Instant.now().toString())
        },"id=?",arrayOf("1"))
    }
}
