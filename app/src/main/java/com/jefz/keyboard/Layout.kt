package com.jefz.keyboard

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// type: char (teks) | code (nama KEYCODE) | mod (CTRL/ALT/META) | combo (CTRL+SHIFT+P)
//       act (SHIFT/DEL/ENTER/SPACE/EMOJI/HIDE/SWITCH/SETTINGS/LAYER:nama)
// x,w = fraksi lebar keyboard; y,h = dalam baris. lp = tekan lama "tipe:nilai"
data class Key(
    var label: String, var type: String, var value: String,
    var x: Float = 0f, var y: Float = 0f, var w: Float = 0.1f, var h: Float = 1f, var lp: String = ""
) {
    companion object {
        fun spec(s: String): Key {
            val i = s.indexOf(':')
            return if (i < 0) Key(s, "char", s) else Key(s, s.substring(0, i), s.substring(i + 1))
        }
    }
}

class Layer(var name: String, var rows: Int, val keys: MutableList<Key>)

object Layouts {
    private fun mk(l: String, t: String, v: String, w: Float = 1f, lp: String = "") = Key(l, t, v, 0f, 0f, w, 1f, lp)
    private fun c(s: String, lp: String = "") = mk(s, "char", s, 1f, lp)
    private fun a(l: String, v: String, w: Float = 1f, lp: String = "") = mk(l, "act", v, w, lp)
    private fun k(l: String, code: String) = mk(l, "code", code)
    private fun m(l: String, v: String) = mk(l, "mod", v)
    private fun chars(s: String) = s.map { c(it.toString()) }

    // tiap baris: (daftar tombol dengan bobot lebar, margin kiri/kanan)
    private fun lay(rows: List<Pair<List<Key>, Float>>): MutableList<Key> {
        val out = ArrayList<Key>()
        rows.forEachIndexed { ri, (r, st) ->
            val tot = r.sumOf { it.w.toDouble() }.toFloat() + 2 * st
            var x = st
            r.forEach { key ->
                val w = key.w
                key.x = x / tot; key.w = w / tot; key.y = ri.toFloat()
                out.add(key); x += w
            }
        }
        return out
    }

    fun default(): List<Layer> {
        val top = "qwertyuiop".mapIndexed { i, ch -> c(ch.toString(), "char:${(i + 1) % 10}") }
        val bottom = { first: Key ->
            listOf(first, c(","), a("😀", "EMOJI"), a("space", "SPACE", 4f), c("."), a("⏎", "ENTER", 1.5f, "act:ACTION"))
        }
        val abc = Layer("abc", 4, lay(listOf(
            top to 0f,
            chars("asdfghjkl") to 0.5f,
            (listOf(a("⇧", "SHIFT", 1.5f)) + chars("zxcvbnm") + a("⌫", "DEL", 1.5f)) to 0f,
            bottom(a("?123", "LAYER:sym", 1.5f)) to 0f)))
        val sym = Layer("sym", 5, lay(listOf(
            chars("1234567890") to 0f,
            chars("@#\$%&-+()/") to 0f,
            chars("_=[]{}<>\\|") to 0f,
            (listOf(a("Fn", "LAYER:fn", 1.5f)) + chars("!\"'*:;?") + a("⌫", "DEL", 1.5f)) to 0f,
            bottom(a("ABC", "LAYER:abc", 1.5f)) to 0f)))
        val fn = Layer("fn", 5, lay(listOf(
            listOf(k("Esc", "ESCAPE"), k("Tab", "TAB"), m("Ctrl", "CTRL"), m("Alt", "ALT"), m("Win", "META"), a("⇧", "SHIFT")) to 0f,
            (1..6).map { k("F$it", "F$it") } to 0f,
            (7..12).map { k("F$it", "F$it") } to 0f,
            listOf(k("←", "DPAD_LEFT"), k("↑", "DPAD_UP"), k("↓", "DPAD_DOWN"), k("→", "DPAD_RIGHT"), k("Home", "MOVE_HOME"), k("End", "MOVE_END")) to 0f,
            listOf(k("PgUp", "PAGE_UP"), k("PgDn", "PAGE_DOWN"), k("Ins", "INSERT"), k("Del", "FORWARD_DEL"), a("⌫", "DEL"), a("🖱", "TOUCHPAD"), a("ABC", "LAYER:abc")) to 0f)))
        return listOf(abc, sym, fn)
    }

    fun toJson(ls: List<Layer>): String = JSONArray().apply {
        ls.forEach { l ->
            put(JSONObject().put("name", l.name).put("rows", l.rows).put("keys", JSONArray().apply {
                l.keys.forEach { k ->
                    put(JSONObject().put("l", k.label).put("t", k.type).put("v", k.value)
                        .put("x", k.x.toDouble()).put("y", k.y.toDouble())
                        .put("w", k.w.toDouble()).put("h", k.h.toDouble()).put("lp", k.lp))
                }
            }))
        }
    }.toString()

    fun fromJson(s: String): List<Layer> {
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ks = o.getJSONArray("keys")
            Layer(o.getString("name"), o.getInt("rows"), (0 until ks.length()).map { j ->
                val k = ks.getJSONObject(j)
                Key(k.getString("l"), k.getString("t"), k.getString("v"),
                    k.getDouble("x").toFloat(), k.getDouble("y").toFloat(),
                    k.getDouble("w").toFloat(), k.getDouble("h").toFloat(), k.optString("lp", ""))
            }.toMutableList())
        }
    }

    fun load(p: SharedPreferences): List<Layer> =
        try { p.getString("layout2", null)?.let { fromJson(it) } ?: default() } catch (e: Exception) { default() }
}

object Catalog {
    private fun t(l: String, ty: String, v: String) = Key(l, ty, v)

    val cats: Map<String, List<Key>> by lazy {
        linkedMapOf(
            "Huruf a-z" to ('a'..'z').map { t("$it", "char", "$it") },
            "Angka 0-9" to ('0'..'9').map { t("$it", "char", "$it") },
            "Simbol" to "`~!@#\$%^&*()-_=+[]{}\\|;:'\",.<>/?".map { t("$it", "char", "$it") },
            "PC: Fungsi" to listOf(
                t("Esc", "code", "ESCAPE"), t("Tab", "code", "TAB"), t("Enter", "code", "ENTER"),
                t("Bksp", "code", "DEL"), t("Del", "code", "FORWARD_DEL"), t("Ins", "code", "INSERT"),
                t("Home", "code", "MOVE_HOME"), t("End", "code", "MOVE_END"),
                t("PgUp", "code", "PAGE_UP"), t("PgDn", "code", "PAGE_DOWN"),
                t("PrtSc", "code", "SYSRQ"), t("ScrLk", "code", "SCROLL_LOCK"), t("Pause", "code", "BREAK"),
                t("CapsLk", "code", "CAPS_LOCK"), t("NumLk", "code", "NUM_LOCK"), t("Menu", "code", "MENU"),
                t("Space", "code", "SPACE")),
            "PC: Panah" to listOf(
                t("←", "code", "DPAD_LEFT"), t("↑", "code", "DPAD_UP"), t("↓", "code", "DPAD_DOWN"), t("→", "code", "DPAD_RIGHT")),
            "PC: F1-F12" to (1..12).map { t("F$it", "code", "F$it") },
            "PC: Modifier" to listOf(
                t("Ctrl", "mod", "CTRL"), t("Alt", "mod", "ALT"), t("Win", "mod", "META"), t("⇧", "act", "SHIFT")),
            "PC: Numpad" to ((0..9).map { t("N$it", "code", "NUMPAD_$it") } + listOf(
                t("N+", "code", "NUMPAD_ADD"), t("N-", "code", "NUMPAD_SUBTRACT"), t("N*", "code", "NUMPAD_MULTIPLY"),
                t("N/", "code", "NUMPAD_DIVIDE"), t("N.", "code", "NUMPAD_DOT"), t("N⏎", "code", "NUMPAD_ENTER"),
                t("N=", "code", "NUMPAD_EQUALS"))),
            "Kombinasi" to listOf(
                "Copy" to "CTRL+C", "Paste" to "CTRL+V", "Cut" to "CTRL+X", "Undo" to "CTRL+Z",
                "Redo" to "CTRL+SHIFT+Z", "SelAll" to "CTRL+A", "Save" to "CTRL+S", "Find" to "CTRL+F",
                "Alt+Tab" to "ALT+TAB", "CmdPal" to "CTRL+SHIFT+P", "Komen" to "CTRL+SLASH", "Ctrl+⌫" to "CTRL+DEL"
            ).map { t(it.first, "combo", it.second) },
            "Media" to listOf(
                t("Vol+", "code", "VOLUME_UP"), t("Vol-", "code", "VOLUME_DOWN"), t("Mute", "code", "VOLUME_MUTE"),
                t("Play", "code", "MEDIA_PLAY_PAUSE"), t("Next", "code", "MEDIA_NEXT"), t("Prev", "code", "MEDIA_PREVIOUS")),
            "HP: Aksi" to listOf(
                t("⇧", "act", "SHIFT"), t("⌫", "act", "DEL"), t("⏎", "act", "ENTER"), t("space", "act", "SPACE"),
                t("😀", "act", "EMOJI"), t("Tutup", "act", "HIDE"), t("Ganti", "act", "SWITCH"), t("Kirim", "act", "ACTION"), t("Pad", "act", "TOUCHPAD"), t("⚙", "act", "SETTINGS")),
            "Kustom (teks/snippet)" to listOf(t("?", "char", ""))
        )
    }
}

// Auto-fit: susun ulang tombol satu baris agar memenuhi lebar penuh tanpa celah/tumpang tindih.
// mode 1 = proporsional (lebar relatif dipertahankan), mode 2 = rata (semua sama lebar)
fun fitRow(l: Layer, row: Int, mode: Int) {
    val ks = l.keys.filter { Math.round(it.y) == row && Math.abs(it.h - 1f) < 0.01f }.sortedBy { it.x + it.w / 2 }
    if (ks.isEmpty()) return
    val tot = ks.sumOf { it.w.toDouble() }.toFloat()
    if (mode != 2 && tot <= 0f) return
    var x = 0f
    ks.forEach { k ->
        val w = if (mode == 2) 1f / ks.size else k.w / tot
        k.x = x; k.w = w; k.y = row.toFloat()
        x += w
    }
}

fun fitAll(l: Layer, mode: Int) { for (r in 0 until l.rows) fitRow(l, r, mode) }
