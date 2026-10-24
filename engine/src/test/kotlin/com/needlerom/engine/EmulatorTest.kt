package com.needlerom.engine

import com.needlerom.engine.gb.GameBoy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorTest {

    private fun runBlargg(name: String, done: String, maxFrames: Int = 6000): String? {
        val rom = TestRoms.bytes(name) ?: return null
        val gb = GameBoy(rom)
        repeat(maxFrames) {
            gb.runFrame()
            val out = gb.bus.serialOut.toString()
            if (done in out || "Failed" in out) return out
        }
        return gb.bus.serialOut.toString()
    }

    @Test
    fun blarggCpuInstructions() {
        val out = runBlargg("cpu_instrs.gb", "Passed") ?: return
        println(out)
        assertTrue("Passed" in out && "Failed" !in out && "11:ok" in out, out)
    }

    @Test
    fun blarggInstructionTiming() {
        val out = runBlargg("instr_timing.gb", "Passed") ?: return
        println(out)
        assertTrue("Passed" in out, out)
    }

    @Test
    fun saveStateRoundTrip() {
        val rom = TestRoms.bytes("pokered.gbc") ?: return
        val gb = GameBoy(rom)
        repeat(120) { gb.runFrame() }
        val state = gb.saveState()
        repeat(60) { gb.runFrame() }
        val after = gb.bus.ppu.frame.copyOf()
        gb.loadState(state)
        repeat(60) { gb.runFrame() }
        assertTrue(after.contentEquals(gb.bus.ppu.frame), "Replaying from a save state should be deterministic")
        assertEquals(0x80, gb.read(0xFF40) and 0x80)
    }
}
