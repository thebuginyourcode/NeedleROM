package com.needlerom.core

data class TextHit(val offset: Int, val context: String)

object TextTools {
    const val MIN_QUERY = 3

    fun findAll(rom: Rom, table: TextTable, query: String, limit: Int = 300): List<TextHit> {
        if (query.length < MIN_QUERY) return emptyList()
        val pattern = table.encode(query) ?: return emptyList()
        return rom.findAll(pattern, limit).map { TextHit(it, context(rom, table, it, pattern.size)) }
    }

    fun context(rom: Rom, table: TextTable, offset: Int, length: Int, pad: Int = 12): String {
        val start = (offset - pad).coerceAtLeast(0)
        val end = (offset + length + pad).coerceAtMost(rom.size)
        val sb = StringBuilder()
        for (i in start until end) {
            val b = rom.u8(i)
            sb.append(if (b == table.terminator) '¶' else table.decodeByte(b) ?: '·')
        }
        return sb.toString()
    }

    sealed interface ReplaceResult {
        data class Done(val count: Int) : ReplaceResult
        data class Error(val message: String) : ReplaceResult
    }

    /**
     * Replaces in place. The replacement must not be longer than the original because
     * game text is packed tightly; shorter text is padded with spaces so pointers and
     * control codes that follow are left untouched.
     */
    fun replace(
        rom: Rom,
        table: TextTable,
        find: String,
        replacement: String,
        offsets: List<Int>,
    ): ReplaceResult {
        val findBytes = table.encode(find) ?: return ReplaceResult.Error("Search text has unsupported characters")
        if (replacement.length > find.length) {
            return ReplaceResult.Error("Replacement is ${replacement.length - find.length} character(s) too long. Keep it to ${find.length} or fewer.")
        }
        val bad = table.unsupportedChars(replacement)
        if (bad.isNotEmpty()) return ReplaceResult.Error("Not in ${table.name} font: ${bad.joinToString(" ")}")
        val padded = replacement.padEnd(find.length, ' ')
        val bytes = table.encode(padded)
            ?: return ReplaceResult.Error("${table.name} text has no space character; replacement must be exactly ${find.length} characters")
        var count = 0
        for (o in offsets) {
            if (!rom.inBounds(o, findBytes.size)) continue
            if (!rom.slice(o, findBytes.size).contentEquals(findBytes)) continue
            rom.write(o, bytes, "Text \"$find\" → \"$replacement\"")
            count++
        }
        return ReplaceResult.Done(count)
    }
}
