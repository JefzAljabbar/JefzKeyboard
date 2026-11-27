package com.jefz.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class EditorView(ctx: Context, private val onEdit: (Key) -> Unit) : KeyboardView(ctx) {
    var snap = true
    private var sel: Key? = null
    private var sx = 0f; private var sy = 0f; private var ox = 0f; private var oy = 0f
    private var moved = false
    private val grid = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
    }

    override fun rowH(): Float = 56 * d

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val l = layer ?: return
        if (snap) {
            for (i in 1 until 20) c.drawLine(i * width / 20f, 0f, i * width / 20f, height.toFloat(), grid)
            var y = 0.5f
            while (y < l.rows) { c.drawLine(0f, y * rowH(), width.toFloat(), y * rowH(), grid); y += 0.5f }
        }
        sel?.let { s -> cells.firstOrNull { it.second === s }?.let { c.drawRoundRect(it.first, 8 * d, 8 * d, ring) } }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = layer ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sel = cells.lastOrNull { it.first.contains(e.x, e.y) }?.second
                sel?.let { sx = e.x; sy = e.y; ox = it.x; oy = it.y }
                moved = false; invalidate()
            }
            MotionEvent.ACTION_MOVE -> sel?.let { k ->
                val dx = e.x - sx; val dy = e.y - sy
                if (!moved && abs(dx) + abs(dy) < 8 * d) return true
                moved = true
                var nx = ox + dx / width
                var ny = oy + dy / rowH()
                if (snap) { nx = Math.round(nx * 20) / 20f; ny = Math.round(ny * 2) / 2f }
                k.x = nx.coerceIn(0f, maxOf(0f, 1f - k.w))
                k.y = ny.coerceIn(0f, maxOf(0f, l.rows - k.h))
                build(); invalidate()
            }
            MotionEvent.ACTION_UP -> { if (!moved) sel?.let { onEdit(it) }; invalidate() }
        }
        return true
    }
}

class LayoutEditorActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private var layers = mutableListOf<Layer>()
    private var cur = 0
    private lateinit var ev: EditorView
    private lateinit var layerBtn: Button
    private lateinit var snapBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("kb", MODE_PRIVATE)
        layers = Layouts.load(prefs).toMutableList()
        val d = resources.displayMetrics.density
        ev = EditorView(this) { editKey(it) }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { f() }
        }
        fun bar(vararg bs: Button) = HorizontalScrollView(this).apply {
            addView(LinearLayout(this@LayoutEditorActivity).apply { bs.forEach { addView(it) } })
        }
        layerBtn = btn("Layer") { pickLayer() }
        snapBtn = btn("Snap: ON") {
            ev.snap = !ev.snap
            snapBtn.text = if (ev.snap) "Snap: ON" else "Bebas"
            ev.invalidate()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (28 * d).toInt(), 0, 0)
            setBackgroundColor(0xFF121212.toInt())
            addView(bar(layerBtn, snapBtn, btn("+ Tombol") { addKey() }, btn("+ Baris") { rows(1) }, btn("− Baris") { rows(-1) }))
            addView(bar(btn("Layer baru") { newLayer() }, btn("Hapus layer") { delLayer() },
                btn("Ekspor") { exportLayout() }, btn("Impor") { importLayout() }, btn("Reset") { reset() }, btn("Selesai") { finish() }))
            addView(TextView(this@LayoutEditorActivity).apply {
                text = "Seret tombol untuk memindah · ketuk untuk edit · otomatis tersimpan"
                textSize = 12f; setTextColor(0xFF9AA0B5.toInt()); setPadding((12 * d).toInt(), (6 * d).toInt(), 0, (6 * d).toInt())
            })
            addView(ev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        show()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("layout2", Layouts.toJson(layers)).apply()
    }

    private fun show() {
        ev.showLayer(layers[cur])
        layerBtn.text = "Layer: ${layers[cur].name}"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun pickLayer() {
        AlertDialog.Builder(this).setItems(layers.map { it.name }.toTypedArray()) { _, i -> cur = i; show() }.show()
    }

    private fun addKey() {
        val cats = LinkedHashMap(Catalog.cats)
        cats["Pindah layer"] = layers.map { Key("▤ ${it.name}", "act", "LAYER:${it.name}", w = 0.15f) }
        val names = cats.keys.toList()
        AlertDialog.Builder(this).setTitle("Kategori").setItems(names.toTypedArray()) { _, i ->
            val list = cats[names[i]]!!
            val shown = list.map { "${it.label.ifEmpty { "(kosong)" }}   ${if (it.type == "char") "" else it.value}" }
            AlertDialog.Builder(this).setTitle(names[i]).setItems(shown.toTypedArray()) { _, j ->
                val k = list[j].copy(x = 0f, y = 0f)
                layers[cur].keys.add(k)
                ev.showLayer(layers[cur])
                if (k.value.isEmpty()) editKey(k)
            }.show()
        }.show()
    }

    private fun rows(delta: Int) {
        val l = layers[cur]
        val n = l.rows + delta
        if (n < 1) return
        l.rows = n
        l.keys.forEach { if (it.y + it.h > n) it.y = maxOf(0f, n - it.h) }
        ev.showLayer(l)
    }

    private fun newLayer() {
        val name = EditText(this).apply { hint = "Nama layer (tanpa spasi)" }
        AlertDialog.Builder(this).setTitle("Layer baru").setView(name)
            .setPositiveButton("OK") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty() || layers.any { it.name == n }) toast("Nama kosong/sudah ada")
                else { layers.add(Layer(n, 4, mutableListOf())); cur = layers.size - 1; show() }
            }.setNegativeButton("Batal", null).show()
    }

    private fun delLayer() {
        if (layers[cur].name == "abc" || layers.size < 2) { toast("Layer abc tidak bisa dihapus"); return }
        layers.removeAt(cur); cur = 0; show()
    }

    private fun exportLayout() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("layout", Layouts.toJson(layers)))
        toast("Layout disalin ke clipboard")
    }

    private fun importLayout() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
        try {
            val ls = Layouts.fromJson(t)
            if (ls.none { it.name == "abc" }) throw Exception("no abc")
            layers = ls.toMutableList(); cur = 0; show(); toast("Layout diimpor")
        } catch (e: Exception) { toast("Clipboard bukan layout yang valid") }
    }

    private fun reset() {
        AlertDialog.Builder(this).setMessage("Kembalikan semua layer ke default?")
            .setPositiveButton("Ya") { _, _ -> layers = Layouts.default().toMutableList(); cur = 0; show() }
            .setNegativeButton("Batal", null).show()
    }

    private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun editKey(k: Key) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun field(h: String, v: String, num: Boolean = false): EditText = EditText(this).apply {
            hint = h; setText(v)
            if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            box.addView(this)
        }
        val types = listOf("char", "code", "mod", "act", "combo")
        val fl = field("Label", k.label)
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@LayoutEditorActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(maxOf(0, types.indexOf(k.type)))
            box.addView(this)
        }
        val fv = field("Nilai (teks / nama KEYCODE / aksi)", k.value)
        val fp = field("Tekan lama (tipe:nilai, mis. char:1)", k.lp)
        val fw = field("Lebar (1 = satu tombol standar)", f2(k.w * 10), true)
        val fh = field("Tinggi (baris)", f2(k.h), true)
        box.addView(TextView(this).apply {
            text = "char=teks · code=KEYCODE (DPAD_LEFT) · mod=CTRL/ALT/META · combo=CTRL+SHIFT+P · " +
                "act=SHIFT/DEL/ENTER/SPACE/EMOJI/HIDE/SWITCH/SETTINGS/LAYER:nama"
            textSize = 11f
        })
        AlertDialog.Builder(this).setTitle("Edit tombol").setView(box)
            .setPositiveButton("OK") { _, _ ->
                k.label = fl.text.toString(); k.type = sp.selectedItem as String
                k.value = fv.text.toString(); k.lp = fp.text.toString().trim()
                fw.text.toString().replace(',', '.').toFloatOrNull()?.let { if (it > 0) k.w = (it / 10f).coerceAtMost(1f) }
                fh.text.toString().replace(',', '.').toFloatOrNull()?.let { if (it > 0) k.h = it }
                k.x = k.x.coerceIn(0f, maxOf(0f, 1f - k.w))
                k.y = k.y.coerceIn(0f, maxOf(0f, layers[cur].rows - k.h))
                ev.showLayer(layers[cur])
            }
            .setNeutralButton("Hapus") { _, _ -> layers[cur].keys.removeAll { it === k }; ev.showLayer(layers[cur]) }
            .setNegativeButton("Batal", null).show()
    }
}
