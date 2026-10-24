package com.needlerom.engine.pokered

import com.needlerom.engine.rom.FreeSpace
import com.needlerom.engine.rom.Gb
import com.needlerom.engine.script.ScriptEngine

data class MapInfo(
    val id: Int,
    val name: String,
    val header: Int,
    val bank: Int,
    val tileset: Int,
    val width: Int,
    val height: Int,
)

/** Every map, tileset and overworld sprite. */
class World internal constructor(val game: PokeRed) {
    private val rom get() = game.rom

    val maps: List<MapInfo> by lazy {
        val seen = HashSet<Int>()
        (0 until PokeRed.NUM_MAPS).mapNotNull { id ->
            val h = headerOffset(id) ?: return@mapNotNull null
            if (!seen.add(h)) return@mapNotNull null
            MapInfo(id, game.mapName(id), h, Gb.bankOf(h), rom.u8(h), rom.u8(h + 2), rom.u8(h + 1))
        }
    }

    fun mapInfo(id: Int): MapInfo? = maps.firstOrNull { it.id == id }

    internal fun headerOffset(id: Int): Int? {
        val ptr = rom.u16(game.sym("MapHeaderPointers") + id * 2)
        val bank = rom.u8(game.sym("MapHeaderBanks") + id)
        if (ptr !in 0x4000..0x7FFF || bank == 0) return null
        val h = Gb.offset(bank, ptr)
        if (!rom.inBounds(h, 12)) return null
        val ts = rom.u8(h); val height = rom.u8(h + 1); val width = rom.u8(h + 2)
        if (ts >= tilesetCount || height !in 1..64 || width !in 1..64) return null
        return h
    }

    /** Parses the map as it is in the working ROM right now. */
    fun map(id: Int): GameMap = GameMap(this, mapInfo(id) ?: error("No map $id"))

    val tilesetCount: Int get() = if (game.isYellow) 25 else 24

    private val tilesetCache = HashMap<Int, Tileset>()
    fun tileset(id: Int): Tileset = tilesetCache.getOrPut(id) { Tileset(this, id) }

    // ---- Sprites -------------------------------------------------------------------------

    val spriteCount: Int get() = Names.SPRITES.keys.maxOrNull() ?: 72

    private val spriteCache = HashMap<Int, IntArray?>()

    /** 16x16 ARGB image of sprite [id] standing, facing down. */
    fun spriteImage(id: Int): IntArray? = spriteCache.getOrPut(id) {
        if (id !in 1..spriteCount) return@getOrPut null
        val e = game.sym("SpriteSheetPointerTable") + (id - 1) * 4
        val gfx = Gb.offset(rom.u8(e + 3), rom.u16(e))
        val out = IntArray(16 * 16)
        for (t in 0 until 4) Gfx.tile(rom.data, gfx + t * 16, out, 16, (t % 2) * 8, (t / 2) * 8, Gfx.SPRITE_PALETTE)
        out
    }

    /** ROM0 address of the event script hook, if installed. */
    val scriptHookAddress: Int? get() = ScriptEngine.hookAddress(game)

    // ---- Space management ---------------------------------------------------------------

    /**
     * Bank used for new dialogue and scripts: the last bank that is completely empty in
     * the original game (0x3F in Red/Blue, 0x3B in Yellow).
     */
    val engineBank: Int by lazy {
        val d = rom.original
        (d.size / Gb.BANK - 1 downTo 1).firstOrNull { b -> (b * Gb.BANK until (b + 1) * Gb.BANK).all { d[it] == 0.toByte() } }
            ?: throw EditException("This ROM has no empty bank for new content")
    }

    /** Stores [bytes] in the engine bank and returns their file offset. */
    internal fun allocEngine(bytes: ByteArray, label: String): Int {
        ScriptEngine.ensureBank(game)
        return FreeSpace.alloc(rom, engineBank, bytes, label) ?: throw EditException("The engine bank is full")
    }

    /** Stores [bytes] in [bank] (for data the game reads with a 16-bit pointer from that bank). */
    internal fun allocIn(bank: Int, bytes: ByteArray, label: String): Int {
        return FreeSpace.alloc(rom, bank, bytes, label)
            ?: throw EditException("ROM bank $bank (this map's bank) is out of free space")
    }

    /** A ROM0 address holding [value]; used for one-byte text scripts like the nurse. */
    internal fun homeByte(value: Int): Int {
        // Search well inside the home bank's code, which no editor ever changes.
        for (a in 0x0100 until 0x3F00) if (rom.original[a].toInt() and 0xFF == value && rom.u8(a) == value) return a
        throw EditException("No home-bank byte 0x${value.toString(16)} found")
    }
}

/** 2bpp graphics helpers. */
object Gfx {
    /** Classic DMG green, lightest to darkest. */
    val SHADES = intArrayOf(0xFFE0F8D0.toInt(), 0xFF88C070.toInt(), 0xFF346856.toInt(), 0xFF081820.toInt())
    val BG_PALETTE = SHADES
    /** Overworld sprite palette (OBP0 = %11010000): colour 0 is transparent. */
    val SPRITE_PALETTE = intArrayOf(0, SHADES[0], SHADES[1], SHADES[3])

    fun tile(d: ByteArray, at: Int, out: IntArray, outWidth: Int, x: Int, y: Int, palette: IntArray) {
        if (at < 0 || at + 16 > d.size) return
        for (row in 0 until 8) {
            val lo = d[at + row * 2].toInt() and 0xFF
            val hi = d[at + row * 2 + 1].toInt() and 0xFF
            for (col in 0 until 8) {
                val bit = 7 - col
                val c = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                val argb = palette[c]
                if (argb != 0) out[(y + row) * outWidth + x + col] = argb
            }
        }
    }
}

/** A tileset: 2bpp tiles, 4x4-tile blocks and the walkable tile list. */
class Tileset internal constructor(private val world: World, val id: Int) {
    private val rom = world.game.rom
    private val entry = world.game.sym("Tilesets") + id * 12
    val bank = rom.u8(entry)
    val blocksOffset = Gb.offset(bank, rom.u16(entry + 1))
    val gfxOffset = Gb.offset(bank, rom.u16(entry + 3))
    val blockCount: Int
    private val walkable: Set<Int>

    init {
        // Blocks run until the next tileset's data in this bank.
        val others = (0 until world.tilesetCount).flatMap { i ->
            val e = world.game.sym("Tilesets") + i * 12
            if (rom.u8(e) != bank) emptyList() else listOf(Gb.offset(bank, rom.u16(e + 1)), Gb.offset(bank, rom.u16(e + 3)))
        }.filter { it > blocksOffset }
        val limit = (others.minOrNull() ?: Gb.bankEnd(bank, rom.size)) - blocksOffset
        blockCount = (limit / 16).coerceIn(1, 128)
        val coll = rom.u16(entry + 5)
        val collBank = Gb.bankOf(world.game.sym("Overworld_Coll"))
        walkable = buildSet {
            var p = Gb.offset(collBank, coll)
            while (rom.inBounds(p) && rom.u8(p) != 0xFF && size < 128) { add(rom.u8(p)); p++ }
        }
    }

    fun blockTiles(block: Int): IntArray {
        val o = blocksOffset + block * 16
        return IntArray(16) { if (rom.inBounds(o + it)) rom.u8(o + it) else 0 }
    }

    /** 32x32 ARGB image of [block]. */
    fun renderBlock(block: Int): IntArray {
        val out = IntArray(32 * 32) { Gfx.SHADES[0] }
        val tiles = blockTiles(block)
        for (i in 0 until 16) {
            val t = tiles[i]
            if (t >= 0x60) continue // tiles past the tileset are font/text tiles at runtime
            Gfx.tile(rom.data, gfxOffset + t * 16, out, 32, (i % 4) * 8, (i / 4) * 8, Gfx.BG_PALETTE)
        }
        return out
    }

    /** The game checks the bottom-left 8x8 tile of each 16x16 step. */
    fun isWalkable(block: Int, qx: Int, qy: Int): Boolean = blockTiles(block)[(qy * 2 + 1) * 4 + qx * 2] in walkable
}
