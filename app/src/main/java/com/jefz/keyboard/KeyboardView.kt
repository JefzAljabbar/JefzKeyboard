package com.jefz.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class KeyboardView(ctx: Context) : View(ctx) {
    var layers: List<Layer> = emptyList()
    var cur = 0
    var onKey: (Key) -> Unit = {}
    var active: (Key) -> Boolean = { false }
    var shifted: () -> Boolean = { false }
    var haptic = true

    private val d = resources.displayMetrics.density
    private val rowH = 52 * d
    private val gap = 3 * d
    private val cells = ArrayList<Pair<RectF, Key>>()
    private val down = HashMap<Int, Key>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hd = Handler(Looper.getMainLooper())
    private var rep: Runnable? = null

    fun setLayer(n: String) {
        val i = layers.indexOfFirst { it.name == n }
        if (i < 0) return
        cur = i
        requestLayout(); build(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val rows = layers.getOrNull(cur)?.rows?.size ?: 4
        setMeasuredDimension(MeasureSpec.getSize(w), (rows * rowH + gap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { build() }

    private fun build() {
        cells.clear()
        val l = layers.getOrNull(cur) ?: return
        if (width == 0) return
        l.rows.forEachIndexed { ri, r ->
            val tot = r.sumOf { it.w.toDouble() }.toFloat()
            var x = 0f
            val top = ri * rowH + gap
            r.forEach { k ->
                val w = width * k.w / tot
                cells.add(RectF(x + gap / 2, top, x + w - gap / 2, top + rowH - gap) to k)
                x += w
            }
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
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = (if (k.label.length > 3) 13 else 18) * d
            val t = if (up && k.type == "char" && k.label.length == 1) k.label.uppercase() else k.label
            c.drawText(t, rc.centerX(), rc.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    private fun hit(x: Float, y: Float): Key? = cells.firstOrNull { it.first.contains(x, y) }?.second

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val k = hit(e.getX(i), e.getY(i))
                if (k != null) { down[e.getPointerId(i)] = k; press(k) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                down.remove(e.getPointerId(e.actionIndex)); stopRep(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down.clear(); stopRep(); invalidate() }
        }
        return true
    }

    private fun press(k: Key) {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onKey(k)
        if ((k.type == "act" && k.value == "DEL") || (k.type == "code" && k.value.startsWith("DPAD"))) startRep(k)
    }

    private fun startRep(k: Key) {
        stopRep()
        val r = object : Runnable { override fun run() { onKey(k); invalidate(); hd.postDelayed(this, 50) } }
        rep = r
        hd.postDelayed(r, 400)
    }

    private fun stopRep() { rep?.let { hd.removeCallbacks(it) }; rep = null }
}
