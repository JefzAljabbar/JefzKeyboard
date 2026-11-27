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
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    var bottom = 0 // tinggi area keyboard (px); pointer/scroll tidak masuk ke area ini
    private lateinit var wm: WindowManager
    private val hdl = Handler(Looper.getMainLooper())
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

    private var dragger: Stroker? = null
    private var startX = 0f
    private var startY = 0f
    private var scroller: Stroker? = null
    private var sFx = 0f
    private var sFy = 0f
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
        scrollEnd(); dragger?.end(); dragger = null
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

    // ukur posisi jendela overlay yang sebenarnya di layar supaya titik pointer = titik klik
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

    // ---------------- gesture dasar
    private fun gesture(s: List<StrokeDescription>, cb: AccessibilityService.GestureResultCallback? = null): Boolean {
        val b = GestureDescription.Builder()
        s.forEach { b.addStroke(it) }
        return try { dispatchGesture(b.build(), cb, null) } catch (e: Exception) { false }
    }

    private fun point() = Path().apply { moveTo(px, py) }

    fun click() { gesture(listOf(StrokeDescription(point(), 0, 50))) }

    // klik kanan = tekan lama (membuka menu konteks)
    fun rightClick() { gesture(listOf(StrokeDescription(point(), 0, 700))) }

    // satu jari yang bergerak terus-menerus (dipakai drag dan scroll)
    private inner class Stroker(x: Float, y: Float) {
        private var stroke: StrokeDescription? = null
        private var last: StrokeDescription? = null
        private var sx = x
        private var sy = y
        private var tx = x
        private var ty = y
        private var busy = false
        private var ending = false
        private val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busy = false; pump() }
            override fun onCancelled(g: GestureDescription?) { busy = false; stroke = null }
        }

        init {
            if (Build.VERSION.SDK_INT >= 26) {
                val s = StrokeDescription(Path().apply { moveTo(x, y) }, 0, 1, true)
                stroke = s; last = s
                busy = gesture(listOf(s), cb)
            }
        }

        fun moveTo(x: Float, y: Float) { tx = x; ty = y; pump() }
        fun end() { ending = true; pump() }

        private fun pump() {
            val s = stroke ?: return
            if (busy) return
            if (!ending && tx == sx && ty == sy) return
            val path = Path().apply { moveTo(sx, sy); if (tx != sx || ty != sy) lineTo(tx, ty) }
            val n = s.continueStroke(path, 0, if (ending) 120L else 16L, !ending)
            sx = tx; sy = ty
            stroke = if (ending) null else n
            last = n
            busy = gesture(listOf(n), cb)
            if (busy) hdl.postDelayed({ if (busy && last === n) busy = false }, 800)
        }
    }

    // ---------------- drag
    fun dragStart() {
        startX = px; startY = py
        if (Build.VERSION.SDK_INT >= 26) dragger = Stroker(px, py)
    }

    fun dragPump() { dragger?.moveTo(px, py) }

    fun dragEnd() {
        if (Build.VERSION.SDK_INT < 26) {
            gesture(listOf(StrokeDescription(Path().apply { moveTo(startX, startY); lineTo(px, py) }, 0, 300)))
            return
        }
        dragger?.end(); dragger = null
    }

    // ---------------- scroll. dy > 0 = isi bergulir ke bawah (jari bergerak ke atas)
    private fun scrollMode() = getSharedPreferences("kb", MODE_PRIVATE).getString("scrollmode", "gesture")

    fun scrollBy(dx: Float, dy: Float) {
        if (Build.VERSION.SDK_INT < 26 || scrollMode() == "action") { scrollAction(dx, dy); return }
        val m = 36 * resources.displayMetrics.density
        val left = m; val right = sw - m; val top = m; val bot = maxOf(top + 1f, visibleH() - m)
        var st = scroller
        if (st == null) {
            sFx = (if (shown) px else sw / 2f).coerceIn(left, right)
            sFy = (if (shown) py else visibleH() / 2f).coerceIn(top, bot)
            st = Stroker(sFx, sFy)
            scroller = st
        }
        var nx = sFx - dx
        var ny = sFy - dy
        if (nx < left || nx > right || ny < top || ny > bot) {
            // mentok tepi layar: angkat jari, mulai lagi dari sisi seberang
            st.end()
            if (nx < left) sFx = left + (right - left) * 0.8f else if (nx > right) sFx = left + (right - left) * 0.2f
            if (ny < top) sFy = top + (bot - top) * 0.8f else if (ny > bot) sFy = top + (bot - top) * 0.2f
            st = Stroker(sFx, sFy)
            scroller = st
            nx = sFx - dx; ny = sFy - dy
        }
        sFx = nx.coerceIn(left, right); sFy = ny.coerceIn(top, bot)
        st.moveTo(sFx, sFy)
    }

    fun scrollEnd() {
        scroller?.end(); scroller = null
        accAx = 0f; accAy = 0f
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
