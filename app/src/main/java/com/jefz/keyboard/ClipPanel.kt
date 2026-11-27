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
    private val onPick: (String) -> Unit
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var items = mutableListOf<ClipItem>()
    private var shown = listOf<ClipItem>()
    private val sel = HashSet<ClipItem>()
    private var mode = 0 // 0 biasa, 1 pilih item untuk dihapus, 2 konfirmasi hapus
    private val bar = LinearLayout(ctx)
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
        sel.clear()
        mode = 0
        recompute()
        render()
    }

    private fun recompute() { shown = items.sortedByDescending { it.pinned } }

    private fun tab(t: String, col: Int = 0xFFFFFFFF.toInt(), f: () -> Unit) = TextView(context).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(col)
        setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        setOnClickListener { f() }
    }

    private fun render() {
        bar.removeAllViews()
        when (mode) {
            0 -> {
                bar.addView(tab("ABC") { onBack() })
                bar.addView(tab("🗑 Hapus…") { mode = 1; sel.clear(); render() })
            }
            1 -> {
                bar.addView(tab("Batal") { mode = 0; sel.clear(); render() })
                bar.addView(tab(if (shown.isNotEmpty() && sel.size == shown.size) "Semua ✓" else "Semua") {
                    if (sel.size == shown.size) sel.clear() else { sel.clear(); sel.addAll(shown) }
                    render()
                })
                bar.addView(tab("Hapus (${sel.size})", if (sel.isEmpty()) 0xFF6B6F80.toInt() else 0xFFFF6B6B.toInt()) {
                    if (sel.isNotEmpty()) { mode = 2; render() }
                })
            }
            else -> {
                val pins = sel.count { it.pinned }
                bar.addView(TextView(context).apply {
                    text = "Hapus ${sel.size} item" + (if (pins > 0) " (termasuk $pins pin)" else "") + "?"
                    textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((14 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
                })
                bar.addView(tab("YA, HAPUS", 0xFFFF6B6B.toInt()) {
                    items.removeAll { it in sel }
                    Clips.save(prefs, items)
                    sel.clear(); mode = 0; recompute(); render()
                })
                bar.addView(tab("Batal") { mode = 1; render() })
            }
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(c: ClipItem): View {
        val pad = (8 * d).toInt()
        val picked = c in sel
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(when {
                picked -> 0xFF3A2A2A.toInt()
                c.pinned -> 0xFF26304A.toInt()
                else -> 0xFF1B1B1B.toInt()
            })
            setPadding((12 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        }
        if (mode != 0) {
            row.addView(TextView(context).apply {
                text = if (picked) "☑" else "☐"; textSize = 22f
                setTextColor(if (picked) 0xFFFF6B6B.toInt() else 0xFF9AA0B5.toInt())
                setPadding(0, 0, pad + pad / 2, 0)
            })
        }
        row.addView(TextView(context).apply {
            text = c.text; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, pad, 0, pad)
            setOnClickListener {
                when (mode) {
                    0 -> onPick(c.text)
                    1 -> { if (c in sel) sel.remove(c) else sel.add(c); render() }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = if (c.pinned) "★" else "☆"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(if (c.pinned) 0xFFFFC107.toInt() else 0xFF9AA0B5.toInt())
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            if (mode == 0) setOnClickListener { c.pinned = !c.pinned; Clips.save(prefs, items); recompute(); render() }
        })
        return row
    }
}
