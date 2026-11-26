package com.jefz.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// type: char | code (Android KEYCODE_ name) | mod (CTRL/ALT/META) | act (SHIFT/DEL/ENTER/SPACE/LAYER:x)
data class Key(val label: String, val type: String, val value: String, val w: Float = 1f)
data class Layer(val name: String, val rows: MutableList<MutableList<Key>>)

object Layouts {
    private fun c(s: String, w: Float = 1f) = Key(s, "char", s, w)
    private fun a(l: String, v: String, w: Float = 1f) = Key(l, "act", v, w)
    private fun k(l: String, code: String, w: Float = 1f) = Key(l, "code", code, w)
    private fun m(l: String, v: String, w: Float = 1f) = Key(l, "mod", v, w)
    private fun row(s: String) = s.map { c(it.toString()) }.toMutableList()
    private fun bottom(first: Key) = mutableListOf(first, c(","), a("space", "SPACE", 5f), c("."), a("⏎", "ENTER", 1.5f))

    fun default(): List<Layer> {
        val abc = Layer("abc", mutableListOf(
            row("qwertyuiop"), row("asdfghjkl"),
            mutableListOf(a("⇧", "SHIFT", 1.5f)).apply { addAll(row("zxcvbnm")); add(a("⌫", "DEL", 1.5f)) },
            bottom(a("?123", "LAYER:sym", 1.5f))))
        val sym = Layer("sym", mutableListOf(
            row("1234567890"), row("@#$%&-+()/"),
            mutableListOf(a("Fn", "LAYER:fn", 1.5f)).apply { addAll(row("!\"'*:;?")); add(a("⌫", "DEL", 1.5f)) },
            bottom(a("ABC", "LAYER:abc", 1.5f))))
        val fn = Layer("fn", mutableListOf(
            mutableListOf(k("Esc", "ESCAPE"), k("Tab", "TAB"), m("Ctrl", "CTRL"), m("Alt", "ALT"), m("Win", "META"), a("⇧", "SHIFT")),
            (1..6).map { k("F$it", "F$it") }.toMutableList(),
            (7..12).map { k("F$it", "F$it") }.toMutableList(),
            mutableListOf(k("←", "DPAD_LEFT"), k("↑", "DPAD_UP"), k("↓", "DPAD_DOWN"), k("→", "DPAD_RIGHT"), k("Home", "MOVE_HOME"), k("End", "MOVE_END")),
            mutableListOf(k("PgUp", "PAGE_UP"), k("PgDn", "PAGE_DOWN"), k("Ins", "INSERT"), k("Del", "FORWARD_DEL"), k("PrtSc", "SYSRQ")),
            mutableListOf(a("ABC", "LAYER:abc", 1.5f), a("⌫", "DEL"), a("space", "SPACE", 3f), a("⏎", "ENTER", 1.5f))))
        return listOf(abc, sym, fn)
    }

    fun toJson(ls: List<Layer>): String = JSONArray().apply {
        ls.forEach { l ->
            put(JSONObject().put("name", l.name).put("rows", JSONArray().apply {
                l.rows.forEach { r ->
                    put(JSONArray().apply {
                        r.forEach { k -> put(JSONObject().put("l", k.label).put("t", k.type).put("v", k.value).put("w", k.w.toDouble())) }
                    })
                }
            }))
        }
    }.toString()

    fun fromJson(s: String): List<Layer> {
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val rs = o.getJSONArray("rows")
            Layer(o.getString("name"), (0 until rs.length()).map { j ->
                val r = rs.getJSONArray(j)
                (0 until r.length()).map { n ->
                    val k = r.getJSONObject(n)
                    Key(k.getString("l"), k.getString("t"), k.getString("v"), k.getDouble("w").toFloat())
                }.toMutableList()
            }.toMutableList())
        }
    }

    fun load(p: SharedPreferences): List<Layer> =
        try { p.getString("layout", null)?.let { fromJson(it) } ?: default() } catch (e: Exception) { default() }
}
