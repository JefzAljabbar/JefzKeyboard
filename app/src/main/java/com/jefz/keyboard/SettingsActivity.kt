package com.jefz.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("kb", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (32 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        fun btn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } })
        }
        fun sw(t: String, key: String) {
            root.addView(Switch(this).apply {
                text = t; isChecked = p.getBoolean(key, true)
                setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
            })
        }
        fun slider(t: String, key: String, min: Int, max: Int, def: Int) {
            val tv = TextView(this)
            val sb = SeekBar(this)
            val cur = p.getInt(key, def)
            sb.max = max - min
            sb.progress = cur - min
            tv.text = "$t: $cur%"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                    tv.text = "$t: ${v + min}%"
                    p.edit().putInt(key, v + min).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            tv.setPadding(0, (12 * d).toInt(), 0, 0)
            root.addView(tv); root.addView(sb)
        }
        btn("1. Aktifkan keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("2. Pilih keyboard") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        btn("Edit tata letak") { startActivity(Intent(this, LayoutEditorActivity::class.java)) }
        sw("Auto kapital setelah titik", "autocap")
        sw("Enter = baris baru (tidak kirim pesan)", "enternl")
        sw("Getar saat ketik", "haptic")
        slider("Tinggi keyboard", "hpct", 60, 160, 100)
        slider("Ukuran font", "fpct", 60, 160, 100)
        root.addView(EditText(this).apply { hint = "Tes ketik di sini" })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
