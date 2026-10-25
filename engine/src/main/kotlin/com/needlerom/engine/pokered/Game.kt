package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb
import com.needlerom.engine.rom.Rom

class VersionInfo(
    val name: String,
    val globalChecksum: Int,
    val isYellow: Boolean,
    val symbols: Map<String, Int>,
    val homeSignatures: Map<String, String>,
    val textTableSizes: IntArray,
    val trainerPartyCounts: List<Int>,
    val starterRefs: List<Pair<Int, Int>>,
)

class UnsupportedRomException(message: String) : Exception(message)

data class PokeType(val id: Int, val name: String)

/**
 * Pokémon Red / Blue / Yellow (English). Entry point to every editor.
 *
 * The version is identified from the original ROM's header. Clean ROMs match exactly; for
 * ROMs that are already hacks, the version is chosen by title and each table is checked
 * before use (editors whose data has moved report themselves unavailable).
 */
class PokeRed(val rom: Rom) {
    val version: VersionInfo
    /** True when the base ROM is a byte-exact retail release. */
    val exact: Boolean

    init {
        val d = rom.original
        val title = String(d, 0x134, 15, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val checksum = ((d[0x14E].toInt() and 0xFF) shl 8) or (d[0x14F].toInt() and 0xFF)
        val byChecksum = KnownVersions.ALL.firstOrNull { it.globalChecksum == checksum }
        val byTitle = when {
            title.startsWith("POKEMON RED") -> KnownVersions.RED
            title.startsWith("POKEMON BLUE") -> KnownVersions.BLUE
            title.startsWith("POKEMON YELLOW") -> KnownVersions.YELLOW
            else -> null
        }
        version = byChecksum ?: byTitle
            ?: throw UnsupportedRomException("This doesn't look like Pokémon Red, Blue or Yellow (title \"$title\")")
        exact = byChecksum != null
    }

    val isYellow get() = version.isYellow

    fun sym(name: String): Int = version.symbols[name] ?: error("No address for $name in ${version.name}")
    fun hasSym(name: String) = name in version.symbols

    /** Home-bank routines the script engine calls, verified against the original bytes. */
    fun homeRoutineIntact(name: String): Boolean {
        val sig = version.homeSignatures[name] ?: return false
        val at = sym(name)
        return (0 until sig.length / 2).all { i -> rom.u8(at + i) == sig.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    // ---- Names -----------------------------------------------------------------------

    private fun readNameList(start: Int, count: Int): List<String> {
        val out = ArrayList<String>(count)
        var p = start
        repeat(count) {
            var e = p
            while (e < rom.size && rom.u8(e) != Text.END && e - p < 20) e++
            out += Text.name(rom.data, p, e - p)
            p = e + 1
        }
        return out
    }

    /** Species names by internal index (1-based; index 0 is unused). */
    val monNames: List<String> get() = List(NUM_INDEXES + 1) { i ->
        if (i == 0) "" else Text.name(rom.data, sym("MonsterNames") + (i - 1) * 10, 10)
    }
    fun monName(internal: Int): String = monNames.getOrNull(internal)?.takeIf { it.isNotBlank() } ?: "#$internal"

    val itemNames: List<String> get() = listOf("") + readNameList(sym("ItemNames"), NUM_ITEMS)
    fun itemName(id: Int): String = when {
        id in 1..NUM_ITEMS -> itemNames[id]
        id in HM01 until HM01 + 5 -> "HM0${id - HM01 + 1}"
        id in TM01 until TM01 + 50 -> "TM" + (id - TM01 + 1).toString().padStart(2, '0')
        else -> "Item #$id"
    }
    /** Every obtainable item id, for pickers. */
    val allItemIds: List<Int> get() = (1..NUM_ITEMS).filter { !itemNames[it].startsWith("?") } + (HM01 until HM01 + 5) + (TM01 until TM01 + 50)

    val moveNames: List<String> get() = listOf("—") + readNameList(sym("MoveNames"), NUM_MOVES)
    fun moveName(id: Int) = moveNames.getOrElse(id) { "Move #$id" }

    val trainerClassNames: List<String> get() = listOf("") + readNameList(sym("TrainerNames"), NUM_TRAINER_CLASSES)

    /** Readable name of a story flag (EVENT_* bit of wEventFlags). */
    fun eventName(event: Int): String = (if (isYellow) EventNames.YELLOW else EventNames.RED_BLUE)[event] ?: "Story flag #$event"

    /** Story flags that have names, for pickers. */
    val namedEvents: List<Int> get() = (if (isYellow) EventNames.YELLOW else EventNames.RED_BLUE).keys.sorted()

    fun mapName(id: Int): String = Names.MAPS[id] ?: "Map ${id.toString(16).uppercase()}"
    fun spriteName(id: Int): String = Names.SPRITES[id] ?: "Sprite $id"

    // ---- Pokédex order -----------------------------------------------------------------

    /** Pokédex number for an internal species index (0 for glitch / unused slots). */
    fun dexOf(internal: Int): Int = if (internal in 1..NUM_INDEXES) rom.u8(sym("PokedexOrder") + internal - 1) else 0

    /** Valid species as (internal index, dex number), sorted by Pokédex number. */
    val species: List<Pair<Int, Int>> get() =
        (1..NUM_INDEXES).map { it to dexOf(it) }.filter { it.second in 1..151 }.sortedBy { it.second }

    fun internalOfDex(dex: Int): Int = species.firstOrNull { it.second == dex }?.first ?: 0

    // ---- Editors ---------------------------------------------------------------------

    val pokemon by lazy { PokemonData(this) }
    val moves by lazy { MoveData(this) }
    val items by lazy { ItemData(this) }
    val trainers by lazy { TrainerData(this) }
    val wild by lazy { WildData(this) }
    val starters by lazy { Starters(this) }
    val world by lazy { World(this) }

    fun bankOf(symbol: String) = Gb.bankOf(sym(symbol))

    companion object {
        const val NUM_INDEXES = 190
        const val NUM_ITEMS = 0x53
        const val NUM_MOVES = 165
        const val NUM_TRAINER_CLASSES = 47
        const val NUM_MAPS = 0xF8
        const val HM01 = 0xC4
        const val TM01 = 0xC9
        const val OPP_ID_OFFSET = 200

        val TYPES = listOf(
            PokeType(0x00, "Normal"), PokeType(0x01, "Fighting"), PokeType(0x02, "Flying"),
            PokeType(0x03, "Poison"), PokeType(0x04, "Ground"), PokeType(0x05, "Rock"),
            PokeType(0x07, "Bug"), PokeType(0x08, "Ghost"), PokeType(0x14, "Fire"),
            PokeType(0x15, "Water"), PokeType(0x16, "Grass"), PokeType(0x17, "Electric"),
            PokeType(0x18, "Psychic"), PokeType(0x19, "Ice"), PokeType(0x1A, "Dragon"),
        )
        fun typeName(id: Int) = TYPES.firstOrNull { it.id == id }?.name ?: "Type $id"

        fun open(rom: Rom): PokeRed = PokeRed(rom)
    }
}
