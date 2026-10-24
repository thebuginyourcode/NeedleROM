package com.needlerom.core

import com.needlerom.core.world.Gb
import com.needlerom.core.world.Gen1Text
import com.needlerom.core.world.Gen1World
import com.needlerom.core.world.Movement
import com.needlerom.core.world.NpcKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds a tiny ROM laid out like Pokémon Red, with the structures the world builder looks for. */
class WorldTest {
    private fun put(d: ByteArray, at: Int, vararg v: Int) = v.forEachIndexed { i, b -> d[at + i] = b.toByte() }
    private val t = TextTable.POKEMON_GB
    private fun text(s: String) = t.encode(s)!!.map { it.toInt() and 0xFF }.toIntArray()

    private fun fakeRed(): ByteArray {
        val d = ByteArray(0x40000)
        put(d, 0x104, 0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B)
        "POKEMON RED".toByteArray().copyInto(d, 0x134)
        // Collision list in bank 0: tiles 0 is walkable.
        put(d, 0x3000, 0x00, 0xFF)
        // Tileset table (bank 3): 8 copies of the overworld entry -> bank 4.
        for (i in 0 until 8) put(d, 0xD000 + i * 12, 4, 0x00, 0x40, 0x00, 0x48, 0x00, 0x30, 0xFF, 0xFF, 0xFF, 0x52, 0x02)
        // Blocks: block 0 = tile 0 (white), block 1 = tile 1 (black).
        for (i in 0 until 16) d[0x10000 + 16 + i] = 1
        for (i in 0 until 16) d[0x10800 + 16 + i] = 0xFF.toByte()
        // 9 town headers in bank 6, all 10x9 on the overworld tileset.
        for (m in 0 until 9) {
            val h = 0x18000 + m * 0x40
            val blocksPtr = 0x5000 + m * 0x100
            put(d, h, 0x00, 9, 10, blocksPtr and 0xFF, blocksPtr shr 8, 0x00, 0x60, 0x00, 0x61, 0x00, 0x00, 0x68)
            put(d, 0x01AE + m * 2, (0x4000 + m * 0x40) and 0xFF, (0x4000 + m * 0x40) shr 8)
            d[0xC23D + m] = 6
        }
        // Pallet Town events at 6:6800.
        put(
            d, 0x1A800,
            0x0B, // border
            1, 5, 5, 0, 0x25, // warp
            1, 9, 11, 2, // sign
            2,
            3, 5 + 4, 8 + 4, 0xFF, 0xD0, 1, // Oak
            0x3D, 3 + 4, 2 + 4, 0xFF, 0xFF, 0x80 or 3, 0x14, // item ball: Potion
            0xF5, 0xC6, 5, 5, // warp-to
        )
        // Text pointers at 6:6000 -> 6:6100 (far), 6:6110 (inline)
        put(d, 0x1A000, 0x00, 0x61, 0x10, 0x61, 0x20, 0x61)
        put(d, 0x1A100, 0x17, 0x00, 0x40, 7, 0x50)
        put(d, 0x1C000, 0x00, *text("Hello"), 0x7F, 0x52, 0xE7, 0x4F, *text("How are you"), 0xE6, 0x58)
        put(d, 0x1A110, 0x00, *text("PALLET TOWN"), 0x50, 0x50)
        // Sprite table: 60 entries pointing at 8:4400.
        for (i in 0 until 60) put(d, 0x20000 + i * 4, 0x00, 0x44, 0xC0, 8)
        for (i in 0 until 16) d[0x20400 + i] = 0xFF.toByte()
        // Item names.
        put(d, 0x24000, *text("MASTER BALL"), 0x50, *text("ULTRA BALL"), 0x50, *text("GREAT BALL"), 0x50)
        // Something non-empty near the end of bank 6 code so free space is the tail only.
        put(d, 0x1B000, 0xC9)
        return d
    }

    private fun world(d: ByteArray = fakeRed()): Pair<Rom, Gen1World> {
        val rom = Rom("red.gb", d)
        return rom to assertNotNull(Gen1World.locate(rom))
    }

    @Test
    fun findsMapsTilesetsSpritesAndItems() {
        val (_, w) = world()
        assertEquals(9, w.maps.size)
        assertEquals("Pallet Town", w.maps[0].name)
        assertEquals(10, w.maps[0].width)
        assertEquals(9, w.maps[0].height)
        assertEquals(8, w.tilesetCount)
        assertEquals("ULTRA BALL", w.itemName(2))
        assertEquals("TM01", w.itemName(0xC9))
        assertTrue(w.hasSprites)
        assertEquals(Gb.SHADES[3], w.spriteImage(1)!![0])
    }

    @Test
    fun sessionOffersWorldBuilder() {
        val rom = Rom("red.gb", fakeRed())
        val s = HackSession(rom, Mode.OPTIMIZED, GameProfiles.byId("pkmn_rbg"))
        assertTrue(s.toolStates().first { it.tool == Tool.WORLD_BUILDER }.available)
        val auto = HackSession(rom, Mode.AUTO, null)
        assertFalse(Tool.WORLD_BUILDER in auto.tools)
    }

    @Test
    fun parsesEvents() {
        val (_, w) = world()
        val m = w.map(w.maps[0])
        assertTrue(m.eventsValid)
        assertEquals(0x0B, m.borderBlock)
        assertEquals(1, m.warps.size)
        assertEquals(0x25, m.warps[0].destMap)
        assertEquals(1, m.signs.size)
        assertEquals(2, m.objects.size)
        val oak = m.objects[0]
        assertEquals(8 to 5, oak.x to oak.y)
        assertEquals(Movement.FACE_DOWN, Movement.of(oak.movement, oak.range))
        val ball = m.objects[1]
        assertEquals(NpcKind.ITEM, ball.kind)
        assertEquals(0x14, ball.extra1)
    }

    @Test
    fun paintFillAndUndo() {
        val (rom, w) = world()
        val m = w.map(w.maps[0])
        m.setBlock(0, 0, 1)
        assertEquals(1, m.block(0, 0))
        assertEquals(89, m.fill(5, 5, 1))
        assertTrue((0 until 9).all { y -> (0 until 10).all { x -> m.block(x, y) == 1 } })
        rom.undo() // the whole fill is one step
        assertEquals(1, m.block(0, 0))
        assertEquals(0, m.block(5, 5))
        rom.undo()
        assertEquals(0, m.block(0, 0))
        assertFalse(rom.canUndo)
    }

    @Test
    fun rendersBlocksAndCollision() {
        val (_, w) = world()
        val ts = w.tileset(0)
        assertEquals(Gb.SHADES[0], ts.renderBlock(0)[0])
        assertEquals(Gb.SHADES[3], ts.renderBlock(1)[0])
        assertTrue(ts.isWalkable(0, 0, 0))
        assertFalse(ts.isWalkable(1, 1, 1))
    }

    @Test
    fun addAndRemoveNpcRelocatesEventData() {
        val (rom, w) = world()
        val ref = w.maps[0]
        val before = w.map(ref)
        assertNull(before.addObject(sprite = 5, x = 3, y = 4, textId = 1, m = Movement.WANDER))
        val after = w.map(ref)
        assertNotEquals(before.eventsOffset, after.eventsOffset)
        assertEquals(6, Gb.bankOf(after.eventsOffset))
        assertEquals(3, after.objects.size)
        assertEquals(3 to 4, after.objects[2].x to after.objects[2].y)
        assertEquals(0x14, after.objects[1].extra1) // item ball survived the move
        assertEquals(1, after.warps.size)
        assertEquals(0xC6F5, rom.u16le(after.eventsOffset + 1 + 1 + 4 + 1 + 3 + 1 + 6 + 7 + 6)) // warp-to kept
        assertEquals(2, after.firstAddedIndex)

        assertTrue(after.removeObject(after.objects[0]) != null) // originals are protected
        assertNull(after.removeObject(after.objects[2]))
        assertEquals(2, w.map(ref).objects.size)

        rom.undo(); rom.undo()
        assertEquals(before.eventsOffset, w.map(ref).eventsOffset)
    }

    @Test
    fun editNpcProperties() {
        val (_, w) = world()
        val ref = w.maps[0]
        var m = w.map(ref)
        m.moveObject(m.objects[0], 10, 7)
        m.setMovement(m.objects[0], Movement.LEFT_RIGHT)
        m.setItem(m.objects[1], 0x01)
        m = w.map(ref)
        assertEquals(10 to 7, m.objects[0].x to m.objects[0].y)
        assertEquals(Movement.LEFT_RIGHT, Movement.of(m.objects[0].movement, m.objects[0].range))
        assertEquals(0x01, m.objects[1].extra1)
    }

    @Test
    fun moveWarpRecalculatesWarpTo() {
        val (rom, w) = world()
        val ref = w.maps[0]
        val m = w.map(ref)
        val wt = m.eventsOffset + 1 + 1 + 4 + 1 + 3 + 1 + 6 + 7
        m.moveWarp(m.warps[0], 9, 7)
        // y 5->7 moves one block row (+16 = width 10 + 6), x 5->9 moves two blocks (+2).
        assertEquals(0xC6F5 + 16 + 2, rom.u16le(wt))
        assertEquals(7, rom.u8(wt + 2))
        assertEquals(9, rom.u8(wt + 3))
        val moved = w.map(ref).warps[0]
        assertEquals(9 to 7, moved.x to moved.y)
    }

    @Test
    fun dialogueRoundTrip() {
        val (rom, w) = world()
        val m = w.map(w.maps[0])
        val d = m.dialogue(1)
        assertTrue(d.editable)
        assertEquals("Hello <PLAYER>!\nHow are you?", d.text)
        assertNull(m.setDialogue(d, "Yo <PLAYER>!\nNice hat."))
        assertEquals("Yo <PLAYER>!\nNice hat.", m.dialogue(1).text)
        assertTrue(m.setDialogue(m.dialogue(1), "This is far far far too long for the box.") != null)

        // Inline "@"-terminated sign text keeps its length.
        val sign = m.dialogue(2)
        assertEquals("PALLET TOWN", sign.text)
        assertNull(m.setDialogue(sign, "NEEDLE TOWN"))
        assertEquals("NEEDLE TOWN", m.dialogue(2).text)
        assertNull(m.setDialogue(m.dialogue(2), "HOME"))
        assertEquals("HOME       ", m.dialogue(2).text)
        assertEquals(0x50, rom.u8(m.dialogue(2).offset + 11))

        assertFalse(m.dialogue(3).editable)
        assertTrue(Gen1Text.longLines("ok\nthis line is way too long for gen one").isNotEmpty())
    }

    @Test
    fun paragraphsEncodeAsNewTextBoxes() {
        val bytes = Gen1Text.encode("A\nB\nC\n\nD").getOrThrow().map { it.toInt() and 0xFF }
        assertEquals(listOf(0x80, 0x4F, 0x81, 0x55, 0x82, 0x51, 0x83), bytes)
    }
}
