package com.needlerom.engine.pokered

import com.needlerom.engine.rom.FreeSpace
import com.needlerom.engine.rom.Gb
import com.needlerom.engine.rom.Rom

/** Thrown when an edit can't be made (e.g. no free space); the message is shown to the user. */
class EditException(message: String) : Exception(message)

internal object Blocks {
    /**
     * Replaces a variable-size block reached through a 16-bit pointer at [ptrField] whose
     * target lives in [bank]. Written in place when it fits and no other pointer shares
     * the block; otherwise copied to free space in the same bank and re-pointed.
     */
    fun replace(rom: Rom, ptrField: Int, bank: Int, oldOffset: Int, oldSize: Int, bytes: ByteArray, shared: Boolean, label: String) {
        rom.batch {
            if (!shared && bytes.size <= oldSize) {
                rom.write(oldOffset, bytes, label)
            } else {
                val at = FreeSpace.alloc(rom, bank, bytes, label)
                    ?: throw EditException("Not enough free space in ROM bank $bank for this change")
                rom.write16(ptrField, Gb.ptr(at), label)
            }
        }
    }

    /**
     * Rewrites a list of "@"-terminated names that starts at [start]. The list may grow
     * into the padding at the end of its bank, if it is the last thing there.
     */
    fun rewriteNameList(rom: Rom, start: Int, names: List<String>, label: String) {
        val encoded = names.map { Text.encodeLine(it) + Text.END.toByte() }
        val newSize = encoded.sumOf { it.size }
        var oldEnd = start
        repeat(names.size) { while (rom.u8(oldEnd) != Text.END) oldEnd++; oldEnd++ }
        val bank = Gb.bankOf(start)
        val bankEnd = Gb.bankEnd(bank, rom.size)
        var room = oldEnd - start
        // Padding directly after the list can be used too.
        var p = oldEnd
        while (p < bankEnd && rom.u8(p) == 0) p++
        if (p == bankEnd) room = bankEnd - start - 8
        if (newSize > room) throw EditException("Names are ${newSize - room} letters too long in total; shorten some")
        val out = ByteArray(maxOf(newSize, oldEnd - start))
        var o = 0
        for (e in encoded) { e.copyInto(out, o); o += e.size }
        // Keep the old tail filled with "@" so nothing reads garbage.
        for (i in newSize until oldEnd - start) out[i] = Text.END.toByte()
        rom.write(start, out, label)
    }
}
