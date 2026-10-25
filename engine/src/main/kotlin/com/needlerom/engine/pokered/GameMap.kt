package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Flags
import com.needlerom.engine.script.ScriptEngine

enum class NpcKind { PERSON, TRAINER, ITEM }

/** An NPC, trainer or item ball. Coordinates are 16x16 steps. */
data class Npc(
    val index: Int,
    val sprite: Int,
    val x: Int,
    val y: Int,
    val movement: Int,
    val range: Int,
    val textId: Int,
    val kind: NpcKind,
    /** Item id (item balls) or trainer class + 200 (trainers). */
    val extra1: Int = 0,
    /** Trainer team number. */
    val extra2: Int = 0,
) {
    val trainerClass: Int get() = extra1 - PokeRed.OPP_ID_OFFSET
}

/** A door/warp. [destWarp] is 1-based like the map editor shows it. */
data class Warp(val index: Int, val x: Int, val y: Int, val destWarp: Int, val destMap: Int)

data class Sign(val index: Int, val x: Int, val y: Int, val textId: Int)

enum class Movement(val label: String, val movement: Int, val range: Int) {
    FACE_DOWN("Stands, faces down", 0xFF, 0xD0),
    FACE_UP("Stands, faces up", 0xFF, 0xD1),
    FACE_LEFT("Stands, faces left", 0xFF, 0xD2),
    FACE_RIGHT("Stands, faces right", 0xFF, 0xD3),
    STAND("Stands, looks around", 0xFF, 0xFF),
    WANDER("Wanders around", 0xFE, 0x00),
    UP_DOWN("Walks up and down", 0xFE, 0x01),
    LEFT_RIGHT("Walks left and right", 0xFE, 0x02);

    companion object {
        fun of(movement: Int, range: Int) = entries.firstOrNull { it.movement == movement && it.range == range }
    }
}

/** What happens when the player talks to an NPC or reads a sign. */
sealed interface Behavior {
    data class Talk(val text: String) : Behavior
    data class Mart(val items: List<Int>) : Behavior
    data object Nurse : Behavior
    data object PokecenterPC : Behavior
    data object PlayersPC : Behavior
    data object BillsPC : Behavior
    data object VendingMachine : Behavior
    data object PrizeVendor : Behavior
    data object CableClub : Behavior
    /** A NeedleScript event (see [com.needlerom.engine.script]). */
    data object Script : Behavior
    data object ItemPickup : Behavior
    /** Original game code (cutscenes, trainer battles, …), kept as is. */
    data object GameCode : Behavior

    val label: String get() = when (this) {
        is Talk -> "Talks"
        is Mart -> "Shop"
        Nurse -> "Pokécenter nurse"
        PokecenterPC -> "Pokécenter PC"
        PlayersPC -> "Player's PC"
        BillsPC -> "Bill's PC"
        VendingMachine -> "Vending machine"
        PrizeVendor -> "Prize vendor"
        CableClub -> "Cable club"
        Script -> "Event script"
        ItemPickup -> "Item pickup"
        GameCode -> "Game script"
    }

    companion object {
        /** Behaviours the editor can assign. */
        val ASSIGNABLE = listOf("Talks", "Shop", "Pokécenter nurse", "Pokécenter PC", "Event script")
    }
}

/**
 * One map. Blocks are read live from the ROM; events are a snapshot, so get a fresh
 * [GameMap] from [World.map] after changing them.
 */
class GameMap internal constructor(val world: World, val info: MapInfo) {
    private val g = world.game
    private val rom = g.rom
    val id = info.id
    val bank = info.bank
    private val h = info.header

    val tilesetId = rom.u8(h)
    val height = rom.u8(h + 1)
    val width = rom.u8(h + 2)
    val blocksOffset = Gb.offset(bank, rom.u16(h + 3))
    private val textPtrField = h + 5
    private val connectionFlags = rom.u8(h + 9)
    private val eventsPtrField = h + 10 + Integer.bitCount(connectionFlags and 0x0F) * 11
    val eventsOffset = Gb.offset(bank, rom.u16(eventsPtrField))

    val tileset: Tileset get() = world.tileset(tilesetId)
    val stepWidth get() = width * 2
    val stepHeight get() = height * 2

    /** Neighbouring maps (north, south, west, east), for display. */
    val connections: List<Pair<String, Int>> = buildList {
        var p = h + 10
        for ((bit, dir) in listOf(8 to "North", 4 to "South", 2 to "West", 1 to "East")) {
            if (connectionFlags and bit != 0) { add(dir to rom.u8(p)); p += 11 }
        }
    }

    // ---- Events -------------------------------------------------------------------------

    private class Events(
        var border: Int,
        val warps: MutableList<ByteArray>,
        val signs: MutableList<ByteArray>,
        val objects: MutableList<ByteArray>,
        val warpTos: MutableList<ByteArray>,
    ) {
        fun encode(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(border)
            out.write(warps.size); warps.forEach(out::write)
            out.write(signs.size); signs.forEach(out::write)
            out.write(objects.size); objects.forEach(out::write)
            warpTos.forEach(out::write)
            return out.toByteArray()
        }
    }

    private fun parseEvents(d: ByteArray, at: Int): Events? = try {
        fun u8(o: Int) = d[o].toInt() and 0xFF
        var p = at
        val border = u8(p++)
        val nw = u8(p++)
        if (nw > 32) throw IllegalStateException()
        val warps = MutableList(nw) { d.copyOfRange(p + it * 4, p + it * 4 + 4) }; p += nw * 4
        val ns = u8(p++)
        if (ns > 16) throw IllegalStateException()
        val signs = MutableList(ns) { d.copyOfRange(p + it * 3, p + it * 3 + 3) }; p += ns * 3
        val no = u8(p++)
        if (no > 16) throw IllegalStateException()
        val objects = mutableListOf<ByteArray>()
        repeat(no) {
            val t = u8(p + 5)
            val size = when { t and 0x40 != 0 -> 8; t and 0x80 != 0 -> 7; else -> 6 }
            objects += d.copyOfRange(p, p + size); p += size
        }
        val warpTos = MutableList(nw) { d.copyOfRange(p + it * 4, p + it * 4 + 4) }
        Events(border, warps, signs, objects, warpTos)
    } catch (_: Exception) {
        null
    }

    private val events: Events? = parseEvents(rom.data, eventsOffset)
    val eventsValid: Boolean get() = events != null

    private val originalEvents: Events? by lazy {
        if (id in world.createdMaps()) return@lazy null // made in the editor: everything is deletable
        val oh = rom.original
        val optr = (oh[eventsPtrField].toInt() and 0xFF) or ((oh[eventsPtrField + 1].toInt() and 0xFF) shl 8)
        parseEvents(oh, Gb.offset(bank, optr))
    }

    val borderBlock: Int get() = events?.border ?: 0

    val npcs: List<Npc> = events?.objects?.mapIndexed { i, b ->
        fun u(k: Int) = b[k].toInt() and 0xFF
        val t = u(5)
        val kind = when { t and 0x40 != 0 -> NpcKind.TRAINER; t and 0x80 != 0 -> NpcKind.ITEM; else -> NpcKind.PERSON }
        Npc(i, u(0), x = u(2) - 4, y = u(1) - 4, movement = u(3), range = u(4), textId = t and 0x3F, kind = kind,
            extra1 = if (b.size > 6) u(6) else 0, extra2 = if (b.size > 7) u(7) else 0)
    } ?: emptyList()

    val warps: List<Warp> = events?.warps?.mapIndexed { i, b ->
        Warp(i, x = b[1].toInt() and 0xFF, y = b[0].toInt() and 0xFF, destWarp = (b[2].toInt() and 0xFF) + 1, destMap = b[3].toInt() and 0xFF)
    } ?: emptyList()

    val signs: List<Sign> = events?.signs?.mapIndexed { i, b ->
        Sign(i, x = b[1].toInt() and 0xFF, y = b[0].toInt() and 0xFF, textId = b[2].toInt() and 0xFF)
    } ?: emptyList()

    /** Events at or beyond these indexes were added in the editor and may be deleted. */
    private val isNew get() = id in world.createdMaps()
    val firstAddedNpc: Int get() = originalEvents?.objects?.size ?: if (isNew) 0 else npcs.size
    val firstAddedSign: Int get() = originalEvents?.signs?.size ?: if (isNew) 0 else signs.size
    val firstAddedWarp: Int get() = originalEvents?.warps?.size ?: if (isNew) 1 else warps.size

    fun npcAt(x: Int, y: Int) = npcs.lastOrNull { it.x == x && it.y == y }
    fun warpAt(x: Int, y: Int) = warps.lastOrNull { it.x == x && it.y == y }
    fun signAt(x: Int, y: Int) = signs.lastOrNull { it.x == x && it.y == y }

    private fun writeEvents(e: Events, label: String, inPlaceOk: Boolean) {
        val bytes = e.encode()
        val old = events?.encode()?.size ?: 0
        rom.batch {
            if (inPlaceOk && bytes.size <= old) {
                rom.write(eventsOffset, bytes, label)
            } else {
                val at = world.allocIn(bank, bytes, label)
                rom.write16(eventsPtrField, Gb.ptr(at), label)
            }
        }
    }

    private fun requireEvents(): Events = events?.let { e ->
        Events(e.border, e.warps.map { it.copyOf() }.toMutableList(), e.signs.map { it.copyOf() }.toMutableList(),
            e.objects.map { it.copyOf() }.toMutableList(), e.warpTos.map { it.copyOf() }.toMutableList())
    } ?: throw EditException("This map's event data couldn't be read")

    private fun inMap(x: Int, y: Int) = x in 0 until stepWidth && y in 0 until stepHeight

    // ---- Blocks ---------------------------------------------------------------------------

    fun block(x: Int, y: Int): Int = rom.u8(blocksOffset + y * width + x)

    fun setBlock(x: Int, y: Int, block: Int) {
        if (x in 0 until width && y in 0 until height) rom.write8(blocksOffset + y * width + x, block, "Paint ${info.name}")
    }

    fun fill(x: Int, y: Int, block: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val target = block(x, y)
        if (target == block) return 0
        var n = 0
        val seen = BooleanArray(width * height)
        val stack = ArrayDeque<Int>().apply { add(y * width + x) }
        rom.batch {
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (seen[i]) continue
                seen[i] = true
                if (rom.u8(blocksOffset + i) != target) continue
                rom.write8(blocksOffset + i, block, "Fill ${info.name}"); n++
                val cx = i % width; val cy = i / width
                if (cx > 0) stack.add(i - 1)
                if (cx < width - 1) stack.add(i + 1)
                if (cy > 0) stack.add(i - width)
                if (cy < height - 1) stack.add(i + width)
            }
        }
        return n
    }

    fun setBorderBlock(block: Int) {
        val e = requireEvents(); e.border = block; writeEvents(e, "Border of ${info.name}", true)
    }

    fun isWalkable(sx: Int, sy: Int): Boolean = tileset.isWalkable(block(sx / 2, sy / 2), sx % 2, sy % 2)

    // ---- Moving & editing events --------------------------------------------------------------

    fun moveNpc(n: Npc, x: Int, y: Int) {
        if (!inMap(x, y)) return
        val e = requireEvents()
        e.objects[n.index][1] = (y + 4).toByte(); e.objects[n.index][2] = (x + 4).toByte()
        writeEvents(e, "Move NPC in ${info.name}", true)
    }

    fun setSprite(n: Npc, sprite: Int) {
        val e = requireEvents(); e.objects[n.index][0] = sprite.toByte(); writeEvents(e, "NPC sprite", true)
    }

    fun setMovement(n: Npc, m: Movement) {
        val e = requireEvents()
        e.objects[n.index][3] = m.movement.toByte(); e.objects[n.index][4] = m.range.toByte()
        writeEvents(e, "NPC movement", true)
    }

    fun setItemBall(n: Npc, item: Int) {
        require(n.kind == NpcKind.ITEM)
        val e = requireEvents(); e.objects[n.index][6] = item.toByte()
        writeEvents(e, "Item ball → ${g.itemName(item)}", true)
    }

    fun setTrainer(n: Npc, trainerClass: Int, team: Int) {
        require(n.kind == NpcKind.TRAINER)
        val e = requireEvents()
        e.objects[n.index][6] = (trainerClass + PokeRed.OPP_ID_OFFSET).toByte()
        e.objects[n.index][7] = team.toByte()
        writeEvents(e, "Trainer team", true)
    }

    fun moveSign(s: Sign, x: Int, y: Int) {
        if (!inMap(x, y)) return
        val e = requireEvents()
        e.signs[s.index][0] = y.toByte(); e.signs[s.index][1] = x.toByte()
        writeEvents(e, "Move sign in ${info.name}", true)
    }

    /** The 8-byte special warp spec (as in NewGameWarp) that puts the player at (x, y) here. */
    fun startSpec(x: Int, y: Int): ByteArray =
        byteArrayOf(id.toByte()) + warpTo(x, y) + byteArrayOf((y and 1).toByte(), (x and 1).toByte(), tilesetId.toByte())

    private fun warpTo(x: Int, y: Int): ByteArray {
        val disp = g.sym("wOverworldMap") + 7 + width + (width + 6) * (y shr 1) + (x shr 1)
        return byteArrayOf(disp.toByte(), (disp shr 8).toByte(), y.toByte(), x.toByte())
    }

    fun moveWarp(w: Warp, x: Int, y: Int) {
        if (!inMap(x, y)) return
        val e = requireEvents()
        e.warps[w.index][0] = y.toByte(); e.warps[w.index][1] = x.toByte()
        e.warpTos[w.index] = warpTo(x, y)
        writeEvents(e, "Move door in ${info.name}", true)
    }

    /** [destMap] 0xFF means "back to the map the player came from" (used by building exits). */
    fun setWarpDestination(w: Warp, destMap: Int, destWarp: Int) {
        val e = requireEvents()
        e.warps[w.index][2] = (destWarp - 1).coerceAtLeast(0).toByte(); e.warps[w.index][3] = destMap.toByte()
        writeEvents(e, "Door destination in ${info.name}", true)
    }

    // ---- Adding & removing events ------------------------------------------------------------

    fun addWarp(x: Int, y: Int, destMap: Int, destWarp: Int): Int {
        if (!inMap(x, y)) throw EditException("Outside the map")
        val e = requireEvents()
        if (e.warps.size >= 32) throw EditException("A map can have at most 32 doors")
        e.warps += byteArrayOf(y.toByte(), x.toByte(), (destWarp - 1).coerceAtLeast(0).toByte(), destMap.toByte())
        e.warpTos += warpTo(x, y)
        writeEvents(e, "Add door to ${info.name}", false)
        return e.warps.size - 1
    }

    fun removeWarp(w: Warp) {
        if (w.index < firstAddedWarp) throw EditException("Original doors can't be deleted (other maps lead to them by number); move them instead")
        val e = requireEvents()
        e.warps.removeAt(w.index); e.warpTos.removeAt(w.index)
        writeEvents(e, "Remove door from ${info.name}", true)
    }

    /** How many NPCs this map can hold without colliding with text the map's scripts show by number. */
    val maxNpcs: Int get() {
        if (id in world.createdMaps()) return 16 // no game script shows this map's texts by number
        val orig = originalEvents
        val scriptOnly = if (orig != null && g.exact) {
            // Texts of the original table that no original NPC or sign uses are shown by the map's
            // scripts. (Slots the editor left unused when moving signs don't count.)
            val origUsed = (orig.objects.map { it[5].toInt() and 0x3F } + orig.signs.map { it[2].toInt() and 0xFF }).toSet()
            (1..g.version.textTableSizes.getOrElse(id) { 0 }).filter { it !in origUsed }
        } else {
            val used = (npcs.map { it.textId } + signs.map { it.textId } + triggers.map { it.textId }).toSet()
            (1..textTableSize).filter { it !in used }
        }
        return minOf(16, (scriptOnly.minOrNull() ?: Int.MAX_VALUE) - 1).coerceAtLeast(npcs.size)
    }

    val canAddNpc: Boolean get() = eventsValid && npcs.size < maxNpcs

    /** Adds an NPC with its own text entry. Returns its index. */
    fun addNpc(sprite: Int, x: Int, y: Int, movement: Movement, behavior: Behavior): Int {
        if (!inMap(x, y)) throw EditException("Outside the map")
        if (!canAddNpc) throw EditException(
            if (npcs.size >= 16) "A map can have at most 16 NPCs"
            else "This map's scripts use text #${npcs.size + 1}, so it can't take more NPCs",
        )
        val e = requireEvents()
        val newCount = e.objects.size + 1
        rom.batch {
            val entries = textEntries().toMutableList()
            entries += behaviorPointer(behavior)
            val t = entries.size
            // Signs and triggers must have text ids above the NPC count; renumber any that would collide.
            for (s in e.signs) {
                val sid = s[2].toInt() and 0xFF
                if (sid <= newCount) { entries += entries[sid - 1]; s[2] = entries.size.toByte() }
            }
            for (tr in triggers) {
                if (tr.textId <= newCount) {
                    entries += entries[tr.textId - 1]
                    ScriptEngine.renumber(g, id, tr.textId, entries.size)
                }
            }
            writeTextTable(entries)
            e.objects += byteArrayOf(sprite.toByte(), (y + 4).toByte(), (x + 4).toByte(), movement.movement.toByte(), movement.range.toByte(), t.toByte())
            writeEvents(e, "Add NPC to ${info.name}", false)
        }
        return e.objects.size - 1
    }

    fun removeNpc(n: Npc) {
        if (n.index < firstAddedNpc) throw EditException("NPCs from the original game can't be deleted (the game's scripts refer to them by number)")
        val e = requireEvents()
        e.objects.removeAt(n.index)
        writeEvents(e, "Remove NPC from ${info.name}", true)
    }

    fun addSign(x: Int, y: Int, text: String): Int {
        if (!inMap(x, y)) throw EditException("Outside the map")
        val e = requireEvents()
        if (e.signs.size >= 16) throw EditException("A map can have at most 16 signs")
        rom.batch {
            val entries = textEntries().toMutableList()
            while (entries.size < e.objects.size) entries += entries.lastOrNull() ?: behaviorPointer(Behavior.Talk("…"))
            entries += behaviorPointer(Behavior.Talk(text))
            writeTextTable(entries)
            e.signs += byteArrayOf(y.toByte(), x.toByte(), entries.size.toByte())
            writeEvents(e, "Add sign to ${info.name}", false)
        }
        return e.signs.size - 1
    }

    fun removeSign(s: Sign) {
        if (s.index < firstAddedSign) throw EditException("Original signs can't be deleted; edit their text instead")
        val e = requireEvents()
        e.signs.removeAt(s.index)
        writeEvents(e, "Remove sign from ${info.name}", true)
    }

    // ---- Triggers -----------------------------------------------------------------------------

    /** Tiles (or the whole map) that start an event when the player steps there. */
    val triggers: List<ScriptEngine.Trigger> = ScriptEngine.triggers(g, id)

    fun triggerAt(x: Int, y: Int) = triggers.lastOrNull { t ->
        val tx = t.x ?: return@lastOrNull false; val ty = t.y ?: return@lastOrNull false
        x in tx until tx + t.width && y in ty until ty + t.height
    }

    private val scriptPtrField = h + 7

    /** Puts the NeedleScript hook in front of this map's own per-frame script. */
    private fun installMapHook() {
        val cur = Gb.offset(bank, rom.u16(scriptPtrField))
        if (ScriptEngine.isMapHook(g, cur)) return
        val stub = ScriptEngine.mapHookBytes(g, rom.u16(scriptPtrField))
        val at = world.allocIn(bank, stub, "Event hook for ${info.name}")
        rom.write16(scriptPtrField, Gb.ptr(at), "Event hook for ${info.name}")
    }

    /**
     * Adds a trigger at (x, y), or anywhere on the map when x/y are null. It fires once,
     * remembered with a flag, and runs [script].
     */
    fun addTrigger(x: Int?, y: Int?, script: EventScript): ScriptEngine.Trigger {
        if (x != null && y != null && !inMap(x, y)) throw EditException("Outside the map")
        val e = requireEvents()
        val used = ScriptEngine.usedTriggerFlags(g)
        val flag = (Flags.COUNT - 1 downTo 64).firstOrNull { it !in used }
            ?: throw EditException("Out of trigger flags")
        var t: ScriptEngine.Trigger? = null
        rom.batch {
            ScriptEngine.install(g)
            installMapHook()
            val entries = textEntries().toMutableList()
            // Like signs, trigger text ids must be above the NPC count.
            while (entries.size < e.objects.size) entries += entries.lastOrNull() ?: behaviorPointer(Behavior.Talk("…"))
            entries += behaviorPointer(Behavior.Script)
            writeTextTable(entries)
            val trigger = ScriptEngine.Trigger(id, x, y, entries.size, flag)
            ScriptEngine.putTrigger(g, trigger)
            ScriptEngine.put(g, id, trigger.textId, script)
            t = trigger
        }
        return t!!
    }

    fun moveTrigger(t: ScriptEngine.Trigger, x: Int?, y: Int?) {
        if (x != null && y != null && !inMap(x, y)) return
        ScriptEngine.putTrigger(g, t.copy(x = x, y = y))
    }

    /** Resizes a trigger to [w]×[h] tiles (1–16 each), keeping it inside the map. */
    fun resizeTrigger(t: ScriptEngine.Trigger, w: Int, h: Int) {
        val x = t.x ?: return; val y = t.y ?: return
        val cw = w.coerceIn(1, minOf(16, width * 2 - x)); val ch = h.coerceIn(1, minOf(16, height * 2 - y))
        ScriptEngine.putTrigger(g, t.copy(width = cw, height = ch))
    }

    fun removeTrigger(t: ScriptEngine.Trigger) = ScriptEngine.removeTrigger(g, id, t.textId)

    // ---- Text table & behaviours --------------------------------------------------------------

    private val textTableOffset get() = Gb.offset(bank, rom.u16(textPtrField))

    /** Number of entries in this map's text pointer table. */
    val textTableSize: Int get() {
        val ptr = rom.u16(textPtrField)
        val origPtr = (rom.original[textPtrField].toInt() and 0xFF) or ((rom.original[textPtrField + 1].toInt() and 0xFF) shl 8)
        val known = g.version.textTableSizes.getOrElse(id) { 0 }
        val referenced = (npcs.map { it.textId } + signs.map { it.textId }).maxOrNull() ?: 0
        // Exact sizes from the disassembly (0 is valid: some maps have no texts at all).
        if (ptr == origPtr && g.exact && id !in world.createdMaps()) return maxOf(known, referenced)
        // Relocated by the editor (terminated by 0x0000) or an unknown hack: scan.
        var n = 0
        while (n < 64) {
            val v = rom.u16(textTableOffset + n * 2)
            if (v == 0 || v > 0x7FFF) break
            n++
        }
        return maxOf(n, referenced)
    }

    private fun textEntries(): List<Int> = (0 until textTableSize).map { rom.u16(textTableOffset + it * 2) }

    private fun writeTextTable(entries: List<Int>) {
        if (entries.size > 63) throw EditException("Too many texts on this map")
        val bytes = ByteArray(entries.size * 2 + 2)
        entries.forEachIndexed { i, v -> bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
        val at = world.allocIn(bank, bytes, "Text table of ${info.name}")
        rom.write16(textPtrField, Gb.ptr(at), "Text table of ${info.name}")
    }

    private fun textAddress(textId: Int): Int? {
        if (textId !in 1..textTableSize) return null
        return Gb.offset(bank, rom.u16(textTableOffset + (textId - 1) * 2))
    }

    /** True if more than one NPC or sign uses this text. */
    fun isTextShared(textId: Int) = (npcs.count { it.textId == textId && it.kind == NpcKind.PERSON } + signs.count { it.textId == textId }) > 1

    fun behavior(textId: Int): Behavior {
        val a = textAddress(textId) ?: return Behavior.GameCode
        return when (rom.u8(a)) {
            TX_MART -> Behavior.Mart((0 until rom.u8(a + 1)).map { rom.u8(a + 2 + it) })
            TX_NURSE -> Behavior.Nurse
            TX_POKECENTER_PC -> Behavior.PokecenterPC
            TX_PLAYERS_PC -> Behavior.PlayersPC
            TX_BILLS_PC -> Behavior.BillsPC
            TX_VENDING -> Behavior.VendingMachine
            TX_PRIZE -> Behavior.PrizeVendor
            TX_CABLE_CLUB -> Behavior.CableClub
            TX_ASM -> when (a) {
                g.sym("PickUpItemText") -> Behavior.ItemPickup
                world.scriptHookAddress -> Behavior.Script
                else -> Behavior.GameCode
            }
            TX_FAR -> readText(Gb.offset(rom.u8(a + 3), rom.u16(a + 1)))?.let { Behavior.Talk(it.first) } ?: Behavior.GameCode
            TX_START -> readText(a)?.let { Behavior.Talk(it.first) } ?: Behavior.GameCode
            else -> Behavior.GameCode
        }
    }

    /** Decodes a `text …` command at [at]; returns the text and its byte length (incl. terminator). */
    private fun readText(at: Int): Pair<String, Int>? {
        if (!rom.inBounds(at) || rom.u8(at) != TX_START) return null
        var p = at + 1
        while (rom.inBounds(p) && p - at < 1200) {
            val b = rom.u8(p)
            if (b == Text.END || b == Text.DONE || b == Text.PROMPT) return Text.decode(rom.data, at + 1, p - at - 1) to (p - at + 1)
            p++
        }
        return null
    }

    /** Builds the data for [b] and returns the 16-bit pointer the text table should hold. */
    private fun behaviorPointer(b: Behavior): Int = when (b) {
        is Behavior.Talk -> {
            val text = byteArrayOf(TX_START.toByte()) + Text.encodeDialogue(b.text) + Text.DONE.toByte()
            val target = world.allocEngine(text, "Dialogue")
            val stub = byteArrayOf(TX_FAR.toByte(), Gb.ptr(target).toByte(), (Gb.ptr(target) shr 8).toByte(), world.engineBank.toByte(), Text.END.toByte())
            Gb.ptr(world.allocIn(bank, stub, "Dialogue"))
        }
        is Behavior.Mart -> {
            if (b.items.isEmpty() || b.items.size > 10) throw EditException("A shop sells 1 to 10 items")
            val data = byteArrayOf(TX_MART.toByte(), b.items.size.toByte()) + b.items.map { it.toByte() }.toByteArray() + 0xFF.toByte()
            Gb.ptr(world.allocIn(bank, data, "Shop"))
        }
        Behavior.Nurse -> world.homeByte(TX_NURSE)
        Behavior.PokecenterPC -> world.homeByte(TX_POKECENTER_PC)
        Behavior.PlayersPC -> world.homeByte(TX_PLAYERS_PC)
        Behavior.BillsPC -> world.homeByte(TX_BILLS_PC)
        Behavior.Script -> ScriptEngine.install(g)
        else -> throw EditException("That behaviour can't be assigned")
    }

    /** Changes what a text id does. Every NPC/sign sharing that text id changes too. */
    fun setBehavior(textId: Int, b: Behavior) {
        if (textId !in 1..textTableSize) throw EditException("Unknown text #$textId")
        rom.batch {
            val ptr = behaviorPointer(b)
            rom.write16(textTableOffset + (textId - 1) * 2, ptr, "Behaviour of text #$textId in ${info.name}")
        }
    }

    /** The event script run by [textId], if it has one. */
    fun script(textId: Int): EventScript? =
        if (behavior(textId) == Behavior.Script) ScriptEngine.get(g, id, textId) else null

    /** Makes [textId] run [script] (installing the script engine if needed). */
    fun setScript(textId: Int, script: EventScript) = rom.batch {
        ScriptEngine.put(g, id, textId, script)
        if (behavior(textId) != Behavior.Script) setBehavior(textId, Behavior.Script)
    }

    /** Gives an NPC its own text entry (so editing it won't change others sharing the text). */
    fun giveOwnText(n: Npc): Int {
        val e = requireEvents()
        var newId = 0
        rom.batch {
            val entries = textEntries().toMutableList()
            entries += entries[n.textId - 1]
            newId = entries.size
            writeTextTable(entries)
            val flags = e.objects[n.index][5].toInt() and 0xC0
            e.objects[n.index][5] = (flags or newId).toByte()
            writeEvents(e, "Separate NPC text", true)
        }
        return newId
    }

    companion object {
        const val TX_START = 0x00
        const val TX_ASM = 0x08
        const val TX_FAR = 0x17
        const val TX_NURSE = 0xFF
        const val TX_MART = 0xFE
        const val TX_BILLS_PC = 0xFD
        const val TX_PLAYERS_PC = 0xFC
        const val TX_POKECENTER_PC = 0xF9
        const val TX_PRIZE = 0xF7
        const val TX_CABLE_CLUB = 0xF6
        const val TX_VENDING = 0xF5
    }
}
