package com.needlerom.core

/** Maps characters to the byte values a specific game engine uses for text. */
class TextTable(
    val name: String,
    private val encodeMap: Map<Char, Int>,
    val terminator: Int?,
    val upperCaseOnly: Boolean = false,
) {
    private val decodeMap: Map<Int, Char> = encodeMap.entries.associate { (c, b) -> b to c }
    val space: Int? = encodeMap[' ']

    fun canEncode(text: String): Boolean = encode(text) != null

    fun unsupportedChars(text: String): Set<Char> =
        prepare(text).filter { it !in encodeMap }.toSet()

    private fun prepare(text: String) = if (upperCaseOnly) text.uppercase() else text

    fun encode(text: String): ByteArray? {
        val t = prepare(text)
        val out = ByteArray(t.length)
        for ((i, c) in t.withIndex()) out[i] = (encodeMap[c] ?: return null).toByte()
        return out
    }

    fun decodeByte(b: Int): Char? = decodeMap[b]

    /** Decodes up to [maxLen] bytes, stopping at the terminator. Unknown bytes become '·'. */
    fun decode(data: ByteArray, offset: Int, maxLen: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxLen) {
            val p = offset + i
            if (p !in data.indices) break
            val b = data[p].toInt() and 0xFF
            if (b == terminator) break
            sb.append(decodeMap[b] ?: '·')
        }
        return sb.toString()
    }

    companion object {
        private fun MutableMap<Char, Int>.range(from: Char, to: Char, start: Int) {
            for (c in from..to) this[c] = start + (c - from)
        }

        /** Pokémon Red/Blue/Yellow and Gold/Silver/Crystal (English). */
        val POKEMON_GB: TextTable = TextTable(
            "Pokémon Gen 1/2",
            buildMap {
                range('A', 'Z', 0x80)
                range('a', 'z', 0xA0)
                range('0', '9', 0xF6)
                this['('] = 0x9A; this[')'] = 0x9B; this[':'] = 0x9C; this[';'] = 0x9D
                this['['] = 0x9E; this[']'] = 0x9F; this['é'] = 0xBA
                this['\''] = 0xE0; this['-'] = 0xE3; this['?'] = 0xE6; this['!'] = 0xE7
                this['.'] = 0xE8; this['♂'] = 0xEF; this['×'] = 0xF1; this['/'] = 0xF3
                this[','] = 0xF4; this['♀'] = 0xF5; this[' '] = 0x7F
            },
            terminator = 0x50,
        )

        /** Pokémon Ruby/Sapphire/Emerald/FireRed/LeafGreen (English). */
        val POKEMON_GBA: TextTable = TextTable(
            "Pokémon Gen 3",
            buildMap {
                this[' '] = 0x00; this['é'] = 0x1B
                range('0', '9', 0xA1)
                this['!'] = 0xAB; this['?'] = 0xAC; this['.'] = 0xAD; this['-'] = 0xAE
                this['…'] = 0xB0; this['“'] = 0xB1; this['”'] = 0xB2; this['‘'] = 0xB3
                this['\''] = 0xB4; this['♂'] = 0xB5; this['♀'] = 0xB6; this[','] = 0xB8
                this['/'] = 0xBA; this[':'] = 0xF0
                range('A', 'Z', 0xBB)
                range('a', 'z', 0xD5)
            },
            terminator = 0xFF,
        )

        /** Super Mario Bros. (NES) background tiles used for in-game messages. */
        val SMB: TextTable = TextTable(
            "Super Mario Bros.",
            buildMap {
                range('0', '9', 0x00)
                range('A', 'Z', 0x0A)
                this[' '] = 0x24; this['-'] = 0x28; this['!'] = 0x2B
            },
            terminator = null,
            upperCaseOnly = true,
        )

        /** Plain ASCII; many N64, GBA and DS games keep strings this way. */
        val ASCII: TextTable = TextTable(
            "ASCII",
            buildMap { for (c in 0x20..0x7E) this[c.toChar()] = c },
            terminator = 0x00,
        )
    }
}
