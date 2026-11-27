package com.jefz.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class EmojiView(
    ctx: Context,
    private val prefs: SharedPreferences,
    private val onPick: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onDel: () -> Unit
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = GridView(ctx)
    private var items: List<String> = emptyList()
    private val cache = HashMap<String, List<String>>()
    private val ranges = linkedMapOf(
        "😀" to listOf(0x1F600..0x1F64F, 0x1F910..0x1F92F),
        "👍" to listOf(0x1F440..0x1F4AF),
        "🐶" to listOf(0x1F400..0x1F43F, 0x1F980..0x1F9AE, 0x1F330..0x1F344),
        "🍔" to listOf(0x1F345..0x1F37F, 0x1F950..0x1F96F),
        "⚽" to listOf(0x1F380..0x1F3D3),
        "🚗" to listOf(0x1F3E0..0x1F3F0, 0x1F680..0x1F6C5, 0x1F30D..0x1F320),
        "💡" to listOf(0x1F4B0..0x1F4FF),
        "❤" to listOf(0x2600..0x27BF, 0x1F500..0x1F53D),
        "→" to listOf(0x2190..0x21FF, 0x2200..0x22FF)
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1B1B.toInt())
        val bar = LinearLayout(ctx)
        fun tab(t: String, f: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            setOnClickListener { f() }
        }
        bar.addView(tab("ABC") { onBack() })
        bar.addView(tab("🕘") { load("🕘") })
        ranges.keys.forEach { key -> bar.addView(tab(key) { load(key) }) }
        bar.addView(tab("⌫") { onDel() })
        addView(HorizontalScrollView(ctx).apply { addView(bar) })

        grid.numColumns = 8
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View =
                ((v as? TextView) ?: TextView(ctx).apply {
                    textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                }).also { it.text = items[p] }
        }
        grid.setOnItemClickListener { _, _, i, _ -> val s = items[i]; onPick(s); addRecent(s) }
        addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        val recent = (prefs.getString("recent", "") ?: "").isNotEmpty()
        load(if (recent) "🕘" else "😀")
    }

    private fun load(key: String) {
        items = if (key == "🕘") (prefs.getString("recent", "") ?: "").split(" ").filter { it.isNotEmpty() }
        else cache.getOrPut(key) {
            val p = Paint()
            ranges[key]!!.flatMap { r ->
                r.mapNotNull { cp -> String(Character.toChars(cp)).takeIf { s -> p.hasGlyph(s) } }
            }
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun addRecent(s: String) {
        val l = (prefs.getString("recent", "") ?: "").split(" ").filter { it.isNotEmpty() && it != s }.toMutableList()
        l.add(0, s)
        prefs.edit().putString("recent", l.take(40).joinToString(" ")).apply()
    }
}
