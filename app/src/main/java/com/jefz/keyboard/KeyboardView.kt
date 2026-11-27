package com.jefz.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

open class KeyboardView(ctx: Context) : View(ctx) {
    var layer: Layer? = null
    var onKey: (Key) -> Unit = {}
    var active: (Key) -> Boolean = { false }
    var shifted: () -> Boolean = { false }
    var haptic = true
    var heightScale = 1f
    var fontScale = 1f
    var onCursor: (Int) -> Unit = {}

    protected val d = resources.displayMetrics.density
    protected val gap = 3 * d
    protected val cells = ArrayList<Pair<RectF, Key>>()
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val down = HashMap<Int, Key>()
    private val pend = HashMap<Int, Runnable>()
    private val fired = HashSet<Int>()
    private val hd = Handler(Looper.getMainLooper())
    private var rep: Runnable? = null
    private val trackX = HashMap<Int, Float>()
    private val scrolled = HashSet<Int>()
    private fun isSpace(k: Key) = k.type == "act" && k.value == "SPACE" && k.lp.isEmpty()

    protected open fun rowH(): Float =
        (if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else 52) * d * heightScale

    fun showLayer(l: Layer?) { layer = l; requestLayout(); build(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((layer?.rows ?: 4) * rowH()).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { build() }

    protected fun build() {
        cells.clear()
        val l = layer ?: return
        if (width == 0) return
        val rh = rowH()
        for (k in l.keys) {
            cells.add(RectF(k.x * width + gap / 2, k.y * rh + gap / 2,
                (k.x + k.w) * width - gap / 2, (k.y + k.h) * rh - gap / 2) to k)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1B1B1B.toInt())
        val up = shifted()
        for ((rc, k) in cells) {
            paint.color = when {
                down.values.any { it === k } -> 0xFF7C93FF.toInt()
                active(k) -> 0xFF5C7CFA.toInt()
                k.type == "char" -> 0xFF3A3A3A.toInt()
                else -> 0xFF2C2C2C.toInt()
            }
            c.drawRoundRect(rc, 8 * d, 8 * d, paint)
            if (k.lp.startsWith("char:")) {
                paint.textSize = 10 * d * fontScale
                paint.color = 0xFF9AA0B5.toInt()
                c.drawText(k.lp.substring(5), rc.right - 9 * d, rc.top + 13 * d, paint)
            }
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = (if (k.label.length > 3) 13 else 18) * d * fontScale
            val t = if (up && k.type == "char" && k.label.length == 1) k.label.uppercase() else k.label
            c.drawText(t, rc.centerX(), rc.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val k = cells.lastOrNull { it.first.contains(e.getX(i), e.getY(i)) }?.second
                if (k != null) { down[id] = k; press(id, k, e.getX(i)) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val step = 12 * d
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    var x = trackX[id] ?: continue
                    val cx = e.getX(i)
                    while (cx - x >= step) { x += step; tick(id); onCursor(1) }
                    while (x - cx >= step) { x -= step; tick(id); onCursor(-1) }
                    trackX[id] = x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                for (r in pend.values) hd.removeCallbacks(r)
                pend.clear(); down.clear(); fired.clear(); trackX.clear(); scrolled.clear(); stopRep(); invalidate()
            }
        }
        return true
    }

    private fun tick(id: Int) {
        scrolled.add(id)
        if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun press(id: Int, k: Key, x: Float) {
        if (isSpace(k)) { trackX[id] = x; return }
        if (k.lp.isEmpty()) { fire(k); return }
        val r = Runnable { fired.add(id); fire(Key.spec(k.lp)) }
        pend[id] = r
        hd.postDelayed(r, 350)
    }

    private fun release(id: Int) {
        val k = down.remove(id)
        pend.remove(id)?.let { hd.removeCallbacks(it) }
        trackX.remove(id)
        if (k != null) {
            if (isSpace(k)) { if (!scrolled.remove(id)) fire(k) }
            else if (k.lp.isNotEmpty() && !fired.remove(id)) fire(k)
        }
        stopRep(); invalidate()
    }

    private fun fire(k: Key) {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onKey(k)
        val repeatable = (k.type == "act" && k.value == "DEL") ||
            (k.type == "code" && (k.value.startsWith("DPAD") || k.value == "DEL" || k.value == "FORWARD_DEL"))
        if (repeatable) startRep(k)
        invalidate()
    }

    private fun startRep(k: Key) {
        stopRep()
        val r = object : Runnable { override fun run() { onKey(k); invalidate(); hd.postDelayed(this, 50) } }
        rep = r
        hd.postDelayed(r, 400)
    }

    private fun stopRep() { rep?.let { hd.removeCallbacks(it) }; rep = null }
}
