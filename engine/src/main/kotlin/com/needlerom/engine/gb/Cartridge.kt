package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A Game Boy cartridge: ROM, optional battery-backed RAM and the memory bank controller
 * (MBC1, MBC3 or MBC5 — which covers every Pokémon and Mario Game Boy game).
 */
class Cartridge(val rom: ByteArray, savedRam: ByteArray? = null) {
    enum class Mbc { NONE, MBC1, MBC3, MBC5 }

    val mbc: Mbc = when (rom.getOrElse(0x147) { 0 }.toInt() and 0xFF) {
        0x01, 0x02, 0x03 -> Mbc.MBC1
        0x0F, 0x10, 0x11, 0x12, 0x13 -> Mbc.MBC3
        0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> Mbc.MBC5
        else -> Mbc.NONE
    }

    val hasBattery: Boolean = (rom.getOrElse(0x147) { 0 }.toInt() and 0xFF) in setOf(0x03, 0x06, 0x09, 0x0D, 0x0F, 0x10, 0x13, 0x1B, 0x1E)

    val ram: ByteArray = ByteArray(
        when (rom.getOrElse(0x149) { 0 }.toInt() and 0xFF) {
            1 -> 0x800; 2 -> 0x2000; 3 -> 0x8000; 4 -> 0x20000; 5 -> 0x10000
            else -> if (mbc == Mbc.MBC1 || mbc == Mbc.NONE) 0 else 0x8000
        },
    ).also { r -> savedRam?.copyInto(r, 0, 0, minOf(savedRam.size, r.size)) }

    private val romBanks = maxOf(2, (rom.size + 0x3FFF) / 0x4000)

    private var ramEnabled = false
    private var romBank = 1
    private var ramBank = 0
    private var mbc1Mode = 0
    /** Set whenever the game writes to battery RAM, so the app knows to persist it. */
    var ramDirty = false

    private fun romByte(offset: Int): Int = if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF

    fun read(addr: Int): Int = when {
        addr < 0x4000 -> {
            if (mbc == Mbc.MBC1 && mbc1Mode == 1) romByte((((ramBank shl 5) % romBanks) * 0x4000) + addr) else romByte(addr)
        }
        addr < 0x8000 -> romByte((effectiveRomBank() % romBanks) * 0x4000 + (addr - 0x4000))
        else -> readRam(addr)
    }

    private fun effectiveRomBank(): Int = when (mbc) {
        Mbc.MBC1 -> (ramBank shl 5) or romBank
        else -> romBank
    }

    private fun ramOffset(addr: Int): Int {
        val bank = when (mbc) {
            Mbc.MBC1 -> if (mbc1Mode == 1) ramBank else 0
            else -> ramBank
        }
        return bank * 0x2000 + (addr - 0xA000)
    }

    private fun readRam(addr: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF
        if (mbc == Mbc.MBC3 && ramBank >= 0x08) return 0 // RTC registers: always read as 0
        val o = ramOffset(addr) % ram.size
        return ram[o].toInt() and 0xFF
    }

    fun write(addr: Int, value: Int) {
        when (mbc) {
            Mbc.NONE -> if (addr in 0xA000..0xBFFF) writeRam(addr, value)
            Mbc.MBC1 -> when {
                addr < 0x2000 -> ramEnabled = value and 0x0F == 0x0A
                addr < 0x4000 -> romBank = (value and 0x1F).let { if (it == 0) 1 else it }
                addr < 0x6000 -> ramBank = value and 0x03
                addr < 0x8000 -> mbc1Mode = value and 1
                else -> writeRam(addr, value)
            }
            Mbc.MBC3 -> when {
                addr < 0x2000 -> ramEnabled = value and 0x0F == 0x0A
                addr < 0x4000 -> romBank = (value and 0x7F).let { if (it == 0) 1 else it }
                addr < 0x6000 -> ramBank = value and 0x0F
                addr < 0x8000 -> {} // RTC latch
                else -> writeRam(addr, value)
            }
            Mbc.MBC5 -> when {
                addr < 0x2000 -> ramEnabled = value and 0x0F == 0x0A
                addr < 0x3000 -> romBank = (romBank and 0x100) or value
                addr < 0x4000 -> romBank = (romBank and 0xFF) or ((value and 1) shl 8)
                addr < 0x6000 -> ramBank = value and 0x0F
                addr < 0x8000 -> {}
                else -> writeRam(addr, value)
            }
        }
    }

    private fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        if (mbc == Mbc.MBC3 && ramBank >= 0x08) return
        val o = ramOffset(addr) % ram.size
        ram[o] = value.toByte()
        ramDirty = true
    }

    /** Current ROM bank in the switchable area (useful for debugging and the playtest HUD). */
    val currentRomBank: Int get() = effectiveRomBank() % romBanks

    fun save(out: DataOutputStream) {
        out.writeBoolean(ramEnabled); out.writeInt(romBank); out.writeInt(ramBank); out.writeInt(mbc1Mode)
        out.writeInt(ram.size); out.write(ram)
    }

    fun load(inp: DataInputStream) {
        ramEnabled = inp.readBoolean(); romBank = inp.readInt(); ramBank = inp.readInt(); mbc1Mode = inp.readInt()
        val n = inp.readInt()
        val buf = ByteArray(n).also { inp.readFully(it) }
        buf.copyInto(ram, 0, 0, minOf(n, ram.size))
    }
}
