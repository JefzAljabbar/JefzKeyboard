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
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

private class Step(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val dur: Long)

/*
 * CATATAN: Android membatalkan gesture buatan Aksesibilitas setiap kali ada sentuhan jari asli di layar.
 * Karena itu semua gesture di sini hanya dikirim SETELAH jari dilepas dari panel touchpad.
 */
class PadService : AccessibilityService() {
    companion object { var instance: PadService? = null }

    var bottom = 0 // tinggi area keyboard (px); pointer/scroll tidak masuk ke area ini
    private lateinit var wm: WindowManager
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var shown = false
    private var offX = 0
    private var offY = 0
    private var tip = 0
    private var px = 0f
    private var py = 0f
    private var sw = 1080
    private var sh = 1920
    private var playing = false
    private var startX = 0f
    private var startY = 0f
    private var accAx = 0f
    private var accAy = 0f

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        cursor = PointerView(this)
        tip = (1 * d).toInt()
        lp = WindowManager.LayoutParams(
            (18 * d).toInt(), (30 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
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

    private fun visibleH() = maxOf(1f, sh - bottom.toFloat())

    // ---------------- pointer
    fun showPointer() {
        refreshScreen()
        if (!shown) {
            val p = lp ?: return
            p.x = 0; p.y = 0
            try { wm.addView(cursor, p); shown = true } catch (e: Exception) { return }
            cursor?.post { calibrate() }
            return
        }
        place()
    }

    // ukur posisi jendela overlay yang sebenarnya di layar (status bar/notch) supaya titik pointer = titik klik
    private fun calibrate() {
        val v = cursor ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        offX = loc[0]; offY = loc[1]
        place()
    }

    fun hidePointer() {
        if (shown) { try { wm.removeView(cursor) } catch (e: Exception) { }; shown = false }
    }

    private fun place() {
        val p = lp ?: return
        p.x = px.toInt() - offX - tip; p.y = py.toInt() - offY - tip
        if (shown) try { wm.updateViewLayout(cursor, p) } catch (e: Exception) { }
    }

    fun move(dx: Float, dy: Float) {
        px = (px + dx).coerceIn(0f, sw - 1f)
        py = (py + dy).coerceIn(0f, maxOf(0f, sh - bottom - 1f))
        place()
    }

    // ---------------- gesture
    private fun gesture(s: List<StrokeDescription>, cb: AccessibilityService.GestureResultCallback? = null): Boolean {
        val b = GestureDescription.Builder()
        s.forEach { b.addStroke(it) }
        return try { dispatchGesture(b.build(), cb, null) } catch (e: Exception) { false }
    }

    private fun point() = Path().apply { moveTo(px, py) }

    fun click() { gesture(listOf(StrokeDescription(point(), 0, 50))) }

    // klik kanan = tekan lama (membuka menu konteks); dipanggil setelah tombol dilepas
    fun rightClick() { gesture(listOf(StrokeDescription(point(), 0, 700))) }

    // memutar beberapa langkah berurutan sebagai SATU jari yang tidak terangkat di antaranya
    private fun playSteps(steps: List<Step>, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 26) {
            val f = steps.first(); val l = steps.last()
            val path = Path().apply { moveTo(f.x0, f.y0); if (l.x1 != f.x0 || l.y1 != f.y0) lineTo(l.x1, l.y1) }
            val ok = gesture(listOf(StrokeDescription(path, 0, steps.sumOf { it.dur })), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done(true) }
                override fun onCancelled(g: GestureDescription?) { done(false) }
            })
            if (!ok) done(false)
            return
        }
        play(steps, 0, null, done)
    }

    private fun play(steps: List<Step>, i: Int, prev: StrokeDescription?, done: (Boolean) -> Unit) {
        if (i >= steps.size) { done(true); return }
        val st = steps[i]
        val path = Path().apply { moveTo(st.x0, st.y0); if (st.x1 != st.x0 || st.y1 != st.y0) lineTo(st.x1, st.y1) }
        val more = i < steps.size - 1
        val s = if (prev == null) StrokeDescription(path, 0, st.dur, more) else prev.continueStroke(path, 0, st.dur, more)
        val ok = gesture(listOf(s), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { play(steps, i + 1, s, done) }
            override fun onCancelled(g: GestureDescription?) { done(false) }
        })
        if (!ok) done(false)
    }

    // ---------------- drag: pointer bergerak langsung, gesture drag dijalankan saat dilepas
    fun dragStart() { startX = px; startY = py }
    fun dragPump() {}
    fun dragCancel() {}

    fun dragEnd() {
        if (playing) return
        playing = true
        playSteps(listOf(
            Step(startX, startY, startX, startY, 350),   // tahan dulu (long-press)
            Step(startX, startY, px, py, 300),           // lalu seret
            Step(px, py, px, py, 100)                    // tahan sebentar sebelum angkat jari
        )) { playing = false }
    }

    // ---------------- scroll. dy > 0 = isi bergulir ke bawah (jari mengusap ke atas)
    private fun scrollMode() = getSharedPreferences("kb", MODE_PRIVATE).getString("scrollmode", "gesture")

    fun scrollBy(dx: Float, dy: Float) {
        if (scrollMode() == "action") { scrollAction(dx, dy); return }
        accAx += dx; accAy += dy // dijalankan sebagai satu usapan saat slider dilepas
    }

    fun scrollEnd() {
        if (scrollMode() == "action") { accAx = 0f; accAy = 0f; return }
        flushScroll()
    }

    fun scrollCancel() { accAx = 0f; accAy = 0f }

    private fun clampSafe(v: Float, lo: Float, hi: Float) = if (lo > hi) (lo + hi) / 2 else v.coerceIn(lo, hi)

    private fun flushScroll() {
        if (playing) return
        val d = resources.displayMetrics.density
        val vh = visibleH()
        if (abs(accAx) < 24 * d && abs(accAy) < 24 * d) { accAx = 0f; accAy = 0f; return }
        val cx = accAx.coerceIn(-sw * 0.7f, sw * 0.7f)
        val cy = accAy.coerceIn(-vh * 0.7f, vh * 0.7f)
        accAx -= cx; accAy -= cy
        val m = 12 * d
        val bx = if (shown) px else sw / 2f
        val by = if (shown) py else vh / 2f
        // jari bergerak berlawanan arah isi; awal & akhir usapan harus tetap di dalam area app
        val x0 = clampSafe(bx + cx / 2, m + maxOf(cx, 0f), sw - m + minOf(cx, 0f))
        val y0 = clampSafe(by + cy / 2, m + maxOf(cy, 0f), vh - m + minOf(cy, 0f))
        val x1 = x0 - cx
        val y1 = y0 - cy
        val dur = (200 + maxOf(abs(cx), abs(cy)) * 0.35f).toLong()
        playing = true
        playSteps(listOf(Step(x0, y0, x1, y1, dur), Step(x1, y1, x1, y1, 120))) { ok ->
            playing = false
            if (ok) flushScroll() else { accAx = 0f; accAy = 0f }
        }
    }

    // mode aksi: perintah scroll langsung ke komponen yang bisa di-scroll di titik target
    private fun scrollAction(dx: Float, dy: Float) {
        val step = 120 * resources.displayMetrics.density
        accAx += dx; accAy += dy
        while (accAy >= step) { accAy -= step; doScroll(true, true) }
        while (accAy <= -step) { accAy += step; doScroll(true, false) }
        while (accAx >= step) { accAx -= step; doScroll(false, true) }
        while (accAx <= -step) { accAx += step; doScroll(false, false) }
    }

    private fun findAt(n: AccessibilityNodeInfo?, x: Int, y: Int, out: Array<AccessibilityNodeInfo?>) {
        if (n == null) return
        val r = Rect()
        n.getBoundsInScreen(r)
        if (!r.contains(x, y)) return
        if (n.isScrollable) out[0] = n
        for (i in 0 until n.childCount) findAt(n.getChild(i), x, y, out)
    }

    private fun doScroll(vertical: Boolean, forward: Boolean) {
        val root = rootInActiveWindow ?: return
        val x = (if (shown) px else sw / 2f).toInt()
        val y = (if (shown) py else visibleH() / 2f).toInt()
        val out = arrayOfNulls<AccessibilityNodeInfo>(1)
        findAt(root, x, y, out)
        val n = out[0] ?: return
        val id = when {
            vertical && forward -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            vertical -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            forward -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        }
        if (!n.performAction(id)) {
            n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }
}
