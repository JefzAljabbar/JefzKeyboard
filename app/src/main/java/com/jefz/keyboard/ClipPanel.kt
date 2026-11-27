package com.jefz.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class ClipItem(val text: String, var pinned: Boolean)

object Clips {
    fun load(p: SharedPreferences): MutableList<ClipItem> = try {
        val a = JSONArray(p.getString("clips", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ClipItem(o.getString("t"), o.optBoolean("p", false))
        }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    fun save(p: SharedPreferences, l: List<ClipItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.text).put("p", it.pinned)) }
        p.edit().putString("clips", a.toString()).apply()
    }

    // pin = true: selalu disimpan (favorit); riwayat biasa dibatasi 40 item dan bisa dimatikan
    fun add(p: SharedPreferences, text: String, pin: Boolean) {
        if (!pin && !p.getBoolean("cliphist", true)) return
        if (text.isBlank()) return
        val t = if (text.length > 10000) text.substring(0, 10000) else text
        val l = load(p)
        val old = l.firstOrNull { it.text == t }
        l.removeAll { it.text == t }
        l.add(0, ClipItem(t, pin || (old?.pinned ?: false)))
        var n = 0
        val iter = l.iterator()
        while (iter.hasNext()) { val c = iter.next(); if (!c.pinned && ++n > 40) iter.remove() }
        save(p, l)
    }
}

class ClipPanel(
    ctx: Context,
    private val prefs: SharedPreferences,
    private val onBack: () -> Unit,
    private val onPick: (String) -> Unit,
    private val newPin: () -> String?
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var items = mutableListOf<ClipItem>()
    private var shown = listOf<ClipItem>()
    private val list = ListView(ctx)
    private val empty = TextView(ctx)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View = row(shown[p])
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1B1B1B.toInt())
        val bar = LinearLayout(ctx)
        fun tab(t: String, f: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            setOnClickListener { f() }
        }
        bar.addView(tab("ABC") { onBack() })
        bar.addView(tab("＋ Pin") {
            newPin()?.takeIf { it.isNotBlank() }?.let { Clips.add(prefs, it, true); refresh() }
        })
        bar.addView(tab("Hapus riwayat") { items.removeAll { !it.pinned }; Clips.save(prefs, items); refresh() })
        addView(HorizontalScrollView(ctx).apply { addView(bar) })

        list.adapter = adapter
        list.divider = null
        empty.text = "Belum ada riwayat copy/cut.\nSalin teks di app mana pun."
        empty.gravity = Gravity.CENTER
        empty.setTextColor(0xFF8A90A5.toInt())
        val box = FrameLayout(ctx)
        box.addView(list)
        box.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        items = Clips.load(prefs)
        shown = items.sortedByDescending { it.pinned }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(c: ClipItem): View {
        val pad = (8 * d).toInt()
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (c.pinned) 0xFF26304A.toInt() else 0xFF1B1B1B.toInt())
            setPadding((12 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        }
        row.addView(TextView(context).apply {
            text = c.text; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, pad, 0, pad)
            setOnClickListener { onPick(c.text) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(t: String, col: Int, f: () -> Unit) = TextView(context).apply {
            text = t; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(col)
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            setOnClickListener { f() }
        }
        row.addView(btn(if (c.pinned) "★" else "☆", if (c.pinned) 0xFFFFC107.toInt() else 0xFF9AA0B5.toInt()) {
            c.pinned = !c.pinned; Clips.save(prefs, items); refresh()
        })
        row.addView(btn("✕", 0xFF9AA0B5.toInt()) { items.remove(c); Clips.save(prefs, items); refresh() })
        return row
    }
}
