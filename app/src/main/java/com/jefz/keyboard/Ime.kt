package com.jefz.keyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class Ime : InputMethodService() {
    private lateinit var v: KeyboardView
    private var shift = 0 // 0 off, 1 once, 2 caps
    private val mods = HashMap<String, Int>() // 0 off, 1 once, 2 lock
    private val prefs by lazy { getSharedPreferences("kb", MODE_PRIVATE) }

    override fun onCreateInputView(): View {
        v = KeyboardView(this)
        v.onKey = { handle(it) }
        v.active = { (it.type == "mod" && (mods[it.value] ?: 0) > 0) || (it.type == "act" && it.value == "SHIFT" && shift > 0) }
        v.shifted = { shift > 0 }
        return v
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        v.layers = Layouts.load(prefs)
        v.haptic = prefs.getBoolean("haptic", true)
        v.setLayer("abc")
        mods.clear(); shift = 0
        val text = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        if (text && autocap() && currentInputConnection?.getTextBeforeCursor(1, 0).isNullOrEmpty()) shift = 1
        v.invalidate()
    }

    private fun autocap() = prefs.getBoolean("autocap", true)

    private fun handle(k: Key) {
        val ic = currentInputConnection ?: return
        when (k.type) {
            "char" -> typeChar(ic, k.value)
            "code" -> { val c = keyCode(k.value); if (c != KeyEvent.KEYCODE_UNKNOWN) send(ic, c); consume() }
            "mod" -> mods[k.value] = ((mods[k.value] ?: 0) + 1) % 3
            "act" -> act(ic, k.value)
        }
        v.invalidate()
    }

    private fun typeChar(ic: InputConnection, s: String) {
        if (hasMods() && s.length == 1 && s[0].isLetterOrDigit()) {
            val c = keyCode(s.uppercase())
            if (c != KeyEvent.KEYCODE_UNKNOWN) { send(ic, c); consume(); return }
        }
        ic.commitText(if (shift > 0) s.uppercase() else s, 1)
        consume()
    }

    private fun act(ic: InputConnection, a: String) {
        when {
            a == "SHIFT" -> shift = (shift + 1) % 3
            a == "DEL" -> { send(ic, KeyEvent.KEYCODE_DEL); consume() }
            a == "ENTER" -> { enter(ic); consume() }
            a == "SPACE" -> {
                if (hasMods()) send(ic, KeyEvent.KEYCODE_SPACE) else ic.commitText(" ", 1)
                consume(); autoCap(ic)
            }
            a.startsWith("LAYER:") -> v.setLayer(a.substring(6))
        }
    }

    private fun enter(ic: InputConnection) {
        val o = currentInputEditorInfo?.imeOptions ?: 0
        val act = o and EditorInfo.IME_MASK_ACTION
        if (o and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(act)
        else send(ic, KeyEvent.KEYCODE_ENTER)
    }

    private fun autoCap(ic: InputConnection) {
        if (!autocap() || shift != 0) return
        val t = ic.getTextBeforeCursor(2, 0)?.toString() ?: return
        if (t.length == 2 && t[1] == ' ' && t[0] in ".!?") shift = 1
    }

    private fun hasMods() = mods.values.any { it > 0 }

    private fun consume() {
        if (shift == 1) shift = 0
        mods.entries.removeAll { it.value == 1 }
    }

    private fun meta(): Int {
        var m = 0
        if ((mods["CTRL"] ?: 0) > 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if ((mods["ALT"] ?: 0) > 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if ((mods["META"] ?: 0) > 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (shift > 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return m
    }

    private fun send(ic: InputConnection, code: Int) {
        val t = SystemClock.uptimeMillis(); val m = meta()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, m))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, m))
    }

    private fun keyCode(n: String) = KeyEvent.keyCodeFromString("KEYCODE_$n")
}
