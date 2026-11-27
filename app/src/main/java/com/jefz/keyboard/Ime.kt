package com.jefz.keyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

class Ime : InputMethodService() {
    private lateinit var v: KeyboardView
    private lateinit var emoji: EmojiView
    private var layers: List<Layer> = emptyList()
    private var shift = 0 // 0 off, 1 sekali, 2 caps
    private val mods = HashMap<String, Int>() // 0 off, 1 sekali, 2 lock
    private val prefs by lazy { getSharedPreferences("kb", MODE_PRIVATE) }

    private val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val ALT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val WIN = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    private val SHF = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        v = KeyboardView(this)
        v.onKey = { handle(it) }
        v.active = { (it.type == "mod" && (mods[it.value] ?: 0) > 0) || (it.type == "act" && it.value == "SHIFT" && shift > 0) }
        v.shifted = { shift > 0 }
        emoji = EmojiView(this, prefs, { commit(it) }, { showKb() }, {
            currentInputConnection?.let { ic -> send(ic, KeyEvent.KEYCODE_DEL) }
        })
        emoji.visibility = View.GONE
        return FrameLayout(this).apply {
            addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(emoji, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (270 * d).toInt()))
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        layers = Layouts.load(prefs)
        v.haptic = prefs.getBoolean("haptic", true)
        v.showLayer(layers.firstOrNull { it.name == "abc" } ?: layers.firstOrNull())
        showKb()
        mods.clear(); shift = 0
        val text = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        if (text && autocap() && currentInputConnection?.getTextBeforeCursor(1, 0).isNullOrEmpty()) shift = 1
        v.invalidate()
    }

    private fun showKb() { emoji.visibility = View.GONE; v.visibility = View.VISIBLE }
    private fun showEmoji() { emoji.refresh(); v.visibility = View.GONE; emoji.visibility = View.VISIBLE }
    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }
    private fun autocap() = prefs.getBoolean("autocap", true)

    private fun handle(k: Key) {
        val ic = currentInputConnection ?: return
        when (k.type) {
            "char" -> typeChar(ic, k.value)
            "code" -> { val c = keyCode(k.value); if (c != KeyEvent.KEYCODE_UNKNOWN) send(ic, c); consume() }
            "mod" -> mods[k.value] = ((mods[k.value] ?: 0) + 1) % 3
            "combo" -> combo(ic, k.value)
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

    private fun combo(ic: InputConnection, s: String) {
        val p = s.split("+").map { it.trim().uppercase() }
        var m = meta()
        p.dropLast(1).forEach {
            when (it) { "CTRL" -> m = m or CTRL; "ALT" -> m = m or ALT; "META", "WIN" -> m = m or WIN; "SHIFT" -> m = m or SHF }
        }
        val c = keyCode(p.last())
        if (c != KeyEvent.KEYCODE_UNKNOWN) send(ic, c, m)
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
            a == "EMOJI" -> showEmoji()
            a == "HIDE" -> requestHideSelf(0)
            a == "SWITCH" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            a == "SETTINGS" -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            a.startsWith("LAYER:") -> {
                val n = a.substring(6)
                layers.firstOrNull { it.name == n }?.let { v.showLayer(it) }
            }
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
        if ((mods["CTRL"] ?: 0) > 0) m = m or CTRL
        if ((mods["ALT"] ?: 0) > 0) m = m or ALT
        if ((mods["META"] ?: 0) > 0) m = m or WIN
        if (shift > 0) m = m or SHF
        return m
    }

    private fun send(ic: InputConnection, code: Int, m: Int = meta()) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, m))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, m))
    }

    private fun keyCode(n: String) = KeyEvent.keyCodeFromString("KEYCODE_$n")
}
