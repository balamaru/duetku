package id.kelolauangku.app.application

import android.graphics.*
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A4 vector report; facts come from Go, only formatting/scaling lives here. */
object PdfReport {
    fun write(report:JSONObject,profileName:String,out:OutputStream,cancelled:AtomicBoolean,brand:android.graphics.drawable.Drawable?=null) {
        Renderer(report,profileName,cancelled,brand).use { it.render(); it.pdf.writeTo(out) }
    }
    private class Renderer(val report:JSONObject,val owner:String,val cancelled:AtomicBoolean,val brand:android.graphics.drawable.Drawable?):AutoCloseable {
        val pdf=PdfDocument()
        val d=report.getJSONObject("Dataset")
        val summary=d.getJSONObject("Summary")
        val ink=Color.rgb(15,23,42); val teal=Color.rgb(13,148,136)
        val coral=Color.rgb(199,67,43); val muted=Color.rgb(83,103,125)
        val pale=Color.rgb(239,250,247); val line=Color.rgb(216,226,234)
        val palette=intArrayOf(teal,Color.rgb(31,112,127),Color.rgb(91,149,158),Color.rgb(105,121,160),Color.rgb(147,122,79),Color.rgb(123,105,145))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        lateinit var page:PdfDocument.Page
        val canvas get()=page.canvas
        var number=0; var y=0f; var open=false
        val money=NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID"))
        fun rp(n:Long)="Rp "+money.format(n)
        fun date(s:String)=runCatching{LocalDate.parse(s).format(DateTimeFormatter.ofPattern("d MMM yyyy",Locale.forLanguageTag("id-ID")))}.getOrDefault(s)
        val period=d.getJSONObject("Period")
        val range=date(period.getString("Start"))+" – "+date(period.getString("End"))
        fun checkCancelled(){check(!cancelled.get()){"Pembuatan PDF dibatalkan."}}
        fun text(s:String,x:Float,baseline:Float,size:Float=10f,color:Int=ink,bold:Boolean=false,width:Float=527f) {
            paint.color=color;paint.textSize=size;paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val clean=s.map{if(it.isISOControl())' ' else it}.joinToString("")
            val count=paint.breakText(clean,true,width,null)
            canvas.drawText(if(count<clean.length)clean.take((count-1).coerceAtLeast(0))+"…" else clean,x,baseline,paint)
        }
        fun rect(x:Float,top:Float,w:Float,h:Float,color:Int) {paint.color=color;canvas.drawRect(x,top,x+w,top+h,paint)}
        fun finish() {
            if(!open)return
            rect(34f,800f,527f,.6f,line)
            text("duetku; kelola uangku · Berisi data keuangan pribadi",34f,815f,8f,muted)
            text("Halaman "+number,490f,815f,8f,muted,width=71f)
            pdf.finishPage(page);open=false
        }
        fun next(first:Boolean=false) {
            checkCancelled();finish();number++
            page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,number).create());open=true
            if(first) {
                rect(0f,0f,595f,126f,ink);rect(0f,123f,595f,3f,teal)
                brand?.let{it.setBounds(477,0,585,108);it.draw(canvas)}
                text("Laporan Analitik Keuangan",34f,44f,24f,Color.WHITE,true)
                text(range+" · "+d.optInt("Days")+" hari",34f,67f,11f,Color.WHITE)
                text("Dibuat "+date(d.getString("Generated"))+if(owner.isBlank())"" else " · "+owner,34f,86f,9f,Color.LTGRAY)
                text(if(report.has("AI"))"Analisis lokal + narasi AI" else "Analisis otomatis lokal",34f,107f,9f,Color.rgb(94,234,212),true)
                y=146f
            }else {
                text("duetku; kelola uangku",34f,31f,12f,teal,true)
                text("Laporan Analitik Keuangan · "+range,34f,49f,9f,muted)
                rect(34f,60f,527f,1f,line);y=81f
            }
        }
        fun room(height:Float){checkCancelled();if(y+height>780f)next()}
        fun heading(title:String,subtitle:String="") {
            room(55f);text(title,34f,y+16f,15f,ink,true);y+=24f
            if(subtitle.isNotEmpty()){paragraph(subtitle,9f,muted);y+=3f}
        }
        fun wrapped(value:String,width:Float,size:Float):List<String> {
            paint.textSize=size;paint.typeface=Typeface.DEFAULT
            var rest=value.map{if(it.isISOControl())' ' else it}.joinToString("")
            val result=mutableListOf<String>()
            while(rest.isNotEmpty()) {
                val n=paint.breakText(rest,true,width,null).coerceAtLeast(1)
                val cut=if(n<rest.length)rest.lastIndexOf(' ',n).takeIf{it>n/2}?:n else n
                result.add(rest.take(cut));rest=rest.drop(cut).trimStart()
            }
            return result
        }
        fun paragraph(value:String,size:Float=10f,color:Int=ink) {
            for(s in wrapped(value,527f,size)){room(size+5f);text(s,34f,y+size,size,color);y+=size+5f};y+=6f
        }
        fun callout(title:String,value:String) {
            val lines=wrapped(value,499f,10f)
            if(lines.size>17){heading(title);paragraph(value);return}
            val h=40f+lines.size*15f;room(h+10f);rect(34f,y,527f,h,pale)
            text(title,48f,y+19f,10f,teal,true)
            lines.forEachIndexed{i,s->text(s,48f,y+39f+i*15f,10f,width=499f)};y+=h+14f
        }
        fun metrics(items:List<Pair<String,String>>) {
            room(76f);val w=(527f-9f*(items.size-1))/items.size
            items.forEachIndexed{i,v->val x=34f+i*(w+9f);rect(x,y,w,64f,Color.rgb(246,248,251));rect(x,y,w,2f,if(i==1)coral else teal)
                text(v.first,x+9f,y+19f,8f,muted,true,w-18f)
                var size=15f;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=size
                while(paint.measureText(v.second)>w-18f&&size>8f){size-=.5f;paint.textSize=size}
                text(v.second,x+9f,y+44f,size,ink,true,w-18f)
            };y+=79f
        }
        fun categories(key:String,income:Boolean=false) {
            val rows=d.optJSONArray(key)?:JSONArray()
            if(rows.length()==0){paragraph("Belum ada "+if(income)"pemasukan tercatat." else "pengeluaran tercatat.");return}
            val total=summary.getLong(if(income)"Income" else "Expense")
            for(i in 0 until rows.length()) {
                val c=rows.getJSONObject(i);val names=wrapped(c.getString("Name"),250f,10f)
                val h=maxOf(32f,names.size*14f+18f);room(h)
                names.forEachIndexed{j,s->text(s,34f,y+12f+j*14f,10f,ink,true,250f)}
                text(rp(c.getLong("Amount"))+" · "+c.getString("Percent")+"%",290f,y+12f,9f,muted,width=271f)
                rect(34f,y+h-15f,527f,6f,line)
                rect(34f,y+h-15f,if(total>0)(527.0*c.getLong("Amount")/total).toFloat() else 0f,6f,palette[i%palette.size]);y+=h
            };y+=8f
        }
        fun donut() {
            val rows=d.optJSONArray("IncomeCategories")?:JSONArray();if(rows.length()==0)return
            room(145f);val top=y;val total=summary.getLong("Income");var angle=-90f
            for(i in 0 until rows.length()){val c=rows.getJSONObject(i);val sweep=(360.0*c.getLong("Amount")/total).toFloat();paint.color=palette[i%palette.size];canvas.drawArc(RectF(38f,top,154f,top+116f),angle,sweep,true,paint);angle+=sweep}
            paint.color=Color.WHITE;canvas.drawCircle(96f,top+58f,37f,paint)
            val main=rows.getJSONObject(0)
            text(main.getString("Percent")+"%",65f,top+62f,14f,ink,true,65f)
            if(rows.length()<=5) {
                for(i in 0 until rows.length()){val c=rows.getJSONObject(i);val yy=top+18f+i*24f
                    rect(177f,yy-7f,5f,5f,palette[i%palette.size])
                    text(c.getString("Name"),190f,yy,9f,ink,true,152f)
                    text(rp(c.getLong("Amount")),347f,yy,9f,ink,true,135f)
                    text(c.getString("Percent")+"%",492f,yy,9f,muted,width=69f)
                }
            }else {
                text("Sumber terbesar: "+main.getString("Name"),178f,top+26f,12f,ink,true,380f)
                text(rp(main.getLong("Amount")),178f,top+78f,18f,teal,true,380f)
                text("dari "+rp(total)+" pemasukan",178f,top+99f,10f,muted,width=380f)
            };y+=145f
        }
        fun chart() {
            val rows=d.getJSONArray("Trends");if(rows.length()==0)return
            room(200f);val top=y+25f;val bottom=top+120f
            text("Pemasukan",380f,y+10f,9f,teal,true,80f);text("Pengeluaran",468f,y+10f,9f,coral,true,93f)
            var max=1L;for(i in 0 until rows.length()){val t=rows.getJSONObject(i);max=maxOf(max,t.getLong("Income"),t.getLong("Expense"))}
            for(i in 0..4){val level=(max.toDouble()/4*i).toLong();val yy=bottom-i*30f;rect(91f,yy,470f,.5f,line);text(compact(level),34f,yy+3f,8f,muted,width=53f)}
            val step=470f/rows.length();val bw=(step*.31f).coerceAtMost(18f)
            for(i in 0 until rows.length()){val t=rows.getJSONObject(i);val x=91f+step*(i+.5f)
                val ih=(120.0*t.getLong("Income")/max).toFloat();val eh=(120.0*t.getLong("Expense")/max).toFloat()
                rect(x-bw-1f,bottom-ih,bw,ih,teal);rect(x+1f,bottom-eh,bw,eh,coral)
                if(i%maxOf(1,(rows.length()+7)/8)==0)text(t.getString("Label").takeLast(5),x-17f,bottom+16f,7f,muted,width=45f)
            };y=bottom+36f
            paragraph("Bucket "+(if(d.optInt("Days")>120)"bulanan" else if(d.optInt("Days")>31)"pekanan (Senin–Minggu)" else "harian")+"; label menampilkan awal bucket. Sumbu dalam rupiah.",8f,muted)
        }
        fun compact(n:Long):String=when{n>=1_000_000_000->String.format(Locale.forLanguageTag("id-ID"),"%.1f M",n/1e9);n>=1_000_000->String.format(Locale.forLanguageTag("id-ID"),"%.1f jt",n/1e6);n>=1000->String.format(Locale.forLanguageTag("id-ID"),"%.0f rb",n/1e3);else->n.toString()}
        fun comparison() {
            if(!d.getBoolean("ComparisonAvailable")){paragraph("Pembanding penuh tidak tersedia dalam jendela retensi.");return}
            val p=d.getJSONObject("ComparisonPeriod")
            paragraph("Pembanding: "+date(p.getString("Start"))+" – "+date(p.getString("End"))+". Angka nol berarti tidak ada transaksi tercatat.",9f,muted)
            fun header(){room(28f);rect(34f,y,527f,24f,pale);text("Kategori",40f,y+16f,8f,muted,true,145f);text("Sebelumnya",192f,y+16f,8f,muted,true,115f);text("Periode ini",315f,y+16f,8f,muted,true,115f);text("Perubahan",438f,y+16f,8f,muted,true,117f);y+=29f}
            header();val rows=d.optJSONArray("Changes")?:JSONArray()
            for(i in 0 until rows.length()) {val c=rows.getJSONObject(i);val names=wrapped(c.getString("Name"),145f,9f);val h=maxOf(43f,names.size*12f+8f)
                if(y+h>780){next();heading("Perbandingan · lanjutan");header()}
                names.forEachIndexed{j,s->text(s,40f,y+12f+j*12f,9f,width=145f)}
                text(rp(c.getLong("Previous")),192f,y+12f,8f,width=115f);text(rp(c.getLong("Current")),315f,y+12f,8f,width=115f)
                val pct=c.getString("Percent");text(if(pct=="tidak tersedia")"Basis nol" else pct+"%",438f,y+12f,9f,if(c.getLong("Delta")>0)coral else teal,true,117f)
                text(rp(c.getLong("Delta")),438f,y+27f,8f,muted,width=117f);rect(34f,y+h-3f,527f,.5f,line);y+=h
            }
        }
        fun weekdays() {
            val rows=d.optJSONArray("Weekdays")?:JSONArray();if(rows.length()==0)return
            room(190f);val top=y;var max=1.0
            fun average(c:JSONObject)=if(c.getInt("Days")==0)0.0 else c.getLong("Amount").toDouble()/c.getInt("Days")
            for(i in 0 until rows.length())max=maxOf(max,average(rows.getJSONObject(i)))
            for(i in 0 until rows.length()){val c=rows.getJSONObject(i);val x=42f+i*74f;val h=(110*average(c)/max).toFloat();rect(x,top+132f-h,40f,h,if(i>=5)coral else teal)
                text(if(c.getInt("Days")==0)"—" else compact(average(c).toLong()),x-4f,top+123f-h,8f,muted,width=68f);text(c.getString("Name").take(3),x,top+148f,9f,width=68f)
            };y+=171f;paragraph("Rata-rata seluruh pengeluaran per hari kalender, termasuk hari tanpa transaksi. Tidak memisahkan rutin/fleksibel.",8f,muted)
        }
        fun narrative(vararg ids:String) {
            val rows=report.getJSONArray("Narrative")
            for(i in 0 until rows.length()){val n=rows.getJSONObject(i);if(n.getString("ID") !in ids)continue;val p=n.getJSONArray("Paragraf");for(j in 0 until p.length())paragraph(p.getString(j))}
        }
        fun transactions(key:String) {
            val rows=d.getJSONArray(key);if(rows.length()==0){paragraph("Tidak ada transaksi untuk ditampilkan.");return}
            for(i in 0 until rows.length()) {val t=rows.getJSONObject(i);val labels=wrapped(t.getString("Category"),210f,10f);val h=maxOf(40f,labels.size*14f+8f);room(h)
                text(date(t.getString("Date")),34f,y+12f,9f,muted,width=110f)
                labels.forEachIndexed{j,s->text(s,148f,y+12+j*14f,10f,width=210f)}
                text(rp(t.getLong("Amount")),368f,y+12f,10f,ink,true,193f)
                text(if(t.getString("Type")=="income")"Pemasukan" else "Pengeluaran",368f,y+27f,8f,muted,width=193f);y+=h
                if(t.optString("Note").isNotBlank())paragraph(t.getString("Note"),9f,muted)
                rect(34f,y,527f,.5f,line);y+=8f
            }
        }
        fun render() {
            next(true)
            metrics(listOf("PEMASUKAN" to rp(summary.getLong("Income")),"PENGELUARAN" to rp(summary.getLong("Expense")),"SALDO PERIODE" to rp(summary.getLong("Balance")),"RASIO TABUNGAN" to (d.getString("SavingRatio")+if(summary.getLong("Income")>0)"%" else "")))
            callout("Ringkasan eksekutif",d.optString("Executive","Ringkasan transaksi pada periode terpilih."))
            heading("Sumber pemasukan","Dari mana uang masuk dan seberapa terkonsentrasi sumbernya?")
            donut();if((d.optJSONArray("IncomeCategories")?.length()?:0)>5)categories("IncomeCategories",true);paragraph(d.optString("IncomeExplanation"))
            metrics(listOf("RATA-RATA / HARI" to "Rp "+d.getString("DailyAverage"),"HARI TANPA BELANJA" to (d.optInt("NoExpenseDays").toString()+" hari"),"TRANSAKSI" to summary.getInt("Count").toString()))
            next();heading("Komposisi pengeluaran","Ke kategori mana uang paling banyak keluar?");categories("Categories");narrative("dominant","concentration")
            heading("Tren arus uang masuk dan keluar","Kapan uang masuk dan kapan pengeluaran memuncak?");chart();callout("Pembacaan tren",d.optString("TrendExplanation"))
            next();heading("Perbandingan dengan periode sebelumnya");comparison();narrative("total_change")
            heading("Pola pengeluaran per hari dalam seminggu");weekdays();narrative("weekday","insufficient")
            next();heading("Sorotan transaksi","Lima pengeluaran terbesar pada periode terpilih");transactions("Top");narrative("peak","spike")
            heading("Kesimpulan periode");callout("Kesimpulan lokal",d.optString("Conclusion",d.optString("Executive")));narrative("deficit")
            if(report.has("AI")){heading("Catatan analis AI");paragraph(report.optString("AISource"),9f,muted);val ai=report.getJSONArray("AI");for(i in 0 until ai.length())paragraph(ai.getString(i));paragraph("Narasi AI perlu ditinjau; angka resmi tetap berasal dari perhitungan lokal.",9f,muted)}
            if(report.has("AIStatus"))paragraph(report.getString("AIStatus"),9f,muted)
            heading("Catatan metodologi")
            paragraph("Angka berasal dari transaksi tersimpan pada periode yang sama dengan dashboard dan CSV. Saldo periode adalah arus kas bersih, bukan saldo rekening. Hari tanpa pengeluaran berarti tidak ada pengeluaran tercatat.",8f,muted)
            val retention=d.getJSONObject("Retention");paragraph("Retensi: "+date(retention.getString("Start"))+" – "+date(retention.getString("End"))+".",8f,muted)
            val notes=d.getJSONArray("Notes");for(i in 0 until notes.length())paragraph(notes.getString(i),8f,muted)
            if(d.getJSONArray("Transactions").length()>0){next();heading("Lampiran transaksi");transactions("Transactions")}
            checkCancelled();finish()
        }
        override fun close(){if(open)finish();pdf.close()}
    }
}
