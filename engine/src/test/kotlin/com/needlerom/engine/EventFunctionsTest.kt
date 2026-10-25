package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import com.needlerom.engine.script.Dir
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Flags
import com.needlerom.engine.script.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Showing/hiding NPCs and the other event steps, played in Red's bedroom (start: (3, 6)). */
class EventFunctionsTest {
    private val room = 0x26

    private fun sprite(gb: GameBoy, n: Int, field: Int) = gb.read(0xC100 + 0x10 * n + field)
    private fun hidden(gb: GameBoy, n: Int) = sprite(gb, n, 2) == 0xFF // image index $FF = not drawn
    private fun pos(gb: GameBoy, n: Int) = gb.read(0xC200 + 0x10 * n + 5) - 4 to gb.read(0xC200 + 0x10 * n + 4) - 4

    private fun flag(gb: GameBoy, g: PokeRed, n: Int): Boolean {
        val e = Flags.FIRST_EVENT + n
        return gb.read(g.sym("wEventFlags") + e / 8) and (1 shl (e % 8)) != 0
    }

    private fun play(g: PokeRed, script: EventScript, check: (GameBoy, Driver) -> Unit) {
        g.world.map(room).addTrigger(4, 6, script)
        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom()
        check(gb, d)
        d.press(Button.RIGHT, hold = 16, after = 30)
        repeat(50) { d.press(Button.B, after = 20) }
    }

    @Test
    fun showHideFaceAndMove() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val map = g.world.map(room)
        val a = map.addNpc(4, 5, 3, Movement.FACE_DOWN, Behavior.Talk("A"))
        val b = g.world.map(room).addNpc(4, 6, 5, Movement.FACE_DOWN, Behavior.Talk("B"))
        val c = g.world.map(room).addNpc(4, 1, 5, Movement.FACE_DOWN, Behavior.Talk("C"))
        g.world.map(room).let { it.setNpcVisibility(it.npcs[b], controlled = true, startHidden = true) }
        assertTrue(g.world.map(room).npcVisibility(g.world.map(room).npcs[b])!!.startHidden)
        var startedHidden = false
        lateinit var gbRef: GameBoy
        play(g, EventScript(listOf(
            Step.Say("Watch!"),
            Step.HideNpc(a),
            Step.ShowNpc(b),
            Step.Face(-1, Dir.UP),
            Step.MoveNpc(c, listOf(Dir.UP, Dir.UP)),
            Step.MovePlayer(listOf(Dir.LEFT)),
            Step.Wait(20), Step.FadeOut, Step.FadeIn,
            Step.SetFlag(5),
        ))) { gb, _ ->
            gbRef = gb
            startedHidden = hidden(gb, b + 1) && !hidden(gb, a + 1)
        }
        val gb = gbRef
        TestRoms.screenshot(gb, "events_visibility")
        assertTrue(startedHidden, "B starts hidden, A visible")
        assertTrue(flag(gb, g, 5), "the event ran to the end")
        assertTrue(hidden(gb, a + 1), "A was hidden")
        assertFalse(hidden(gb, b + 1), "B was shown")
        assertEquals(1 to 3, pos(gb, c + 1), "C walked up two steps")
        assertEquals(3 to 6, gb.read(g.sym("wXCoord")) to gb.read(g.sym("wYCoord")), "the player stepped left")
        assertEquals(0, gb.read(TestRoms.sym("pokered.sym", "wJoyIgnore")))
    }

    @Test
    fun conditionsMoneyBadgesAndWarp() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        lateinit var gbRef: GameBoy
        play(g, EventScript(listOf(
            Step.GiveBadge(0),
            Step.IfBadge(0, listOf(Step.SetFlag(1)), emptyList()),
            Step.IfBadge(3, listOf(Step.SetFlag(9)), emptyList()),
            Step.IfMoney(1000, listOf(Step.SetFlag(2)), emptyList()),
            Step.TakeMoney(1000),
            Step.IfHasPokemon(0x54, emptyList(), listOf(Step.SetFlag(3))),
            Step.IfChance(100, listOf(Step.SetFlag(4)), emptyList()),
            Step.SetFlag(5),
            Step.Warp(0x25, 1), // Red's house 1F
            Step.SetFlag(6),    // not reached: a warp ends the event
        ))) { gb, _ -> gbRef = gb }
        val gb = gbRef
        TestRoms.screenshot(gb, "events_warp")
        for (f in 1..5) assertTrue(flag(gb, g, f), "flag $f")
        assertFalse(flag(gb, g, 6), "the warp ended the event")
        assertFalse(flag(gb, g, 9), "no Soul Badge")
        assertEquals(1, gb.read(g.sym("wObtainedBadges")) and 1, "Boulder Badge")
        val money = TestRoms.sym("pokered.sym", "wPlayerMoney")
        assertEquals(listOf(0x00, 0x20, 0x00), (0..2).map { gb.read(money + it) }, "3000 - 1000")
        assertEquals(0x25, gb.read(g.sym("wCurMap")), "warped downstairs")
    }
}
