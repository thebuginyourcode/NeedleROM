package com.needlerom.engine.pokered

/**
 * The English Gen 1 character set (constants/charmap.asm in pret/pokered).
 *
 * Editor text uses plain characters, with "\n" for a new line, a blank line for a new
 * text box, and <TOKENS> for control codes such as <PLAYER>.
 */
object Text {
    const val END = 0x50      // "@" string terminator
    const val NEXT = 0x4E
    const val LINE = 0x4F
    const val PARA = 0x51
    const val CONT = 0x55
    const val DONE = 0x57
    const val PROMPT = 0x58
    const val SPACE = 0x7F
    const val LINE_WIDTH = 18

    private val chars = HashMap<Int, String>()
    private val encodeMap = HashMap<String, Int>()

    private fun map(s: String, b: Int) { chars.putIfAbsent(b, s); encodeMap[s] = b }

    init {
        for (i in 0 until 26) { map(('A' + i).toString(), 0x80 + i); map(('a' + i).toString(), 0xA0 + i) }
        for (i in 0 until 10) map(('0' + i).toString(), 0xF6 + i)
        map(" ", SPACE)
        listOf("(" to 0x9A, ")" to 0x9B, ":" to 0x9C, ";" to 0x9D, "[" to 0x9E, "]" to 0x9F,
            "é" to 0xBA, "'d" to 0xBB, "'l" to 0xBC, "'s" to 0xBD, "'t" to 0xBE, "'v" to 0xBF,
            "'" to 0xE0, "-" to 0xE3, "'r" to 0xE4, "'m" to 0xE5, "?" to 0xE6, "!" to 0xE7, "." to 0xE8,
            "▷" to 0xEC, "▶" to 0xED, "▼" to 0xEE, "♂" to 0xEF, "¥" to 0xF0, "×" to 0xF1, "/" to 0xF3,
            "," to 0xF4, "♀" to 0xF5, "‘" to 0x70, "’" to 0x71, "“" to 0x72, "”" to 0x73, "…" to 0x75,
        ).forEach { (s, b) -> map(s, b) }
    }

    /** Control codes shown as <NAME> in the editor. */
    private val tokens = mapOf(
        0x49 to "PAGE", 0x4A to "PKMN", 0x4C to "SCROLL", 0x52 to "PLAYER", 0x53 to "RIVAL",
        0x54 to "POKE", 0x56 to "……", 0x59 to "TARGET", 0x5A to "USER", 0x5B to "PC", 0x5C to "TM",
        0x5D to "TRAINER", 0x5E to "ROCKET", 0x5F to "DEXEND", 0xE1 to "PK", 0xE2 to "MN",
    )
    private val tokenBytes = tokens.entries.associate { (b, n) -> n to b }

    /** Decodes a fixed-length or "@"-terminated name. */
    fun name(d: ByteArray, at: Int, maxLen: Int): String = buildString {
        for (i in 0 until maxLen) {
            val p = at + i
            if (p >= d.size) break
            val b = d[p].toInt() and 0xFF
            if (b == END) break
            append(chars[b] ?: tokens[b]?.let { "<$it>" } ?: "<${hex(b)}>")
        }
    }

    /** Decodes dialogue (line/para/cont become newlines) from [start] for [len] bytes. */
    fun decode(d: ByteArray, start: Int, len: Int): String = buildString {
        for (i in 0 until len) {
            val b = d[start + i].toInt() and 0xFF
            when (b) {
                LINE, CONT, NEXT -> append('\n')
                PARA -> append("\n\n")
                else -> append(chars[b] ?: tokens[b]?.let { "<$it>" } ?: "<${hex(b)}>")
            }
        }
    }

    /** Encodes a single line/name (no newlines). Throws [IllegalArgumentException] on bad input. */
    fun encodeLine(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '<') {
                val close = s.indexOf('>', i)
                require(close > i) { "Unclosed < in \"$s\"" }
                val name = s.substring(i + 1, close)
                val b = tokenBytes[name.uppercase()] ?: tokenBytes[name]
                    ?: name.takeIf { it.length == 2 }?.toIntOrNull(16)
                    ?: throw IllegalArgumentException("Unknown code <$name>")
                out.write(b); i = close + 1; continue
            }
            // Prefer the one-tile contractions ('s, 't, …) to save space.
            val contraction = if (s[i] == '\'' && i + 1 < s.length) encodeMap["'" + s[i + 1]] else null
            if (contraction != null) { out.write(contraction); i += 2; continue }
            val b = encodeMap[s[i].toString()] ?: throw IllegalArgumentException("The game font has no \"${s[i]}\"")
            out.write(b); i++
        }
        return out.toByteArray()
    }

    /** Encodes dialogue: lines use line/cont, blank lines start a new text box (para). */
    fun encodeDialogue(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val paragraphs = text.replace("\r", "").trimEnd('\n').split(Regex("\n{2,}"))
        paragraphs.forEachIndexed { pi, para ->
            if (pi > 0) out.write(PARA)
            para.split('\n').forEachIndexed { li, line ->
                if (li == 1) out.write(LINE) else if (li > 1) out.write(CONT)
                out.write(encodeLine(line))
            }
        }
        return out.toByteArray()
    }

    fun canEncode(s: String): Boolean = runCatching { encodeLine(s) }.isSuccess

    /** Approximate on-screen width of a line (names count as 7 tiles, like the game's limit). */
    fun width(line: String): Int =
        line.replace("<PLAYER>", "1234567").replace("<RIVAL>", "1234567").replace("<POKE>", "PKé")
            .replace("<……>", "……").replace(Regex("'[dlstvrm]"), "x").replace(Regex("<[^>]*>"), "xx").length

    fun longLines(text: String): List<String> = text.split('\n').filter { width(it) > LINE_WIDTH }

    private fun hex(b: Int) = b.toString(16).uppercase().padStart(2, '0')
}
