package com.needlerom.core.pokemon

import com.needlerom.core.Bytes
import com.needlerom.core.Rom
import kotlin.math.roundToInt
import kotlin.random.Random

data class BaseStats(
    val hp: Int, val atk: Int, val def: Int, val spd: Int, val spAtk: Int, val spDef: Int,
    val type1: Int, val type2: Int, val catchRate: Int, val baseExp: Int,
) {
    fun total(splitSpecial: Boolean) = hp + atk + def + spd + spAtk + if (splitSpecial) spDef else 0
}

data class Species(val index: Int, val name: String, val statsOffset: Int)

data class NameEntry(val index: Int, val offset: Int, val name: String)

/**
 * Edits Pokémon data in Gen 1–3 ROMs. Tables are located by searching for known
 * Bulbasaur/Ivysaur data rather than fixed offsets, so every language revision and most
 * existing hacks still work. Anything not found is reported as unavailable.
 */
class PokemonEditor(private val rom: Rom, val gen: PokemonGen) {

    private val nameStride = if (gen == PokemonGen.GEN3) 11 else 10
    private val nameCount = when (gen) { PokemonGen.GEN1 -> 190; PokemonGen.GEN2 -> 251; PokemonGen.GEN3 -> 412 }

    /** Offset of the Bulbasaur base-stats entry, or null if this ROM doesn't have one we recognise. */
    val bulbasaurStats: Int? = locateStats()
    private val gen1MewStats: Int? = if (gen == PokemonGen.GEN1) locateGen1Mew() else null

    /** Start of the name table (index 0), or null. */
    val nameTable: Int? = locateNames()

    val hasStats: Boolean get() = bulbasaurStats != null
    val hasNames: Boolean get() = nameTable != null

    private fun locateStats(): Int? {
        var at = rom.indexOf(gen.bulbasaurSig)
        while (at >= 0) {
            val next = at + gen.statsStride
            if (rom.inBounds(next, gen.ivysaurSig.size) &&
                rom.slice(next, gen.ivysaurSig.size).contentEquals(gen.ivysaurSig)
            ) {
                return at
            }
            at = rom.indexOf(gen.bulbasaurSig, at + 1)
        }
        return null
    }

    /** Red/Blue keep Mew outside the main table; Yellow appends it. */
    private fun locateGen1Mew(): Int? {
        val sig = Bytes.of(151, 100, 100, 100, 100, 100, 0x18, 0x18, 45)
        val base = bulbasaurStats
        if (base != null) {
            val inline = base + 150 * gen.statsStride
            if (rom.inBounds(inline, sig.size) && rom.slice(inline, sig.size).contentEquals(sig)) return inline
        }
        return rom.indexOf(sig).takeIf { it >= 0 }
    }

    private fun locateNames(): Int? {
        val t = gen.table
        val term = t.terminator!!.toByte()
        val (first, second) = if (gen == PokemonGen.GEN1) "RHYDON" to "KANGASKHAN" else "BULBASAUR" to "IVYSAUR"
        val firstBytes = t.encode(first)!! + term
        val secondBytes = t.encode(second)!!
        var at = rom.indexOf(firstBytes)
        while (at >= 0) {
            val next = at + nameStride
            if (rom.inBounds(next, secondBytes.size) && rom.slice(next, secondBytes.size).contentEquals(secondBytes)) {
                // Gen 3 tables start with a placeholder entry before Bulbasaur.
                return if (gen == PokemonGen.GEN3) at - nameStride else at
            }
            at = rom.indexOf(firstBytes, at + 1)
        }
        return null
    }

    fun names(): List<NameEntry> {
        val base = nameTable ?: return emptyList()
        val out = mutableListOf<NameEntry>()
        for (i in 0 until nameCount) {
            val off = base + i * nameStride
            if (!rom.inBounds(off, nameStride)) break
            out += NameEntry(i, off, gen.table.decode(rom.data, off, gen.nameLength))
        }
        return out
    }

    fun rename(entry: NameEntry, newName: String): String? {
        val t = gen.table
        if (newName.isBlank()) return "Name can't be empty"
        if (newName.length > gen.nameLength) return "Max ${gen.nameLength} characters"
        val bad = t.unsupportedChars(newName)
        if (bad.isNotEmpty()) return "Can't use: ${bad.joinToString(" ")}"
        val bytes = ByteArray(nameStride) { t.terminator!!.toByte() }
        t.encode(newName)!!.copyInto(bytes)
        rom.write(entry.offset, bytes, "Rename ${entry.name} → $newName")
        return null
    }

    /** Every species with editable stats, labelled with the best name available. */
    fun species(): List<Species> {
        val base = bulbasaurStats ?: return emptyList()
        val romNames = names()
        return when (gen) {
            PokemonGen.GEN1 -> buildList {
                for (dex in 1..150) add(Species(dex, KANTO_NAMES[dex - 1], base + (dex - 1) * gen.statsStride))
                gen1MewStats?.let { add(Species(151, "Mew", it)) }
            }
            PokemonGen.GEN2 -> (1..251).mapNotNull { dex ->
                val off = base + (dex - 1) * gen.statsStride
                if (!rom.inBounds(off, gen.statsStride)) return@mapNotNull null
                val name = romNames.getOrNull(dex - 1)?.name?.let(::pretty) ?: "No. $dex"
                Species(dex, name, off)
            }
            PokemonGen.GEN3 -> {
                // Index 0 is a dummy entry; 252-276 are unused "?" slots in the original games.
                val tableStart = base - gen.statsStride
                (1 until nameCount).mapNotNull { idx ->
                    val off = tableStart + idx * gen.statsStride
                    if (!rom.inBounds(off, gen.statsStride)) return@mapNotNull null
                    val raw = romNames.getOrNull(idx)?.name
                    if (raw != null && raw.all { it == '?' || it == ' ' }) return@mapNotNull null
                    Species(idx, raw?.let(::pretty) ?: "Species $idx", off)
                }
            }
        }
    }

    private fun pretty(name: String): String =
        name.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun read(sp: Species): BaseStats {
        val o = sp.statsOffset
        return BaseStats(
            hp = rom.u8(o + gen.hpOffset), atk = rom.u8(o + gen.atk), def = rom.u8(o + gen.def),
            spd = rom.u8(o + gen.spd), spAtk = rom.u8(o + gen.spAtk), spDef = rom.u8(o + gen.spDef),
            type1 = rom.u8(o + gen.type1), type2 = rom.u8(o + gen.type2),
            catchRate = rom.u8(o + gen.catchRate), baseExp = rom.u8(o + gen.baseExp),
        )
    }

    fun write(sp: Species, s: BaseStats, label: String = "Edit ${sp.name} stats") {
        val o = sp.statsOffset
        val first = o + gen.hpOffset
        val block = rom.slice(first, gen.baseExp - gen.hpOffset + 1)
        fun put(field: Int, v: Int) { block[field - gen.hpOffset] = v.coerceIn(0, 255).toByte() }
        put(gen.hpOffset, s.hp.coerceAtLeast(1)); put(gen.atk, s.atk); put(gen.def, s.def)
        put(gen.spd, s.spd); put(gen.spAtk, s.spAtk)
        if (gen.hasSplitSpecial) put(gen.spDef, s.spDef)
        put(gen.type1, s.type1); put(gen.type2, s.type2)
        put(gen.catchRate, s.catchRate); put(gen.baseExp, s.baseExp)
        rom.write(first, block, label)
    }

    // ---- Bulk hacks -------------------------------------------------------------------

    fun maxCatchRates(): Int = species().count { sp ->
        val s = read(sp)
        if (s.catchRate == 255) false else { write(sp, s.copy(catchRate = 255), "Max catch rate"); true }
    }

    fun scaleStats(percent: Int): Int {
        val f = percent / 100.0
        fun sc(v: Int) = (v * f).roundToInt().coerceIn(1, 255)
        return species().onEach { sp ->
            val s = read(sp)
            write(sp, s.copy(hp = sc(s.hp), atk = sc(s.atk), def = sc(s.def), spd = sc(s.spd),
                spAtk = sc(s.spAtk), spDef = sc(s.spDef)), "Scale stats $percent%")
        }.size
    }

    /** Redistributes each Pokémon's stat total randomly, keeping every species' overall power. */
    fun randomizeStats(seed: Long): Int {
        val rnd = Random(seed)
        val n = if (gen.hasSplitSpecial) 6 else 5
        return species().onEach { sp ->
            val s = read(sp)
            val total = s.total(gen.hasSplitSpecial)
            val weights = DoubleArray(n) { 0.5 + rnd.nextDouble() }
            val sum = weights.sum()
            val v = IntArray(n) { (total * weights[it] / sum).roundToInt().coerceIn(10, 255) }
            write(sp, s.copy(hp = v[0], atk = v[1], def = v[2], spd = v[3], spAtk = v[4],
                spDef = if (n == 6) v[5] else v[4]), "Randomize stats (seed $seed)")
        }.size
    }

    fun randomizeTypes(seed: Long): Int {
        val rnd = Random(seed)
        val pool = gen.types.filter { it.name != "???" }
        return species().onEach { sp ->
            val s = read(sp)
            val t1 = pool.random(rnd).id
            val t2 = if (rnd.nextBoolean()) t1 else pool.random(rnd).id
            write(sp, s.copy(type1 = t1, type2 = t2), "Randomize types (seed $seed)")
        }.size
    }
}
