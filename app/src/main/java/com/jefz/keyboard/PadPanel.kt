package com.jefz.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class PadPanel(
    ctx: Context,
    private val prefs: SharedPreferences,
    private val onBack: () -> Unit,
    private val onNav: (Int, Int, Boolean) -> Unit, // arah horizontal, vertikal, seleksi
    private val onPaste: () -> Unit
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hd = Handler(Looper.getMainLooper())
    private val pad = RectF()
    private val vs = RectF()
    private val hs = RectF()
    private val bBack = RectF()
    private val bL = RectF()
    private val bR = RectF()

    // role: 0 pad, 1 slider vertikal, 2 slider horizontal, 3 klik kiri, 4 klik kanan, 5 kembali
    private class Ptr(val role: Int, var x: Float, var y: Float) { var ax = 0f; var ay = 0f; var mv = 0f }
    private val ptrs = HashMap<Int, Ptr>()
    private var padPointers = 0
    private var gStart = 0L
    private var moved = false
    private var twoFinger = false
    private var lastTapUp = 0L
    private var dragging = false
    private var lHeld = false
    private var lRun: Runnable? = null
    private var vOff = 0f
    private var hOff = 0f

    private fun svc(): PadService? = if (prefs.getString("padmode", "pointer") == "pointer") PadService.instance else null
    private fun scrollK() = 2f * prefs.getInt("scrollspeed", 150) / 100f * (if (prefs.getBoolean("scrollinv", false)) -1f else 1f)
    private fun buzz() { if (prefs.getBoolean("haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val g = 4 * d; val bar = 52 * d; val st = 34 * d
        val top = h - bar
        bBack.set(g, top + g, w * 0.2f - g / 2, h - g)
        bL.set(w * 0.2f + g / 2, top + g, w * 0.6f - g / 2, h - g)
        bR.set(w * 0.6f + g / 2, top + g, w - g, h - g)
        vs.set(g, g, g + st, top - g)
        hs.set(st + 2 * g, top - g - st, w - g, top - g)
        pad.set(st + 2 * g, g, w - g, top - 2 * g - st)
    }

    override fun onVisibilityChanged(cv: View, vis: Int) {
        super.onVisibilityChanged(cv, vis)
        if (vis != VISIBLE) {
            PadService.instance?.scrollEnd()
            if (dragging) PadService.instance?.dragEnd()
            dragging = false; lHeld = false; ptrs.clear(); padPointers = 0; vOff = 0f; hOff = 0f
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1B1B1B.toInt())
        fun box(r: RectF, col: Int) { paint.color = col; c.drawRoundRect(r, 10 * d, 10 * d, paint) }
        fun txt(s: String, r: RectF, size: Float, col: Int = 0xFFFFFFFF.toInt()) {
            paint.color = col; paint.textSize = size * d
            c.drawText(s, r.centerX(), r.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
        val held = ptrs.values.map { it.role }
        val s = svc()
        box(pad, 0xFF2A2A2A.toInt()); box(vs, 0xFF2C2C2C.toInt()); box(hs, 0xFF2C2C2C.toInt())
        box(bBack, 0xFF3A3A3A.toInt()); txt("⌨", bBack, 20f)
        box(bL, if (3 in held) 0xFF5C7CFA.toInt() else 0xFF3A3A3A.toInt()); txt(if (s != null) "KLIK KIRI" else "PILIH", bL, 14f)
        box(bR, if (4 in held) 0xFF5C7CFA.toInt() else 0xFF3A3A3A.toInt()); txt(if (s != null) "KLIK KANAN" else "TEMPEL", bR, 14f)
        paint.color = 0xFF7C93FF.toInt()
        c.drawRoundRect(vs.left + 4 * d, vs.centerY() - 16 * d + vOff, vs.right - 4 * d, vs.centerY() + 16 * d + vOff, 8 * d, 8 * d, paint)
        c.drawRoundRect(hs.centerX() - 16 * d + hOff, hs.top + 4 * d, hs.centerX() + 16 * d + hOff, hs.bottom - 4 * d, 8 * d, 8 * d, paint)
        val hint = when {
            prefs.getString("padmode", "pointer") == "pointer" && s == null -> "Aktifkan Layanan Aksesibilitas · sementara mode kursor teks"
            s != null -> "Geser: pointer · ketuk: klik · 2 jari: klik kanan"
            else -> "Geser: kursor teks · tahan PILIH: seleksi · slider: " + (if (PadService.instance != null) "scroll halaman" else "pindah kursor")
        }
        txt(hint, pad, 11f, 0xFF8A90A5.toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = svc()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i), s)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i), s)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex), s)
            MotionEvent.ACTION_CANCEL -> for (id in ptrs.keys.toList()) up(id, s)
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float, s: PadService?) {
        val r = when {
            bBack.contains(x, y) -> 5
            bL.contains(x, y) -> 3
            bR.contains(x, y) -> 4
            vs.contains(x, y) -> 1
            hs.contains(x, y) -> 2
            pad.contains(x, y) -> 0
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        ptrs[id] = Ptr(r, x, y)
        when (r) {
            0 -> {
                padPointers++
                if (padPointers == 1) {
                    gStart = now; moved = false; twoFinger = false
                    if (s != null && now - lastTapUp < 300) { lastTapUp = 0; dragging = true; s.dragStart() }
                } else twoFinger = true
            }
            3 -> {
                lHeld = true
                if (s != null) {
                    val run = Runnable { dragging = true; s.dragStart() }
                    lRun = run
                    hd.postDelayed(run, 250)
                }
            }
            4 -> { buzz(); if (s != null) s.rightClick() else onPaste() }
            5 -> onBack()
        }
    }

    private fun move(id: Int, x: Float, y: Float, s: PadService?) {
        val p = ptrs[id] ?: return
        val dx = x - p.x
        val dy = y - p.y
        p.x = x; p.y = y
        when (p.role) {
            0 -> {
                p.mv += abs(dx) + abs(dy)
                if (p.mv > 10 * d) moved = true
                if (padPointers > 1) return
                if (s != null) {
                    val f = prefs.getInt("padspeed", 150) / 100f * (1f + min(hypot(dx, dy), 30 * d) / (30 * d) * 0.8f)
                    s.move(dx * f, dy * f)
                    if (dragging) s.dragPump()
                } else {
                    p.ax += dx; p.ay += dy
                    val sx = 14 * d; val sy = 26 * d
                    while (p.ax >= sx) { p.ax -= sx; onNav(1, 0, lHeld) }
                    while (p.ax <= -sx) { p.ax += sx; onNav(-1, 0, lHeld) }
                    while (p.ay >= sy) { p.ay -= sy; onNav(0, 1, lHeld) }
                    while (p.ay <= -sy) { p.ay += sy; onNav(0, -1, lHeld) }
                }
            }
            1 -> {
                val lim = vs.height() / 2 - 16 * d
                vOff = (vOff + dy).coerceIn(-lim, lim)
                val ss = PadService.instance
                if (ss != null) ss.scrollBy(0f, dy * scrollK()) else {
                    p.ay += dy
                    val st = 22 * d
                    while (p.ay >= st) { p.ay -= st; onNav(0, 1, false) }
                    while (p.ay <= -st) { p.ay += st; onNav(0, -1, false) }
                }
            }
            2 -> {
                val lim = hs.width() / 2 - 16 * d
                hOff = (hOff + dx).coerceIn(-lim, lim)
                val ss = PadService.instance
                if (ss != null) ss.scrollBy(dx * scrollK(), 0f) else {
                    p.ax += dx
                    val st = 22 * d
                    while (p.ax >= st) { p.ax -= st; onNav(1, 0, false) }
                    while (p.ax <= -st) { p.ax += st; onNav(-1, 0, false) }
                }
            }
        }
    }

    private fun up(id: Int, s: PadService?) {
        val p = ptrs.remove(id) ?: return
        val now = SystemClock.uptimeMillis()
        when (p.role) {
            0 -> {
                padPointers--
                if (padPointers <= 0) {
                    padPointers = 0
                    if (dragging && !lHeld) { dragging = false; s?.dragEnd() }
                    else if (!moved && now - gStart < 300 && s != null) {
                        if (twoFinger) { buzz(); s.rightClick() }
                        else if (prefs.getBoolean("taptoclick", true)) { buzz(); s.click(); lastTapUp = now }
                    }
                }
            }
            3 -> {
                lHeld = false
                lRun?.let { hd.removeCallbacks(it) }
                lRun = null
                if (dragging) { dragging = false; s?.dragEnd() } else if (s != null) { buzz(); s.click() }
            }
            1 -> { vOff = 0f; PadService.instance?.scrollEnd() }
            2 -> { hOff = 0f; PadService.instance?.scrollEnd() }
        }
    }
}
