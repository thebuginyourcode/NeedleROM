package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Flags
import com.needlerom.engine.script.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NPCs walking up to the player and leading the player, played in Red's bedroom. */
class WalkTest {
    private val room = 0x26

    private class Result(val player: Pair<Int, Int>, val npc: Pair<Int, Int>, val continued: Boolean, val controls: Int)

    private fun play(npcAt: Pair<Int, Int>, facing: Movement, steps: (Int) -> List<Step>, trigger: Pair<Int?, Int?>?): Result? {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return null
        val g = PokeRed(Rom(bytes))
        val npc = g.world.map(room).addNpc(4, npcAt.first, npcAt.second, facing, Behavior.Talk("Hi"))
        val script = EventScript(steps(npc) + Step.SetFlag(5))
        if (trigger != null) g.world.map(room).addTrigger(trigger.first, trigger.second, script)
        else g.world.map(room).setScript(g.world.map(room).npcs[npc].textId, script)
        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom() // the player starts at (3, 6)
        when {
            trigger == null -> { d.press(Button.LEFT, hold = 2, after = 20); d.press(Button.A, after = 30) }
            trigger.first != null -> d.press(Button.RIGHT, hold = 16, after = 30)
        }
        repeat(40) { d.press(Button.B, after = 20) }
        val sprite = TestRoms.sym("pokered.sym", "wSpriteStateData2") + 0x10 * (npc + 1)
        val e = Flags.FIRST_EVENT + 5
        return Result(
            gb.read(g.sym("wXCoord")) to gb.read(g.sym("wYCoord")),
            gb.read(sprite + 5) - 4 to gb.read(sprite + 4) - 4,
            gb.read(g.sym("wEventFlags") + e / 8) and (1 shl (e % 8)) != 0,
            gb.read(TestRoms.sym("pokered.sym", "wJoyIgnore")),
        )
    }

    private fun assertAdjacent(r: Result) {
        val dist = Math.abs(r.player.first - r.npc.first) + Math.abs(r.player.second - r.npc.second)
        assertEquals(1, dist, "NPC ${r.npc} next to the player ${r.player}")
        assertTrue(r.continued, "the event carried on after the walk")
        assertEquals(0, r.controls, "the player can move again")
    }

    @Test
    fun walksUpFromAboveAndDiagonally() {
        // Above the player and partly off screen (the game's own routine gets these wrong).
        play(4 to 1, Movement.FACE_DOWN, { listOf(Step.Say("Hey!"), Step.WalkToPlayer(it)) }, 4 to 6)?.let(::assertAdjacent)
        play(0 to 1, Movement.FACE_DOWN, { listOf(Step.Say("Hey!"), Step.WalkToPlayer(it)) }, 4 to 6)?.let(::assertAdjacent)
        // A map-entry trigger, as the map loads.
        play(1 to 2, Movement.FACE_DOWN, { listOf(Step.Say("Hey!"), Step.WalkToPlayer(it)) }, null to null)?.let(::assertAdjacent)
    }

    @Test
    fun npcLeadsThePlayer() {
        // Talk to the NPC on the left; it leads the player up to (2, 2).
        val r = play(2 to 6, Movement.FACE_RIGHT, { listOf(Step.Say("Follow me!"), Step.Lead(it, 2, 2)) }, null) ?: return
        assertEquals(2 to 2, r.npc)
        assertEquals(2 to 3, r.player, "one step behind")
        assertTrue(r.continued)
        assertEquals(0, r.controls)
    }
}
