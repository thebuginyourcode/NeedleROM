package com.needlerom.engine.script

import com.needlerom.engine.pokered.EditException
import com.needlerom.engine.pokered.KnownVersions
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.FreeSpace
import com.needlerom.engine.rom.Gb

/**
 * Installs and manages NeedleScript in a ROM.
 *
 * - The interpreter and a script table live at the start of the engine bank.
 * - A 13-byte `text_asm` hook in ROM0 (free space at the end of bank 0) switches to the
 *   engine bank and runs the script registered for the current map and text id.
 * - An NPC or sign runs a script when its text pointer points at the hook.
 */
object ScriptEngine {
    private const val TABLE = 0x0800 // offset inside the engine bank ($4800)
    private const val TABLE_ENTRIES = 255
    private val MAGIC = "NEEDLE".toByteArray()

    /** Home routines the interpreter calls; all must be unmodified for scripts to be offered. */
    private val REQUIRED = listOf("Bankswitch", "TextScriptEnd", "PrintText", "GiveItem", "GivePokemon", "YesNoChoice", "IsItemInBag", "PlaySound")

    private fun versionKey(g: PokeRed) = when (g.version) {
        KnownVersions.RED -> "RED"
        KnownVersions.BLUE -> "BLUE"
        else -> "YELLOW"
    }

    fun isSupported(g: PokeRed): Boolean = REQUIRED.all { g.homeRoutineIntact(it) }

    private fun bankStart(g: PokeRed) = g.world.engineBank * Gb.BANK

    /** True once the interpreter has been written to the engine bank. */
    fun bankReady(g: PokeRed): Boolean {
        val s = bankStart(g)
        return MAGIC.indices.all { g.rom.u8(s + it) == MAGIC[it].toInt() and 0xFF }
    }

    /** Writes the interpreter and an empty script table, reserving the start of the engine bank. */
    fun ensureBank(g: PokeRed) {
        if (bankReady(g)) return
        val code = ScriptBlobs.CODE.getValue(versionKey(g)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val s = bankStart(g)
        // Anything already allocated must be past the reserved area ($4000-$4BFF).
        g.rom.batch {
            g.rom.write(s, code, "Install event script engine")
            g.rom.write(s + TABLE, ByteArray(TABLE_ENTRIES * 4 + 4) { 0xFF.toByte() }, "Install event script engine")
        }
    }

    private fun hookBytes(g: PokeRed): ByteArray {
        val bs = g.sym("Bankswitch"); val end = g.sym("TextScriptEnd")
        return byteArrayOf(
            0x08,                                   // text_asm
            0x06, g.world.engineBank.toByte(),       // ld b, ENGINE_BANK
            0x21, 0x08, 0x40,                       // ld hl, NeedleRun ($4008)
            0xCD.toByte(), bs.toByte(), (bs shr 8).toByte(),   // call Bankswitch
            0xC3.toByte(), end.toByte(), (end shr 8).toByte(), // jp TextScriptEnd
        )
    }

    /** ROM0 address of the hook, or null if not installed. */
    fun hookAddress(g: PokeRed): Int? {
        val hook = hookBytes(g)
        val at = g.rom.indexOf(hook, 0x3000, 0x4000)
        return at.takeIf { it >= 0 }
    }

    /** Installs the interpreter and the ROM0 hook. Returns the hook address. */
    fun install(g: PokeRed): Int {
        hookAddress(g)?.let { return it }
        if (!isSupported(g)) throw EditException("This ROM's game code has been modified, so event scripts can't be installed safely")
        val hook = hookBytes(g)
        // Free space at the end of bank 0 (90 bytes in Red/Blue, 17 in Yellow).
        var start = 0x4000
        while (start > 0x3F00 && g.rom.original[start - 1] == 0.toByte()) start--
        val at = start + 1
        if (at + hook.size > 0x4000) throw EditException("No room in bank 0 for the script hook")
        g.rom.batch {
            ensureBank(g)
            g.rom.write(at, hook, "Install event script engine")
        }
        return at
    }

    // ---- Script table -------------------------------------------------------------------------

    private fun tableOffset(g: PokeRed) = bankStart(g) + TABLE

    private data class Entry(val slot: Int, val map: Int, val textId: Int, val ptr: Int)

    private fun entries(g: PokeRed): List<Entry> {
        if (!bankReady(g)) return emptyList()
        val t = tableOffset(g)
        val out = mutableListOf<Entry>()
        for (i in 0 until TABLE_ENTRIES) {
            val o = t + i * 4
            val map = g.rom.u8(o)
            if (map == 0xFF) break
            out += Entry(i, map, g.rom.u8(o + 1), g.rom.u16(o + 2))
        }
        return out
    }

    /** Every installed script as (map, text id). */
    fun scripts(g: PokeRed): List<Pair<Int, Int>> = entries(g).filter { it.map != DEAD }.map { it.map to it.textId }

    /** Reads back the script for a map/text id (from the source stored next to its bytecode). */
    fun get(g: PokeRed, map: Int, textId: Int): EventScript? {
        val e = entries(g).firstOrNull { it.map == map && it.textId == textId } ?: return null
        val code = Gb.offset(g.world.engineBank, e.ptr)
        val len = g.rom.u16(code - 2)
        val src = String(g.rom.slice(code - 2 - len, len), Charsets.UTF_8)
        return ScriptCodec.decode(src)
    }

    /** Compiles and stores [script] for the given map and text id (replacing any previous one). */
    fun put(g: PokeRed, map: Int, textId: Int, script: EventScript) {
        val compiler = ScriptCompiler(g.sym("wEventFlags"))
        val codeSize = compiler.compile(script)
        val src = ScriptCodec.encode(script).toByteArray(Charsets.UTF_8)
        val bank = g.world.engineBank
        g.rom.batch {
            ensureBank(g)
            val total = src.size + 2 + codeSize
            val at = FreeSpace.find(g.rom, bank, total + 1, margin = 1) ?: throw EditException("The engine bank is full")
            val codeAt = at + src.size + 2
            val linked = compiler.link(Gb.ptr(codeAt))
            val block = src + byteArrayOf(src.size.toByte(), (src.size shr 8).toByte()) + linked + 0xFF.toByte()
            g.rom.write(at, block, "Event script")
            val list = entries(g)
            val slot = list.firstOrNull { it.map == map && it.textId == textId }?.slot
                ?: list.firstOrNull { it.map == DEAD }?.slot ?: list.size
            if (slot >= TABLE_ENTRIES) throw EditException("Too many event scripts")
            val ptr = Gb.ptr(codeAt)
            g.rom.write(tableOffset(g) + slot * 4, byteArrayOf(map.toByte(), textId.toByte(), ptr.toByte(), (ptr shr 8).toByte()), "Event script")
        }
    }

    /** Detaches a script: its table slot is marked with map id $FE, which never matches. */
    fun remove(g: PokeRed, map: Int, textId: Int) {
        val e = entries(g).firstOrNull { it.map == map && it.textId == textId } ?: return
        g.rom.write8(tableOffset(g) + e.slot * 4, DEAD, "Remove event script")
    }

    private const val DEAD = 0xFE
}
