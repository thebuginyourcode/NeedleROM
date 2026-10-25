package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Step

/** One piece of the game's own dialogue: a TX_START text at [offset] that can be rewritten. */
data class GameLine(val offset: Int, val text: String, val terminator: Int, val length: Int)

/**
 * The original game's scripts (text_asm machine code) read back as blocks. Gen 1 has no
 * script language, so this recognizes the patterns the game's scripts are written with
 * (story flag checks, PrintText, GiveItem, …) and follows every path; whatever isn't
 * recognized becomes a [Block.Code] block, which keeps the script from being converted.
 */
object GameScripts {
    sealed interface Cond {
        data class Event(val event: Int) : Cond
        data class HasItem(val item: Int) : Cond
        /** The player answered YES to the question just shown. */
        data object Yes : Cond
        /** A check the reader doesn't understand (e.g. counting Pokédex entries). */
        data class Other(val what: String) : Cond
    }

    sealed interface Block {
        data class Say(val line: GameLine) : Block
        /** A name the text inserts from memory (e.g. the item just received). */
        data class TextRam(val address: Int) : Block
        /** A sound played inside a text (e.g. the "got key item" jingle). */
        data class TextSound(val command: Int) : Block
        data class If(val cond: Cond, val then: List<Block>, val otherwise: List<Block>) : Block
        data class SetEvent(val event: Int, val value: Boolean) : Block
        data class GiveItem(val item: Int, val quantity: Int, val ifBagFull: List<Block>?) : Block
        data class GivePokemon(val species: Int, val level: Int, val ifNoRoom: List<Block>?) : Block
        data class WildBattle(val species: Int, val level: Int) : Block
        data class PlaySound(val sound: Int) : Block
        data class Code(val what: String) : Block
        data object End : Block
    }

    class Outline(val blocks: List<Block>) {
        /** Every editable line in the script (each once). */
        val lines: List<GameLine> get() = buildList { collect(blocks, this) }.distinctBy { it.offset }

        private fun collect(list: List<Block>, out: MutableList<GameLine>) {
            for (b in list) when (b) {
                is Block.Say -> out += b.line
                is Block.If -> { collect(b.then, out); collect(b.otherwise, out) }
                is Block.GiveItem -> b.ifBagFull?.let { collect(it, out) }
                is Block.GivePokemon -> b.ifNoRoom?.let { collect(it, out) }
                else -> {}
            }
        }
    }

    /** The outline of text [textId] on [map] if it's game code (text_asm), else null. */
    fun outline(g: PokeRed, map: GameMap, textId: Int): Outline? {
        val at = map.textAddress(textId) ?: return null
        if (g.rom.u8(at) != 0x08) return null
        return Reader(g, map.bank).run { Outline(read(Gb.ptr(at) + 1)) }
    }

    /** The editable lines of the text structure at file offset [at] (following far jumps). */
    fun textLines(g: PokeRed, at: Int): List<GameLine> =
        Reader(g, Gb.bankOf(at)).textsAt(at).filterIsInstance<Block.Say>().map { it.line }

    // ---- Reading machine code -----------------------------------------------------------------

    private class Reader(val g: PokeRed, val bank: Int) {
        private val rom = g.rom
        private var budget = 600 // blocks, to bound the copies made by following every path
        private val names: Map<Int, String> = g.version.symbols.filter { it.value < 0x4000 }.entries.associate { it.value to it.key }
        private fun sym(n: String) = g.version.symbols[n] ?: -1
        private val eventsStart = sym("wEventFlags")

        private fun off(addr: Int) = Gb.offset(bank, addr)
        private fun u8(addr: Int) = rom.u8(off(addr))
        private fun u16(addr: Int) = rom.u16(off(addr))

        private fun length(op: Int): Int = when (op) {
            0x01, 0x11, 0x21, 0x31, 0x08, 0xC2, 0xC3, 0xCA, 0xD2, 0xDA, 0xC4, 0xCC, 0xCD, 0xD4, 0xDC, 0xEA, 0xFA -> 3
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E, 0x18, 0x20, 0x28, 0x30, 0x38,
            0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE, 0xE0, 0xF0, 0xE8, 0xF8, 0xCB, 0x10 -> 2
            else -> 1
        }

        private fun isCall(pc: Int, name: String) = u8(pc) == 0xCD && u16(pc + 1) == sym(name)

        /** Conditional jump at pc: (condition code, target, next pc) or null. cc: 0 nz, 1 z, 2 nc, 3 c. */
        private fun condJump(pc: Int): Triple<Int, Int, Int>? = when (val op = u8(pc)) {
            0x20, 0x28, 0x30, 0x38 -> Triple((op - 0x20) / 8, pc + 2 + u8(pc + 1).toByte(), pc + 2)
            0xC2, 0xCA, 0xD2, 0xDA -> Triple((op - 0xC2) / 8, u16(pc + 1), pc + 3)
            else -> null
        }

        private fun eventAt(addr: Int, bit: Int): Int? =
            if (eventsStart >= 0 && addr in eventsStart until eventsStart + 0x140) (addr - eventsStart) * 8 + bit else null

        fun read(start: Int, depth: Int = 0, knownA: Int? = null, knownHL: Int? = null): List<Block> {
            val out = mutableListOf<Block>()
            var pc = start
            var lastA: Int? = knownA // address last loaded into a with ld a, [nn]
            var hl: Int? = knownHL   // value last loaded into hl with ld hl, nn (a text for PrintText)
            val seen = HashSet<Int>()
            val code = mutableListOf<String>()
            fun flushCode() { if (code.isNotEmpty()) { out += Block.Code(code.distinct().joinToString(", ")); code.clear() } }
            fun emit(b: Block) { flushCode(); out += b; budget-- }
            fun branch(cond: Cond, taken: Int, next: Int, takenMeansTrue: Boolean, prefix: List<Block> = emptyList()): List<Block> {
                flushCode()
                if (depth > 24 || budget <= 0) { out += Block.Code("more game code"); return out }
                val a = prefix + read(taken, depth + 1, lastA, hl)
                val b = prefix + read(next, depth + 1, lastA, hl)
                out += if (takenMeansTrue) Block.If(cond, a, b) else Block.If(cond, b, a)
                budget--
                return out
            }
            while (true) {
                if (budget <= 0 || !seen.add(pc) || pc >= 0x8000) { flushCode(); out += Block.Code("more game code"); return out }
                if (pc < 0x4000) { // jumped into the home bank
                    flushCode()
                    out += if (pc == sym("TextScriptEnd")) Block.End else Block.Code(names[pc] ?: "game code")
                    return out
                }
                val op = u8(pc)
                // ld hl, text / call PrintText
                if (op == 0x21 && isCall(pc + 3, "PrintText")) {
                    flushCode()
                    out += texts(u16(pc + 1))
                    pc += 6; continue
                }
                // ld hl, text ... (jump) ... call PrintText
                if (isCall(pc, "PrintText") && hl != null) {
                    flushCode()
                    out += texts(hl!!)
                    hl = null; pc += 3; continue
                }
                // Story flag checks: ld a, [nn] / bit b, a / jr cc  (and CheckEventReuseA: bit b, a)
                if (op == 0xFA) { lastA = u16(pc + 1); pc += 3
                    if (u8(pc) != 0xCB) code += "reads memory"
                    continue }
                if (op == 0xCB && u8(pc + 1) in 0x40..0x7F && (u8(pc + 1) and 7) == 7) {
                    val bit = (u8(pc + 1) - 0x40) / 8
                    val ev = lastA?.let { eventAt(it, bit) }
                    val j = condJump(pc + 2)
                    if (j != null && j.first < 2) {
                        val cond = ev?.let { Cond.Event(it) } ?: Cond.Other("a game setting")
                        return branch(cond, j.second, j.third, takenMeansTrue = j.first == 0)
                    }
                }
                // ld hl, nn / bit b, [hl] (/ set b, [hl]) / jr cc   — CheckEvent, CheckAndSetEvent
                if (op == 0x21 && u8(pc + 3) == 0xCB && u8(pc + 4) in 0x40..0x7F && (u8(pc + 4) and 7) == 6) {
                    val addr = u16(pc + 1); val bit = (u8(pc + 4) - 0x40) / 8
                    var p = pc + 5
                    val setsToo = u8(p) == 0xCB && u8(p + 1) == 0xC6 + bit * 8
                    if (setsToo) p += 2
                    val j = condJump(p)
                    val ev = eventAt(addr, bit)
                    if (j != null && j.first < 2) {
                        val cond = ev?.let { Cond.Event(it) } ?: Cond.Other("a game setting")
                        val prefix = if (setsToo && ev != null) listOf(Block.SetEvent(ev, true)) else emptyList()
                        if (setsToo && ev == null) code += "changes a game setting"
                        return branch(cond, j.second, j.third, takenMeansTrue = j.first == 0, prefix)
                    }
                }
                // SetEvent / ResetEvent: ld hl, nn / set|res b, [hl]
                if (op == 0x21 && u8(pc + 3) == 0xCB && (u8(pc + 4) and 7) == 6 && u8(pc + 4) >= 0x80) {
                    val addr = u16(pc + 1); val sub = u8(pc + 4); val bit = (sub and 0x3F) / 8
                    val ev = eventAt(addr, bit)
                    if (ev != null) { emit(Block.SetEvent(ev, sub >= 0xC0)); pc += 5; continue }
                }
                // ld b, item / call IsItemInBag / jr nz|z
                if (op == 0x06 && isCall(pc + 2, "IsItemInBag")) {
                    val j = condJump(pc + 5)
                    if (j != null && j.first < 2) return branch(Cond.HasItem(u8(pc + 1)), j.second, j.third, takenMeansTrue = j.first == 0)
                }
                // lb bc, item, qty / call GiveItem (/ jr nc, bag full)
                if (op == 0x01 && (isCall(pc + 3, "GiveItem") || isCall(pc + 3, "GivePokemon"))) {
                    val v = u16(pc + 1); val hi = v shr 8; val lo = v and 0xFF
                    val item = isCall(pc + 3, "GiveItem")
                    val j = condJump(pc + 6)
                    if (j != null && j.first == 2) { // jr nc: failed
                        flushCode()
                        val fail = read(j.second, depth + 1)
                        out += if (item) Block.GiveItem(hi, lo, fail) else Block.GivePokemon(hi, lo, fail)
                        pc = j.third; continue
                    }
                    emit(if (item) Block.GiveItem(hi, lo, null) else Block.GivePokemon(hi, lo, null))
                    pc += 6; continue
                }
                // call YesNoChoice / ld a, [wCurrentMenuItem] / and a / jr nz, no
                if (isCall(pc, "YesNoChoice") && u8(pc + 3) == 0xFA && u16(pc + 4) == sym("wCurrentMenuItem") && u8(pc + 6) == 0xA7) {
                    val j = condJump(pc + 7)
                    if (j != null && j.first < 2) return branch(Cond.Yes, j.second, j.third, takenMeansTrue = j.first == 1)
                }
                // ld a, species / ld [wCurOpponent], a / ld a, level / ld [wCurEnemyLevel], a
                if (op == 0x3E && u8(pc + 2) == 0xEA && u16(pc + 3) == sym("wCurOpponent") && u8(pc + 5) == 0x3E &&
                    u8(pc + 7) == 0xEA && u16(pc + 8) == sym("wCurEnemyLevel") && u8(pc + 1) < 200) {
                    emit(Block.WildBattle(u8(pc + 1), u8(pc + 6))); pc += 10; continue
                }
                // ld a, sound / call PlaySound
                if (op == 0x3E && isCall(pc + 2, "PlaySound")) { emit(Block.PlaySound(u8(pc + 1))); pc += 5; continue }
                // ld hl, nn not used by the patterns above: remember it (a text, usually).
                if (op == 0x21) { hl = u16(pc + 1); pc += 3; continue }
                // Endings and jumps.
                when (op) {
                    0xC3 -> { pc = u16(pc + 1); continue }               // jp nn
                    0x18 -> { pc = pc + 2 + u8(pc + 1).toByte(); continue } // jr
                    0xC9 -> { flushCode(); out += Block.End; return out }  // ret
                }
                val j = condJump(pc)
                if (j != null) return branch(Cond.Other("a game check"), j.second, j.third, takenMeansTrue = true)
                // Anything else is game code.
                code += when {
                    op == 0xCD -> names[u16(pc + 1)]?.let { describe(it) } ?: "calls game code"
                    op == 0xEA -> "changes game memory"
                    else -> "game code"
                }
                if (op == 0xCD && u16(pc + 1) == sym("Predef")) code[code.lastIndex] = "game routine"
                lastA = null; hl = null
                pc += length(op)
            }
        }

        private fun describe(name: String) = when (name) {
            "Bankswitch" -> "game routine"
            "DisplayTextID" -> "shows another text"
            "GBFadeOutToBlack", "GBFadeInFromBlack" -> "fades the screen"
            "DelayFrames", "DelayFrame", "Delay3" -> "waits"
            else -> "runs $name"
        }

        fun textsAt(offset: Int): List<Block> = commands(offset, 0)

        /** The blocks shown by PrintText for the text structure at [addr]. */
        private fun texts(addr: Int): List<Block> = commands(if (addr < 0x4000) addr else off(addr), 0)

        /** Text commands at file offset [at]: lines, sounds and far jumps (followed). */
        private fun commands(at: Int, depth: Int): List<Block> {
            val out = mutableListOf<Block>()
            var p = at
            var guard = 0
            while (guard++ < 16 && depth < 6) {
                when (val c = rom.u8(p)) {
                    0x17 -> { out += commands(Gb.offset(rom.u8(p + 3), rom.u16(p + 1)), depth + 1); p += 4 }
                    0x00 -> {
                        val line = GameText.read(g, p) ?: return out + Block.Code("text with special codes")
                        out += Block.Say(line)
                        if (line.terminator != Text.END) return out
                        p += line.length
                    }
                    in 0x0B..0x16 -> { out += Block.TextSound(c); p++ }
                    0x01 -> { out += Block.TextRam(rom.u16(p + 1)); p += 3 }
                    0x50 -> return out
                    else -> return out + Block.Code("text with special codes")
                }
            }
            return out
        }
    }

    // ---- Converting to an event -----------------------------------------------------------------

    /** Text sound commands as overworld sound effects (null: no equivalent outside battle). */
    private fun textSound(g: PokeRed, command: Int): Int? {
        val fx = com.needlerom.engine.script.ScriptEngine.sounds(g).effects
        fun named(n: String) = fx.firstOrNull { it.second == n }?.first
        return when (command) {
            0x0B, 0x0F -> named("Get Item (jingle)")
            0x10 -> named("Get Item (short)")
            0x11 -> named("Get Key Item")
            0x0E -> named("Pokédex Rating")
            else -> null
        }
    }

    /** Why the outline can't become an event, or null if it can. */
    fun whyNotConvertible(g: PokeRed, o: Outline): String? = reason(g, o.blocks)

    private fun reason(g: PokeRed, list: List<Block>, gave: Boolean = false): String? {
        var given = gave
        for ((i, b) in list.withIndex()) {
            if (b is Block.GiveItem || b is Block.GivePokemon) given = true
            val r = when (b) {
                is Block.TextRam -> if (b.address != g.sym("wStringBuffer") || !given) "its text shows a value from memory" else null
                is Block.Code -> "it runs game code (${b.what})"
                is Block.If -> when (val c = b.cond) {
                    is Cond.Other -> "it checks ${c.what}"
                    Cond.Yes -> if (list.getOrNull(i - 1) !is Block.Say) "its question can't be found" else null
                    else -> null
                } ?: reason(g, b.then, given) ?: reason(g, b.otherwise, given)
                is Block.TextSound -> if (textSound(g, b.command) == null) "it plays a sound that only works in battle" else null
                is Block.GiveItem -> b.ifBagFull?.let { reason(g, it, given) }
                is Block.GivePokemon -> b.ifNoRoom?.let { reason(g, it, given) }
                else -> null
            }
            if (r != null) return r
        }
        return null
    }

    /** The event that does what the outline does. Call only when [whyNotConvertible] is null. */
    fun toEvent(g: PokeRed, o: Outline): EventScript = EventScript(steps(g, o.blocks))

    private fun steps(g: PokeRed, list: List<Block>, given: String? = null): List<Step> {
        val out = mutableListOf<Step>()
        var name = given      // what wStringBuffer holds: the name of the last item/Pokémon given
        val text = StringBuilder()
        var open = false      // a text that continues into the next pieces
        fun flush() { if (open || text.isNotEmpty()) { out += Step.Say(text.toString()); text.clear(); open = false } }
        for (b in list) {
            if (b !is Block.Say && b !is Block.TextRam) flush()
            when (b) {
                is Block.Say -> {
                    text.append(b.line.text)
                    if (b.line.terminator == Text.END) open = true else flush()
                }
                is Block.TextRam -> { text.append(name ?: ""); open = true }
                is Block.TextSound -> out += Step.PlaySound(textSound(g, b.command)!!)
                is Block.If -> out += when (val c = b.cond) {
                    is Cond.Event -> Step.IfGameEvent(c.event, steps(g, b.then, name), steps(g, b.otherwise, name))
                    is Cond.HasItem -> Step.IfHasItem(c.item, steps(g, b.then, name), steps(g, b.otherwise, name))
                    Cond.Yes -> {
                        val q = out.removeAt(out.lastIndex) as Step.Say
                        Step.Ask(q.text, steps(g, b.then, name), steps(g, b.otherwise, name))
                    }
                    is Cond.Other -> error("not convertible")
                }
                is Block.SetEvent -> out += Step.SetGameEvent(b.event, b.value)
                is Block.GiveItem -> {
                    name = g.itemName(b.item)
                    out += Step.GiveItem(b.item, b.quantity, b.ifBagFull?.let { steps(g, it, name) } ?: emptyList())
                }
                is Block.GivePokemon -> {
                    name = g.monName(b.species)
                    out += Step.GivePokemon(b.species, b.level, b.ifNoRoom?.let { steps(g, it, name) } ?: emptyList())
                }
                is Block.WildBattle -> out += Step.WildBattle(b.species, b.level)
                is Block.PlaySound -> out += Step.PlaySound(b.sound)
                is Block.Code -> error("not convertible")
                Block.End -> {}
            }
        }
        flush()
        return out
    }
}

/** Reading and rewriting the game's own dialogue. */
object GameText {
    /** The TX_START text at [at] (its characters up to the first terminator), or null. */
    fun read(g: PokeRed, at: Int): GameLine? {
        val rom = g.rom
        if (!rom.inBounds(at) || rom.u8(at) != 0x00) return null
        var p = at + 1
        while (rom.inBounds(p) && p - at < 1200) {
            val b = rom.u8(p)
            if (b == Text.END || b == Text.DONE || b == Text.PROMPT) {
                val s = runCatching { Text.decode(rom.data, at + 1, p - at - 1) }.getOrNull() ?: return null
                return GameLine(at, s, b, p - at + 1)
            }
            p++
        }
        return null
    }

    /**
     * Rewrites [line] with [text]. It's changed in place when it fits; otherwise the new text
     * goes to free space and the old spot becomes a jump to it, so every script that shows
     * this line shows the new one.
     */
    fun write(g: PokeRed, line: GameLine, text: String) {
        val chars = Text.encodeDialogue(text)
        val rom = g.rom
        rom.batch {
            if (chars.size + 2 <= line.length) {
                rom.write(line.offset, byteArrayOf(0) + chars + line.terminator.toByte(), "Edit game text")
                return@batch
            }
            if (line.length < 5) throw EditException("This line is too short to be lengthened")
            var moved = byteArrayOf(0) + chars + line.terminator.toByte()
            if (line.terminator == Text.END) {
                // More text commands follow the line: continue with them after the new text.
                val rest = line.offset + line.length
                moved += byteArrayOf(0x17, Gb.ptr(rest).toByte(), (Gb.ptr(rest) shr 8).toByte(), Gb.bankOf(rest).toByte(), Text.END.toByte())
            }
            val at = g.world.allocEngine(moved, "Game text")
            rom.write(line.offset, byteArrayOf(0x17, Gb.ptr(at).toByte(), (Gb.ptr(at) shr 8).toByte(), g.world.engineBank.toByte(), Text.END.toByte()), "Edit game text")
        }
    }
}
