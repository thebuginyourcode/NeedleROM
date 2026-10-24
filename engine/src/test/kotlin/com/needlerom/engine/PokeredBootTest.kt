package com.needlerom.engine

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boots the real game and drives it with scripted input, saving screenshots along the way. */
class PokeredBootTest {
    @Test
    fun bootsIntoTheOverworld() {
        val rom = TestRoms.bytes("pokered.gbc") ?: return
        val gb = GameBoy(rom)
        val driver = Driver(gb)
        driver.newGameToBedroom()
        TestRoms.screenshot(gb, "red_10_bedroom")
        val t0 = System.nanoTime()
        driver.frames(600)
        val fps = 600 / ((System.nanoTime() - t0) / 1e9)
        println("Emulation speed: %.0f fps (%.1fx real time)".format(fps, fps / 59.7))
        // Red's House 2F is map 0x26.
        assertEquals(0x26, gb.read(Driver.W_CUR_MAP))
        assertTrue(gb.bus.ppu.frame.count { it.toInt() != 0 } > 2000)
    }
}

/** Scripted input for driving Pokémon Red/Blue in tests. */
class Driver(val gb: GameBoy) {
    fun frames(n: Int) = repeat(n) { gb.runFrame() }

    fun press(button: Int, hold: Int = 4, after: Int = 12) {
        gb.buttons = button; frames(hold); gb.buttons = 0; frames(after)
    }

    fun mash(button: Int, times: Int) = repeat(times) { press(button) }

    /** From power-on through Oak's speech and naming, ending in Red's bedroom. */
    fun newGameToBedroom() {
        frames(300)
        // Skip the intro and title until the main menu shows (cursor drawn = NEW GAME menu).
        repeat(40) { press(Button.START, after = 30) }
        press(Button.A, after = 60) // NEW GAME
        // Oak's speech and both naming menus: mashing A accepts the defaults.
        repeat(400) {
            if (it > 200 && gb.read(W_CUR_MAP) == 0x26) { frames(120); return }
            press(Button.A, after = 10)
        }
    }

    companion object {
        const val W_CUR_MAP = 0xD35E
    }
}
