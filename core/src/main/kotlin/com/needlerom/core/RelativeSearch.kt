package com.needlerom.core

/**
 * Relative search: the classic ROM hacking trick for finding text without knowing a
 * game's character table. Most games store letters in alphabetical order, so the
 * *differences* between letters match even when the absolute values don't.
 */
data class RelativeMatch(
    val offset: Int,
    /** Byte value that the letter 'A' maps to in this game's table. */
    val valueOfA: Int,
    val preview: String,
)

object RelativeSearch {
    const val MIN_LETTERS = 4

    fun normalize(query: String): String = query.uppercase().filter { it in 'A'..'Z' }

    fun search(rom: Rom, query: String, limit: Int = 200): List<RelativeMatch> {
        val q = normalize(query)
        if (q.length < MIN_LETTERS) return emptyList()
        val deltas = IntArray(q.length) { q[it] - q[0] }
        val d = rom.data
        val out = mutableListOf<RelativeMatch>()
        var i = 0
        val last = d.size - q.length
        while (i <= last && out.size < limit) {
            val first = d[i].toInt() and 0xFF
            var ok = true
            for (k in 1 until q.length) {
                if ((d[i + k].toInt() and 0xFF) - first != deltas[k]) { ok = false; break }
            }
            if (ok) {
                val a = first - (q[0] - 'A')
                if (a >= 0 && a + 25 <= 0xFF) out += RelativeMatch(i, a, preview(rom, i, a, q.length))
            }
            i++
        }
        return out
    }

    fun preview(rom: Rom, offset: Int, valueOfA: Int, length: Int, pad: Int = 10): String {
        val start = (offset - pad).coerceAtLeast(0)
        val end = (offset + length + pad).coerceAtMost(rom.size)
        val sb = StringBuilder()
        for (p in start until end) {
            val v = rom.u8(p) - valueOfA
            sb.append(if (v in 0..25) 'A' + v else '·')
        }
        return sb.toString()
    }

    /** Writes [newText] (letters only, same length as the match) using the inferred table. */
    fun replace(rom: Rom, match: RelativeMatch, originalLength: Int, newText: String): String? {
        val t = normalize(newText)
        if (t.length != originalLength) return "Replacement must be exactly $originalLength letters (A–Z)"
        val bytes = ByteArray(t.length) { (match.valueOfA + (t[it] - 'A')).toByte() }
        rom.write(match.offset, bytes, "Relative text → \"$t\"")
        return null
    }
}
