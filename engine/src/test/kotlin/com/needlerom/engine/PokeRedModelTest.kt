package com.needlerom.engine

import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.Evolution
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.pokered.Party
import com.needlerom.engine.pokered.PartyMon
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PokeRedModelTest {
    private fun open(name: String): PokeRed? = TestRoms.bytes(name)?.let { PokeRed(Rom(it)) }

    @Test
    fun identifiesVersions() {
        val red = open("pokered.gbc") ?: return
        assertEquals("Pokémon Red", red.version.name); assertTrue(red.exact)
        assertEquals("Pokémon Blue", open("pokeblue.gbc")!!.version.name)
        val y = open("pokeyellow.gbc")!!
        assertTrue(y.isYellow); assertTrue(y.exact)
    }

    @Test
    fun readsNamesAndStats() {
        for (name in listOf("pokered.gbc", "pokeyellow.gbc")) {
            val g = open(name) ?: return
            assertEquals("BULBASAUR", g.monName(0x99))
            assertEquals(151, g.species.size)
            assertEquals(1, g.dexOf(0x99))
            assertEquals("MASTER BALL", g.itemName(1))
            assertEquals("TM01", g.itemName(PokeRed.TM01))
            assertEquals("POUND", g.moveName(1))
            assertEquals("YOUNGSTER", g.trainerClassNames[1])
            val bulba = g.pokemon.stats(1)
            assertEquals(listOf(45, 49, 49, 45, 65), listOf(bulba.hp, bulba.attack, bulba.defense, bulba.speed, bulba.special))
            assertEquals(listOf(0x16, 0x03), listOf(bulba.type1, bulba.type2))
            val mew = g.pokemon.stats(151)
            assertEquals(100, mew.hp)
            val evo = g.pokemon.evosMoves(0x99)
            assertEquals(Evolution.Level(16, 0x09), evo.evolutions.single())
            assertEquals(7, evo.learnset.first().level)
        }
    }

    @Test
    fun trainersWildAndStarters() {
        val g = open("pokered.gbc") ?: return
        val brock = g.trainers.parties(34).single()
        assertEquals(listOf(PartyMon(12, 0xA9), PartyMon(14, 0x22)), brock.mons)
        assertEquals(9, g.trainers.parties(25).size)
        val route1 = g.wild.encounters(0x0C)
        assertEquals(25, route1.grassRate)
        assertEquals(PartyMon(3, 0x24), route1.grass[0]) // Pidgey
        assertEquals(0, route1.waterRate)
        assertEquals(listOf(0xB0, 0xB1, 0x99), g.starters.current())
    }

    @Test
    fun editsRoundTrip() {
        val g = open("pokered.gbc") ?: return
        // Brock gets a bigger team (doesn't fit in place -> relocated within bank E).
        g.trainers.setParty(34, 1, Party(listOf(PartyMon(15, 0xA9), PartyMon(16, 0x22), PartyMon(18, 0x22))))
        assertEquals(3, g.trainers.parties(34).single().mons.size)
        assertEquals(PartyMon(14, 0xA9), g.trainers.parties(1)[0].mons.first().copy(level = 14).copy(species = 0xA9))
        // Route 1 gets water encounters (block grows -> relocated, not shared).
        val r1 = g.wild.encounters(0x0C)
        g.wild.setEncounters(0x0C, r1.copy(waterRate = 10, water = List(10) { PartyMon(5, 0x85) }))
        assertEquals(10, g.wild.encounters(0x0C).waterRate)
        assertEquals(25, g.wild.encounters(0x0C).grassRate)
        // Pallet Town's shared "nothing" block must stay untouched for other maps.
        g.wild.setEncounters(0x00, r1)
        assertEquals(25, g.wild.encounters(0x00).grassRate)
        assertEquals(0, g.wild.encounters(0x01).grassRate)
        // Starter swap updates every reference.
        g.starters.set(1, 0x15) // Mew
        assertEquals(0x15, g.starters.current()[0])
        assertTrue(g.trainers.parties(25).any { p -> p.mons.any { it.species == 0x15 } })
        // Evolutions grow.
        val e = g.pokemon.evosMoves(0x99)
        g.pokemon.setEvosMoves(0x99, e.copy(evolutions = e.evolutions + Evolution.Item(0x0A, 1, 0x15)))
        assertEquals(2, g.pokemon.evosMoves(0x99).evolutions.size)
        g.items.setPrice(1, 123456)
        assertEquals(123456, g.items.price(1))
        // Undo everything.
        while (g.rom.canUndo) g.rom.undo()
        assertTrue(g.rom.data.contentEquals(g.rom.original))
    }

    @Test
    fun parsesPalletTown() {
        val g = open("pokered.gbc") ?: return
        val pallet = g.world.map(0)
        assertEquals(10, pallet.width); assertEquals(9, pallet.height)
        assertEquals(3, pallet.npcs.size); assertEquals(4, pallet.signs.size); assertEquals(3, pallet.warps.size)
        assertEquals(7, pallet.textTableSize)
        assertEquals(Behavior.GameCode, pallet.behavior(1)) // Oak: scripted
        assertTrue(pallet.behavior(2) is Behavior.Talk)      // the girl
        assertTrue((pallet.behavior(5) as Behavior.Talk).text.contains("PALLET TOWN"))
        assertEquals("Viridian City", g.mapName(1))
        assertTrue(g.world.maps.size > 200)
        assertEquals(0x3F, g.world.engineBank)
        val yellow = open("pokeyellow.gbc")!!
        assertEquals(0x3B, yellow.world.engineBank)
        assertEquals(10, yellow.world.map(0).width)
    }

    @Test
    fun addNpcRenumbersSigns() {
        val g = open("pokered.gbc") ?: return
        val before = g.world.map(0)
        val idx = before.addNpc(sprite = 4, x = 5, y = 6, movement = Movement.WANDER, behavior = Behavior.Talk("I'm new here!"))
        val after = g.world.map(0)
        assertEquals(4, after.npcs.size)
        assertEquals(3, idx)
        assertTrue(after.signs.all { it.textId > after.npcs.size }, "sign ids must stay above the NPC count")
        assertEquals("I'm new here!", (after.behavior(after.npcs[3].textId) as Behavior.Talk).text)
        // Original sign texts still resolve.
        assertTrue(after.signs.all { after.behavior(it.textId) is Behavior.Talk })
        assertNotEquals(before.eventsOffset, after.eventsOffset)
        assertFalse(runCatching { after.removeNpc(after.npcs[0]) }.isSuccess)
        after.removeNpc(after.npcs[3])
        assertEquals(3, g.world.map(0).npcs.size)
    }
}
