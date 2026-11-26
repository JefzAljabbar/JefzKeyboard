package com.jefz.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch

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
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        fun sw(t: String, key: String) {
            root.addView(Switch(this).apply {
                text = t; isChecked = p.getBoolean(key, true)
                setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
            })
        }
        btn("1. Aktifkan keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("2. Pilih keyboard") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        sw("Auto kapital setelah titik", "autocap")
        sw("Getar saat ketik", "haptic")
        btn("Reset tata letak ke default") { p.edit().remove("layout").apply() }
        root.addView(EditText(this).apply { hint = "Tes ketik di sini" })
        setContentView(root)
    }
}
