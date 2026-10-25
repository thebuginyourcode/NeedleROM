package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.pokered.PartyMon
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.ScriptEngine
import com.needlerom.engine.script.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Trigger tiles, NPCs walking to the player, custom trainer teams and new maps, played in the real game. */
class TriggerAndMapTest {
    private val bedroom = 0x26

    @Test
    fun triggerMakesTrainerWalkOverAndBattle() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        // A Youngster by the bed, and a trigger on the tile right of where the player starts.
        val npc = g.world.map(bedroom).addNpc(sprite = 4, x = 1, y = 6, movement = Movement.FACE_RIGHT, behavior = Behavior.Talk("Hi!"))
        val team = listOf(PartyMon(3, 0xA5), PartyMon(4, 0x24)) // Rattata, Pidgey
        val t = g.world.map(bedroom).addTrigger(4, 6, EventScript(listOf(
            Step.Say("Hey, you!"),
            Step.WalkToPlayer(npc),
            Step.Say("Let's battle!"),
            Step.TrainerBattle(1, 0, customTeam = team),
        )))
        val saved = assertNotNull(ScriptEngine.get(g, bedroom, t.textId))
        val battle = saved.steps.last() as Step.TrainerBattle
        assertTrue(battle.team > 13, "custom team appended to the Youngster class: ${battle.team}")
        assertEquals(team, g.trainers.parties(1)[battle.team - 1].mons)

        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom()
        val wIsInBattle = TestRoms.sym("pokered.sym", "wIsInBattle")
        // Sprite 1's map X (+4) in wSpriteStateData2.
        val npcX = TestRoms.sym("pokered.sym", "wSpriteStateData2") + 0x10 + 5
        assertEquals(1 + 4, gb.read(npcX))
        d.press(Button.RIGHT, hold = 16, after = 30) // step onto the trigger
        TestRoms.screenshot(gb, "trigger_01_hey")
        var inBattle = false
        var maxX = 0
        repeat(60) {
            if (!inBattle) d.press(Button.B, after = 20)
            if (it == 2) TestRoms.screenshot(gb, "trigger_02_walked")
            maxX = maxOf(maxX, gb.read(npcX) - 4)
            if (gb.read(wIsInBattle) != 0) inBattle = true
        }
        val walked = maxX == 3 // stopped right next to the player at x=4
        d.frames(200)
        TestRoms.screenshot(gb, "trigger_03_battle")
        assertTrue(walked, "the trainer walked toward the player")
        assertTrue(inBattle, "the battle started")
        assertEquals(2, gb.read(wIsInBattle), "trainer battle")
    }

    @Test
    fun newMapIsReachableThroughADoor() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val id = g.world.createMap(tileset = 1, width = 6, height = 5, fillBlock = 1)
        val made = g.world.map(id)
        assertEquals(6, made.width)
        assertEquals(1, made.warps.size)
        made.addNpc(sprite = 3, x = 5, y = 4, movement = Movement.FACE_DOWN, behavior = Behavior.Talk("Welcome to my\nnew room!"))
        assertTrue(id in g.world.maps.map { it.id })
        // The bedroom stairs now lead to the new map.
        val stairs = g.world.map(bedroom).warps.first()
        g.world.map(bedroom).setWarpDestination(stairs, id, 1)

        val gb = GameBoy(g.rom.data)
        val d = Driver(gb)
        d.newGameToBedroom()
        val wX = g.sym("wXCoord"); val wY = g.sym("wYCoord")
        // Walk to the stairs.
        // Around the plant: right to x=5, up to the top row, then right to the stairs.
        repeat(24) {
            val x = gb.read(wX); val y = gb.read(wY)
            when {
                gb.read(g.sym("wCurMap")) != bedroom -> return@repeat
                y > stairs.y && x < 5 -> d.press(Button.RIGHT, hold = 16, after = 4)
                y > stairs.y -> d.press(Button.UP, hold = 16, after = 4)
                else -> d.press(Button.RIGHT, hold = 16, after = 4)
            }
        }
        d.frames(120)
        TestRoms.screenshot(gb, "newmap_01_inside")
        println("after walking: map=${gb.read(g.sym("wCurMap"))} x=${gb.read(wX)} y=${gb.read(wY)} stairs=(${stairs.x},${stairs.y})")
        assertEquals(id, gb.read(g.sym("wCurMap")), "entered the new map")
    }
}
