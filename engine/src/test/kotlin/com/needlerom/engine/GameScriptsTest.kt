package com.needlerom.engine

import com.needlerom.engine.pokered.GameScripts
import com.needlerom.engine.pokered.GameScripts.Block
import com.needlerom.engine.pokered.GameText
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameScriptsTest {
    private fun dump(list: List<Block>, ind: String = ""): String = buildString {
        for (b in list) when (b) {
            is Block.Say -> appendLine("$ind say \"${b.line.text.replace("\n", "/").take(40)}\"")
            is Block.If -> { appendLine("$ind if ${b.cond}"); append(dump(b.then, "$ind  ")); appendLine("$ind else"); append(dump(b.otherwise, "$ind  ")) }
            else -> appendLine("$ind $b")
        }
    }

    @Test
    fun readsOaksScriptAndEditsHisLines() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val lab = g.world.map(0x28) // Oak's Lab
        val oak = lab.npcs.withIndex().filter { lab.behavior(it.value.textId) is com.needlerom.engine.pokered.Behavior.GameCode }
        val outlines = oak.mapNotNull { (i, n) -> GameScripts.outline(g, lab, n.textId)?.let { i to it } }
        outlines.forEach { (i, o) -> println("NPC $i:\n" + dump(o.blocks)) }
        val o = outlines.map { it.second }.first { o -> o.lines.any { "which" in it.text.lowercase() } }
        assertNotNull(GameScripts.whyNotConvertible(g, o), "Oak's script uses game code")
        // Edit a line, longer than the original.
        val line = o.lines.first { "which" in it.text.lowercase() }
        GameText.write(g, line, "Pick any one you\\nlike, I made\\nthem all myself!".replace("\\n", "\n"))
        val again = GameScripts.outline(g, g.world.map(0x28), oak.first { (_, n) -> GameScripts.outline(g, lab, n.textId)?.lines?.isNotEmpty() == true }.value.textId)
        val all = outlines.flatMap { (i, _) -> GameScripts.outline(g, g.world.map(0x28), lab.npcs[i].textId)!!.lines.map { it.text } }
        assertTrue(all.any { it.startsWith("Pick any one you") }, "the edit reads back: $all")
    }

    @Test
    fun lengthenedLineShowsInGame() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val route1 = g.world.map(0x0C)
        val npc = route1.npcs.first { GameScripts.outline(g, route1, it.textId) != null }
        val line = GameScripts.outline(g, route1, npc.textId)!!.lines.first { it.text.startsWith("Hi! I work") }
        GameText.write(g, line, "Welcome! This line\nis much longer than\nthe original one.".replace("\\n", "\n"))
        val rom = com.needlerom.engine.pokered.QuickStart.rom(g, 0x0C, npc.x, npc.y + 1)
        val gb = com.needlerom.engine.gb.GameBoy(rom)
        assertTrue(com.needlerom.engine.pokered.QuickStart.skipIntro(gb, g, 0x0C, npc.x, npc.y + 1))
        val d = Driver(gb)
        d.press(com.needlerom.engine.gb.Button.UP, hold = 2, after = 10)
        d.press(com.needlerom.engine.gb.Button.A, after = 120)
        TestRoms.screenshot(gb, "edited_line")
        // The text box rows of the screen's tile map hold the character codes.
        val tileMap = TestRoms.sym("pokered.sym", "wTileMap")
        val screen = (14..16).joinToString("|") { row -> com.needlerom.engine.pokered.Text.decode(ByteArray(18) { gb.read(tileMap + row * 20 + 1 + it).toByte() }, 0, 18) }
        assertTrue("Welcome!" in screen, "shown: $screen")
    }

    @Test
    fun convertsASimpleGiftScript() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val route1 = g.world.map(0x0C)
        val (o, textId) = route1.npcs.mapNotNull { n -> GameScripts.outline(g, route1, n.textId)?.let { it to n.textId } }.first()
        println(dump(o.blocks))
        assertNull(GameScripts.whyNotConvertible(g, o), "the Potion gift is recognizable")
        val event = GameScripts.toEvent(g, o)
        println(event)
        route1.setScript(textId, event)
        assertEquals(event, g.world.map(0x0C).script(textId))

        // Play it: stand below the Mart employee, talk, and get the Potion.
        val npc = g.world.map(0x0C).npcs.first { it.textId == textId }
        val rom = com.needlerom.engine.pokered.QuickStart.rom(g, 0x0C, npc.x, npc.y + 1)
        val gb = com.needlerom.engine.gb.GameBoy(rom)
        assertTrue(com.needlerom.engine.pokered.QuickStart.skipIntro(gb, g, 0x0C, npc.x, npc.y + 1))
        val d = Driver(gb)
        d.press(com.needlerom.engine.gb.Button.UP, hold = 2, after = 10)
        d.press(com.needlerom.engine.gb.Button.A, after = 40)
        TestRoms.screenshot(gb, "converted_route1")
        repeat(30) { d.press(com.needlerom.engine.gb.Button.B, after = 15) }
        val bag = TestRoms.sym("pokered.sym", "wNumBagItems")
        val items = (0 until gb.read(bag)).map { gb.read(bag + 1 + it * 2) }
        assertTrue(0x14 in items, "got the Potion: $items")
        val e = 960 // EVENT_GOT_POTION_SAMPLE
        assertTrue(gb.read(TestRoms.sym("pokered.sym", "wEventFlags") + e / 8) and (1 shl (e % 8)) != 0, "story flag set")
    }
}
