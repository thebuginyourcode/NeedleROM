package com.needlerom.engine.gb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A complete DMG Game Boy. Typical use: set [Bus.buttons], call [runFrame] 60 times a
 * second, draw [Ppu.frame] and drain audio from [Apu].
 */
class GameBoy(rom: ByteArray, savedRam: ByteArray? = null, sampleRate: Int = 44100) {
    val cart = Cartridge(rom, savedRam)
    val bus = Bus(cart, Apu(sampleRate))
    val cpu = Cpu(bus)
    var cycles = 0L
        private set

    init {
        // Register state after the DMG boot ROM hands over to the cartridge.
        bus.write(0xFF05, 0); bus.write(0xFF06, 0); bus.write(0xFF07, 0)
        bus.write(0xFF26, 0xF1); bus.write(0xFF25, 0xF3); bus.write(0xFF24, 0x77)
        bus.write(0xFF47, 0xFC); bus.write(0xFF48, 0xFF); bus.write(0xFF49, 0xFF)
        bus.write(0xFF40, 0x91)
    }

    /** Runs one instruction and advances every component. */
    fun step(): Int {
        val c = cpu.step()
        bus.timer.tick(c)
        bus.ppu.tick(c)
        bus.apu.tick(c)
        cycles += c
        return c
    }

    /** Runs until the next frame is complete (~1/60 s of emulated time). */
    fun runFrame() {
        bus.ppu.frameReady = false
        var budget = CYCLES_PER_FRAME
        while (!bus.ppu.frameReady && budget > 0) budget -= step()
    }

    var buttons: Int
        get() = bus.buttons
        set(v) { bus.buttons = v }

    fun read(addr: Int): Int = bus.read(addr)
    fun write(addr: Int, v: Int) = bus.write(addr, v)

    /** Battery RAM to persist as the game's save file. */
    val saveRam: ByteArray get() = cart.ram

    fun saveState(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(STATE_VERSION)
            out.writeLong(cycles)
            cpu.save(out)
            bus.save(out)
        }
        return bytes.toByteArray()
    }

    fun loadState(state: ByteArray) {
        DataInputStream(ByteArrayInputStream(state)).use { inp ->
            require(inp.readInt() == STATE_VERSION) { "Save state from a different version" }
            cycles = inp.readLong()
            cpu.load(inp)
            bus.load(inp)
        }
    }

    companion object {
        const val CYCLES_PER_FRAME = 70224
        private const val STATE_VERSION = 1
    }
}
