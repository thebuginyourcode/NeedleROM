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
    private const val TRIGGERS = 0x0600 // $4600
    private const val TRIGGER_ENTRIES = 63
    private const val FORMAT = 5
    private const val TRIGGER_FORMAT = 2 // first format with the trigger table
    private val MAGIC = "NEEDLE".toByteArray()

    /** Home routines the interpreter calls; all must be unmodified for scripts to be offered. */
    private val REQUIRED = listOf("Bankswitch", "TextScriptEnd", "PrintText", "GiveItem", "GivePokemon", "YesNoChoice", "IsItemInBag", "PlaySound", "PlayCry", "PlayMusic")

    private fun versionKey(g: PokeRed) = when (g.version) {
        KnownVersions.RED -> "RED"
        KnownVersions.BLUE -> "BLUE"
        else -> "YELLOW"
    }

    fun isSupported(g: PokeRed): Boolean = REQUIRED.all { g.homeRoutineIntact(it) }

    /** Named sound effects and music this game version can play from an event. */
    fun sounds(g: PokeRed): Sounds.Table = if (g.version.isYellow) Sounds.YELLOW else Sounds.RED_BLUE

    private fun bankStart(g: PokeRed) = g.world.engineBank * Gb.BANK

    /** True once the interpreter has been written to the engine bank. */
    fun bankReady(g: PokeRed): Boolean {
        val s = bankStart(g)
        return MAGIC.indices.all { g.rom.u8(s + it) == MAGIC[it].toInt() and 0xFF }
    }

    /**
     * Writes the interpreter and empty tables, reserving the start of the engine bank, or
     * upgrades an older engine in place (keeping its scripts).
     */
    fun ensureBank(g: PokeRed) {
        val s = bankStart(g)
        val ready = bankReady(g)
        if (ready && g.rom.u8(s + 6) >= FORMAT) return
        val code = ScriptBlobs.CODE.getValue(versionKey(g)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Anything already allocated must be past the reserved area ($4000-$4BFF).
        val keepTriggers = ready && g.rom.u8(s + 6) >= TRIGGER_FORMAT
        g.rom.batch {
            g.rom.write(s, code, "Install event script engine")
            if (!keepTriggers) g.rom.write(s + TRIGGERS, ByteArray(TRIGGER_ENTRIES * 8 + 8) { 0xFF.toByte() }, "Install event script engine")
            if (!ready) g.rom.write(s + TABLE, ByteArray(TABLE_ENTRIES * 4 + 4) { 0xFF.toByte() }, "Install event script engine")
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
    fun put(g: PokeRed, map: Int, textId: Int, script0: EventScript) {
        val script = materializeTeams(g, script0)
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

    /** Moves a script from one text id to another (used when the editor renumbers texts). */
    fun renumber(g: PokeRed, map: Int, from: Int, to: Int) {
        entries(g).firstOrNull { it.map == map && it.textId == from }?.let {
            g.rom.write8(tableOffset(g) + it.slot * 4 + 1, to, "Renumber event")
        }
        triggerEntries(g).filter { it.map == map && it.textId == from }.forEach {
            g.rom.write8(triggerOffset(g) + it.slot * 8 + 3, to, "Renumber event")
        }
    }

    // ---- Custom trainer teams -----------------------------------------------------------------

    /** Gives every trainer battle with a custom team a real team number in the ROM. */
    private fun materializeTeams(g: PokeRed, s: EventScript): EventScript {
        fun fix(list: List<Step>): List<Step> = list.map { st ->
            when (st) {
                is Step.TrainerBattle -> {
                    val team = st.customTeam
                    if (team.isNullOrEmpty()) st else st.copy(team = g.trainers.putCustomTeam(st.trainerClass, st.team, team))
                }
                is Step.Ask -> st.copy(yes = fix(st.yes), no = fix(st.no))
                is Step.IfFlag -> st.copy(then = fix(st.then), otherwise = fix(st.otherwise))
                is Step.IfHasItem -> st.copy(then = fix(st.then), otherwise = fix(st.otherwise))
                is Step.GiveItem -> st.copy(ifBagFull = fix(st.ifBagFull))
                is Step.GivePokemon -> st.copy(ifNoRoom = fix(st.ifNoRoom))
                else -> st
            }
        }
        return EventScript(fix(s.steps))
    }

    // ---- Triggers -----------------------------------------------------------------------------

    /**
     * A rectangle of [width]×[height] tiles at (x, y) (or the whole map, when x/y are null)
     * that starts an event when the player steps in it.
     */
    data class Trigger(val map: Int, val x: Int?, val y: Int?, val textId: Int, val flag: Int, val width: Int = 1, val height: Int = 1)

    private data class TriggerEntry(val slot: Int, val map: Int, val textId: Int)

    private fun triggerOffset(g: PokeRed) = bankStart(g) + TRIGGERS

    private fun triggerEntries(g: PokeRed): List<TriggerEntry> {
        if (!bankReady(g) || g.rom.u8(bankStart(g) + 6) < TRIGGER_FORMAT) return emptyList()
        val out = mutableListOf<TriggerEntry>()
        for (i in 0 until TRIGGER_ENTRIES) {
            val o = triggerOffset(g) + i * 8
            val map = g.rom.u8(o)
            if (map == 0xFF) break
            out += TriggerEntry(i, map, g.rom.u8(o + 3))
        }
        return out
    }

    fun triggers(g: PokeRed, map: Int): List<Trigger> = triggerEntries(g).filter { it.map == map }.map { e ->
        val o = triggerOffset(g) + e.slot * 8
        val x = g.rom.u8(o + 1); val y = g.rom.u8(o + 2)
        val addr = g.rom.u16(o + 4); val mask = g.rom.u8(o + 6)
        val event = (addr - g.sym("wEventFlags")) * 8 + Integer.numberOfTrailingZeros(mask)
        val size = g.rom.u8(o + 7)
        Trigger(map, x.takeIf { it != 0xFF }, y.takeIf { it != 0xFF }, e.textId, event - Flags.FIRST_EVENT, (size shr 4) + 1, (size and 15) + 1)
    }

    /** All maps that have at least one trigger. */
    fun triggerMaps(g: PokeRed): Set<Int> = triggerEntries(g).map { it.map }.filter { it != DEAD }.toSet()

    /** Adds or updates the trigger for [t]'s map and text id. */
    fun putTrigger(g: PokeRed, t: Trigger) {
        require(t.flag in 0 until Flags.COUNT)
        require(t.width in 1..16 && t.height in 1..16) { "Triggers can be at most 16×16 tiles" }
        ensureBank(g)
        val e = Flags.FIRST_EVENT + t.flag
        val addr = g.sym("wEventFlags") + e / 8
        val bytes = byteArrayOf(t.map.toByte(), (t.x ?: 0xFF).toByte(), (t.y ?: 0xFF).toByte(), t.textId.toByte(),
            addr.toByte(), (addr shr 8).toByte(), (1 shl (e % 8)).toByte(), (((t.width - 1) shl 4) or (t.height - 1)).toByte())
        val list = triggerEntries(g)
        val slot = list.firstOrNull { it.map == t.map && it.textId == t.textId }?.slot
            ?: list.firstOrNull { it.map == DEAD }?.slot ?: list.size
        if (slot >= TRIGGER_ENTRIES) throw EditException("Too many triggers (max $TRIGGER_ENTRIES)")
        g.rom.write(triggerOffset(g) + slot * 8, bytes, "Trigger")
    }

    fun removeTrigger(g: PokeRed, map: Int, textId: Int) {
        triggerEntries(g).filter { it.map == map && it.textId == textId }.forEach {
            g.rom.write8(triggerOffset(g) + it.slot * 8, DEAD, "Remove trigger")
        }
        remove(g, map, textId)
    }

    /** Flags already used by triggers (so new triggers can pick a free one). */
    fun usedTriggerFlags(g: PokeRed): Set<Int> = triggerEntries(g).filter { it.map != DEAD }.flatMap { e -> triggers(g, e.map).map { it.flag } }.toSet()

    // ---- Per-map script hook ------------------------------------------------------------------

    /** The 11-byte stub placed in a map's bank: run NeedleMapScript, then the map's own script. */
    fun mapHookBytes(g: PokeRed, original: Int): ByteArray {
        val bs = g.sym("Bankswitch")
        return byteArrayOf(
            0x06, g.world.engineBank.toByte(),                 // ld b, ENGINE_BANK
            0x21, 0x0B, 0x40,                                  // ld hl, NeedleMapScript ($400B)
            0xCD.toByte(), bs.toByte(), (bs shr 8).toByte(),   // call Bankswitch
            0xC3.toByte(), original.toByte(), (original shr 8).toByte(), // jp <map's own script>
        )
    }

    /** True if [at] holds a map hook stub. */
    fun isMapHook(g: PokeRed, at: Int): Boolean {
        val probe = mapHookBytes(g, 0)
        return (0 until 8).all { g.rom.inBounds(at + it) && g.rom.u8(at + it) == (probe[it].toInt() and 0xFF) }
    }
}
