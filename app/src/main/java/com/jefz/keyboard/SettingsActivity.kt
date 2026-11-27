package com.jefz.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private var status: TextView? = null

    override fun onResume() {
        super.onResume()
        status?.text = if (PadService.instance != null) "Layanan Aksesibilitas: AKTIF" else "Layanan Aksesibilitas: belum aktif"
    }

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
        fun sw(t: String, key: String, def: Boolean = true) {
            root.addView(Switch(this).apply {
                text = t; isChecked = p.getBoolean(key, def)
                setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
            })
        }
        fun label(t: String) {
            root.addView(TextView(this).apply { text = t; textSize = 16f; setPadding(0, (16 * d).toInt(), 0, 0) })
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

        label("Touchpad")
        val rg = RadioGroup(this)
        rg.addView(RadioButton(this).apply { text = "Mode pointer layar (butuh Aksesibilitas)"; id = 101 })
        rg.addView(RadioButton(this).apply { text = "Mode kursor teks (tanpa izin tambahan)"; id = 102 })
        rg.check(if (p.getString("padmode", "pointer") == "text") 102 else 101)
        rg.setOnCheckedChangeListener { _, id -> p.edit().putString("padmode", if (id == 102) "text" else "pointer").apply() }
        root.addView(rg)
        btn("Aktifkan Layanan Aksesibilitas") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        status = TextView(this).also { root.addView(it) }
        slider("Kecepatan pointer", "padspeed", 50, 400, 150)
        sw("Ketuk pad = klik kiri", "taptoclick")
        sw("Arah scroll dibalik", "scrollinv", false)

        root.addView(EditText(this).apply { hint = "Tes ketik di sini" })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
