package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/** DIV/TIMA/TMA/TAC. TIMA ticks on the falling edge of a bit of the 16-bit internal counter. */
class Timer(private val requestInterrupt: (Int) -> Unit) {
    private var counter = 0
    var tima = 0; private set
    var tma = 0; private set
    var tac = 0; private set

    val div: Int get() = (counter shr 8) and 0xFF

    private fun selectedBit(): Int = when (tac and 3) { 0 -> 9; 1 -> 3; 2 -> 5; else -> 7 }

    private fun signal(c: Int) = tac and 4 != 0 && (c shr selectedBit()) and 1 != 0

    private fun incrementTima() {
        tima++
        if (tima > 0xFF) {
            tima = tma
            requestInterrupt(Interrupts.TIMER)
        }
    }

    fun tick(cycles: Int) {
        var left = cycles
        while (left > 0) {
            val before = signal(counter)
            counter = (counter + 4) and 0xFFFF
            if (before && !signal(counter)) incrementTima()
            left -= 4
        }
    }

    fun writeDiv() {
        if (signal(counter)) incrementTima()
        counter = 0
    }

    fun writeTima(v: Int) { tima = v and 0xFF }
    fun writeTma(v: Int) { tma = v and 0xFF }
    fun writeTac(v: Int) {
        val before = signal(counter)
        tac = v and 7
        if (before && !signal(counter)) incrementTima()
    }

    fun save(out: DataOutputStream) { out.writeInt(counter); out.writeInt(tima); out.writeInt(tma); out.writeInt(tac) }
    fun load(inp: DataInputStream) { counter = inp.readInt(); tima = inp.readInt(); tma = inp.readInt(); tac = inp.readInt() }
}

object Interrupts {
    const val VBLANK = 0
    const val STAT = 1
    const val TIMER = 2
    const val SERIAL = 3
    const val JOYPAD = 4
}
