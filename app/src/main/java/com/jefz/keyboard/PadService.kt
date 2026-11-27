package com.jefz.keyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

private class PointerView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val arrow = Path().apply {
        moveTo(1 * d, 1 * d); lineTo(1 * d, 21 * d); lineTo(6 * d, 16.5f * d); lineTo(10 * d, 25 * d)
        lineTo(13 * d, 23.5f * d); lineTo(9 * d, 15 * d); lineTo(15 * d, 15 * d); close()
    }
    override fun onDraw(c: Canvas) { c.drawPath(arrow, fill); c.drawPath(arrow, line) }
}

class PadService : AccessibilityService() {
    companion object { var instance: PadService? = null }

    var bottom = 0 // tinggi area keyboard (px); pointer tidak masuk ke area ini
    private lateinit var wm: WindowManager
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var shown = false
    private var px = 0f
    private var py = 0f
    private var sw = 1080
    private var sh = 1920

    // drag
    private var stroke: StrokeDescription? = null
    private var sentX = 0f
    private var sentY = 0f
    private var busy = false
    private var ending = false
    private var startX = 0f
    private var startY = 0f

    // scroll
    private var accX = 0f
    private var accY = 0f
    private var scrollBusy = false

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        cursor = PointerView(this)
        lp = WindowManager.LayoutParams(
            (18 * d).toInt(), (30 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        refreshScreen()
        px = sw / 2f; py = sh / 3f
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hidePointer()
        return super.onUnbind(intent)
    }

    fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    @Suppress("DEPRECATION")
    private fun refreshScreen() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        px = px.coerceIn(0f, sw - 1f)
        py = py.coerceIn(0f, maxOf(0f, sh - bottom - 1f))
    }

    fun showPointer() {
        refreshScreen()
        if (!shown) {
            try { wm.addView(cursor, lp); shown = true } catch (e: Exception) { }
        }
        place()
    }

    fun hidePointer() {
        if (shown) { try { wm.removeView(cursor) } catch (e: Exception) { }; shown = false }
    }

    private fun place() {
        val p = lp ?: return
        p.x = px.toInt(); p.y = py.toInt()
        if (shown) try { wm.updateViewLayout(cursor, p) } catch (e: Exception) { }
    }

    fun move(dx: Float, dy: Float) {
        px = (px + dx).coerceIn(0f, sw - 1f)
        py = (py + dy).coerceIn(0f, maxOf(0f, sh - bottom - 1f))
        place()
    }

    private fun gesture(s: List<StrokeDescription>, cb: AccessibilityService.GestureResultCallback? = null): Boolean {
        val b = GestureDescription.Builder()
        s.forEach { b.addStroke(it) }
        return try { dispatchGesture(b.build(), cb, null) } catch (e: Exception) { false }
    }

    private fun point() = Path().apply { moveTo(px, py) }

    fun click() { gesture(listOf(StrokeDescription(point(), 0, 50))) }

    // klik kanan = tekan lama (membuka menu konteks)
    fun rightClick() { gesture(listOf(StrokeDescription(point(), 0, 700))) }

    // ---- drag (tahan tombol kiri + gerak)
    private val dragCb = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { busy = false; pump() }
        override fun onCancelled(g: GestureDescription?) { busy = false; stroke = null }
    }

    fun dragStart() {
        startX = px; startY = py
        if (Build.VERSION.SDK_INT < 26) return
        sentX = px; sentY = py; ending = false
        val s = StrokeDescription(Path().apply { moveTo(px, py) }, 0, 1, true)
        stroke = s
        busy = gesture(listOf(s), dragCb)
    }

    fun dragPump() { pump() }

    private fun pump() {
        val s = stroke ?: return
        if (busy || Build.VERSION.SDK_INT < 26) return
        if (!ending && px == sentX && py == sentY) return
        val path = Path().apply { moveTo(sentX, sentY); lineTo(px, py) }
        val n = s.continueStroke(path, 0, 16, !ending)
        sentX = px; sentY = py
        stroke = if (ending) null else n
        busy = gesture(listOf(n), dragCb)
    }

    fun dragEnd() {
        if (Build.VERSION.SDK_INT < 26) {
            val path = Path().apply { moveTo(startX, startY); lineTo(px, py) }
            gesture(listOf(StrokeDescription(path, 0, 300)))
            return
        }
        if (stroke == null) return
        ending = true
        pump()
    }

    // ---- scroll: dy > 0 = isi layar bergulir ke bawah (jari mengusap ke atas)
    fun scrollBy(dx: Float, dy: Float) {
        accX += dx; accY += dy
        pumpScroll()
    }

    private fun pumpScroll() {
        if (scrollBusy) return
        val min = 12 * resources.displayMetrics.density
        if (abs(accX) < min && abs(accY) < min) return
        val cx = accX.coerceIn(-sw * 0.4f, sw * 0.4f)
        val cy = accY.coerceIn(-sh * 0.4f, sh * 0.4f)
        accX -= cx; accY -= cy
        val mx = px.coerceIn(abs(cx) / 2, sw - 1f - abs(cx) / 2)
        val my = py.coerceIn(abs(cy) / 2, sh - 1f - abs(cy) / 2)
        val path = Path().apply { moveTo(mx + cx / 2, my + cy / 2); lineTo(mx - cx / 2, my - cy / 2) }
        val dur = (150 + maxOf(abs(cx), abs(cy)) * 0.5f).toLong()
        scrollBusy = true
        val ok = gesture(listOf(StrokeDescription(path, 0, dur)), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { scrollBusy = false; pumpScroll() }
            override fun onCancelled(g: GestureDescription?) { scrollBusy = false }
        })
        if (!ok) scrollBusy = false
    }
}
