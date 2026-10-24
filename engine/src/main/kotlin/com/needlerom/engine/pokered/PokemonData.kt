package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb

data class BaseStats(
    val hp: Int, val attack: Int, val defense: Int, val speed: Int, val special: Int,
    val type1: Int, val type2: Int, val catchRate: Int, val baseExp: Int,
    /** The 4 moves known at level 1 (0 = none). */
    val startMoves: List<Int>,
    val growthRate: Int,
    /** TM01-TM50 then HM01-HM05 compatibility, 55 flags. */
    val tmhm: List<Boolean>,
) {
    val total get() = hp + attack + defense + speed + special
}

sealed interface Evolution {
    val species: Int
    data class Level(val level: Int, override val species: Int) : Evolution
    data class Item(val item: Int, val minLevel: Int, override val species: Int) : Evolution
    data class Trade(val minLevel: Int, override val species: Int) : Evolution
}

data class LearnedMove(val level: Int, val move: Int)

data class EvosMoves(val evolutions: List<Evolution>, val learnset: List<LearnedMove>)

/** Base stats (by Pokédex number), names, evolutions and level-up moves (by internal index). */
class PokemonData internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom

    private fun statsOffset(dex: Int): Int = when {
        dex == 151 && g.hasSym("MewBaseStats") -> g.sym("MewBaseStats")
        else -> g.sym("BaseStats") + (dex - 1) * 28
    }

    fun stats(dex: Int): BaseStats {
        val o = statsOffset(dex)
        val tm = rom.slice(o + 20, 7)
        return BaseStats(
            hp = rom.u8(o + 1), attack = rom.u8(o + 2), defense = rom.u8(o + 3), speed = rom.u8(o + 4),
            special = rom.u8(o + 5), type1 = rom.u8(o + 6), type2 = rom.u8(o + 7),
            catchRate = rom.u8(o + 8), baseExp = rom.u8(o + 9),
            startMoves = (0 until 4).map { rom.u8(o + 15 + it) },
            growthRate = rom.u8(o + 19),
            tmhm = (0 until 55).map { (tm[it / 8].toInt() shr (it % 8)) and 1 == 1 },
        )
    }

    fun setStats(dex: Int, s: BaseStats) {
        val o = statsOffset(dex)
        val head = bytes(s.hp, s.attack, s.defense, s.speed, s.special, s.type1, s.type2, s.catchRate, s.baseExp)
        val tm = ByteArray(7)
        s.tmhm.forEachIndexed { i, on -> if (on) tm[i / 8] = (tm[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        rom.batch {
            rom.write(o + 1, head, "${g.monName(g.internalOfDex(dex))} stats")
            val moves = ByteArray(5) { if (it < 4) s.startMoves.getOrElse(it) { 0 }.toByte() else s.growthRate.toByte() }
            rom.write(o + 15, moves, "${g.monName(g.internalOfDex(dex))} stats")
            rom.write(o + 20, tm, "${g.monName(g.internalOfDex(dex))} TM/HM")
        }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].coerceIn(0, 255).toByte() }

    fun rename(internal: Int, name: String) {
        require(name.isNotBlank()) { "Name can't be empty" }
        val bytes = Text.encodeLine(name)
        if (bytes.size > 10) throw EditException("Pokémon names can be at most 10 characters")
        val out = ByteArray(10) { Text.END.toByte() }
        bytes.copyInto(out)
        rom.write(g.sym("MonsterNames") + (internal - 1) * 10, out, "Rename ${g.monName(internal)} → $name")
    }

    // ---- Evolutions & learnsets --------------------------------------------------------

    private fun evosPtrField(internal: Int) = g.sym("EvosMovesPointerTable") + (internal - 1) * 2
    private val evosBank get() = g.bankOf("EvosMovesPointerTable")
    private fun evosOffset(internal: Int) = Gb.offset(evosBank, rom.u16(evosPtrField(internal)))

    private fun parseEvos(o: Int): Pair<EvosMoves, Int> {
        var p = o
        val evos = mutableListOf<Evolution>()
        while (rom.u8(p) != 0) {
            evos += when (rom.u8(p)) {
                1 -> Evolution.Level(rom.u8(p + 1), rom.u8(p + 2)).also { p += 3 }
                2 -> Evolution.Item(rom.u8(p + 1), rom.u8(p + 2), rom.u8(p + 3)).also { p += 4 }
                else -> Evolution.Trade(rom.u8(p + 1), rom.u8(p + 2)).also { p += 3 }
            }
        }
        p++
        val moves = mutableListOf<LearnedMove>()
        while (rom.u8(p) != 0) { moves += LearnedMove(rom.u8(p), rom.u8(p + 1)); p += 2 }
        p++
        return EvosMoves(evos, moves) to p - o
    }

    fun evosMoves(internal: Int): EvosMoves = parseEvos(evosOffset(internal)).first

    fun setEvosMoves(internal: Int, data: EvosMoves) {
        val out = java.io.ByteArrayOutputStream()
        for (e in data.evolutions) when (e) {
            is Evolution.Level -> { out.write(1); out.write(e.level); out.write(e.species) }
            is Evolution.Item -> { out.write(2); out.write(e.item); out.write(e.minLevel); out.write(e.species) }
            is Evolution.Trade -> { out.write(3); out.write(e.minLevel); out.write(e.species) }
        }
        out.write(0)
        for (m in data.learnset.sortedBy { it.level }) { out.write(m.level.coerceIn(1, 100)); out.write(m.move) }
        out.write(0)
        val o = evosOffset(internal)
        val oldSize = parseEvos(o).second
        val ptr = rom.u16(evosPtrField(internal))
        val shared = (1..PokeRed.NUM_INDEXES).count { rom.u16(evosPtrField(it)) == ptr } > 1
        Blocks.replace(rom, evosPtrField(internal), evosBank, o, oldSize, out.toByteArray(), shared,
            "${g.monName(internal)} evolutions & moves")
    }

    // ---- Bulk ------------------------------------------------------------------------

    fun allDex(): List<Int> = (1..151).toList()

    fun scaleAll(percent: Int) = rom.batch {
        for (dex in allDex()) {
            val s = stats(dex)
            fun sc(v: Int) = (v * percent / 100).coerceIn(1, 255)
            setStats(dex, s.copy(hp = sc(s.hp), attack = sc(s.attack), defense = sc(s.defense), speed = sc(s.speed), special = sc(s.special)))
        }
    }

    fun maxCatchRates() = rom.batch { for (dex in allDex()) setStats(dex, stats(dex).copy(catchRate = 255)) }
}

/** Move data: 6 bytes per move (animation, effect, power, type, accuracy, PP). */
data class Move(val effect: Int, val power: Int, val type: Int, val accuracy: Int, val pp: Int) {
    /** Accuracy is stored as 0-255; shown as a percentage. */
    val accuracyPercent get() = (accuracy * 100 + 254) / 255
}

class MoveData internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom
    private fun o(id: Int) = g.sym("Moves") + (id - 1) * 6

    fun move(id: Int): Move = o(id).let { Move(rom.u8(it + 1), rom.u8(it + 2), rom.u8(it + 3), rom.u8(it + 4), rom.u8(it + 5)) }

    fun setMove(id: Int, m: Move) {
        val b = ByteArray(5) { listOf(m.effect, m.power, m.type, m.accuracy, m.pp.coerceIn(1, 40))[it].coerceIn(0, 255).toByte() }
        rom.write(o(id) + 1, b, "${g.moveName(id)} data")
    }

    fun renameAll(names: List<String>) = Blocks.rewriteNameList(rom, g.sym("MoveNames"), names, "Move names")

    companion object {
        fun accuracyFromPercent(p: Int) = (p.coerceIn(0, 100) * 255 / 100)
    }
}

/** Item names and prices. */
class ItemData internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom

    fun price(id: Int): Int {
        if (id !in 1..PokeRed.NUM_ITEMS) return 0
        val o = g.sym("ItemPrices") + (id - 1) * 3
        return (0 until 3).fold(0) { acc, i -> val b = rom.u8(o + i); acc * 100 + (b shr 4) * 10 + (b and 0xF) }
    }

    fun setPrice(id: Int, price: Int) {
        require(id in 1..PokeRed.NUM_ITEMS)
        val p = price.coerceIn(0, 999999)
        val digits = p.toString().padStart(6, '0')
        val b = ByteArray(3) { (((digits[it * 2] - '0') shl 4) or (digits[it * 2 + 1] - '0')).toByte() }
        rom.write(g.sym("ItemPrices") + (id - 1) * 3, b, "${g.itemName(id)} price")
    }

    fun renameAll(names: List<String>) = Blocks.rewriteNameList(rom, g.sym("ItemNames"), names, "Item names")
}
