package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * DMG sound: two square channels (one with sweep), a wave channel and a noise channel,
 * mixed to 16-bit stereo PCM at [sampleRate].
 */
class Apu(val sampleRate: Int = 44100) {
    private val regs = IntArray(0x30) // FF10..FF3F
    private var powered = true

    // Output ring buffer of interleaved stereo samples.
    private val buffer = ShortArray(sampleRate / 4 * 2)
    private var writePos = 0
    private var readPos = 0

    private var sampleClock = 0.0
    private val cyclesPerSample = 4194304.0 / sampleRate
    private var frameSeqClock = 0
    private var frameSeqStep = 0
    private var capL = 0.0
    private var capR = 0.0

    /** Mutes output without stopping emulation (e.g. when the app is in the background). */
    var muted = false

    private inner class Square(val hasSweep: Boolean) {
        var enabled = false
        var dacOn = false
        var length = 0
        var lengthEnabled = false
        var duty = 0
        var dutyPos = 0
        var timer = 0
        var freq = 0
        var volume = 0
        var envInitial = 0
        var envUp = false
        var envPeriod = 0
        var envTimer = 0
        var sweepPeriod = 0
        var sweepNegate = false
        var sweepShift = 0
        var sweepTimer = 0
        var sweepEnabled = false
        var shadow = 0

        fun tick(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += (2048 - freq) * 4
                dutyPos = (dutyPos + 1) and 7
            }
        }

        fun output(): Int {
            if (!enabled || !dacOn) return 0
            return if (DUTY[duty][dutyPos] == 1) volume else 0
        }

        fun clockLength() { if (lengthEnabled && length > 0 && --length == 0) enabled = false }

        fun clockEnvelope() {
            if (envPeriod == 0) return
            if (--envTimer <= 0) {
                envTimer = envPeriod
                if (envUp && volume < 15) volume++ else if (!envUp && volume > 0) volume--
            }
        }

        fun sweepCalc(): Int {
            val delta = shadow shr sweepShift
            val n = if (sweepNegate) shadow - delta else shadow + delta
            if (n > 2047) enabled = false
            return n
        }

        fun clockSweep() {
            if (--sweepTimer <= 0) {
                sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
                if (sweepEnabled && sweepPeriod != 0) {
                    val n = sweepCalc()
                    if (n <= 2047 && sweepShift != 0) {
                        shadow = n; freq = n
                        sweepCalc()
                    }
                }
            }
        }

        fun trigger() {
            enabled = dacOn
            if (length == 0) length = 64
            timer = (2048 - freq) * 4
            volume = envInitial
            envTimer = envPeriod
            if (hasSweep) {
                shadow = freq
                sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
                sweepEnabled = sweepPeriod != 0 || sweepShift != 0
                if (sweepShift != 0) sweepCalc()
            }
        }
    }

    private inner class Wave {
        var enabled = false
        var dacOn = false
        var length = 0
        var lengthEnabled = false
        var timer = 0
        var freq = 0
        var pos = 0
        var volumeCode = 0
        var sample = 0

        fun tick(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += (2048 - freq) * 2
                pos = (pos + 1) and 31
                val byte = regs[0x20 + pos / 2]
                sample = if (pos % 2 == 0) byte shr 4 else byte and 0x0F
            }
        }

        fun output(): Int {
            if (!enabled || !dacOn) return 0
            return when (volumeCode) { 0 -> 0; 1 -> sample; 2 -> sample shr 1; else -> sample shr 2 }
        }

        fun clockLength() { if (lengthEnabled && length > 0 && --length == 0) enabled = false }

        fun trigger() {
            enabled = dacOn
            if (length == 0) length = 256
            timer = (2048 - freq) * 2
            pos = 0
        }
    }

    private inner class Noise {
        var enabled = false
        var dacOn = false
        var length = 0
        var lengthEnabled = false
        var timer = 0
        var lfsr = 0x7FFF
        var shift = 0
        var narrow = false
        var divisorCode = 0
        var volume = 0
        var envInitial = 0
        var envUp = false
        var envPeriod = 0
        var envTimer = 0

        private fun period() = (if (divisorCode == 0) 8 else divisorCode * 16) shl shift

        fun tick(cycles: Int) {
            timer -= cycles
            while (timer <= 0) {
                timer += period()
                val x = (lfsr and 1) xor ((lfsr shr 1) and 1)
                lfsr = (lfsr shr 1) or (x shl 14)
                if (narrow) lfsr = (lfsr and 0x40.inv()) or (x shl 6)
            }
        }

        fun output(): Int = if (!enabled || !dacOn) 0 else if (lfsr and 1 == 0) volume else 0

        fun clockLength() { if (lengthEnabled && length > 0 && --length == 0) enabled = false }

        fun clockEnvelope() {
            if (envPeriod == 0) return
            if (--envTimer <= 0) {
                envTimer = envPeriod
                if (envUp && volume < 15) volume++ else if (!envUp && volume > 0) volume--
            }
        }

        fun trigger() {
            enabled = dacOn
            if (length == 0) length = 64
            timer = period()
            lfsr = 0x7FFF
            volume = envInitial
            envTimer = envPeriod
        }
    }

    private val sq1 = Square(true)
    private val sq2 = Square(false)
    private val wave = Wave()
    private val noise = Noise()

    fun read(addr: Int): Int {
        val i = addr - 0xFF10
        if (addr in 0xFF30..0xFF3F) return regs[i]
        if (addr == 0xFF26) {
            return (if (powered) 0x80 else 0) or 0x70 or
                (if (sq1.enabled) 1 else 0) or (if (sq2.enabled) 2 else 0) or
                (if (wave.enabled) 4 else 0) or (if (noise.enabled) 8 else 0)
        }
        return regs[i] or READ_MASK[i]
    }

    fun write(addr: Int, v: Int) {
        val i = addr - 0xFF10
        if (addr in 0xFF30..0xFF3F) { regs[i] = v; return }
        if (addr == 0xFF26) {
            val on = v and 0x80 != 0
            if (powered && !on) {
                for (r in 0 until 0x16) regs[r] = 0
                listOf(sq1, sq2).forEach { it.enabled = false; it.dacOn = false }
                wave.enabled = false; wave.dacOn = false; noise.enabled = false; noise.dacOn = false
            }
            powered = on
            return
        }
        if (!powered) return
        regs[i] = v
        when (addr) {
            0xFF10 -> { sq1.sweepPeriod = (v shr 4) and 7; sq1.sweepNegate = v and 8 != 0; sq1.sweepShift = v and 7 }
            0xFF11 -> { sq1.duty = v shr 6; sq1.length = 64 - (v and 0x3F) }
            0xFF12 -> envelope(sq1, v)
            0xFF13 -> sq1.freq = (sq1.freq and 0x700) or v
            0xFF14 -> { sq1.freq = (sq1.freq and 0xFF) or ((v and 7) shl 8); sq1.lengthEnabled = v and 0x40 != 0; if (v and 0x80 != 0) sq1.trigger() }
            0xFF16 -> { sq2.duty = v shr 6; sq2.length = 64 - (v and 0x3F) }
            0xFF17 -> envelope(sq2, v)
            0xFF18 -> sq2.freq = (sq2.freq and 0x700) or v
            0xFF19 -> { sq2.freq = (sq2.freq and 0xFF) or ((v and 7) shl 8); sq2.lengthEnabled = v and 0x40 != 0; if (v and 0x80 != 0) sq2.trigger() }
            0xFF1A -> { wave.dacOn = v and 0x80 != 0; if (!wave.dacOn) wave.enabled = false }
            0xFF1B -> wave.length = 256 - v
            0xFF1C -> wave.volumeCode = (v shr 5) and 3
            0xFF1D -> wave.freq = (wave.freq and 0x700) or v
            0xFF1E -> { wave.freq = (wave.freq and 0xFF) or ((v and 7) shl 8); wave.lengthEnabled = v and 0x40 != 0; if (v and 0x80 != 0) wave.trigger() }
            0xFF20 -> noise.length = 64 - (v and 0x3F)
            0xFF21 -> {
                noise.envInitial = v shr 4; noise.envUp = v and 8 != 0; noise.envPeriod = v and 7
                noise.dacOn = v and 0xF8 != 0; if (!noise.dacOn) noise.enabled = false
            }
            0xFF22 -> { noise.shift = v shr 4; noise.narrow = v and 8 != 0; noise.divisorCode = v and 7 }
            0xFF23 -> { noise.lengthEnabled = v and 0x40 != 0; if (v and 0x80 != 0) noise.trigger() }
        }
    }

    private fun envelope(s: Square, v: Int) {
        s.envInitial = v shr 4; s.envUp = v and 8 != 0; s.envPeriod = v and 7
        s.dacOn = v and 0xF8 != 0
        if (!s.dacOn) s.enabled = false
    }

    fun tick(cycles: Int) {
        if (powered) {
            sq1.tick(cycles); sq2.tick(cycles); wave.tick(cycles); noise.tick(cycles)
            frameSeqClock += cycles
            while (frameSeqClock >= 8192) {
                frameSeqClock -= 8192
                when (frameSeqStep) {
                    0, 4 -> clockLengths()
                    2, 6 -> { clockLengths(); sq1.clockSweep() }
                    7 -> { sq1.clockEnvelope(); sq2.clockEnvelope(); noise.clockEnvelope() }
                }
                frameSeqStep = (frameSeqStep + 1) and 7
            }
        }
        sampleClock += cycles
        while (sampleClock >= cyclesPerSample) {
            sampleClock -= cyclesPerSample
            emitSample()
        }
    }

    private fun clockLengths() { sq1.clockLength(); sq2.clockLength(); wave.clockLength(); noise.clockLength() }

    private fun emitSample() {
        var left = 0.0
        var right = 0.0
        if (powered) {
            val nr51 = regs[0x15]
            for (ch in 0 until 4) {
                val on = when (ch) { 0 -> sq1.dacOn; 1 -> sq2.dacOn; 2 -> wave.dacOn; else -> noise.dacOn }
                if (!on) continue
                val level = when (ch) { 0 -> sq1.output(); 1 -> sq2.output(); 2 -> wave.output(); else -> noise.output() }
                // DAC: 0..15 -> -1..1
                val dac = level / 7.5 - 1.0
                if (nr51 and (0x10 shl ch) != 0) left += dac
                if (nr51 and (1 shl ch) != 0) right += dac
            }
            val nr50 = regs[0x14]
            left *= (((nr50 shr 4) and 7) + 1) / 8.0
            right *= ((nr50 and 7) + 1) / 8.0
        }
        // High-pass filter removes the DC offset like the real hardware's capacitor.
        val outL = left - capL
        capL = left - outL * 0.996
        val outR = right - capR
        capR = right - outR * 0.996
        val scale = if (muted) 0.0 else 4096.0
        push((outL * scale).toInt(), (outR * scale).toInt())
    }

    private fun push(l: Int, r: Int) {
        buffer[writePos] = l.coerceIn(-32768, 32767).toShort()
        buffer[writePos + 1] = r.coerceIn(-32768, 32767).toShort()
        writePos = (writePos + 2) % buffer.size
        if (writePos == readPos) readPos = (readPos + 2) % buffer.size // drop oldest on overflow
    }

    /** Copies pending interleaved stereo samples into [out]; returns the number of shorts copied. */
    fun drain(out: ShortArray): Int {
        var n = 0
        while (readPos != writePos && n < out.size) {
            out[n++] = buffer[readPos]
            readPos = (readPos + 1) % buffer.size
        }
        return n
    }

    fun save(out: DataOutputStream) {
        for (r in regs) out.writeInt(r)
        out.writeBoolean(powered)
    }

    /** Restores registers; channel timers restart, which is inaudible in practice. */
    fun load(inp: DataInputStream) {
        val saved = IntArray(regs.size) { inp.readInt() }
        powered = inp.readBoolean()
        saved.copyInto(regs)
        for (addr in 0xFF10..0xFF25) if (addr != 0xFF14 && addr != 0xFF19 && addr != 0xFF1E && addr != 0xFF23) write(addr, saved[addr - 0xFF10])
    }

    companion object {
        private val DUTY = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0),
        )
        private val READ_MASK = IntArray(0x30) { 0xFF }.also { m ->
            val masks = intArrayOf(
                0x80, 0x3F, 0x00, 0xFF, 0xBF, // NR10-NR14
                0xFF, 0x3F, 0x00, 0xFF, 0xBF, // unused, NR21-NR24
                0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // NR30-NR34
                0xFF, 0xFF, 0x00, 0x00, 0xBF, // unused, NR41-NR44
                0x00, 0x00, 0x70,             // NR50-NR52
            )
            masks.copyInto(m)
        }
    }
}
