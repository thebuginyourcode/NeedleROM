package com.needlerom.core.world

import com.needlerom.core.Bytes
import com.needlerom.core.Rom
import com.needlerom.core.TextTable
import com.needlerom.core.world.Gb.u16
import com.needlerom.core.world.Gb.u8

data class MapRef(
    val id: Int,
    val name: String,
    val headerOffset: Int,
    val bank: Int,
    val tileset: Int,
    val width: Int,
    val height: Int,
)

/**
 * The overworld of Pokémon Red / Blue / Yellow: every map, tileset and overworld sprite.
 *
 * Nothing is read from hard-coded addresses alone. The map header tables, tileset table,
 * sprite table and item names are each found by searching for their known structure and
 * then validated, so this works across revisions and on most existing hacks. If anything
 * can't be found, [locate] returns null and the world builder stays switched off.
 */
class Gen1World private constructor(
    val rom: Rom,
    private val headerTable: Int,
    private val bankTable: Int,
    private val tilesetTable: Int,
    val tilesetCount: Int,
    private val spriteTable: Int?,
    val spriteCount: Int,
    private val itemNameList: List<String>,
) {
    val maps: List<MapRef> = buildList {
        val seen = HashSet<Int>()
        for (id in 0 until MAP_COUNT) {
            val h = headerOffset(rom.data, headerTable, bankTable, id) ?: continue
            if (!headerOk(rom.data, h, tilesetCount) || !seen.add(h)) continue
            add(MapRef(id, mapName(id), h, Gb.bankOf(h), u8(rom.data, h), u8(rom.data, h + 2), u8(rom.data, h + 1)))
        }
    }

    fun mapRef(id: Int): MapRef? = maps.firstOrNull { it.id == id }

    private val tilesetCache = HashMap<Int, Gen1Tileset>()
    fun tileset(id: Int): Gen1Tileset = tilesetCache.getOrPut(id) { Gen1Tileset(rom, tilesetTable + id * 12, tilesetTable, tilesetCount) }

    /** Parses the map as it is now in the working ROM. */
    fun map(ref: MapRef): Gen1Map = Gen1Map(this, ref, rom.data)

    /** Parses the map as it was in the untouched ROM (null if the header moved). */
    fun originalMap(ref: MapRef): Gen1Map? =
        if (headerOk(rom.original, ref.headerOffset, tilesetCount)) Gen1Map(this, ref, rom.original) else null

    // ---- Sprites ---------------------------------------------------------------------

    private val spriteCache = HashMap<Int, IntArray?>()

    /** 16x16 ARGB image of sprite [id] standing and facing down, or null if unknown. */
    fun spriteImage(id: Int): IntArray? = spriteCache.getOrPut(id) {
        val table = spriteTable ?: return@getOrPut null
        if (id !in 1..spriteCount) return@getOrPut null
        val e = table + (id - 1) * 4
        val d = rom.data
        val gfx = Gb.toOffset(u8(d, e + 3), u16(d, e))
        val out = IntArray(16 * 16)
        for (t in 0 until 4) Gb.drawTile(d, gfx + t * 16, out, 16, (t % 2) * 8, (t / 2) * 8, Gb.SPRITE_PALETTE)
        out
    }

    val hasSprites: Boolean get() = spriteTable != null

    // ---- Items -----------------------------------------------------------------------

    fun itemName(id: Int): String = when {
        id in 1..itemNameList.size -> itemNameList[id - 1]
        id in 0xC4..0xC8 -> "HM0${id - 0xC3}"
        id in 0xC9..0xFA -> "TM" + (id - 0xC8).toString().padStart(2, '0')
        else -> "Item 0x${Bytes.hex(id)}"
    }

    val itemIds: List<Int> get() = (1..itemNameList.size).filter { !itemNameList[it - 1].startsWith("?") } + (0xC4..0xFA)

    companion object {
        const val MAP_COUNT = 0xF8

        fun locate(rom: Rom): Gen1World? {
            val d = rom.data
            val (tsTable, tsCount) = findTilesets(d) ?: return null
            val (hdr, banks) = findMapTables(d, tsCount) ?: return null
            val (spr, sprCount) = findSpriteTable(d) ?: (null to 0)
            return Gen1World(rom, hdr, banks, tsTable, tsCount, spr, sprCount, findItemNames(d))
        }

        internal fun headerOffset(d: ByteArray, headerTable: Int, bankTable: Int, id: Int): Int? {
            if (!Gb.inRom(d, headerTable + id * 2, 2) || !Gb.inRom(d, bankTable + id)) return null
            val ptr = u16(d, headerTable + id * 2)
            val bank = u8(d, bankTable + id)
            if (ptr !in 0x4000..0x7FFF || bank == 0) return null
            return Gb.toOffset(bank, ptr).takeIf { Gb.inRom(d, it, 12) }
        }

        internal fun headerOk(d: ByteArray, h: Int, tilesetCount: Int): Boolean {
            if (!Gb.inRom(d, h, 12)) return false
            val tileset = u8(d, h)
            val height = u8(d, h + 1)
            val width = u8(d, h + 2)
            val blocks = u16(d, h + 3)
            val conn = u8(d, h + 9)
            if (tileset >= tilesetCount || height !in 1..64 || width !in 1..64) return false
            if (blocks !in 0x4000..0x7FFF || conn and 0xF0 != 0) return false
            val bank = Gb.bankOf(h)
            val off = Gb.toOffset(bank, blocks)
            return off + width * height <= Gb.bankEnd(bank, d.size)
        }

        /** The tileset table: first entry is the overworld tileset (no counters, grass tile $52, water/flower animation). */
        private fun findTilesets(d: ByteArray): Pair<Int, Int>? {
            val sig = Bytes.of(0xFF, 0xFF, 0xFF, 0x52, 0x02)
            var at = Bytes.indexOf(d, sig)
            while (at >= 0) {
                val start = at - 7
                if (start >= 0 && tilesetEntryOk(d, start)) {
                    var n = 1
                    while (n < 32 && tilesetEntryOk(d, start + n * 12)) n++
                    if (n >= 8) return start to n
                }
                at = Bytes.indexOf(d, sig, at + 1)
            }
            return null
        }

        private fun tilesetEntryOk(d: ByteArray, e: Int): Boolean {
            if (!Gb.inRom(d, e, 12)) return false
            val bank = u8(d, e)
            if (bank !in 1..0x3F || bank * Gb.BANK_SIZE >= d.size) return false
            val blocks = u16(d, e + 1)
            val gfx = u16(d, e + 3)
            return blocks in 0x4000..0x7FFF && gfx in 0x4000..0x7FFF && u8(d, e + 11) <= 2
        }

        private fun mapTablesOk(d: ByteArray, hdr: Int, banks: Int, tsCount: Int): Boolean {
            // Maps 0-8 are the towns from Pallet to Cinnabar, all on the overworld tileset.
            for (i in 0..8) {
                val h = headerOffset(d, hdr, banks, i) ?: return false
                if (!headerOk(d, h, tsCount) || u8(d, h) != 0) return false
            }
            return true
        }

        private fun findMapTables(d: ByteArray, tsCount: Int): Pair<Int, Int>? {
            // Where the original games keep them (MapHeaderPointers / MapHeaderBanks).
            if (mapTablesOk(d, 0x01AE, 0xC23D, tsCount)) return 0x01AE to 0xC23D
            // Otherwise: find Pallet Town's header (overworld, 9x10, north+south links)...
            val sig = Bytes.of(0x00, 0x09, 0x0A)
            var at = Bytes.indexOf(d, sig, Gb.BANK_SIZE)
            while (at >= 0) {
                if (Gb.inRom(d, at, 12) && u8(d, at + 9) == 0x0C && headerOk(d, at, tsCount)) {
                    val ptr = Gb.toPtr(at)
                    val bank = Gb.bankOf(at)
                    // ...then the pointer to it in bank 0, and the matching bank table.
                    var p = 0
                    while (p < Gb.BANK_SIZE - 2) {
                        if (u16(d, p) == ptr) {
                            var b = Bytes.indexOf(d, byteArrayOf(bank.toByte()))
                            while (b >= 0) {
                                if (mapTablesOk(d, p, b, tsCount)) return p to b
                                b = Bytes.indexOf(d, byteArrayOf(bank.toByte()), b + 1)
                            }
                        }
                        p++
                    }
                }
                at = Bytes.indexOf(d, sig, at + 1)
            }
            return null
        }

        /** Overworld sprite sheets: entries of (pointer, size $40 or $C0, bank). */
        private fun findSpriteTable(d: ByteArray): Pair<Int, Int>? {
            fun ok(e: Int) = Gb.inRom(d, e, 4) && u16(d, e) in 0x4000..0x7FFF &&
                (u8(d, e + 2) == 0x40 || u8(d, e + 2) == 0xC0) && u8(d, e + 3) in 1..0x3F
            var p = 0
            while (p < d.size - 4 * 40) {
                if (ok(p)) {
                    var n = 1
                    while (ok(p + n * 4)) n++
                    if (n >= 40 && u8(d, p + 2) == 0xC0) return p to n
                    p += n * 4
                } else {
                    p++
                }
            }
            return null
        }

        private fun findItemNames(d: ByteArray): List<String> {
            val t = TextTable.POKEMON_GB
            val sig = t.encode("MASTER BALL")!! + 0x50.toByte() + t.encode("ULTRA BALL")!!
            val start = Bytes.indexOf(d, sig).takeIf { it >= 0 } ?: return emptyList()
            val names = mutableListOf<String>()
            var p = start
            // Items 0x01-0x53 have names; HMs/TMs are numbered separately.
            while (names.size < 0x53 && p < d.size) {
                val s = t.decode(d, p, 16)
                if (s.isEmpty() || s.contains('·')) break
                names += s
                p += s.length + 1
            }
            return names
        }

        fun mapName(id: Int): String = MAP_NAMES[id] ?: when (id) {
            in 0x0C..0x24 -> "Route ${id - 0x0B}"
            else -> "Map 0x${Bytes.hex(id)}"
        }

        private val MAP_NAMES = mapOf(
            0x00 to "Pallet Town", 0x01 to "Viridian City", 0x02 to "Pewter City",
            0x03 to "Cerulean City", 0x04 to "Lavender Town", 0x05 to "Vermilion City",
            0x06 to "Celadon City", 0x07 to "Fuchsia City", 0x08 to "Cinnabar Island",
            0x09 to "Indigo Plateau", 0x0A to "Saffron City",
            0x25 to "Red's House 1F", 0x26 to "Red's House 2F", 0x27 to "Blue's House",
            0x28 to "Oak's Lab",
        )
    }
}

/** One tileset: 2bpp tiles, 4x4-tile blocks and the list of walkable tiles. */
class Gen1Tileset internal constructor(private val rom: Rom, entry: Int, table: Int, count: Int) {
    val bank: Int
    val blocksOffset: Int
    val gfxOffset: Int
    val blockCount: Int
    private val walkable: Set<Int>

    init {
        val d = rom.data
        bank = u8(d, entry)
        blocksOffset = Gb.toOffset(bank, u16(d, entry + 1))
        gfxOffset = Gb.toOffset(bank, u16(d, entry + 3))
        // Blocks run until the next tileset data in the same bank (or 128 blocks).
        val others = (0 until count).flatMap { i ->
            val e = table + i * 12
            if (u8(d, e) != bank) emptyList() else listOf(Gb.toOffset(bank, u16(d, e + 1)), Gb.toOffset(bank, u16(d, e + 3)))
        }.filter { it > blocksOffset }
        val limit = (others.minOrNull() ?: Gb.bankEnd(bank, d.size)) - blocksOffset
        blockCount = (limit / 16).coerceIn(1, 128)
        val coll = u16(d, entry + 5)
        walkable = buildSet {
            if (coll < 0x4000) {
                var p = coll
                while (p < 0x4000 && u8(d, p) != 0xFF && size < 128) { add(u8(d, p)); p++ }
            }
        }
    }

    val hasCollision: Boolean get() = walkable.isNotEmpty()

    fun blockTiles(block: Int): IntArray {
        val o = blocksOffset + block * 16
        return IntArray(16) { if (Gb.inRom(rom.data, o + it)) u8(rom.data, o + it) else 0 }
    }

    /** 32x32 ARGB image of [block]. */
    fun renderBlock(block: Int): IntArray {
        val out = IntArray(32 * 32) { Gb.SHADES[0] }
        val tiles = blockTiles(block)
        for (i in 0 until 16) {
            val t = tiles[i]
            if (t >= 0x60) continue // font/text tiles aren't part of the tileset graphics
            Gb.drawTile(rom.data, gfxOffset + t * 16, out, 32, (i % 4) * 8, (i / 4) * 8, Gb.BG_PALETTE)
        }
        return out
    }

    /** Whether the 16x16 step at quadrant (qx, qy) of [block] can be walked on. */
    fun isWalkable(block: Int, qx: Int, qy: Int): Boolean =
        blockTiles(block)[(qy * 2 + 1) * 4 + qx * 2] in walkable
}
