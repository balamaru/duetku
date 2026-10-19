package id.kelolauangku.app.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.TextWatcher
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*
import id.kelolauangku.app.R
import id.kelolauangku.app.application.App
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    override fun attachBaseContext(base:Context) {
        val theme=base.getSharedPreferences("appearance",Context.MODE_PRIVATE).getString("theme","system")
        val config=android.content.res.Configuration(base.resources.configuration)
        if(theme=="light"||theme=="dark"){
            config.uiMode=(config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(theme=="dark")android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            super.attachBaseContext(base.createConfigurationContext(config))
        }else super.attachBaseContext(base)
    }
    private val app get() = application as App
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var navigation: LinearLayout
    private val ui by lazy { UiKit(this) }
    private var subpage = false
    private var animateNext = false
    private val disabledViews = mutableMapOf<View, Boolean>()
    private var retentionStart = LocalDate.now().minusMonths(12)
    private var screen = "Ringkasan"
    private var busy = false
    private var categories = JSONArray()
    private var filter = JSONObject()
    private var pageOffset = 0
    private var draft: JSONObject? = null
    private var captureDraft: (() -> Unit)? = null
    private var lastPurgedDay: LocalDate? = null
    private var purgePending = false
    private var storageFailure: Throwable? = null
    private var activeSheet: Dialog? = null
    private var visible = false
    private var profile = JSONObject()
    private var profileLoaded = false
    private var analyticType = "expense"
    private var lifecycleEpoch = 0
    private var biometricCancellation: android.os.CancellationSignal? = null
    private var reportCancellation: java.util.concurrent.atomic.AtomicBoolean? = null
    private fun trackDialog(dialog:Dialog) {
        activeSheet=dialog
        if(!app.security.screenshots)dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    // Registered only while visible: no periodic wakeups or background service.
    private val dateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (lastPurgedDay != LocalDate.now()) {
                captureDraft?.invoke()
                refresh(true)
            }
        }
    }
    private val currency = NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
    private fun rupiah(value: Long) = "Rp ${currency.format(value)}"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        window.setDecorFitsSystemWindows(false)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        content = findViewById(R.id.content); status = findViewById(R.id.status)
        title = findViewById(R.id.title); navigation = findViewById(R.id.navigation)
        screen = saved?.getString("screen") ?: "Ringkasan"
        filter = JSONObject(saved?.getString("filter") ?: "{}")
        onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { goBack() }
        findViewById<Button>(R.id.back).apply {
            backgroundTintList=null;stateListAnimator=null;background = ui.touch(R.color.card, 24,R.color.outline)
            setCompoundDrawables(null, null, null, null)
            text = ""; foreground = ui.icon(R.drawable.ic_back, R.color.ink); foregroundGravity = Gravity.CENTER
            setOnClickListener { goBack() }
        }
        findViewById<Button>(R.id.period).apply {
            textSize = 13f; backgroundTintList = null; stateListAnimator=null;background = ui.touch(R.color.card, 24,R.color.outline)
            setCompoundDrawables(null,null,ui.icon(R.drawable.ic_down,R.color.ink,16),null);compoundDrawablePadding=dp(8)
            setPadding(dp(14), dp(8), dp(14), dp(8)); setTextColor(getColor(R.color.ink))
            setOnClickListener { if (!busy) periodDialog() }
        }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        status.visibility = View.GONE
        navigation.setBackgroundColor(getColor(R.color.card))
        // Keep content comfortable on a wide phone/landscape without stretching forms.
        val gutter = maxOf(20, (resources.configuration.screenWidthDp - 600) / 2)
        content.setPadding(dp(gutter), 0, dp(gutter), dp(24))
        listOf("Ringkasan", "Riwayat", "Tambah", "Analitik", "Kelola").forEachIndexed { index, tab ->
            val b = ui.button(if(tab=="Ringkasan")"Beranda" else if(tab=="Tambah")"" else tab) {
                if (!busy) {
                    if(tab=="Tambah"){newTransaction();return@button}
                    if (draft != null) discardDraft(tab)
                    else { screen = tab; subpage = false; pageOffset = 0; animateNext = true; refresh() }
                }
            }.apply {
                tag = tab; textSize = 11f; minHeight = dp(60)
                setPadding(dp(2), dp(6), dp(2), dp(6)); compoundDrawablePadding = dp(4)
                setCompoundDrawables(null, ui.icon(navIcon(index), R.color.muted), null, null)
                if(tab=="Tambah"){
                    contentDescription="Catat transaksi";foreground=ui.icon(R.drawable.ic_add,R.color.on_primary,24)
                    foregroundGravity=Gravity.CENTER;setCompoundDrawables(null,null,null,null)
                    minHeight=0;minimumHeight=0;setPadding(0,0,0,0);elevation=dp(6).toFloat()
                    outlineAmbientShadowColor=getColor(R.color.primary);outlineSpotShadowColor=getColor(R.color.primary)
                }
            }
            val slot=FrameLayout(this).apply{clipChildren=false;clipToPadding=false;addView(b,FrameLayout.LayoutParams(if(tab=="Tambah")dp(52) else -1,if(tab=="Tambah")dp(52) else dp(60),Gravity.CENTER))}
            navigation.addView(slot, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
    }
    private fun navIcon(index: Int) = intArrayOf(R.drawable.ic_home, R.drawable.ic_history, R.drawable.ic_add, R.drawable.ic_analytics, R.drawable.ic_settings)[index]
    private fun goBack() {
        if (busy) return
        if(subpage && draft!=null){subpage=false;refresh()}
        else if (draft != null) discardDraft()
        else if (subpage) { subpage = false; refresh() }
        else if (screen != "Ringkasan") { screen = "Ringkasan"; refresh() }
        else finish()
    }
    private fun pageTitle(value: String, child: Boolean = false) {
        subpage = child; title.text = value
        title.textSize=if(child||draft!=null)20f else 28f
        title.gravity=if(child||draft!=null)Gravity.CENTER else Gravity.START
        findViewById<View>(R.id.avatar).visibility=View.GONE
        navigation.visibility=if(child||draft!=null||!app.security.unlocked)View.GONE else View.VISIBLE
        findViewById<View>(R.id.back).visibility = if (child || draft != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.period).apply {
            visibility = if (!child && draft == null && screen != "Kelola") View.VISIBLE else View.GONE
            text = periodTitle()
            contentDescription = "Pilih periode laporan"
        }
    }
    private fun newTransaction() {
        draft = JSONObject().put("Type", "expense").put("Date", LocalDate.now().toString()); refresh()
    }
    private fun setActionsEnabled(view: View, enabled: Boolean) {
        if (view is Button || view is EditText || view is Spinner || view.isClickable) {
            if (!enabled) { disabledViews[view] = view.isEnabled; view.isEnabled = false; view.alpha = .55f }
            else { view.isEnabled = disabledViews.remove(view) ?: view.isEnabled; view.alpha = if (view.isEnabled) 1f else .55f }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) setActionsEnabled(view.getChildAt(i), enabled)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        app.security.foreground()
        registerReceiver(dateChanges, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        openSession()
    }
    override fun onDestroy() {
        activeSheet?.dismiss(); activeSheet = null
        super.onDestroy()
    }
    override fun onPause() {
        lifecycleEpoch++
        biometricCancellation?.cancel();biometricCancellation=null
        reportCancellation?.set(true)
        visible = false
        app.security.background()
        activeSheet?.dismiss(); activeSheet = null
        captureDraft = null; draft = null
        content.removeAllViews(); status.text = ""; title.text = "duetku"
        navigation.visibility = View.GONE
        findViewById<View>(R.id.period).visibility = View.GONE
        unregisterReceiver(dateChanges)
        super.onPause()
    }
    override fun onSaveInstanceState(out: Bundle) {
        captureDraft?.invoke(); out.putString("screen",screen); out.putString("filter",filter.toString())
        super.onSaveInstanceState(out)
    }
    private fun work(label: String, action: () -> Any?, done: (Any?) -> Unit) {
        if (busy) return
        busy = true; status.text = label
        val epoch=lifecycleEpoch
        setActionsEnabled(findViewById(R.id.root), false)
        app.worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                setActionsEnabled(findViewById(R.id.root), true)
                if (!visible) return@runOnUiThread
                if (epoch!=lifecycleEpoch) {openSession();return@runOnUiThread}
                result.fold({
                    storageFailure = null
                    status.text = ""
                    done(it)
                }, { error ->
                    status.text = error.message ?: "Proses gagal. Silakan coba lagi."
                    if (generateSequence(error) { it.cause }.any { it.message == "Penyimpanan lokal gagal." }) {
                        storageFailure = error
                        showStorageFailure(error)
                    }
                })
                if (purgePending && !busy) refresh(true)
            }
        }
    }
    private fun showStorageFailure(error: Throwable) {
        content.removeAllViews()
        pageTitle("Penyimpanan belum dapat dibuka")
        text("Aplikasi tidak dapat membaca atau menyiapkan database lokal. Data tetap berada di penyimpanan privat aplikasi.")
        val cause = generateSequence(error.cause) { it.cause }.lastOrNull() ?: error
        val detail = if(app.security.unlocked) "${cause.javaClass.simpleName}: ${cause.message?.take(240) ?: "Tidak ada detail tambahan."}" else "${cause.javaClass.simpleName}: Penyimpanan belum siap. Detail data disembunyikan saat terkunci."
        text("Detail untuk diagnosis: $detail", 14f, R.color.muted)
        button("Salin detail") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Detail error duetku", detail))
            status.text = "Detail error disalin. Detail ini tidak memuat isi transaksi."
        }
        button("Coba lagi", true) { storageFailure = null; refresh(true) }
    }
    private fun queryFilter() = JSONObject(filter.toString()).apply {
        if (!has("From")) put("From", LocalDate.now().withDayOfMonth(1).toString())
        if (!has("To")) put("To", LocalDate.now().toString())
    }
    private fun refresh(purge: Boolean = false) {
        if (!app.security.unlocked) { if (!busy && visible) openSession(); return }
        if (storageFailure != null && !purge) {
            showStorageFailure(storageFailure!!)
            return
        }
        if (purge) purgePending = true
        if (busy) return
        val performPurge = purgePending || lastPurgedDay != LocalDate.now()
        purgePending = false
        val purgeDay = LocalDate.now()
        val requestedScreen = screen
        val editingTransaction = draft != null
        val requestedFilter = queryFilter()
        val requestedOffset = pageOffset
        work("Memuat catatan lokal…", {
            if (performPurge) app.core.initialize()
            val cats = app.core.call("categories") as JSONArray
            val window = app.core.call("window") as JSONObject
            val f = if(requestedScreen=="Analitik")JSONObject().put("From",requestedFilter.getString("From")).put("To",requestedFilter.getString("To")) else requestedFilter
            val data = if (editingTransaction) JSONObject() else when (requestedScreen) {
                "Ringkasan", "Analitik" -> JSONObject().put("Summary", app.core.call("summary", JSONObject().put("Filter",f)))
                    .put("Breakdown",app.core.call("breakdown",JSONObject().put("Filter",f)))
                    .put("Recent",app.core.call("listTransactions",JSONObject().put("Filter",JSONObject(f.toString()).put("Limit",3))))
                "Riwayat" -> (app.core.call("listTransactions",JSONObject().put("Filter",JSONObject(f.toString()).put("Offset",requestedOffset).put("Limit",20))) as JSONObject)
                    .put("Summary",app.core.call("summary",JSONObject().put("Filter",f)))
                else -> JSONObject()
            }
            Triple(cats,window,data)
        }) { raw ->
            if (!app.security.unlocked) { showLock(); return@work }
            navigation.visibility = View.VISIBLE
            @Suppress("UNCHECKED_CAST") val data = raw as Triple<JSONArray,JSONObject,JSONObject>
            categories = data.first
            retentionStart = LocalDate.parse(data.second.getString("Start"))
            if (performPurge) lastPurgedDay = purgeDay
            for (i in 0 until navigation.childCount) {
                val tab = (navigation.getChildAt(i) as FrameLayout).getChildAt(0) as Button
                val selected = tab.tag == screen
                if(tab.tag=="Tambah"){tab.background=ui.touch(R.color.primary,18);continue}
                tab.setTextColor(getColor(if (selected) R.color.primary else R.color.muted))
                tab.setCompoundDrawables(null, ui.icon(navIcon(i), if (selected) R.color.primary else R.color.muted), null, null)
                tab.background = ui.touch(R.color.card, 18)
                tab.isSelected = selected
            }
            captureDraft = null; content.removeAllViews(); pageTitle(if (draft == null) screen else if (draft!!.optString("ID").isEmpty()) "Catat transaksi" else "Ubah transaksi")
            if (draft != null) transactionForm() else when (screen) {
                "Ringkasan" -> dashboard(data.third)
                "Riwayat" -> history(data.third)
                "Analitik" -> analytics(data.third)
                else -> manage(data.second)
            }
            if (animateNext && ValueAnimator.areAnimatorsEnabled()) {
                content.alpha = .65f
                content.animate().alpha(1f).setDuration(180).start()
            }
            animateNext = false
            reportFullyDrawn()
        }
    }
    private fun text(value: String, size: Float = 16f, color: Int = R.color.ink): TextView {
        val view = ui.label(value, size, color, size >= 20f).apply { setPadding(0, dp(8), 0, dp(8)) }
        content.addView(view); return view
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button {
        val b = ui.button(label, primary, label.startsWith("Hapus")) { if (!busy) action() }
        content.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return b
    }
    private fun styledSpinner() = Spinner(this).apply {
        minimumHeight=dp(52);background=ui.touch(R.color.card,14,R.color.outline)
        setPadding(dp(12),dp(6),dp(12),dp(6))
    }
    private fun styledCheckBox() = CheckBox(this).apply {
        minHeight=dp(52);textSize=14f;setTextColor(getColor(R.color.ink));buttonTintList=ColorStateList.valueOf(getColor(R.color.primary))
        setPadding(dp(8),dp(8),dp(8),dp(8));background=ui.touch(R.color.card,14)
    }
    private fun field(label: String, value: String, numeric: Boolean = false): EditText = input(content, label, value, numeric)
    private fun input(parent: LinearLayout, label: String, value: String, numeric: Boolean = false): EditText {
        val caption = ui.label(label, 13f, R.color.muted).apply { setPadding(0, dp(16), 0, dp(8)) }
        parent.addView(caption)
        val edit = EditText(this).apply {
            id = View.generateViewId(); minHeight = dp(52); textSize = 16f; setText(value)
            setTextColor(getColor(R.color.ink)); setHintTextColor(getColor(R.color.muted))
            background = ui.shape(R.color.input_surface, 12, R.color.outline)
            setPadding(dp(14), dp(12), dp(14), dp(12)); setSelectAllOnFocus(numeric)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnFocusChangeListener { _, focused -> background = ui.shape(R.color.input_surface, 12, if (focused) R.color.primary else R.color.outline) }
        }
        caption.labelFor = edit.id; parent.addView(edit, LinearLayout.LayoutParams(-1, -2)); return edit
    }
    private fun line() { content.addView(View(this).apply { setBackgroundColor(getColor(R.color.outline)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(24); bottomMargin = dp(12) }) }
    private fun addBlock(view: View, margin: Int = 16) { content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(margin) }) }
    private fun addLabel(parent: LinearLayout, value: String, size: Float = 16f, color: Int = R.color.ink, bold: Boolean = false): TextView {
        val label = ui.label(value, size, color, bold)
        parent.addView(label, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }); return label
    }
    private fun section(value: String) = text(value, 20f).apply { isAccessibilityHeading = true; setPadding(0, dp(24), 0, dp(12)) }
    private fun periodDescription(): String {
        val f = queryFilter()
        fun date(value: String) = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))) }.getOrDefault(value)
        return "${date(f.optString("From"))} · ${date(f.optString("To"))}"
    }
    private fun periodTitle():String {
        val f=queryFilter()
        val from=runCatching{LocalDate.parse(f.optString("From"))}.getOrDefault(LocalDate.now())
        val to=runCatching{LocalDate.parse(f.optString("To"))}.getOrDefault(LocalDate.now())
        return if(from.month==to.month&&from.year==to.year)to.format(DateTimeFormatter.ofPattern("MMM yyyy",Locale.forLanguageTag("id-ID"))) else "Periode"
    }
    private fun percent(value:Long,total:Long):String = if(total<=0)"—" else java.math.BigDecimal.valueOf(value)
        .multiply(java.math.BigDecimal.valueOf(100)).divide(java.math.BigDecimal.valueOf(total),1,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.',',')+"%"
    private val series get()=intArrayOf(R.color.chart_fourth,R.color.chart_second,R.color.chart_third,R.color.primary)
    private fun sectionLink(label:String,actionLabel:String,action:()->Unit) {
        val row=ui.row()
        row.addView(ui.label(label,17f,R.color.ink,true).apply{isAccessibilityHeading=true},LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.button(actionLabel){if(!busy)action()}.apply{background=ui.touch(R.color.surface);setTextColor(getColor(R.color.primary));textSize=12f;setPadding(dp(8),0,0,0)},LinearLayout.LayoutParams(-2,-2))
        addBlock(row,12)
    }
    private fun categoryPanel(groups:List<JSONObject>,total:Long,bars:Boolean) {
        val panel=ui.column(R.color.card,16)
        if(groups.isEmpty()){addLabel(panel,"Belum ada transaksi pada periode ini.",14f,R.color.muted);addBlock(panel,4);return}
        if(!bars)panel.addView(CategoryStrip(this,groups.map{g->g.getLong("Amount") to getColor(categoryColor(g.getString("Name")))}),LinearLayout.LayoutParams(-1,dp(10)).apply{bottomMargin=dp(8)})
        groups.forEachIndexed { index,g ->
            val item=ui.column().apply{background=ui.touch(R.color.card,12);setPadding(0,dp(if(bars)12 else 5),0,dp(if(bars)12 else 5));minimumHeight=dp(if(bars)60 else 28)
                if(bars){isFocusable=true;setOnClickListener{if(!busy){filter.put("CategoryID",g.getLong("CategoryID")).put("Type",g.getString("Type"));screen="Riwayat";pageOffset=0;refresh()}}}}
            val row=ui.row()
            row.addView(View(this).apply{background=ui.shape(categoryColor(g.getString("Name")),if(bars)3 else 8)},LinearLayout.LayoutParams(dp(9),dp(9)).apply{marginEnd=dp(8)})
            row.addView(ui.label(g.getString("Name")+"  "+percent(g.getLong("Amount"),total),14f),LinearLayout.LayoutParams(0,-2,1f))
            val amount=ui.label(rupiah(g.getLong("Amount")),14f,R.color.ink,true)
            row.addView(amount,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            if(resources.configuration.fontScale>1.2f||rupiah(g.getLong("Amount")).length>17){row.removeView(amount);item.addView(row);item.addView(amount)}else item.addView(row)
            if(bars)item.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=1000;progress=if(total<=0)0 else java.math.BigInteger.valueOf(g.getLong("Amount")).multiply(java.math.BigInteger.valueOf(1000)).divide(java.math.BigInteger.valueOf(total)).toInt()
                progressTintList=ColorStateList.valueOf(getColor(categoryColor(g.getString("Name"))));progressBackgroundTintList=ColorStateList.valueOf(getColor(R.color.surface_container));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(-1,dp(5)).apply{topMargin=dp(8)})
            panel.addView(item)
        }
        if(!bars){panel.isFocusable=true;panel.contentDescription="Lihat analitik pengeluaran per kategori";panel.setOnClickListener{if(!busy){screen="Analitik";analyticType="expense";refresh()}}}
        addBlock(panel,0)
    }
    private fun transactionRow(parent:LinearLayout,row:JSONObject,showDate:Boolean=false) {
        val income=row.getString("Type")=="income"
        val item=ui.row().apply{setPadding(dp(16),dp(10),dp(16),dp(10));minimumHeight=dp(62);background=ui.touch(R.color.card,18);isFocusable=true
            setOnClickListener{if(!busy)transactionDetails(row)}}
        val symbol=TextView(this).apply{background=ui.shape(if(income)R.color.balance_surface else R.color.surface_container,12);gravity=Gravity.CENTER
            foreground=ui.icon(if(income)R.drawable.ic_wallet else transactionIcon(categoryName(row.getLong("CategoryID"))),if(income)R.color.primary else categoryColor(categoryName(row.getLong("CategoryID"))),20);foregroundGravity=Gravity.CENTER
            background=ui.shape(if(income)R.color.balance_surface else categorySurface(categoryName(row.getLong("CategoryID"))),12)}
        item.addView(symbol,LinearLayout.LayoutParams(dp(40),dp(40)).apply{marginEnd=dp(12)})
        val labels=ui.column()
        labels.addView(ui.label(row.optString("Note").ifBlank{categoryName(row.getLong("CategoryID"))},15f,R.color.ink,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        labels.addView(ui.label(categoryName(row.getLong("CategoryID"))+if(showDate)" · "+relativeDate(row.getString("Date")) else "",12f,R.color.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
        item.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val value=ui.label((if(income)"+" else "−")+rupiah(row.getLong("Amount")),15f,if(income)R.color.primary else R.color.ink,true)
        if(resources.configuration.fontScale>1.2f||rupiah(row.getLong("Amount")).length>16)labels.addView(value) else item.addView(value,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
        parent.addView(item,LinearLayout.LayoutParams(-1,-2))
    }
    private fun dashboard(data: JSONObject) {
        val name=profile.optString("Name").ifBlank{"Pemilik"}
        val greetingDate=LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM",Locale.forLanguageTag("id-ID")))
        title.text=android.text.SpannableString(greetingDate+"\nHalo, "+name).apply{
            setSpan(android.text.style.AbsoluteSizeSpan(13,true),0,greetingDate.length,0)
            setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.muted)),0,greetingDate.length,0)
            setSpan(android.text.style.TypefaceSpan("sans-serif"),0,greetingDate.length,0)
        }
        title.textSize=18f
        findViewById<Button>(R.id.avatar).apply{visibility=View.VISIBLE;text=name.take(1).uppercase();textSize=18f;setTextColor(avatarInk(profile.optInt("Color")));backgroundTintList=null;background=ui.touch(avatarColors[profile.optInt("Color").coerceIn(0,7)],28);setOnClickListener{if(!busy)profilePage()}}
        val summary=data.getJSONObject("Summary");val income=summary.getLong("Income");val expense=summary.getLong("Expense")
        val hero=ui.column(R.color.hero,20)
        addLabel(hero,"Sisa uang · "+compactPeriod(),13f,R.color.hero_muted)
        addLabel(hero,rupiah(summary.getLong("Balance")),34f,R.color.on_hero,true).apply{setAutoSizeTextTypeUniformWithConfiguration(20,34,1,android.util.TypedValue.COMPLEX_UNIT_SP);maxLines=1}
        hero.addView(RoundedBar(this,if(income<=0){if(expense>0)1f else 0f}else (expense.toDouble()/income).toFloat(),getColor(R.color.hero_accent),getColor(R.color.hero_track)).apply{
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-1,dp(8)).apply{topMargin=dp(10);bottomMargin=dp(10)})
        addLabel(hero,if(income==0L)"Belum ada pemasukan tercatat" else percent(expense,income)+" pemasukan sudah terpakai",13f,R.color.hero_muted)
        val metrics=ui.row();val stacked=resources.configuration.fontScale>1.2f||rupiah(maxOf(income,expense)).length>16
        if(stacked)metrics.orientation=LinearLayout.VERTICAL
        listOf("Pemasukan" to income,"Pengeluaran" to expense).forEachIndexed{i,(label,value)->
            val tile=ui.column(R.color.hero_tile,12).apply{background=ui.shape(R.color.hero_tile,16)}
            val heading=ui.row()
            heading.addView(TextView(this).apply{
                background=ui.shape(if(i==0)R.color.hero_income else R.color.hero_accent,20)
                foreground=ui.icon(if(i==0)R.drawable.ic_arrow_in else R.drawable.ic_arrow_out,R.color.hero,12);foregroundGravity=Gravity.CENTER
            },LinearLayout.LayoutParams(dp(20),dp(20)).apply{marginEnd=dp(6)})
            heading.addView(ui.label(label,12f,R.color.hero_muted));tile.addView(heading)
            tile.addView(ui.label(rupiah(value),17f,R.color.on_hero,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            metrics.addView(tile,LinearLayout.LayoutParams(if(stacked)-1 else 0,-2,if(stacked)0f else 1f).apply{if(i>0){if(stacked)topMargin=dp(8)else marginStart=dp(8)}})
        };hero.addView(metrics);addBlock(hero,4)
        if(filter.optString("Search").isNotEmpty()||filter.optString("Type").isNotEmpty()||filter.optLong("CategoryID")!=0L||filter.optLong("MinAmount")!=0L||filter.optLong("MaxAmount")!=0L)button("Filter aktif · Ubah"){filterForm()}
        sectionLink("Pengeluaran per kategori","Lihat analitik"){screen="Analitik";analyticType="expense";refresh()}
        val groups=data.getJSONArray("Breakdown");categoryPanel((0 until groups.length()).map{groups.getJSONObject(it)}.filter{it.getString("Type")=="expense"}.sortedByDescending{it.getLong("Amount")},expense,false)
        sectionLink("Transaksi terbaru","Lihat semua"){screen="Riwayat";pageOffset=0;refresh()}
        val recent=data.getJSONObject("Recent").getJSONArray("Items")
        val panel=ui.column(R.color.card).apply{setPadding(0,dp(4),0,dp(4))}
        for(i in 0 until recent.length())transactionRow(panel,recent.getJSONObject(i),true)
        if(recent.length()==0){addLabel(panel,"Belum ada transaksi. Ketuk + untuk mulai mencatat.",14f,R.color.muted).setPadding(dp(16),dp(20),dp(16),dp(20))}
        addBlock(panel,4)
    }
    private fun segments(parent:LinearLayout,labels:List<String>,selected:Int,onSelected:(Int)->Unit) {
        val row=ui.row().apply{background=ui.shape(R.color.surface_container,16);setPadding(dp(4),dp(4),dp(4),dp(4))}
        if(resources.configuration.fontScale>1.3f)row.orientation=LinearLayout.VERTICAL
        labels.forEachIndexed{i,label->row.addView(ui.button(label){if(!busy)onSelected(i)}.apply{
            background=ui.touch(if(i==selected)R.color.card else R.color.surface_container,12);setTextColor(getColor(if(i==selected)R.color.ink else R.color.muted));isSelected=i==selected;minHeight=dp(48);setPadding(dp(4),dp(8),dp(4),dp(8))
        },LinearLayout.LayoutParams(if(row.orientation==LinearLayout.HORIZONTAL)0 else -1,-2,if(row.orientation==LinearLayout.HORIZONTAL)1f else 0f))}
        parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(12)})
    }
    private fun analytics(data:JSONObject) {
        segments(content,listOf("Pengeluaran","Pemasukan"),if(analyticType=="expense")0 else 1){analyticType=if(it==0)"expense" else "income";content.removeAllViews();analytics(data)}
        val summary=data.getJSONObject("Summary");val total=summary.getLong(if(analyticType=="expense")"Expense" else "Income")
        val card=ui.column(R.color.card,20)
        addLabel(card,"Total "+(if(analyticType=="expense")"pengeluaran" else "pemasukan")+" · "+compactPeriod(),13f,R.color.muted)
        addLabel(card,rupiah(total),30f,R.color.ink,true)
        if(analyticType=="expense")addLabel(card,if(summary.getLong("Income")>0)"Setara "+percent(total,summary.getLong("Income"))+" dari pemasukan periode ini" else "Belum ada pemasukan sebagai pembanding",13f,R.color.muted)
        addBlock(card,4);section("Per kategori")
        val source=data.getJSONArray("Breakdown");val groups=(0 until source.length()).map{source.getJSONObject(it)}.filter{it.getString("Type")==analyticType}.sortedByDescending{it.getLong("Amount")}
        categoryPanel(groups,total,true)
        if(groups.isNotEmpty()){val top=groups.first();infoPanel(top.getString("Name")+" menyumbang "+percent(top.getLong("Amount"),total)+" dari "+(if(analyticType=="expense")"pengeluaran" else "pemasukan")+" tercatat pada periode ini.")}
        button("Unduh laporan PDF"){pdfOptions()}.apply{setCompoundDrawables(ui.icon(R.drawable.ic_download,R.color.ink,18),null,null,null)}
    }

    private fun periodDialog() {
        AlertDialog.Builder(this).setTitle("Periode laporan")
            .setItems(arrayOf("Hari ini","Minggu ini","Bulan ini","12 bulan","Rentang tanggal","Seluruh data tersedia")) { _,which ->
                val today=LocalDate.now()
                if(which==4) { rangeForm(false); return@setItems }
                filter.remove("From"); filter.remove("To")
                val start=when(which){0->today;1->today.minusDays((today.dayOfWeek.value-1).toLong());2->today.withDayOfMonth(1);3->today.minusMonths(12).plusDays(1);else->null}
                if(start!=null) filter.put("From",start.toString()).put("To",today.toString())
                // Explicit broad range lets the core clip to its retention window.
                if(which==5) filter.put("From",retentionStart.toString()).put("To",today.toString())
                pageOffset=0; refresh()
            }.setNegativeButton("Batal",null).show().also{trackDialog(it)}
    }
    private fun chipRow(parent: LinearLayout, options: List<Pair<String, Boolean>>, action: (Int) -> Unit) {
        val row = ui.row()
        options.forEachIndexed { index, (label, selected) ->
            row.addView(ui.button(label, selected) { if (!busy) action(index) }.apply {
                minHeight = dp(48); background = ui.touch(if (selected) R.color.balance_surface else R.color.card, 24, if (selected) R.color.primary else R.color.outline)
                setTextColor(getColor(if(selected)R.color.primary else R.color.ink));isSelected=selected
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun sheet(heading: String, build: (LinearLayout, Dialog) -> Unit) {
        activeSheet?.dismiss()
        val dialog = Dialog(this)
        activeSheet = dialog
        dialog.setOnDismissListener { if (activeSheet === dialog) activeSheet = null }
        val panel = ui.column(R.color.card, 20)
        panel.addView(View(this).apply { background = ui.shape(R.color.outline, 3) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })
        addLabel(panel, heading, 22f, R.color.ink, true).isAccessibilityHeading = true
        build(panel, dialog)
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(panel) }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        trackDialog(dialog); dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .84).toInt())
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            panel.setPadding(dp(20) + bars.left, dp(20), dp(20) + bars.right, dp(20) + bars.bottom)
            insets
        }
        scroll.requestApplyInsets()
    }
    private fun history(data: JSONObject) {
        val search=EditText(this).apply{
            setText(filter.optString("Search"));hint="Cari catatan transaksi";textSize=15f;minHeight=dp(52);setSingleLine(true)
            imeOptions=EditorInfo.IME_ACTION_SEARCH;inputType=InputType.TYPE_CLASS_TEXT
            background=ui.shape(R.color.input_surface,16,R.color.outline);setTextColor(getColor(R.color.ink));setHintTextColor(getColor(R.color.muted))
            setPadding(dp(16),dp(10),dp(12),dp(10));compoundDrawablePadding=dp(10);setCompoundDrawables(ui.icon(R.drawable.ic_search,R.color.muted),null,null,null)
        }
        fun searchNow(){filter.put("Search",search.text.toString());pageOffset=0;refresh()}
        search.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH&&!busy){searchNow();true}else false}
        addBlock(search,4)
        segments(content,listOf("Semua","Keluar","Masuk"),when(filter.optString("Type")){"expense"->1;"income"->2;else->0}) {
            filter.put("Type",when(it){1->"expense";2->"income";else->""});filter.remove("CategoryID");pageOffset=0;refresh()
        }
        val summary=data.getJSONObject("Summary")
        val totals=ui.row();if(resources.configuration.fontScale>1.2f)totals.orientation=LinearLayout.VERTICAL
        listOf("Pemasukan" to "Income","Pengeluaran" to "Expense").forEachIndexed{i,(label,key)->
            val tile=ui.column(R.color.card,14);addLabel(tile,label,12f,R.color.muted);addLabel(tile,rupiah(summary.getLong(key)),16f,if(i==0)R.color.primary else R.color.ink,true)
            totals.addView(tile,LinearLayout.LayoutParams(if(totals.orientation==LinearLayout.HORIZONTAL)0 else -1,-2,if(totals.orientation==LinearLayout.HORIZONTAL)1f else 0f).apply{if(i==1){marginStart=dp(6);topMargin=dp(2)}})
        };addBlock(totals,0)
        sectionLink(data.getInt("Total").toString()+" transaksi","Filter"){filterForm()}
        if(filter.optLong("CategoryID")!=0L)text("Kategori: "+categoryName(filter.getLong("CategoryID")),13f,R.color.muted)
        text(periodDescription(),12f,R.color.muted)
        val rows=data.getJSONArray("Items")
        if(rows.length()==0)infoPanel("Belum ada transaksi yang cocok. Ketuk + untuk mencatat atau ubah filter.")
        else {
            var day="";var group:LinearLayout?=null
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                if(day!=row.getString("Date")){
                    day=row.getString("Date")
                    text(runCatching{LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, d MMM",Locale.forLanguageTag("id-ID")))}.getOrDefault(day),14f).apply{typeface=android.graphics.Typeface.DEFAULT_BOLD}
                    group=ui.column(R.color.card);addBlock(group,4)
                }
                transactionRow(requireNotNull(group),row)
            }
            text("Ketuk transaksi untuk melihat, mengubah, atau menghapus.",12f,R.color.muted)
        }
        if(pageOffset>0)button("Sebelumnya"){pageOffset=maxOf(0,pageOffset-20);refresh()}
        if(pageOffset+rows.length()<data.getInt("Total"))button("Berikutnya"){pageOffset+=20;refresh()}
    }

    private fun transactionDetails(row: JSONObject) {
        sheet(row.optString("Note").ifBlank{categoryName(row.getLong("CategoryID"))}) { panel,dialog ->
            val income=row.getString("Type")=="income"
            addLabel(panel,(if(income)"+" else "−")+rupiah(row.getLong("Amount")),30f,if(income)R.color.primary else R.color.ink,true)
            for((label,value) in listOf("Jenis" to if(income)"Pemasukan" else "Pengeluaran","Kategori" to categoryName(row.getLong("CategoryID")),"Tanggal" to row.getString("Date"),"Catatan" to row.optString("Note").ifBlank{"Tanpa catatan"})){
                addLabel(panel,label,12f,R.color.muted);addLabel(panel,value,16f)
            }
            val actions=ui.row()
            actions.addView(ui.button("Hapus",danger=true){dialog.dismiss();previewDelete(JSONObject().put("Kind","one").put("ID",row.getString("ID")))},LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(ui.button("Ubah",true){dialog.dismiss();draft=JSONObject(row.toString());refresh()},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
            panel.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
        }
    }

    private fun categoryName(id: Long): String { for(i in 0 until categories.length()){val c=categories.getJSONObject(i);if(c.getLong("ID")==id)return c.getString("Name")};return "Kategori" }
    private fun transactionForm() {
        val data=draft?:return
        var kind=data.optString("Type","expense")
        var selectedCategory=data.optLong("CategoryID")
        segments(content,listOf("Pengeluaran","Pemasukan"),if(kind=="expense")0 else 1){
            captureDraft?.invoke();data.put("Type",if(it==0)"expense" else "income").put("CategoryID",0);content.removeAllViews();transactionForm()
        }
        text("Nominal (Rp)",14f,R.color.muted).gravity=Gravity.CENTER
        val amount=EditText(this).apply{
            inputType=InputType.TYPE_CLASS_NUMBER;setText(data.optString("Amount").takeIf{it!="0"}?:"");hint="0";textSize=40f;gravity=Gravity.CENTER
            setTextColor(getColor(R.color.ink));setHintTextColor(getColor(R.color.muted));background=null;minHeight=dp(64)
            filters=arrayOf(android.text.InputFilter.LengthFilter(13));showSoftInputOnFocus=false;isSaveEnabled=false;contentDescription="Nominal dalam rupiah";setSelection(text.length)
        };content.addView(amount,LinearLayout.LayoutParams(-1,-2))
        text("Kategori",14f,R.color.muted)
        val choices=(0 until categories.length()).map{categories.getJSONObject(it)}.filter{it.getString("Type")==kind&&it.getBoolean("Active")}
        if(choices.none{it.getLong("ID")==selectedCategory})selectedCategory=choices.firstOrNull()?.getLong("ID")?:0
        val chips=ui.column();content.addView(chips)
        fun drawCategories(){
            chips.removeAllViews()
            val flow=ChipFlow(this)
            choices.forEach{category->
                val selected=category.getLong("ID")==selectedCategory
                flow.addView(ui.button(category.getString("Name")){selectedCategory=category.getLong("ID");drawCategories()}.apply{
                    minHeight=dp(44);background=ui.touch(if(selected)R.color.balance_surface else R.color.card,24,if(selected)R.color.primary else R.color.outline)
                    val dot=ui.shape(categoryColor(category.getString("Name")),8).apply{setBounds(0,0,dp(8),dp(8))}
                    setCompoundDrawables(dot,null,null,null);compoundDrawablePadding=dp(8);isSelected=selected
                })
            }
            flow.addView(ui.button("Baru"){captureDraft?.invoke();categoryForm(null)}.apply{
                minHeight=dp(44);background=ui.touch(R.color.surface,24,R.color.outline)
                setCompoundDrawables(ui.icon(R.drawable.ic_add,R.color.ink,16),null,null,null);compoundDrawablePadding=dp(6)
            });chips.addView(flow)
        };drawCategories()
        val details=ui.row()
        val note=EditText(this).apply{
            setText(data.optString("Note"));hint="Catatan (opsional)";contentDescription="Catatan transaksi, opsional";textSize=14f;minHeight=dp(52)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;setTextColor(getColor(R.color.ink));setHintTextColor(getColor(R.color.muted))
            background=ui.shape(R.color.card,14,R.color.outline);setPadding(dp(12),dp(10),dp(12),dp(10))
            filters=arrayOf(android.text.InputFilter.LengthFilter(255));maxLines=3;isSaveEnabled=false
        }
        val dateContainer=ui.column()
        val date=dateField(dateContainer,"Tanggal",data.optString("Date",LocalDate.now().toString()))
        dateContainer.removeAllViews()
        details.addView(note,LinearLayout.LayoutParams(0,-2,1f))
        date.text=relativeDate(data.optString("Date",LocalDate.now().toString()))
        details.addView(date,LinearLayout.LayoutParams(dp(110),-2).apply{marginStart=dp(8)})
        if(resources.configuration.fontScale>1.2f){details.orientation=LinearLayout.VERTICAL;note.layoutParams=LinearLayout.LayoutParams(-1,-2);date.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)}}
        addBlock(details,16)
        captureDraft={data.put("Type",kind).put("CategoryID",selectedCategory).put("Amount",amount.text.toString()).put("Note",note.text.toString()).put("Date",date.tag.toString())}
        content.addView(View(this),LinearLayout.LayoutParams(-1,dp(24),1f))
        val keypad=ui.column()
        val keys=listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("000","0","Hapus"))
        keys.forEach{values->
            val row=ui.row()
            values.forEachIndexed{i,key->row.addView(ui.button(key){
                if(!busy){
                    val old=amount.text.toString()
                    val start=amount.selectionStart.coerceAtLeast(0);val end=amount.selectionEnd.coerceAtLeast(start)
                    val next=if(key=="Hapus"){if(end>start)old.removeRange(start,end) else if(start>0)old.removeRange(start-1,start)else old}else old.replaceRange(start,end,key).trimStart('0').ifBlank{"0"}
                    if(next.length<=13){amount.setText(next);amount.setSelection(if(key=="Hapus")(start-if(end==start)1 else 0).coerceIn(0,next.length)else (start+key.length).coerceAtMost(next.length))}
                }
            }.apply{textSize=if(key=="Hapus")14f else 24f;minHeight=dp(54);background=ui.touch(R.color.card,16);if(key=="Hapus"){text="";contentDescription="Hapus digit terakhir";foreground=ui.icon(R.drawable.ic_backspace,R.color.ink,22);foregroundGravity=Gravity.CENTER}},
                LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)marginStart=dp(8)})}
            keypad.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        };addBlock(keypad,12)
        val save=button("Simpan transaksi",true) {
            captureDraft?.invoke();val nominal=data.optString("Amount").toLongOrNull()
            if(nominal==null||nominal<=0){amount.error="Isi nominal lebih dari nol.";return@button}
            if(selectedCategory==0L){status.text="Tambahkan kategori terlebih dahulu.";return@button}
            val payload=JSONObject(data.toString());payload.remove("ID");payload.remove("CreatedAt");payload.remove("UpdatedAt");payload.put("Amount",nominal)
            work("Menyimpan transaksi…",{app.core.call("saveTransaction",JSONObject().put("TransactionID",data.optString("ID")).put("Input",payload))}){draft=null;captureDraft=null;refresh()}
        }
        fun updateSave(){save.isEnabled=(amount.text.toString().toLongOrNull()?:0)>0&&selectedCategory!=0L;save.alpha=if(save.isEnabled)1f else .5f;save.text=if((amount.text.toString().toLongOrNull()?:0)<=0)"Masukkan nominal dulu" else "Simpan transaksi"}
        amount.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateSave()};override fun afterTextChanged(s:Editable?)=Unit})
        updateSave()
        note.setOnFocusChangeListener{_,focused->keypad.visibility=if(focused)View.GONE else View.VISIBLE}
        amount.setOnFocusChangeListener{_,focused->if(focused){keypad.visibility=View.VISIBLE;(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(amount.windowToken,0)}}
    }

    private fun discardDraft(destination: String? = null){AlertDialog.Builder(this).setTitle("Batalkan perubahan?").setMessage("Isian yang belum disimpan akan hilang.").setNegativeButton("Lanjut mengisi",null).setPositiveButton("Batalkan"){_,_->draft=null;captureDraft=null;if(destination!=null){screen=destination;pageOffset=0};refresh()}.show().also{trackDialog(it)}}
    private var menuParent:LinearLayout?=null
    private fun infoPanel(message:String) {
        val panel=ui.column(R.color.balance_surface,16)
        addLabel(panel,message,14f,R.color.primary)
        addBlock(panel,16)
    }
    private fun settingsGroup(label:String,build:()->Unit) {
        text(label,13f,R.color.muted).setPadding(dp(4),dp(22),0,dp(8))
        val group=ui.column(R.color.card,4);addBlock(group,0)
        menuParent=group
        try{build()}finally{menuParent=null}
    }
    private fun menuRow(label: String, description: String? = null, icon: Int, danger: Boolean = false, action: () -> Unit) {
        val row=ui.row().apply{
            minimumHeight=dp(60);setPadding(dp(14),dp(14),dp(14),dp(14));background=ui.touch(R.color.card,18)
            isFocusable=true;setOnClickListener{if(!busy)action()}
        }
        row.addView(TextView(this).apply{setCompoundDrawables(ui.icon(icon,if(danger)R.color.expense else R.color.ink),null,null,null);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(32),-2))
        val labels=ui.column();addLabel(labels,label,16f,if(danger)R.color.expense else R.color.ink)
        if(!description.isNullOrBlank())addLabel(labels,description,12f,R.color.muted)
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(TextView(this).apply{setCompoundDrawables(ui.icon(R.drawable.ic_chevron,R.color.muted,18),null,null,null)},LinearLayout.LayoutParams(dp(20),-2))
        val target=menuParent
        if(target!=null){if(target.childCount>0)target.addView(View(this).apply{setBackgroundColor(getColor(R.color.surface_container))},LinearLayout.LayoutParams(-1,dp(1)).apply{marginStart=dp(16);marginEnd=dp(16)});target.addView(row)}else addBlock(row,8)
    }
    private fun manage(window: JSONObject) {
        val name=profile.optString("Name").ifBlank{"Pemilik"}
        val profileRow=ui.row().apply{background=ui.touch(R.color.card,22);setPadding(dp(16),dp(16),dp(16),dp(16));isFocusable=true;setOnClickListener{if(!busy)profilePage()}}
        val avatar=ui.label(name.take(1).uppercase(),22f).apply{gravity=Gravity.CENTER;background=ui.shape(avatarColors[profile.optInt("Color").coerceIn(0,7)],28);setTextColor(avatarInk(profile.optInt("Color")))}
        profileRow.addView(avatar,LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(14)})
        val labels=ui.column();addLabel(labels,name,18f,R.color.ink,true);addLabel(labels,"Ubah nama, warna, dan tampilan",12f,R.color.muted)
        profileRow.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        addBlock(profileRow,4)
        settingsGroup("Data"){
            menuRow("Kategori",categories.length().toString()+" kategori",R.drawable.ic_category){categoryList()}
            menuRow("Ekspor ke CSV",periodDescription(),R.drawable.ic_download){stepUp{exportCSV()}}
            menuRow("Laporan PDF","Ringkasan dan analisis keuangan",R.drawable.ic_receipt){pdfOptions()}
        }
        settingsGroup("Asisten AI"){
            menuRow("Konfigurasi LLM","Gemini, OpenAI, atau Claude",R.drawable.ic_ai){stepUp(true){llmPage()}}
        }
        settingsGroup("Keamanan"){menuRow("PIN & kunci otomatis",icon=R.drawable.ic_lock){securityPage()}}
        settingsGroup("Hapus data"){
            menuRow("Hapus berdasarkan tanggal",icon=R.drawable.ic_calendar,danger=true){rangeForm(true)}
            menuRow("Hapus semua transaksi",icon=R.drawable.ic_warning,danger=true){stepUp(true){previewDelete(JSONObject().put("Kind","all"))}}
        }
        text("Data tersimpan di HP ini, tanpa akun. Riwayat tersedia sejak "+window.getString("Start")+". Kategori tidak ikut terhapus saat menghapus transaksi. AI hanya mengirim agregat saat ekspor diaktifkan.",12f,R.color.muted)
        val brand=ui.row().apply{gravity=Gravity.CENTER}
        brand.addView(ImageView(this).apply{setImageResource(R.drawable.brand_mark);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(56),dp(56)))
        brand.addView(ui.label(getString(R.string.brand_identity),13f,R.color.muted))
        addBlock(brand,16)
    }

    private fun previewDelete(scope: JSONObject) {
        work("Menghitung transaksi…",{app.core.call("previewDelete",JSONObject().put("Scope",scope))}) { raw ->
            val preview=raw as JSONObject
            AlertDialog.Builder(this).setTitle("Hapus ${preview.getInt("Count")} transaksi?")
                .setMessage("Penghapusan tidak dapat dibatalkan. Kategori tetap tersedia.")
                .setNegativeButton("Batal",null).setPositiveButton("Hapus"){_,_->
                    work("Menghapus…",{app.core.call("deleteHistory",JSONObject().put("Preview",preview).put("Confirmed",true))}){pageOffset=0;refresh()}
                }.show().also{trackDialog(it)}
        }
    }
    private fun categoryList() {
        content.removeAllViews(); pageTitle("Kategori", true)
        button("Tambah kategori", true) { categoryForm(null) }
        listOf("income", "expense").forEach { type ->
            section(if (type == "income") "Pemasukan" else "Pengeluaran")
            for (i in 0 until categories.length()) {
                val c = categories.getJSONObject(i)
                if (c.getString("Type") == type) menuRow(c.getString("Name"), if (c.getBoolean("Active")) "Aktif" else "Nonaktif", R.drawable.ic_category) { categoryForm(c) }
            }
        }
        button("Kembali") { refresh() }
    }
    private fun categoryForm(category: JSONObject?) {
        content.removeAllViews();pageTitle(if(category==null)"Tambah kategori" else "Ubah kategori", true)
        val name=field("Nama kategori",category?.getString("Name")?:"")
        val kind=styledSpinner().apply{minimumHeight=dp(48);adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Pengeluaran","Pemasukan"));setSelection(if((category?.optString("Type")?:draft?.optString("Type"))=="income")1 else 0);isEnabled=category==null};content.addView(kind)
        val active=styledCheckBox().apply{text="Kategori aktif";isChecked=category?.getBoolean("Active")?:true;minHeight=dp(48)};content.addView(active)
        button("Simpan kategori",true){
            val payload=JSONObject().put("ID",category?.optLong("ID")?:0).put("Name",name.text.toString())
                .put("Type",if(kind.selectedItemPosition==1)"income" else "expense").put("Active",active.isChecked)
            work("Menyimpan kategori…",{app.core.call("saveCategory",payload)}){saved->
                if(draft!=null&&saved is JSONObject&&saved.optString("Type")==draft?.optString("Type"))draft?.put("CategoryID",saved.optLong("ID"))
                refresh()
            }
        }
        if (category != null) button("Hapus / nonaktifkan kategori") {
            AlertDialog.Builder(this).setTitle("Hapus kategori?")
                .setMessage("Kategori yang masih dipakai akan dinonaktifkan. Kategori yang tidak dipakai akan dihapus.")
                .setNegativeButton("Batal", null).setPositiveButton("Lanjutkan") { _, _ ->
                    work("Memperbarui kategori…", { app.core.call("deleteCategory", JSONObject().put("ID", category.getLong("ID"))) }) { refresh() }
                }.show().also{trackDialog(it)}
        }
        button("Batal"){if(draft!=null)refresh()else categoryList()}
    }
    private fun dateField(parent: LinearLayout, label: String, value: String): Button {
        addLabel(parent, label, 13f, R.color.muted)
        val field = ui.button(value.ifEmpty { "Pilih tanggal" }) { }
        field.tag = value
        field.background = ui.touch(R.color.input_surface, 12, R.color.outline)
        field.setCompoundDrawables(ui.icon(R.drawable.ic_calendar, R.color.muted, 18), null, null, null)
        field.compoundDrawablePadding = dp(8)
        field.setOnClickListener {
            if (!busy) {
                val selected = runCatching { LocalDate.parse(field.tag.toString()) }.getOrDefault(LocalDate.now())
                val picker = DatePickerDialog(this, { _, y, m, d ->
                    val date = LocalDate.of(y, m + 1, d).toString(); field.tag = date; field.text = if(draft!=null)relativeDate(date) else date
                }, selected.year, selected.monthValue - 1, selected.dayOfMonth)
                val zone = java.time.ZoneId.systemDefault()
                picker.datePicker.minDate = retentionStart.atStartOfDay(zone).toInstant().toEpochMilli()
                picker.datePicker.maxDate = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
                trackDialog(picker); picker.show()
            }
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        return field
    }
    private fun rangeForm(delete: Boolean) {
        content.removeAllViews(); pageTitle(if (delete) "Rentang penghapusan" else "Rentang laporan", true)
        if (delete) {
            val warning = ui.column(R.color.danger_surface, 16)
            addLabel(warning, "Transaksi pada rentang ini akan dihapus dari HP Anda. Anda bisa meninjau jumlahnya sebelum menghapus.", 14f, R.color.expense)
            addBlock(warning, 4)
        }
        val panel = ui.column(R.color.card, 16)
        val from = dateField(panel, "Dari", LocalDate.now().withDayOfMonth(1).toString())
        val to = dateField(panel, "Sampai", LocalDate.now().toString())
        addLabel(panel, "Pilih cepat", 13f, R.color.muted)
        chipRow(panel, listOf("Bulan ini" to false, "3 bulan" to false, "Tahun ini" to false)) { index ->
            val today = LocalDate.now()
            val start = when (index) { 0 -> today.withDayOfMonth(1); 1 -> today.minusMonths(3).plusDays(1); else -> today.withDayOfYear(1) }
            from.tag = maxOf(start, retentionStart).toString(); from.text = from.tag.toString()
            to.tag = today.toString(); to.text = to.tag.toString()
        }
        addBlock(panel)
        button(if (delete) "Tinjau penghapusan" else "Terapkan periode", true) {
            val start = from.tag.toString(); val end = to.tag.toString()
            if (start > end) { status.text = "Tanggal Dari tidak boleh melewati Sampai."; return@button }
            if (delete) previewDelete(JSONObject().put("Kind", "range").put("From", start).put("To", end))
            else { filter.put("From", start).put("To", end); pageOffset = 0; subpage = false; refresh() }
        }
        button("Batal") { subpage = false; refresh() }
    }
    private fun filterForm() {
        sheet("Filter riwayat") { panel, dialog ->
            val period = queryFilter()
            val from = dateField(panel, "Dari", period.optString("From"))
            val to = dateField(panel, "Sampai", period.optString("To"))
            val min = input(panel, "Nominal minimum (opsional)", filter.optLong("MinAmount").takeIf { it > 0 }?.toString() ?: "", true)
            val max = input(panel, "Nominal maksimum (opsional)", filter.optLong("MaxAmount").takeIf { it > 0 }?.toString() ?: "", true)
            addLabel(panel, "Jenis transaksi", 14f, R.color.muted).setPadding(0, dp(16), 0, 0)
            val types = styledSpinner().apply {
                minimumHeight = dp(52); contentDescription = "Jenis transaksi"
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Semua jenis", "Pemasukan", "Pengeluaran"))
                setSelection(when (filter.optString("Type")) { "income" -> 1; "expense" -> 2; else -> 0 })
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.outline))
            }
            panel.addView(types, LinearLayout.LayoutParams(-1, -2))
            addLabel(panel, "Kategori", 14f, R.color.muted).setPadding(0, dp(16), 0, 0)
            val cats = styledSpinner().apply {
                minimumHeight = dp(52); contentDescription = "Filter kategori"
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Semua kategori") + (0 until categories.length()).map { categories.getJSONObject(it).let { c -> "${c.getString("Name")} · ${if (c.getString("Type") == "income") "Pemasukan" else "Pengeluaran"}" } })
                val selected = (0 until categories.length()).firstOrNull { categories.getJSONObject(it).getLong("ID") == filter.optLong("CategoryID") }
                setSelection(selected?.plus(1) ?: 0)
            }
            panel.addView(cats, LinearLayout.LayoutParams(-1, -2))
            panel.addView(ui.button("Terapkan filter", true) {
                val minValue = if (min.text.isBlank()) 0L else min.text.toString().toLongOrNull()
                val maxValue = if (max.text.isBlank()) 0L else max.text.toString().toLongOrNull()
                if (minValue == null) { min.error = "Nominal tidak sah."; return@button }
                if (maxValue == null || (maxValue != 0L && maxValue < minValue)) { max.error = "Maksimum harus lebih besar atau sama dengan minimum."; return@button }
                if (from.tag.toString() > to.tag.toString()) { from.error = "Tanggal Dari tidak boleh melewati Sampai."; return@button }
                filter.put("From", from.tag.toString()).put("To", to.tag.toString()).put("MinAmount", minValue).put("MaxAmount", maxValue)
                    .put("Type", when (types.selectedItemPosition) { 1 -> "income"; 2 -> "expense"; else -> "" })
                    .put("CategoryID", if (cats.selectedItemPosition == 0) 0 else categories.getJSONObject(cats.selectedItemPosition - 1).getLong("ID"))
                pageOffset = 0; dialog.dismiss(); refresh()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            panel.addView(ui.button("Reset filter") { filter = JSONObject(); pageOffset = 0; dialog.dismiss(); refresh() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            panel.addView(ui.button("Batal") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }
    private fun openSession() {
        if (busy) return
        purgePending=false
        work("Menyiapkan penyimpanan privat…", {
            app.core.initialize(); app.security.load()
        }) {
            lastPurgedDay=LocalDate.now()
            if (app.security.screenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (!app.security.configured) setupPin(true)
            else if (!app.security.unlocked) showLock()
            else loadProfileAndRefresh()
        }
    }
    private fun loadProfileAndRefresh() {
        work("Memuat preferensi…", {
            val profile=app.profile.load()
            val summary=app.core.call("summary") as JSONObject
            val count=summary.getInt("Count")
            profile.put("Count",count)
            if(count>0){
                val page=app.core.call("listTransactions",JSONObject().put("Filter",JSONObject().put("Offset",count-1).put("Limit",1))) as JSONObject
                profile.put("Oldest",page.getJSONArray("Items").getJSONObject(0).getString("Date"))
            }
            profile
        }) {
            profile=it as JSONObject
            if (!profileLoaded && !filter.has("From")) applyDefaultPeriod(profile.optString("DashboardPeriod","month"))
            profileLoaded=true;refresh(true)
        }
    }
    private fun applyDefaultPeriod(value:String) {
        val today=LocalDate.now()
        val start=when(value){"day"->today;"week"->today.minusDays(6);"year12"->today.minusMonths(12).plusDays(1);else->today.withDayOfMonth(1)}
        filter=JSONObject().put("From",start.toString()).put("To",today.toString())
    }
    private fun authPage(titleText:String) {
        content.removeAllViews(); status.text=""; pageTitle(titleText,true)
        navigation.visibility=View.GONE; findViewById<View>(R.id.back).visibility=View.GONE
        val mark=ImageView(this).apply{setImageResource(R.drawable.brand_mark);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        content.addView(mark,LinearLayout.LayoutParams(dp(132),dp(100)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        content.addView(ui.label(getString(R.string.brand_identity),18f,R.color.ink,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})
    }
    private fun secretField(label:String,numeric:Boolean=true):EditText = field(label,"",numeric).apply {
        inputType=if(numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSaveEnabled=false; importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        filters=arrayOf(android.text.InputFilter.LengthFilter(if(numeric)6 else 64))
        isLongClickable=false;setTextIsSelectable(false)
    }
    private fun showLock(recovery:Boolean=false, after:(()->Unit)?=null, allowBiometric:Boolean=true) {
        authPage(if(recovery)"Pulihkan PIN" else "Buka kunci")
        val pin=secretField(if(recovery)"Recovery key" else "PIN 6 digit",!recovery)
        button("Lanjutkan",true) {
            val secret=pin.text.toString().toCharArray();pin.text.clear()
            work("Memeriksa kode…",{try {app.security.verify(secret,recovery)}finally{secret.fill(' ')}}) {
                if(recovery) setupPin(false) else {app.security.unlock();if(after!=null)after()else loadProfileAndRefresh()}
            }
        }
        if(!recovery && after==null) button("Lupa PIN") {showLock(true)}
        if(!recovery && allowBiometric && app.security.biometricEnabled) button("Gunakan biometrik") { biometric(false){if(after!=null)after()else loadProfileAndRefresh()} }
        if(recovery) {
            text("Jika recovery key juga hilang, reset akan menghapus seluruh data aplikasi.",14f,R.color.muted)
            button("Reset aplikasi") {resetApplication()}
            button("Kembali ke PIN") {showLock()}
        }
    }
    private fun setupPin(first:Boolean) {
        authPage(if(first)"Amankan catatan Anda" else "Atur PIN baru")
        text("PIN melindungi akses aplikasi. Database lokal belum dienkripsi oleh aplikasi.",14f,R.color.muted)
        val pin=secretField("PIN 6 digit")
        val confirm=secretField("Ulangi PIN")
        button("Simpan PIN",true) {
            if(pin.text.toString()!=confirm.text.toString()){status.text="PIN belum sama.";return@button}
            val secret=pin.text.toString().toCharArray();pin.text.clear();confirm.text.clear()
            work("Menyiapkan keamanan lokal…",{try{app.security.setPin(secret);if(first)app.security.recoveryKey()else null}finally{secret.fill(' ')}}) { key ->
                if(key!=null) showRecovery(key as String) else {app.security.unlock();loadProfileAndRefresh()}
            }
        }
    }
    private fun showRecovery(key:String) {
        authPage("Simpan recovery key")
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        text("Catat kode ini di tempat aman. Ditampilkan sekali; jika PIN dan kode hilang, data harus direset.")
        text(key,20f).apply{isSaveEnabled=false;isLongClickable=false;setTextIsSelectable(false)}
        button("Salin recovery key") {
            val clipboard=getSystemService(android.content.ClipboardManager::class.java)
            val clip=android.content.ClipData.newPlainText("Recovery key duetku",key)
            clip.description.extras=android.os.PersistableBundle().apply{putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)}
            clipboard.setPrimaryClip(clip)
            status.text="Recovery key disalin. Tempel ke tempat penyimpanan aman."
            android.os.Handler(mainLooper).postDelayed({
                if(clipboard.primaryClip?.getItemAt(0)?.text?.toString()==key)clipboard.clearPrimaryClip()
            },60_000)
        }
        text("Salinan ditandai sensitif dan dibersihkan setelah 1 menit jika layar aplikasi masih aktif. Aplikasi tempat Anda menempel dapat menyimpan salinannya.",13f,R.color.muted)
        val saved=styledCheckBox().apply{text="Saya sudah menyimpan recovery key";minHeight=dp(48)};content.addView(saved)
        button("Selesai",true){if(!saved.isChecked){status.text="Konfirmasikan penyimpanan recovery key."}else{app.security.unlock();loadProfileAndRefresh()}}
    }
    private fun stepUp(always:Boolean=false,action:()->Unit) {
        if(!always && app.security.recent()) action() else showLock(after=action)
    }
    private fun resetApplication() {
        authPage("Reset aplikasi")
        text("Seluruh transaksi, kategori, profil, PIN dan recovery key akan dihapus. Tidak dapat dibatalkan.")
        val confirm=field("Ketik KONFIRMASI","")
        button("Hapus semua data aplikasi") {
            if(confirm.text.toString()!="KONFIRMASI"){status.text="Ketik KONFIRMASI dengan tepat.";return@button}
            AlertDialog.Builder(this).setTitle("Reset seluruh aplikasi?").setMessage("Semua data lokal akan hilang permanen.")
                .setNegativeButton("Batal",null).setPositiveButton("Reset"){_,_->
                    work("Mereset…",{app.security.resetKeys()}) {
                        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
                    }
                }.show().also{trackDialog(it)}
        }
    }
    private fun securityPage() {
        content.removeAllViews();pageTitle("Keamanan",true)
        infoPanel("PIN dan biometrik melindungi akses aplikasi di HP ini.")
        section("Kunci otomatis")
        text("Kunci setelah meninggalkan aplikasi",13f,R.color.muted)
        val seconds=listOf(0,30,60,300)
        val choice=styledSpinner().apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Langsung","30 detik","1 menit","5 menit"));setSelection(seconds.indexOf(app.security.autoLock))};content.addView(choice)
        val screenshots=styledCheckBox().apply{text="Izinkan tangkapan layar";isChecked=app.security.screenshots};content.addView(screenshots)
        val wipe=styledCheckBox().apply{text="Reset semua data setelah 15 kode salah";isChecked=app.security.wipeAfterFailures};content.addView(wipe)
        text("Opsi reset otomatis dapat menghilangkan seluruh transaksi, profil, dan kunci secara permanen. Default mati.",13f,R.color.expense)
        button("Simpan pengaturan",true){
            val selectedSeconds=seconds[choice.selectedItemPosition];val allow=screenshots.isChecked;val enableWipe=wipe.isChecked
            fun savePreferences(){work("Menyimpan…",{app.security.preferences(selectedSeconds,allow,enableWipe)}){openSession()}}
            if(enableWipe&&!app.security.wipeAfterFailures)AlertDialog.Builder(this).setTitle("Aktifkan reset otomatis?").setMessage("Setelah 15 kode salah, seluruh data aplikasi dihapus tanpa dapat dipulihkan. Orang lain yang mencoba PIN juga bisa memicu penghapusan ini.").setNegativeButton("Batal",null).setPositiveButton("Aktifkan"){_,_->savePreferences()}.show().also{trackDialog(it)}
            else savePreferences()
        }
        button("Ubah PIN"){showLock(after={setupPin(false)},allowBiometric=false)}
        button("Buat ulang recovery key"){stepUp(true){work("Membuat kode baru…",{app.security.recoveryKey()}){showRecovery(it as String)}}}
        button("Kunci sekarang"){app.security.lock();showLock()}
        button("Aktifkan / perbarui biometrik"){showLock(after={biometric(true){securityPage()}})}
        if(app.security.biometricEnabled)button("Nonaktifkan biometrik"){work("Menonaktifkan biometrik…",{app.security.disableBiometric()}){securityPage()}}
    }
    private fun biometric(create:Boolean,done:()->Unit) {
        work("Menyiapkan biometrik…",{
            check(app.security.remainingLockSeconds()==0L){"Tunggu jeda percobaan sebelum menggunakan biometrik."}
            app.security.biometricCipher(create)
        }) { raw ->
            val cancellation=android.os.CancellationSignal();biometricCancellation=cancellation
            val prompt=android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle(if(create)"Aktifkan biometrik" else "Buka kunci duetku")
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Gunakan PIN",mainExecutor){_,_->status.text="Masukkan PIN untuk melanjutkan."}.build()
            prompt.authenticate(android.hardware.biometrics.BiometricPrompt.CryptoObject(raw as javax.crypto.Cipher),cancellation,mainExecutor,
                object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    override fun onAuthenticationError(code:Int,message:CharSequence){if(visible)status.text="Biometrik tidak tersedia atau dibatalkan. Gunakan PIN."}
                    override fun onAuthenticationFailed(){if(visible)status.text="Biometrik belum cocok. Coba lagi atau gunakan PIN."}
                    override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult){
                        if(!visible)return
                        work("Memverifikasi biometrik…",{app.security.finishBiometric(requireNotNull(result.cryptoObject?.cipher),create)}){app.security.unlock();done()}
                    }
                })
        }
    }
    private val avatarColors get()=intArrayOf(R.color.avatar,R.color.chart_second,R.color.chart_fourth,R.color.primary,R.color.chart_third,R.color.expense,R.color.muted,R.color.avatar_eighth)
    private fun avatarInk(index:Int):Int {
        val color=getColor(avatarColors[index.coerceIn(0,7)])
        fun linear(c:Int):Double {val v=c/255.0;return if(v<=.04045)v/12.92 else Math.pow((v+.055)/1.055,2.4)}
        val luminance=.2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))
        return if(luminance>.179)Color.BLACK else Color.WHITE
    }
    private fun compactPeriod():String {
        val f=queryFilter();val from=LocalDate.parse(f.getString("From"));val to=LocalDate.parse(f.getString("To"))
        val format=DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("id-ID"))
        return if(from==to)to.format(format) else if(from.year==to.year&&from.month==to.month)"${from.dayOfMonth}–${to.format(format)}" else "${from.format(format)}–${to.format(format)}"
    }
    private fun relativeDate(value:String):String {
        val date=LocalDate.parse(value);val today=LocalDate.now()
        return when(date){today->"Hari ini";today.minusDays(1)->"Kemarin";else->date.format(DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("id-ID")))}
    }
    private fun categoryColor(name:String)=when{ name.contains("makan",true)->R.color.chart_third;name.contains("tagihan",true)->R.color.chart_second;else->R.color.chart_fourth }
    private fun categorySurface(name:String)=when{ name.contains("makan",true)->R.color.category_orange_surface;name.contains("tagihan",true)->R.color.category_blue_surface;else->R.color.category_purple_surface }
    private fun transactionIcon(name:String)=when{ name.contains("makan",true)->R.drawable.ic_food;name.contains("hiburan",true)->R.drawable.ic_ticket;else->R.drawable.ic_receipt }
    private fun profilePage() {
        content.removeAllViews();pageTitle("Edit profil",true)
        val colors=avatarColors
        var selectedColor=profile.optInt("Color").coerceIn(0,7)
        val avatar=ui.label(profile.optString("Name").ifBlank{"P"}.take(1).uppercase(),38f,R.color.on_hero,true).apply{gravity=Gravity.CENTER;background=ui.shape(colors[selectedColor],52);setTextColor(avatarInk(selectedColor))}
        content.addView(avatar,LinearLayout.LayoutParams(dp(96),dp(96)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(16);bottomMargin=dp(16)})
        val colorRow=ui.row().apply{gravity=Gravity.CENTER}
        fun drawColors(){
            colorRow.removeAllViews()
            colors.forEachIndexed{i,color->colorRow.addView(ui.button(""){selectedColor=i;avatar.background=ui.shape(colors[i],52);avatar.setTextColor(avatarInk(i));drawColors()}.apply{
                background=ui.touch(color,28,if(i==selectedColor)R.color.ink else null);minHeight=dp(48);contentDescription="Warna profil "+(i+1)+if(i==selectedColor)", dipilih" else "";isSelected=i==selectedColor;setPadding(0,0,0,0)
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(8)})}
        };drawColors()
        content.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(colorRow)})
        val name=field("Nama",profile.optString("Name")).apply{filters=arrayOf(android.text.InputFilter.LengthFilter(50))}
        text("Preferensi",14f,R.color.muted).setPadding(dp(4),dp(22),0,dp(8))
        val preferences=ui.column(R.color.card,16);content.addView(preferences)
        fun preferenceRow(label:String,value:String){
            val row=ui.row().apply{minimumHeight=dp(48)}
            row.addView(ui.label(label,15f),LinearLayout.LayoutParams(0,-2,1f));row.addView(ui.label(value,13f,R.color.muted))
            preferences.addView(row)
        }
        preferenceRow("Mata uang","Rupiah (IDR)")
        preferences.addView(View(this).apply{setBackgroundColor(getColor(R.color.surface_container))},LinearLayout.LayoutParams(-1,dp(1)))
        preferenceRow("Awal periode","Tanggal 1")
        preferences.addView(View(this).apply{setBackgroundColor(getColor(R.color.surface_container))},LinearLayout.LayoutParams(-1,dp(1)))
        preferences.addView(ui.label("Tampilan",15f),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        val periods=listOf("day","week","month","year12")
        val period=styledSpinner().apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Hari ini","7 hari","Bulan ini","12 bulan"));setSelection(periods.indexOf(profile.optString("DashboardPeriod","month")))}
        val reportPeriods=listOf("week","month","lastMonth","quarter","year12")
        val report=styledSpinner().apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("7 hari","Bulan ini","Bulan lalu","3 bulan","12 bulan"));setSelection(reportPeriods.indexOf(profile.optString("ReportPeriod","month")))}
        val include=styledCheckBox().apply{text="Sertakan nama di PDF";isChecked=profile.optBoolean("IncludeName",true)}
        var theme=getSharedPreferences("appearance",MODE_PRIVATE).getString("theme","system")?:"system"
        val choices=listOf("light","dark","system")
        val themes=ui.column();preferences.addView(themes)
        fun drawThemes(){themes.removeAllViews();segments(themes,listOf("Terang","Gelap","Otomatis"),choices.indexOf(theme)){theme=choices[it];drawThemes()}}
        drawThemes()
        val advanced=ui.column(R.color.card,16).apply{visibility=View.GONE}
        addLabel(advanced,"Periode default Beranda",13f,R.color.muted);advanced.addView(period)
        addLabel(advanced,"Periode default PDF",13f,R.color.muted);advanced.addView(report);advanced.addView(include)
        button("Preferensi laporan"){advanced.visibility=if(advanced.visibility==View.GONE)View.VISIBLE else View.GONE}.apply{background=ui.touch(R.color.surface,12);setTextColor(getColor(R.color.muted));textSize=13f}
        addBlock(advanced,0)
        text("Profil hanya disimpan di HP ini, tidak perlu akun.",13f,R.color.muted)
        content.addView(View(this),LinearLayout.LayoutParams(-1,dp(24),1f))
        button("Simpan perubahan",true){
            val displayName=name.text.toString();val colorIndex=selectedColor;val includeName=include.isChecked
            val dashboardPeriod=periods[period.selectedItemPosition];val reportPeriod=reportPeriods[report.selectedItemPosition]
            val selectedTheme=theme
            work("Menyimpan profil…",{
                app.profile.save(displayName,colorIndex,includeName,dashboardPeriod,reportPeriod)
                check(getSharedPreferences("appearance",MODE_PRIVATE).edit().putString("theme",selectedTheme).commit())
            }){profileLoaded=false;filter=JSONObject();recreate()}
        }
    }

    private fun llmPage() {
        work("Memuat konfigurasi…",{app.llm.metadata() to app.llm.configured()}) { raw ->
            @Suppress("UNCHECKED_CAST") val loaded=raw as Pair<Pair<String,String>,Boolean>
            content.removeAllViews();pageTitle("Konfigurasi LLM",true);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            infoPanel(if(loaded.second)"AI siap digunakan saat ekspor PDF. Angka tetap dihitung di HP Anda." else "Tambahkan penyedia AI untuk membantu menjelaskan laporan. Tanpa konfigurasi, laporan tetap tersedia secara lokal.")
            section("Penyedia AI")
            text("LLM_PROVIDER",12f,R.color.muted)
            val providers=listOf("gemini","openai","claude");var provider=loaded.first.first
            val selector=ui.column();content.addView(selector)
            fun drawProviders(){selector.removeAllViews();segments(selector,listOf("Gemini","OpenAI","Claude"),providers.indexOf(provider)){provider=providers[it];drawProviders()}}
            drawProviders()
            val model=field("Model · LLM_MODEL",loaded.first.second).apply{hint="ID model dari penyedia";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;isSaveEnabled=false}
            val key=secretField("API key · LLM_API_KEY",false).apply{
                hint=if(loaded.second)"Key tersimpan · isi untuk mengganti" else "Tempel API key Anda"
                filters=arrayOf(android.text.InputFilter.LengthFilter(1024));isLongClickable=true
                importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;imeOptions=imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            if(loaded.second)text("Key disimpan terenkripsi dan tidak ditampilkan kembali. Kosongkan untuk mempertahankan key pada penyedia yang sama.",12f,R.color.muted)
            section("Data yang digunakan")
            text("AI menerima agregat nominal, kategori, periode, dan pola saat ekspor PDF. Nama profil, catatan, dan rincian transaksi tidak dikirim.",14f)
            text("Memerlukan internet dan dapat memakai kuota berbayar penyedia. Anda dapat memilih analisis lokal pada layar ekspor; jika AI gagal, laporan tetap dibuat lokal.",13f,R.color.muted)
            button("Simpan dan aktifkan AI",true){
                val selected=provider;val modelId=model.text.toString().trim();val secret=key.text.toString();key.text.clear()
                work("Menyimpan konfigurasi…",{app.llm.save(selected,modelId,secret)}){llmPage()}
            }
            if(loaded.second)button("Hapus konfigurasi AI"){
                AlertDialog.Builder(this).setTitle("Nonaktifkan AI?").setMessage("API key akan dihapus dari aplikasi. Laporan lokal tetap tersedia.").setNegativeButton("Batal",null).setPositiveButton("Hapus"){_,_->
                    work("Menghapus konfigurasi…",{app.llm.clear()}){llmPage()}
                }.show().also{trackDialog(it)}
            }
        }
    }

    private fun pdfOptions() {
        content.removeAllViews();pageTitle("PDF analitik",true)
        text("Laporan visual dengan ringkasan eksekutif, sumber pemasukan, tren, perbandingan dan kesimpulan. AI otomatis dipakai jika sudah dikonfigurasi.",14f,R.color.muted)
        section("Metode analisis")
        val localOnly=styledCheckBox().apply{text="Gunakan analisis lokal saja (tanpa internet)"};content.addView(localOnly)
        text("Saat AI aktif, agregat periode terpilih dikirim ke penyedia LLM. Catatan, lampiran dan nama profil tetap tidak dikirim. Biaya mengikuti akun penyedia.",13f,R.color.muted)
        section("Periode laporan")
        val from=dateField(content,"Dari",LocalDate.now().withDayOfMonth(1).toString())
        val to=dateField(content,"Sampai",LocalDate.now().toString())
        val presets=listOf("week","month","lastMonth","quarter","year12","custom")
        val selector=styledSpinner().apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("7 hari terakhir","Bulan ini","Bulan lalu","3 bulan terakhir","12 bulan terakhir","Kustom"));setSelection(presets.indexOf(profile.optString("ReportPeriod","month")))}
        selector.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                val today=LocalDate.now();var end=today
                val start=when(presets[position]){"week"->today.minusDays(6);"month"->today.withDayOfMonth(1);"lastMonth"->{end=today.withDayOfMonth(1).minusDays(1);end.withDayOfMonth(1)};"quarter"->today.minusMonths(3).plusDays(1);"year12"->today.minusMonths(12).plusDays(1);else->return}
                from.tag=start.toString();from.text=start.toString();to.tag=end.toString();to.text=end.toString()
            }
        };content.addView(selector)
        section("Isi laporan")
        val appendix=styledCheckBox().apply{text="Lampiran maksimal 2.000 transaksi terbaru"};content.addView(appendix)
        val notes=styledCheckBox().apply{text="Sertakan catatan transaksi (data sensitif)"};content.addView(notes)
        val name=styledCheckBox().apply{text="Sertakan nama profil";isChecked=profile.optBoolean("IncludeName",true)};content.addView(name)
        button("Pratinjau data untuk AI") {
            val previewFilter=JSONObject().put("Filter",JSONObject().put("From",from.tag.toString()).put("To",to.tag.toString()))
            work("Menyiapkan pratinjau…",{
                val report=app.core.call("report",previewFilter) as JSONObject
                id.kelolauangku.app.application.LlmAnalyzer.payload(report.getJSONObject("Dataset"))
            }) { raw ->
                val data=raw as JSONObject
                val body=TextView(this).apply{text=data.toString(2);setPadding(dp(16),dp(12),dp(16),dp(12));textSize=12f;isSaveEnabled=false}
                val scroll=ScrollView(this).apply{addView(body)}
                AlertDialog.Builder(this).setTitle("Agregat yang dikirim")
                    .setMessage("Data "+data.toString().toByteArray().size+" byte, ditambah instruksi analisis. Perkiraan kasar input "+(data.toString().length/4+250)+" token; biaya aktual ditentukan penyedia. Tidak dikirim saat pratinjau.")
                    .setView(scroll).setPositiveButton("Tutup",null).show().also{trackDialog(it)}
            }
        }
        fun generate(share:Boolean) {
            val payload=JSONObject().put("Filter",JSONObject().put("From",from.tag.toString()).put("To",to.tag.toString())).put("Appendix",appendix.isChecked).put("Notes",notes.isChecked)
            val owner=if(name.isChecked)profile.optString("Name").ifBlank{"Pemilik"}else ""
            val useAI=!localOnly.isChecked
            stepUp {
                val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
                reportCancellation=cancelled
                val cancel=button("Batalkan pembuatan PDF"){}
                work("Membuat laporan analitik…",{
                    val report=app.core.call("report",payload) as JSONObject
                    if(useAI) {
                        val config=try{app.llm.load()}catch(_:Exception){report.put("AIStatus","Konfigurasi AI tidak dapat dibaca. Simpan ulang key di Pengaturan LLM. Laporan memakai analisis lokal.");null}
                        if(config!=null)id.kelolauangku.app.application.LlmAnalyzer.enrich(report,config,cancelled)
                    }
                    val directory=java.io.File(cacheDir,"reports").apply{mkdirs()}
                    val file=java.io.File(directory,"laporan-${java.util.UUID.randomUUID()}.pdf")
                    try{file.outputStream().use{id.kelolauangku.app.application.PdfReport.write(report,owner,it,cancelled,getDrawable(R.drawable.brand_mark))};file}catch(e:Exception){file.delete();throw e}
                }) { file ->
                    reportCancellation=null
                    cancel.visibility=View.GONE
                    app.pendingPDF=file as java.io.File
                    if(share){
                        val uri=android.net.Uri.parse("content://$packageName.reports/${file.name}")
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("Laporan",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Bagikan laporan"))
                    }else startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(Intent.EXTRA_TITLE,"duetku-${LocalDate.now()}.pdf")},101)
                }
                cancel.isEnabled=true;cancel.alpha=1f;cancel.setOnClickListener{cancelled.set(true);cancel.isEnabled=false}
            }
        }
        button("Simpan PDF",true){generate(false)}
        button("Bagikan PDF"){generate(true)}
    }
    private fun exportCSV() {
        val payload=JSONObject().put("Filter",queryFilter())
        work("Menyiapkan CSV…",{app.core.call("exportCSV",payload)}) { raw ->
            app.pendingCSV=(raw as JSONObject).getString("CSV")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/csv";putExtra(Intent.EXTRA_TITLE,"duetku-${LocalDate.now()}.csv")},100)
        }
    }
    @Deprecated("Platform callback retained to avoid additional runtime dependencies")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==101){
            val file=app.pendingPDF;app.pendingPDF=null
            if(resultCode!=RESULT_OK){app.worker.execute{file?.delete()};return}
            val uri=data?.data?:return
            app.worker.execute{
                val result=runCatching{check(file!=null&&file.exists()){ "Ekspor terhenti; buat ulang PDF." };contentResolver.openOutputStream(uri,"wt")?.use{out->file.inputStream().use{it.copyTo(out)}}?:error("Tujuan tidak tersedia.")}
                file?.delete()
                if(result.isFailure)runCatching{android.provider.DocumentsContract.deleteDocument(contentResolver,uri)}
                runOnUiThread{if(visible&&!isDestroyed&&app.security.unlocked)status.text=if(result.isSuccess)"PDF tersimpan." else "Ekspor gagal; periksa dan hapus file kosong di tujuan."}
            };return
        }
        if(requestCode!=100)return
        val csv=app.pendingCSV;app.pendingCSV=null
        if(resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        if(csv==null){
            app.worker.execute {
                val removed=runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver,uri) }.getOrDefault(false)
                if(!removed)runOnUiThread { if(!isDestroyed)status.text="Ekspor terhenti. File kosong mungkin tersisa; hapus melalui aplikasi File lalu ulangi ekspor." }
            }
            status.text="Ekspor terhenti. Silakan ulangi ekspor.";return
        }
        work("Menyimpan CSV…",{try{contentResolver.openOutputStream(uri,"wt")?.use{it.write(csv.toByteArray(Charsets.UTF_8))}?:error("Tujuan ekspor tidak tersedia.")}catch(_:Exception){
            val removed=runCatching{android.provider.DocumentsContract.deleteDocument(contentResolver,uri)}.getOrDefault(false)
            error(if(removed)"Ekspor gagal. Silakan coba lagi." else "Ekspor gagal. File sebagian mungkin tersisa; hapus melalui aplikasi File sebelum mencoba lagi.")
        }}){status.text="CSV berhasil disimpan."}
    }
}
