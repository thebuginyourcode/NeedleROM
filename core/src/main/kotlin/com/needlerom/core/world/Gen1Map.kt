package com.needlerom.core.world

import com.needlerom.core.world.Gb.u16
import com.needlerom.core.world.Gb.u8

enum class NpcKind { PERSON, TRAINER, ITEM }

/** An NPC / item ball / trainer. Coordinates are in 16x16 steps. */
data class MapObject(
    val index: Int,
    val offset: Int,
    val sprite: Int,
    val x: Int,
    val y: Int,
    val movement: Int,
    val range: Int,
    val textId: Int,
    val kind: NpcKind,
    /** Item id for item balls; trainer class for trainers. */
    val extra1: Int = 0,
    /** Trainer party number. */
    val extra2: Int = 0,
) {
    val size: Int get() = when (kind) { NpcKind.PERSON -> 6; NpcKind.ITEM -> 7; NpcKind.TRAINER -> 8 }
}

data class Warp(val index: Int, val offset: Int, val x: Int, val y: Int, val destWarp: Int, val destMap: Int)
data class Sign(val index: Int, val offset: Int, val x: Int, val y: Int, val textId: Int)

/** How an NPC moves: the game's movement byte plus its range/facing byte. */
enum class Movement(val label: String, val movement: Int, val range: Int) {
    FACE_DOWN("Stands, faces down", 0xFF, 0xD0),
    FACE_UP("Stands, faces up", 0xFF, 0xD1),
    FACE_LEFT("Stands, faces left", 0xFF, 0xD2),
    FACE_RIGHT("Stands, faces right", 0xFF, 0xD3),
    STAND("Stands, turns randomly", 0xFF, 0xFF),
    WANDER("Wanders around", 0xFE, 0x00),
    UP_DOWN("Walks up & down", 0xFE, 0x01),
    LEFT_RIGHT("Walks left & right", 0xFE, 0x02);

    companion object {
        fun of(movement: Int, range: Int): Movement? = entries.firstOrNull { it.movement == movement && it.range == range }
    }
}

/**
 * A parsed map. Blocks are read live from the ROM; events (NPCs, warps, signs) are a
 * snapshot, so reload with [Gen1World.map] after editing them.
 */
class Gen1Map internal constructor(val world: Gen1World, val ref: MapRef, d: ByteArray) {
    private val rom = world.rom
    val bank = ref.bank
    val tilesetId = u8(d, ref.headerOffset)
    val height = u8(d, ref.headerOffset + 1)
    val width = u8(d, ref.headerOffset + 2)
    val blocksOffset = Gb.toOffset(bank, u16(d, ref.headerOffset + 3))
    private val textTablePtr = u16(d, ref.headerOffset + 5)
    private val connectionCount = Integer.bitCount(u8(d, ref.headerOffset + 9) and 0x0F)
    /** Location of the 2-byte pointer to this map's event data. */
    private val objectPtrField = ref.headerOffset + 10 + connectionCount * 11
    val eventsOffset = Gb.toOffset(bank, u16(d, objectPtrField))

    val tileset: Gen1Tileset get() = world.tileset(tilesetId)

    /** Steps (16x16 cells NPCs stand on) are half a block each way. */
    val stepWidth get() = width * 2
    val stepHeight get() = height * 2

    var borderBlock = 0; private set
    var warps: List<Warp> = emptyList(); private set
    var signs: List<Sign> = emptyList(); private set
    var objects: List<MapObject> = emptyList(); private set
    /** False when the event data doesn't parse cleanly; events are then read-only and hidden. */
    var eventsValid = false; private set
    private var objectCountOffset = 0
    private var objectsEnd = 0
    private var eventsEnd = 0

    init {
        parseEvents(d)
    }

    private fun parseEvents(d: ByteArray) {
        try {
            var p = eventsOffset
            val end = Gb.bankEnd(bank, d.size)
            fun need(n: Int) { if (p + n > end) throw IllegalStateException() }
            need(2)
            borderBlock = u8(d, p); p++
            val nw = u8(d, p); p++
            if (nw > 32) return
            need(nw * 4)
            warps = (0 until nw).map { i ->
                val o = p + i * 4
                Warp(i, o, x = u8(d, o + 1), y = u8(d, o), destWarp = u8(d, o + 2), destMap = u8(d, o + 3))
            }
            p += nw * 4
            need(1)
            val ns = u8(d, p); p++
            if (ns > 32) return
            need(ns * 3)
            signs = (0 until ns).map { i -> val o = p + i * 3; Sign(i, o, x = u8(d, o + 1), y = u8(d, o), textId = u8(d, o + 2)) }
            p += ns * 3
            need(1)
            objectCountOffset = p
            val no = u8(d, p); p++
            if (no > MAX_OBJECTS) return
            val objs = mutableListOf<MapObject>()
            repeat(no) { i ->
                need(6)
                val text = u8(d, p + 5)
                val kind = when {
                    text and 0x40 != 0 -> NpcKind.TRAINER
                    text and 0x80 != 0 -> NpcKind.ITEM
                    else -> NpcKind.PERSON
                }
                val o = MapObject(
                    i, p, sprite = u8(d, p), y = u8(d, p + 1) - 4, x = u8(d, p + 2) - 4,
                    movement = u8(d, p + 3), range = u8(d, p + 4), textId = text and 0x3F, kind = kind,
                    extra1 = if (kind != NpcKind.PERSON && p + 6 < end) u8(d, p + 6) else 0,
                    extra2 = if (kind == NpcKind.TRAINER && p + 7 < end) u8(d, p + 7) else 0,
                )
                need(o.size)
                objs += o
                p += o.size
            }
            objectsEnd = p
            need(nw * 4) // warp-to table, one entry per warp
            eventsEnd = p + nw * 4
            objects = objs
            val inside = { x: Int, y: Int -> x in 0 until stepWidth + 8 && y in 0 until stepHeight + 8 }
            eventsValid = objs.all { inside(it.x, it.y) } && warps.all { inside(it.x, it.y) }
        } catch (_: IllegalStateException) {
            eventsValid = false
        }
    }

    // ---- Blocks ----------------------------------------------------------------------

    fun block(x: Int, y: Int): Int = rom.u8(blocksOffset + y * width + x)

    fun setBlock(x: Int, y: Int, block: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        rom.write8(blocksOffset + y * width + x, block, "Paint ${ref.name}")
    }

    /** Flood-fills the connected area of identical blocks. Returns how many changed. */
    fun fill(x: Int, y: Int, block: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val target = block(x, y)
        if (target == block) return 0
        var count = 0
        val stack = ArrayDeque<Int>().apply { add(y * width + x) }
        val seen = BooleanArray(width * height)
        rom.batch {
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (seen[i]) continue
                seen[i] = true
                val cx = i % width
                val cy = i / width
                if (block(cx, cy) != target) continue
                rom.write8(blocksOffset + i, block, "Fill ${ref.name}")
                count++
                if (cx > 0) stack.add(i - 1)
                if (cx < width - 1) stack.add(i + 1)
                if (cy > 0) stack.add(i - width)
                if (cy < height - 1) stack.add(i + width)
            }
        }
        return count
    }

    fun setBorderBlock(block: Int) {
        if (eventsValid) rom.write8(eventsOffset, block, "Border block of ${ref.name}")
    }

    /** Is the step at (sx, sy) walkable according to the tileset's collision list? */
    fun isWalkable(sx: Int, sy: Int): Boolean {
        val b = block(sx / 2, sy / 2)
        return tileset.isWalkable(b, sx % 2, sy % 2)
    }

    // ---- Events ----------------------------------------------------------------------

    fun objectAt(sx: Int, sy: Int): MapObject? = objects.lastOrNull { it.x == sx && it.y == sy }
    fun warpAt(sx: Int, sy: Int): Warp? = warps.lastOrNull { it.x == sx && it.y == sy }
    fun signAt(sx: Int, sy: Int): Sign? = signs.lastOrNull { it.x == sx && it.y == sy }

    private fun inSteps(x: Int, y: Int) = x in 0 until stepWidth && y in 0 until stepHeight

    fun moveObject(o: MapObject, x: Int, y: Int) {
        if (!inSteps(x, y)) return
        rom.batch {
            rom.write8(o.offset + 1, y + 4, "Move NPC in ${ref.name}")
            rom.write8(o.offset + 2, x + 4, "Move NPC in ${ref.name}")
        }
    }

    fun setSprite(o: MapObject, sprite: Int) = rom.write8(o.offset, sprite, "NPC sprite in ${ref.name}")

    fun setMovement(o: MapObject, m: Movement) = rom.batch {
        rom.write8(o.offset + 3, m.movement, "NPC movement in ${ref.name}")
        rom.write8(o.offset + 4, m.range, "NPC movement in ${ref.name}")
    }

    fun setItem(o: MapObject, item: Int) {
        require(o.kind == NpcKind.ITEM)
        rom.write8(o.offset + 6, item, "Item ball → ${world.itemName(item)}")
    }

    fun setTrainerParty(o: MapObject, party: Int) {
        require(o.kind == NpcKind.TRAINER)
        rom.write8(o.offset + 7, party, "Trainer team #$party")
    }

    fun setTextId(o: MapObject, textId: Int) {
        val flags = rom.u8(o.offset + 5) and 0xC0
        rom.write8(o.offset + 5, flags or (textId and 0x3F), "NPC dialogue in ${ref.name}")
    }

    fun moveSign(s: Sign, x: Int, y: Int) {
        if (!inSteps(x, y)) return
        rom.batch {
            rom.write8(s.offset, y, "Move sign in ${ref.name}")
            rom.write8(s.offset + 1, x, "Move sign in ${ref.name}")
        }
    }

    fun setWarpDestination(w: Warp, destMap: Int, destWarp: Int) = rom.batch {
        rom.write8(w.offset + 2, destWarp, "Door destination in ${ref.name}")
        rom.write8(w.offset + 3, destMap, "Door destination in ${ref.name}")
    }

    /**
     * Moves a warp. The game also keeps a "warp-to" entry per warp holding a pointer into
     * the map buffer; it is recalculated from the existing entry so no RAM layout is assumed.
     */
    fun moveWarp(w: Warp, x: Int, y: Int) {
        if (!inSteps(x, y)) return
        val wt = objectsEnd + w.index * 4
        val oldPtr = rom.u16le(wt)
        val stride = width + 6
        val base = oldPtr - stride * (w.y shr 1) - (w.x shr 1)
        val newPtr = base + stride * (y shr 1) + (x shr 1)
        rom.batch {
            rom.write8(w.offset, y, "Move door in ${ref.name}")
            rom.write8(w.offset + 1, x, "Move door in ${ref.name}")
            rom.write(wt, byteArrayOf(newPtr.toByte(), (newPtr shr 8).toByte(), y.toByte(), x.toByte()), "Move door in ${ref.name}")
        }
    }

    /** Index of the first object that wasn't in the original ROM (only those can be deleted safely). */
    val firstAddedIndex: Int by lazy { world.originalMap(ref)?.objects?.size ?: objects.size }

    val canAddObject: Boolean get() = eventsValid && objects.size < MAX_OBJECTS

    /**
     * Adds a new NPC. Event data can't grow in place, so the whole block is rebuilt in free
     * space at the end of this map's ROM bank and the map header is re-pointed to it.
     * Returns an error message, or null on success.
     */
    fun addObject(sprite: Int, x: Int, y: Int, textId: Int, m: Movement): String? {
        if (!eventsValid) return "This map's event data couldn't be read"
        if (objects.size >= MAX_OBJECTS) return "A map can have at most $MAX_OBJECTS NPCs"
        if (!inSteps(x, y)) return "Outside the map"
        val newObj = byteArrayOf(sprite.toByte(), (y + 4).toByte(), (x + 4).toByte(), m.movement.toByte(), m.range.toByte(), (textId and 0x3F).toByte())
        val rebuilt = rebuild(insert = newObj)
        return relocate(rebuilt, "Add NPC to ${ref.name}")
    }

    fun removeObject(o: MapObject): String? {
        if (o.index < firstAddedIndex) return "Only NPCs you added can be deleted (the game's scripts refer to the originals)"
        val rebuilt = rebuild(remove = o)
        rom.write(eventsOffset, rebuilt, "Remove NPC from ${ref.name}")
        return null
    }

    private fun rebuild(insert: ByteArray? = null, remove: MapObject? = null): ByteArray {
        val d = rom.data
        val out = java.io.ByteArrayOutputStream()
        out.write(d, eventsOffset, objectCountOffset - eventsOffset)
        val count = objects.size + (if (insert != null) 1 else 0) - (if (remove != null) 1 else 0)
        out.write(count)
        for (o in objects) if (o.index != remove?.index) out.write(d, o.offset, o.size)
        if (insert != null) out.write(insert)
        out.write(d, objectsEnd, eventsEnd - objectsEnd)
        return out.toByteArray()
    }

    private fun relocate(bytes: ByteArray, label: String): String? {
        val at = FreeSpace.findInBank(rom.data, bank, bytes.size)
            ?: return "No free space left in ROM bank ${bank} for this map's NPCs"
        val ptr = Gb.toPtr(at)
        rom.batch {
            rom.write(at, bytes, label)
            rom.write(objectPtrField, byteArrayOf(ptr.toByte(), (ptr shr 8).toByte()), label)
        }
        return null
    }

    // ---- Dialogue --------------------------------------------------------------------

    /** Text ids used by this map's NPCs and signs, so new NPCs can reuse one. */
    val usedTextIds: List<Int> get() = (objects.map { it.textId } + signs.map { it.textId }).distinct().sorted()

    fun dialogue(textId: Int): Dialogue = Gen1Text.resolve(rom, bank, textTablePtr, textId)

    fun setDialogue(d: Dialogue, text: String): String? = Gen1Text.write(rom, d, text)

    companion object {
        const val MAX_OBJECTS = 15
    }
}
