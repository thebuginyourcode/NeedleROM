package com.needlerom.core.world

import com.needlerom.core.Bytes
import com.needlerom.core.Rom
import com.needlerom.core.TextTable

data class Dialogue(
    val textId: Int,
    /** Offset of the first character. */
    val offset: Int,
    /** Bytes available, including the terminator. */
    val length: Int,
    val terminator: Int,
    val text: String,
    val editable: Boolean,
    val note: String? = null,
)

/**
 * Gen 1 dialogue: a text box is written as `text … line … cont … para … done/prompt`.
 * In the editor, a newline is a new line and a blank line starts a new text box.
 */
object Gen1Text {
    private const val TX_FAR = 0x17
    private const val LINE = 0x4F
    private const val PARA = 0x51
    private const val CONT = 0x55
    private const val NEXT = 0x4E
    private const val END = 0x50
    private const val DONE = 0x57
    private const val PROMPT = 0x58
    private const val SPACE = 0x7F
    const val LINE_WIDTH = 18

    private val TOKENS = mapOf(
        0x4A to "PKMN", 0x52 to "PLAYER", 0x53 to "RIVAL", 0x54 to "POKE", 0x56 to "DOTS",
        0x59 to "TARGET", 0x5A to "USER", 0x5B to "PC", 0x5C to "TM", 0x5D to "TRAINER", 0x5E to "ROCKET",
    )
    private val TOKEN_BYTES = TOKENS.entries.associate { (b, n) -> n to b }
    private val table = TextTable.POKEMON_GB

    fun resolve(rom: Rom, bank: Int, textTablePtr: Int, textId: Int): Dialogue {
        fun fail(note: String) = Dialogue(textId, -1, 0, 0, "", false, note)
        if (textId < 1) return fail("No dialogue")
        val d = rom.data
        val entry = Gb.toOffset(bank, textTablePtr) + (textId - 1) * 2
        if (!Gb.inRom(d, entry, 2)) return fail("Text pointer is outside the ROM")
        val script = Gb.toOffset(bank, Gb.u16(d, entry))
        if (!Gb.inRom(d, script, 4)) return fail("Text pointer is outside the ROM")
        val textOff = when (Gb.u8(d, script)) {
            TX_FAR -> Gb.toOffset(Gb.u8(d, script + 3), Gb.u16(d, script + 1))
            0x00 -> script
            else -> return fail("This one runs a script (battle, gift, menu…) so its text can't be edited here")
        }
        if (!Gb.inRom(d, textOff) || Gb.u8(d, textOff) != 0x00) return fail("Unrecognised text format")
        val start = textOff + 1
        var p = start
        while (p < d.size && p - start < 1000) {
            val b = Gb.u8(d, p)
            if (b == END || b == DONE || b == PROMPT) {
                return Dialogue(textId, start, p - start + 1, b, decode(d, start, p - start), true)
            }
            p++
        }
        return fail("Couldn't find the end of this text")
    }

    fun decode(d: ByteArray, start: Int, len: Int): String = buildString {
        for (i in 0 until len) {
            val b = Gb.u8(d, start + i)
            when (b) {
                LINE, CONT, NEXT -> append('\n')
                PARA -> append("\n\n")
                SPACE -> append(' ')
                else -> {
                    val c = table.decodeByte(b)
                    when {
                        c != null -> append(c)
                        TOKENS.containsKey(b) -> append('<').append(TOKENS[b]).append('>')
                        else -> append('<').append(Bytes.hex(b)).append('>')
                    }
                }
            }
        }
    }

    /** Encodes editor text back to game bytes, or returns an error message. */
    fun encode(text: String): Result<ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        val paragraphs = text.replace("\r", "").trimEnd('\n').split(Regex("\n{2,}"))
        paragraphs.forEachIndexed { pi, para ->
            if (pi > 0) out.write(PARA)
            para.split('\n').forEachIndexed { li, line ->
                if (li == 1) out.write(LINE) else if (li > 1) out.write(CONT)
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (c == '<') {
                        val close = line.indexOf('>', i)
                        if (close < 0) return Result.failure(IllegalArgumentException("Unclosed < in \"$line\""))
                        val name = line.substring(i + 1, close).uppercase()
                        val b = TOKEN_BYTES[name] ?: name.takeIf { it.length == 2 }?.toIntOrNull(16)
                            ?: return Result.failure(IllegalArgumentException("Unknown code <$name>"))
                        out.write(b)
                        i = close + 1
                        continue
                    }
                    val b = if (c == ' ') SPACE else table.encode(c.toString())?.get(0)?.toInt()?.and(0xFF)
                        ?: return Result.failure(IllegalArgumentException("The game font has no \"$c\""))
                    out.write(b)
                    i++
                }
            }
        }
        return Result.success(out.toByteArray())
    }

    /** Displayed width of each line (codes like <PLAYER> count as their in-game length). */
    fun longLines(text: String): List<String> = text.split('\n').filter { visibleWidth(it) > LINE_WIDTH }

    private fun visibleWidth(line: String): Int =
        line.replace("<PLAYER>", "RED".padEnd(7)).replace("<RIVAL>", "BLUE".padEnd(7))
            .replace("<POKE>", "POKé").replace(Regex("<[^>]*>"), "XX").length

    fun write(rom: Rom, d: Dialogue, text: String): String? {
        if (!d.editable) return d.note ?: "Not editable"
        val bytes = encode(text).getOrElse { return it.message }
        val room = d.length - 1
        if (bytes.size > room) return "Too long by ${bytes.size - room} character(s). This message has room for $room."
        val out = ByteArray(d.length)
        bytes.copyInto(out)
        if (d.terminator == END) {
            // The game keeps reading commands after "@", so pad with spaces instead of ending early.
            val lastLine = text.substringAfterLast('\n')
            if (bytes.size < room && visibleWidth(lastLine) + (room - bytes.size) > LINE_WIDTH) {
                return "This message must stay ${room} characters long (add spaces or words to the last line)"
            }
            for (i in bytes.size until room) out[i] = SPACE.toByte()
            out[room] = END.toByte()
        } else {
            // done/prompt stops the text engine, so anything after it is never read.
            out[bytes.size] = d.terminator.toByte()
            for (i in bytes.size + 1 until d.length) out[i] = END.toByte()
        }
        rom.write(d.offset, out, "Dialogue #${d.textId}")
        return null
    }
}
