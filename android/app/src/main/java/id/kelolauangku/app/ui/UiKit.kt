package id.kelolauangku.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import id.kelolauangku.app.R

/** Small native view helpers. All colors resolve from the current Android theme. */
class UiKit(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 22, outline: Int? = null) = GradientDrawable().apply {
        setColor(context.getColor(color)); cornerRadius = dp(radius).toFloat()
        outline?.let { setStroke(dp(1), context.getColor(it)) }
    }
    fun touch(color: Int, radius: Int = 16, outline: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(context.getColor(R.color.ripple)), shape(color, radius, outline), null
    )
    fun label(value: String, size: Float = 16f, color: Int = R.color.ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(context.getColor(color)); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    fun column(color: Int? = null, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        color?.let { background = shape(it) }
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun button(value: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(52); minimumWidth = 0; minWidth = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(if (primary) R.color.on_primary else if (danger) R.color.expense else R.color.ink))
        backgroundTintList = null
        background = touch(if (primary) R.color.primary else R.color.card, 16, if (danger) R.color.danger_outline else if(primary)null else R.color.outline)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    fun icon(id: Int, color: Int = R.color.primary, size: Int = 22):android.graphics.drawable.Drawable {
        val source=context.getDrawable(id)!!.mutate().apply{setTint(context.getColor(color))}
        val side=dp(size)
        return object:android.graphics.drawable.Drawable(){
            override fun getIntrinsicWidth()=side
            override fun getIntrinsicHeight()=side
            override fun draw(canvas:Canvas){source.bounds=bounds;source.draw(canvas)}
            override fun setAlpha(alpha:Int){source.alpha=alpha}
            override fun setColorFilter(filter:android.graphics.ColorFilter?){source.colorFilter=filter}
            @Deprecated("Deprecated in Android") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
        }.apply{setBounds(0,0,side,side)}
    }
}

/** Proportions of the selected period; amount conversion is for drawing only. */
class ChipFlow(context:Context):ViewGroup(context){
    private val gap=(8*resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=MeasureSpec.getSize(widthMeasureSpec);var x=0;var y=0;var rowHeight=0
        for(i in 0 until childCount){
            val child=getChildAt(i);child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            if(x>0&&x+child.measuredWidth>width){x=0;y+=rowHeight+gap;rowHeight=0}
            x+=child.measuredWidth+gap;rowHeight=maxOf(rowHeight,child.measuredHeight)
        };setMeasuredDimension(width,resolveSize(y+rowHeight,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
        var x=0;var y=0;var rowHeight=0
        for(i in 0 until childCount){val child=getChildAt(i)
            if(x>0&&x+child.measuredWidth>width){x=0;y+=rowHeight+gap;rowHeight=0}
            child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight);x+=child.measuredWidth+gap;rowHeight=maxOf(rowHeight,child.measuredHeight)
        }
    }
    override fun generateDefaultLayoutParams()=LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT)
}

class RoundedBar(context:Context,private val fraction:Float,private val fill:Int,private val track:Int):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        val radius=height/2f
        paint.color=track;canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,paint)
        paint.color=fill;canvas.drawRoundRect(0f,0f,width*fraction.coerceIn(0f,1f),height.toFloat(),radius,radius,paint)
    }
}

class CategoryStrip(context:Context,private val values:List<Pair<Long,Int>>):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val total=values.sumOf{it.first.toDouble()};if(total<=0)return
        val bounds=android.graphics.Path().apply{addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),height/2f,height/2f,android.graphics.Path.Direction.CW)}
        canvas.save();canvas.clipPath(bounds);var x=0f
        values.forEachIndexed{index,(amount,color)->val end=x+(width*amount/total).toFloat();paint.color=color;canvas.drawRect(x,0f,end-(if(index<values.lastIndex)context.resources.displayMetrics.density*2 else 0f),height.toFloat(),paint);x=end}
        canvas.restore()
    }
}

/** A real data chart; empty data is a still ring, never an apparent loading spinner. */
class ExpenseChart(context: Context, private val income: Long, private val expense: Long) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width.coerceAtMost(height) * .14f
        paint.strokeWidth = stroke
        val side = width.coerceAtMost(height).toFloat()
        val left = (width - side) / 2f + stroke / 2f
        val top = (height - side) / 2f + stroke / 2f
        val bounds = RectF(left, top, left + side - stroke, top + side - stroke)
        paint.color = context.getColor(R.color.outline); canvas.drawArc(bounds, 0f, 360f, false, paint)
        // Floating point is used only for visual angles, never for stored amounts or totals.
        if (income > 0) {
            paint.color = context.getColor(R.color.primary)
            canvas.drawArc(bounds, 0f, 360f, false, paint)
        }
        val fraction = if (income > 0) (expense.toDouble() / income).coerceIn(0.0, 1.0) else if (expense > 0) 1.0 else 0.0
        paint.color = context.getColor(R.color.expense)
        canvas.drawArc(bounds, -90f, (fraction * 360).toFloat(), false, paint)
    }
}
