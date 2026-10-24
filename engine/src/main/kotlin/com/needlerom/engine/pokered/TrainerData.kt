package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb

data class PartyMon(val level: Int, val species: Int)

/** One team a trainer class can use. Teams are numbered from 1, as in the map data. */
data class Party(val mons: List<PartyMon>) {
    /** The game stores same-level teams more compactly. */
    internal fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val sameLevel = mons.map { it.level }.distinct().size == 1
        if (sameLevel) {
            out.write(mons.first().level)
            mons.forEach { out.write(it.species) }
        } else {
            out.write(0xFF)
            mons.forEach { out.write(it.level); out.write(it.species) }
        }
        out.write(0)
        return out.toByteArray()
    }
}

data class TrainerClass(val id: Int, val name: String, val parties: List<Party>)

class TrainerData internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom
    private val bank get() = g.bankOf("TrainerDataPointers")
    private fun ptrField(cls: Int) = g.sym("TrainerDataPointers") + (cls - 1) * 2
    private fun dataOffset(cls: Int) = Gb.offset(bank, rom.u16(ptrField(cls)))

    private fun partyCount(cls: Int): Int = g.version.trainerPartyCounts.getOrElse(cls - 1) { 1 }

    /** Parses the class's teams; returns them with the byte size they occupy. */
    private fun parse(cls: Int): Pair<List<Party>, Int> {
        val start = dataOffset(cls)
        var p = start
        val parties = mutableListOf<Party>()
        repeat(partyCount(cls)) {
            val mons = mutableListOf<PartyMon>()
            if (rom.u8(p) == 0xFF) {
                p++
                while (rom.u8(p) != 0) { mons += PartyMon(rom.u8(p), rom.u8(p + 1)); p += 2 }
            } else {
                val level = rom.u8(p); p++
                while (rom.u8(p) != 0) { mons += PartyMon(level, rom.u8(p)); p++ }
            }
            p++
            parties += Party(mons)
        }
        return parties to (p - start)
    }

    val classes: List<TrainerClass> get() {
        val names = g.trainerClassNames
        return (1..PokeRed.NUM_TRAINER_CLASSES).map { TrainerClass(it, names.getOrElse(it) { "Class $it" }, parse(it).first) }
    }

    fun parties(cls: Int): List<Party> = parse(cls).first

    /** Replaces all of a class's teams. The number of teams must stay the same (maps refer to them by number). */
    fun setParties(cls: Int, parties: List<Party>) {
        val (old, oldSize) = parse(cls)
        if (parties.size != old.size) throw EditException("This class must keep exactly ${old.size} team(s)")
        if (parties.any { it.mons.isEmpty() || it.mons.size > 6 }) throw EditException("Each team needs 1 to 6 Pokémon")
        val bytes = parties.flatMap { it.encode().toList() }.toByteArray()
        val ptr = rom.u16(ptrField(cls))
        val shared = (1..PokeRed.NUM_TRAINER_CLASSES).count { rom.u16(ptrField(it)) == ptr } > 1
        Blocks.replace(rom, ptrField(cls), bank, dataOffset(cls), oldSize, bytes, shared, "${g.trainerClassNames.getOrElse(cls) { "Trainer" }} teams")
    }

    fun setParty(cls: Int, team: Int, party: Party) {
        val all = parties(cls).toMutableList()
        all[team - 1] = party
        setParties(cls, all)
    }
}

/** Wild Pokémon for one map: grass and water tables of 10 slots each. */
data class WildEncounters(val grassRate: Int, val grass: List<PartyMon>, val waterRate: Int, val water: List<PartyMon>) {
    companion object {
        /** Chance of each of the 10 slots being picked, in percent. */
        val SLOT_CHANCE = listOf(20, 20, 15, 10, 10, 10, 5, 5, 4, 1)
    }
}

class WildData internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom
    private val bank get() = g.bankOf("WildDataPointers")
    private fun ptrField(map: Int) = g.sym("WildDataPointers") + map * 2
    private fun dataOffset(map: Int) = Gb.offset(bank, rom.u16(ptrField(map)))

    private fun parse(map: Int): Pair<WildEncounters, Int> {
        val start = dataOffset(map)
        var p = start
        fun slots(): List<PartyMon> = (0 until 10).map { PartyMon(rom.u8(p + it * 2), rom.u8(p + it * 2 + 1)) }.also { p += 20 }
        val gr = rom.u8(p); p++
        val grass = if (gr != 0) slots() else emptyList()
        val wr = rom.u8(p); p++
        val water = if (wr != 0) slots() else emptyList()
        return WildEncounters(gr, grass, wr, water) to (p - start)
    }

    fun encounters(map: Int): WildEncounters = parse(map).first

    /** Maps that have wild Pokémon in the original game. */
    fun mapsWithEncounters(): List<Int> = (0 until PokeRed.NUM_MAPS).filter {
        runCatching { encounters(it).let { e -> e.grassRate > 0 || e.waterRate > 0 } }.getOrDefault(false)
    }

    fun setEncounters(map: Int, e: WildEncounters) {
        fun fill(list: List<PartyMon>): List<PartyMon> =
            if (list.isEmpty()) List(10) { PartyMon(5, g.internalOfDex(19)) } else (list + List(10) { list.last() }).take(10)
        val out = java.io.ByteArrayOutputStream()
        out.write(e.grassRate.coerceIn(0, 255))
        if (e.grassRate > 0) fill(e.grass).forEach { out.write(it.level.coerceIn(1, 100)); out.write(it.species) }
        out.write(e.waterRate.coerceIn(0, 255))
        if (e.waterRate > 0) fill(e.water).forEach { out.write(it.level.coerceIn(1, 100)); out.write(it.species) }
        val (_, oldSize) = parse(map)
        val ptr = rom.u16(ptrField(map))
        val shared = (0 until PokeRed.NUM_MAPS).count { rom.u16(ptrField(it)) == ptr } > 1
        Blocks.replace(rom, ptrField(map), bank, dataOffset(map), oldSize, out.toByteArray(), shared, "Wild Pokémon in ${g.mapName(map)}")
    }
}

/** The three starters in Oak's lab (Red/Blue). Every place the game refers to them is updated together. */
class Starters internal constructor(private val g: PokeRed) {
    private val rom get() = g.rom
    val supported: Boolean get() = g.version.starterRefs.isNotEmpty()

    /** Internal species ids for slots 1-3 (left ball = Charmander, middle = Squirtle, right = Bulbasaur in Red). */
    fun current(): List<Int> = (1..3).map { slot -> g.version.starterRefs.first { it.second == slot }.first.let(rom::u8) }

    fun set(slot: Int, species: Int, updateRival: Boolean = true) {
        if (!supported) throw EditException("Yellow's starter is always Pikachu")
        val old = current()[slot - 1]
        rom.batch {
            for ((off, s) in g.version.starterRefs) if (s == slot) rom.write8(off, species, "Starter $slot → ${g.monName(species)}")
            if (updateRival) {
                // The rival's teams use the starter species directly; swap the unevolved one too.
                for (cls in listOf(RIVAL1, RIVAL2, RIVAL3)) {
                    val parties = g.trainers.parties(cls)
                    val updated = parties.map { p -> Party(p.mons.map { if (it.species == old) it.copy(species = species) else it }) }
                    if (updated != parties) g.trainers.setParties(cls, updated)
                }
            }
        }
    }

    companion object {
        const val RIVAL1 = 25
        const val RIVAL2 = 42
        const val RIVAL3 = 43
    }
}
