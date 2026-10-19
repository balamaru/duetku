import id.kelolauangku.app.application.LlmAnalyzer as L
import id.kelolauangku.app.application.LlmConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val d=JSONObject("""{"Summary":{"Income":1000,"Expense":250},"Period":{"Start":"2026-10-01","End":"2026-10-16"},"Categories":[{"CategoryID":8,"Name":"Makanan","Amount":250,"Percent":"100,0"}],"Top":[{"Note":"SECRET"}],"Transactions":[{"Note":"SECRET"}],"Profile":"SECRET","IncomeCategories":[],"Changes":[]}""")
    val p=L.payload(d)
    check(!p.toString().contains("SECRET")&&!p.toString().contains("CategoryID"))
    val valid="""{"paragraphs":["Pengeluaran terkonsentrasi pada kategori makanan.","Tinjau catatan kategori sebelum menentukan penyesuaian anggaran."]}"""
    check(L.validate(valid).length()==2)
    for(bad in listOf(
        """{"paragraphs":["Pengeluaran mencapai Rp 999.","Tinjau catatan kategori sebelum menentukan penyesuaian anggaran."]}""",
        """{"paragraphs":["Pengeluaran mencapai seratus juta.","Tinjau catatan kategori sebelum menentukan penyesuaian anggaran."]}""",
        """{"paragraphs":["Pengeluaran mencapai ٢٥۰.","Tinjau catatan kategori sebelum menentukan penyesuaian anggaran."]}""",
        """{"paragraphs":["Terlalu pendek."]}""",
        """{"paragraphs":["Pengeluaran terkonsentrasi pada kategori makanan.","Tinjau catatan kategori sebelum menentukan penyesuaian anggaran."],"extra":"bad"}"""))check(runCatching{L.validate(bad)}.isFailure)
    for(provider in listOf("gemini","openai","claude")) {
        val c=LlmConfig.Config(provider,"test-model","test-key-not-a-real-credential")
        val request=L.request(c,p)
        check(request.url.startsWith("https://")&&!request.url.contains(c.key))
        check(!request.body.toString().contains(c.key))
        val response=when(provider) {
            "openai"->JSONObject().put("output",org.json.JSONArray().put(JSONObject().put("type","message").put("content",org.json.JSONArray().put(JSONObject().put("type","output_text").put("text",valid))))).toString()
            "claude"->JSONObject().put("content",org.json.JSONArray().put(JSONObject().put("type","text").put("text",valid))).toString()
            else->JSONObject().put("candidates",org.json.JSONArray().put(JSONObject().put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",valid)))))).toString()
        }
        check(L.extract(provider,response)==valid)
        val report=JSONObject().put("Dataset",d)
        val snapshot=d.toString()
        L.enrich(report,c,AtomicBoolean(false),L.Transport { r,_->check(r.body.toString().contains("Makanan"));L.Reply(200,response)})
        check(report.has("AI")&&snapshot==d.toString())
        for(status in listOf(401,403,429,500,302)) {
            val fallback=JSONObject().put("Dataset",d)
            L.enrich(fallback,c,AtomicBoolean(false),L.Transport{_,_->L.Reply(status,"")})
            check(!fallback.has("AI")&&fallback.has("AIStatus"))
        }
        val malformed=JSONObject().put("Dataset",d)
        L.enrich(malformed,c,AtomicBoolean(false),L.Transport{_,_->L.Reply(200,"invalid")})
        check(malformed.has("AIStatus"))
        val offline=JSONObject().put("Dataset",d)
        L.enrich(offline,c,AtomicBoolean(false),L.Transport{_,_->throw java.io.IOException()})
        check(offline.has("AIStatus"))
        check(runCatching{L.enrich(JSONObject().put("Dataset",d),c,AtomicBoolean(true),L.Transport{_,_->error("Must not send")})}.isFailure)
        check(runCatching{L.request(c.copy(model="../invalid"),p)}.isFailure)
    }
    println("PASS: three provider contracts, aggregate privacy, numerical rejection, malformed output, offline/auth/quota/server/redirect fallback and cancellation.")
}
