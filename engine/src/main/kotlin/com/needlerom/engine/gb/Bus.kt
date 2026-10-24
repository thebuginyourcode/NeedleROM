package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/** Joypad buttons as bit masks. */
object Button {
    const val RIGHT = 0x01
    const val LEFT = 0x02
    const val UP = 0x04
    const val DOWN = 0x08
    const val A = 0x10
    const val B = 0x20
    const val SELECT = 0x40
    const val START = 0x80
}

/** The DMG memory map and I/O registers. */
class Bus(val cart: Cartridge, val apu: Apu = Apu()) {
    val wram = ByteArray(0x2000)
    val hram = ByteArray(0x7F)
    var ie = 0
    var iflag = 0xE1

    val timer = Timer(::request)
    val ppu = Ppu(::request)

    /** Currently held buttons (Button.* bits). */
    var buttons = 0
        set(v) {
            val newlyPressed = v and field.inv()
            field = v
            if (newlyPressed != 0) request(Interrupts.JOYPAD)
        }
    private var joypadSelect = 0x30

    private var serialData = 0
    private var serialControl = 0
    /** Bytes sent over the link port (test ROMs print their results here). */
    val serialOut = StringBuilder()

    fun request(bit: Int) { iflag = iflag or (1 shl bit) }

    fun read(addr: Int): Int = when {
        addr < 0x8000 -> cart.read(addr)
        addr < 0xA000 -> ppu.vram[addr - 0x8000].toInt() and 0xFF
        addr < 0xC000 -> cart.read(addr)
        addr < 0xE000 -> wram[addr - 0xC000].toInt() and 0xFF
        addr < 0xFE00 -> wram[addr - 0xE000].toInt() and 0xFF
        addr < 0xFEA0 -> ppu.oam[addr - 0xFE00].toInt() and 0xFF
        addr < 0xFF00 -> 0xFF
        addr < 0xFF80 -> readIo(addr)
        addr < 0xFFFF -> hram[addr - 0xFF80].toInt() and 0xFF
        else -> ie
    }

    fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        when {
            addr < 0x8000 -> cart.write(addr, v)
            addr < 0xA000 -> ppu.vram[addr - 0x8000] = v.toByte()
            addr < 0xC000 -> cart.write(addr, v)
            addr < 0xE000 -> wram[addr - 0xC000] = v.toByte()
            addr < 0xFE00 -> wram[addr - 0xE000] = v.toByte()
            addr < 0xFEA0 -> ppu.oam[addr - 0xFE00] = v.toByte()
            addr < 0xFF00 -> {}
            addr < 0xFF80 -> writeIo(addr, v)
            addr < 0xFFFF -> hram[addr - 0xFF80] = v.toByte()
            else -> ie = v
        }
    }

    private fun readJoypad(): Int {
        var low = 0x0F
        if (joypadSelect and 0x10 == 0) low = low and (buttons and 0x0F).inv()
        if (joypadSelect and 0x20 == 0) low = low and ((buttons shr 4) and 0x0F).inv()
        return 0xC0 or joypadSelect or (low and 0x0F)
    }

    private fun readIo(addr: Int): Int = when (addr) {
        0xFF00 -> readJoypad()
        0xFF01 -> serialData
        0xFF02 -> serialControl or 0x7E
        0xFF04 -> timer.div
        0xFF05 -> timer.tima
        0xFF06 -> timer.tma
        0xFF07 -> timer.tac or 0xF8
        0xFF0F -> iflag or 0xE0
        in 0xFF10..0xFF3F -> apu.read(addr)
        0xFF40 -> ppu.lcdc
        0xFF41 -> ppu.readStat()
        0xFF42 -> ppu.scy
        0xFF43 -> ppu.scx
        0xFF44 -> ppu.ly
        0xFF45 -> ppu.lyc
        0xFF46 -> dmaSource
        0xFF47 -> ppu.bgp
        0xFF48 -> ppu.obp0
        0xFF49 -> ppu.obp1
        0xFF4A -> ppu.wy
        0xFF4B -> ppu.wx
        else -> 0xFF
    }

    private var dmaSource = 0

    private fun writeIo(addr: Int, v: Int) {
        when (addr) {
            0xFF00 -> joypadSelect = v and 0x30
            0xFF01 -> serialData = v
            0xFF02 -> {
                serialControl = v and 0x81
                if (v and 0x81 == 0x81) {
                    // No link partner: the byte goes out, 0xFF comes back.
                    serialOut.append(serialData.toChar())
                    serialData = 0xFF
                    serialControl = serialControl and 0x7F
                    request(Interrupts.SERIAL)
                }
            }
            0xFF04 -> timer.writeDiv()
            0xFF05 -> timer.writeTima(v)
            0xFF06 -> timer.writeTma(v)
            0xFF07 -> timer.writeTac(v)
            0xFF0F -> iflag = v and 0x1F
            in 0xFF10..0xFF3F -> apu.write(addr, v)
            0xFF40 -> ppu.writeLcdc(v)
            0xFF41 -> ppu.writeStat(v)
            0xFF42 -> ppu.scy = v
            0xFF43 -> ppu.scx = v
            0xFF44 -> {}
            0xFF45 -> ppu.lyc = v
            0xFF46 -> {
                dmaSource = v
                val src = v shl 8
                for (i in 0 until 0xA0) ppu.oam[i] = read(src + i).toByte()
            }
            0xFF47 -> ppu.bgp = v
            0xFF48 -> ppu.obp0 = v
            0xFF49 -> ppu.obp1 = v
            0xFF4A -> ppu.wy = v
            0xFF4B -> ppu.wx = v
        }
    }

    fun save(out: DataOutputStream) {
        out.write(wram); out.write(hram)
        for (x in intArrayOf(ie, iflag, joypadSelect, serialData, serialControl, dmaSource)) out.writeInt(x)
        cart.save(out); timer.save(out); ppu.save(out); apu.save(out)
    }

    fun load(inp: DataInputStream) {
        inp.readFully(wram); inp.readFully(hram)
        ie = inp.readInt(); iflag = inp.readInt(); joypadSelect = inp.readInt()
        serialData = inp.readInt(); serialControl = inp.readInt(); dmaSource = inp.readInt()
        cart.load(inp); timer.load(inp); ppu.load(inp); apu.load(inp)
    }
}
