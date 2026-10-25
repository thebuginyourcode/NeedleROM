package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.pokered.GameText
import com.needlerom.engine.pokered.Intro
import com.needlerom.engine.pokered.Pics
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntroTest {
    /** The 7x7 tiles the game put in vFrontPic, as shades per pixel (56x56, row-major). */
    private fun vram(gb: GameBoy): IntArray {
        val out = IntArray(56 * 56)
        for (col in 0 until 7) for (row in 0 until 7) {
            val t = 0x9000 + (col * 7 + row) * 16
            for (y in 0 until 8) {
                val lo = gb.read(t + y * 2); val hi = gb.read(t + y * 2 + 1)
                for (x in 0 until 8) {
                    val m = 0x80 shr x
                    out[(row * 8 + y) * 56 + col * 8 + x] = (if (lo and m != 0) 1 else 0) or (if (hi and m != 0) 2 else 0)
                }
            }
        }
        return out
    }

    private fun toOakSpeech(gb: GameBoy): Driver {
        val d = Driver(gb)
        d.frames(300)
        repeat(40) { d.press(Button.START, after = 30) }
        d.press(Button.A, after = 150) // NEW GAME; the professor fades in
        return d
    }

    @Test
    fun decodesPicturesLikeTheGame() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        assertTrue(Intro.supported(g))
        val oak = Intro.picture(g, Intro.Role.PROFESSOR)
        assertEquals("Prof. Oak", oak.name)
        val gb = GameBoy(g.rom.data)
        toOakSpeech(gb)
        val pic = Pics.decode(g.rom, oak.bank, oak.ptr)!!
        assertEquals(vram(gb).toList(), pic.centered().toList(), "decoded Oak matches the game's tiles")
        // Every trainer picture decodes.
        val all = Intro.choices(g)
        assertTrue(all.size > 10, "choices: ${all.map { it.name }}")
        all.forEach { assertTrue(Pics.decode(g.rom, it.bank, it.ptr) != null, "decodes ${it.name}") }
    }

    @Test
    fun changedIntroPlays() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val g = PokeRed(Rom(bytes))
        val brock = Intro.choices(g).first { it.name.uppercase() == "BROCK" }
        println("choices: " + Intro.choices(g).joinToString { it.name })
        Intro.setPicture(g, Intro.Role.PROFESSOR, brock)
        Intro.setSpecies(g, 0x54) // Pikachu
        Intro.setCry(g, 0x0A)
        val welcome = Intro.texts(g).first().second.first()
        GameText.write(g, welcome, "Hey! I'm the boss\nand this is my\nworld now!")
        assertEquals(brock, Intro.picture(g, Intro.Role.PROFESSOR))
        assertEquals(0x54, Intro.species(g))
        assertTrue(Intro.texts(g).first().second.first().text.startsWith("Hey! I'm the boss"))

        val gb = GameBoy(g.rom.data)
        val d = toOakSpeech(gb)
        d.frames(60)
        TestRoms.screenshot(gb, "intro_brock")
        assertEquals(Pics.decode(g.rom, brock.bank, brock.ptr)!!.centered().toList(), vram(gb).toList(), "the new professor is shown")
        // Through the rest of the speech and naming: the game reaches the overworld.
        repeat(400) { if (gb.read(0xC100) != 0 && gb.read(0xFFB0) == 0x90) return@repeat; d.press(Button.A, after = 10) }
        assertEquals(0x26, gb.read(g.sym("wCurMap")), "the new game starts")
    }
}
