package com.needlerom.engine.pokered

import com.needlerom.engine.gb.Button
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.rom.Gb

/** "Playtest from here": a new game that starts on any map tile, with the intro pressed through. */
object QuickStart {
    /** A copy of the hack whose new game starts at (x, y) on [mapId] instead of the bedroom. */
    fun rom(g: PokeRed, mapId: Int, x: Int, y: Int): ByteArray {
        val out = g.rom.data.copyOf()
        // NewGameWarp lives in bank 1 in every version.
        g.world.map(mapId).startSpec(x, y).copyInto(out, Gb.offset(1, g.sym("NewGameWarp")))
        return out
    }

    /**
     * Presses through the title, Oak's speech and naming (taking the default names) on a
     * console with no save, until the player stands at the start. Returns false if it gave up
     * or was [cancelled].
     */
    fun skipIntro(gb: GameBoy, g: PokeRed, mapId: Int, x: Int, y: Int, cancelled: () -> Boolean = { false }): Boolean {
        fun frames(n: Int) = repeat(n) { gb.runFrame() }
        fun press(button: Int, after: Int) { gb.buttons = button; frames(4); gb.buttons = 0; frames(after) }
        val wCurMap = g.sym("wCurMap"); val wX = g.sym("wXCoord"); val wY = g.sym("wYCoord")
        // The start position is set before Oak's speech, so also wait for the player's sprite
        // (picture id at wSpriteStateData1, $C100) and the hidden window (hWY = $90, $FFB0)
        // that mean the overworld is running. Both addresses are the same in every version.
        fun arrived() = gb.read(0xC100) != 0 && gb.read(wCurMap) == mapId && gb.read(wX) == x && gb.read(wY) == y
        fun ready() = arrived() && gb.read(0xFFB0) == 0x90
        frames(300)
        repeat(40) { press(Button.START, 30) } // intro and title, up to the NEW GAME menu
        press(Button.A, 60)
        repeat(500) {
            if (ready()) return true
            if (cancelled()) return false
            // Once on the map, B finishes the last text without talking to anyone.
            press(if (arrived()) Button.B else Button.A, 10)
        }
        return false
    }
}
