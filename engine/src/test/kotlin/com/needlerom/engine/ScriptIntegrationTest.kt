package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Flags
import com.needlerom.engine.script.ScriptEngine
import com.needlerom.engine.script.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Installs NeedleScript into the real game, then plays it in the emulator: a new sign in
 * Red's bedroom runs a script that gives items and a Pokémon and sets a flag.
 */
class ScriptIntegrationTest {
    private val masterBall = 0x01
    private val potion = 0x14

    private fun bag(gb: GameBoy, g: PokeRed): List<Pair<Int, Int>> {
        val n = gb.read(g.sym("wNumBagItems"))
        return (0 until n).map { gb.read(g.sym("wNumBagItems") + 1 + it * 2) to gb.read(g.sym("wNumBagItems") + 2 + it * 2) }
    }

    private fun flagSet(gb: GameBoy, g: PokeRed, n: Int): Boolean {
        val e = Flags.FIRST_EVENT + n
        return gb.read(g.sym("wEventFlags") + e / 8) and (1 shl (e % 8)) != 0
    }

    @Test
    fun scriptedSignGivesItemsAndPokemonOnce() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        // 1. Find where the player starts.
        val probe = Driver(GameBoy(bytes)).also { it.newGameToBedroom() }
        val g0 = PokeRed(Rom(bytes))
        val px = probe.gb.read(g0.sym("wXCoord"))
        val py = probe.gb.read(g0.sym("wYCoord"))

        // 2. Hack the ROM: a sign beside the start position runs a script. (Not on the SNES:
        //    hidden events like it are checked before signs.)
        val g = PokeRed(Rom(bytes))
        val room = g.world.map(0x26)
        room.addSign(px - 1, py, "placeholder")
        val sign = g.world.map(0x26).signs.last()
        val script = EventScript(listOf(
            Step.IfFlag(0,
                then = listOf(Step.Say("You already got\nyour gifts!")),
                otherwise = listOf(
                    Step.Say("NeedleScript says\nhello!"),
                    Step.GiveItem(masterBall, 1),
                    Step.GiveItem(potion, 5),
                    Step.GivePokemon(0x15, 7), // Mew
                    Step.SetFlag(0),
                    Step.Say("Enjoy!"),
                ),
            ),
        ))
        g.world.map(0x26).setScript(sign.textId, script)
        assertEquals(script, ScriptEngine.get(g, 0x26, sign.textId), "script source round-trips through the ROM")

        // 3. Play the hacked game.
        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom()
        assertEquals(px, gb.read(g.sym("wXCoord")))
        d.press(Button.LEFT, hold = 2, after = 20) // face the sign (it blocks the way)
        d.press(Button.A, after = 60)           // read it
        TestRoms.screenshot(gb, "script_01_hello")
        // B advances text like A, and answers "no" to the nickname prompt.
        repeat(30) { d.press(Button.B, after = 15) }
        TestRoms.screenshot(gb, "script_02_after")

        val items = bag(gb, g)
        assertTrue(masterBall to 1 in items, "Master Ball in bag: $items")
        assertTrue(potion to 5 in items, "5 Potions in bag: $items")
        assertEquals(1, gb.read(g.sym("wPartyCount")), "Mew joined the party")
        assertEquals(0x15, gb.read(g.sym("wPartyCount") + 1))
        assertTrue(flagSet(gb, g, 0))

        // Reading it again takes the "already" branch and gives nothing more.
        d.press(Button.A, after = 90)
        TestRoms.screenshot(gb, "script_03_again")
        repeat(10) { d.press(Button.B, after = 15) }
        assertEquals(items, bag(gb, g))
        assertEquals(1, gb.read(g.sym("wPartyCount")))
    }

    @Test
    fun scriptedWildBattleStarts() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val probe = Driver(GameBoy(bytes)).also { it.newGameToBedroom() }
        val g = PokeRed(Rom(bytes))
        val px = probe.gb.read(g.sym("wXCoord")); val py = probe.gb.read(g.sym("wYCoord"))
        g.world.map(0x26).addSign(px - 1, py, "x")
        val sign = g.world.map(0x26).signs.last()
        g.world.map(0x26).setScript(sign.textId, EventScript(listOf(
            Step.GivePokemon(0xB0, 10),
            Step.Say("A wild MEW\nappears!"),
            Step.WildBattle(0x15, 5),
        )))
        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom()
        d.press(Button.LEFT, hold = 2, after = 20)
        d.press(Button.A, after = 60)
        var inBattle = false
        val wIsInBattle = TestRoms.sym("pokered.sym", "wIsInBattle")
        repeat(40) {
            if (!inBattle) d.press(Button.B, after = 20)
            if (gb.read(wIsInBattle) != 0) inBattle = true
        }
        d.frames(200)
        TestRoms.screenshot(gb, "script_04_battle")
        assertTrue(inBattle, "a wild battle started")
        assertEquals(1, gb.read(wIsInBattle), "wild (not trainer) battle")
    }
}
