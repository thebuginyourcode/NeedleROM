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
    /** The frame sequencer step that runs next (lengths on even steps, sweep 2/6, envelope 7). */
    private var frameSeqStep = 0
    private var capL = 0.0
    private var capR = 0.0
    // Output is averaged over each sample period (a box filter), so high notes and noise
    // don't alias into harsh tones the way point sampling does.
    private var accL = 0L
    private var accR = 0L
    private var accCycles = 0

    /** Mutes output without stopping emulation (e.g. when the app is in the background). */
    var muted = false

    /** True if the frame sequencer's next step doesn't clock length counters (an odd step). */
    private val lengthQuietNext get() = frameSeqStep and 1 == 1

    /** Length counter and NRx4 handling shared by all four channels, with the DMG's quirks. */
    private abstract inner class Channel(val maxLength: Int) {
        var enabled = false
        var dacOn = false
        var length = 0
        var lengthEnabled = false
        var freq = 0

        abstract fun onTrigger()

        fun clockLength() { if (lengthEnabled && length > 0 && --length == 0) enabled = false }

        fun writeLength(v: Int) { length = maxLength - v }

        /** NRx4: frequency high bits, length enable and trigger. */
        fun writeControl(v: Int, setsFreq: Boolean = true) {
            if (setsFreq) freq = (freq and 0xFF) or ((v and 7) shl 8)
            val wasEnabled = lengthEnabled
            lengthEnabled = v and 0x40 != 0
            val trigger = v and 0x80 != 0
            // Enabling the length counter between length clocks clocks it once.
            if (!wasEnabled && lengthEnabled && lengthQuietNext && length != 0) {
                length--
                if (length == 0 && !trigger) enabled = false
            }
            if (trigger) {
                enabled = dacOn
                if (length == 0) length = if (lengthEnabled && lengthQuietNext) maxLength - 1 else maxLength
                onTrigger()
            }
        }
    }

    private inner class Square(val hasSweep: Boolean) : Channel(64) {
        var duty = 0
        var dutyPos = 0
        var timer = 0
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
        var negateUsed = false
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

        fun clockEnvelope() {
            if (--envTimer > 0) return
            envTimer = if (envPeriod == 0) 8 else envPeriod
            if (envPeriod == 0) return
            if (envUp && volume < 15) volume++ else if (!envUp && volume > 0) volume--
        }

        fun sweepCalc(): Int {
            val delta = shadow shr sweepShift
            val n = if (sweepNegate) { negateUsed = true; shadow - delta } else shadow + delta
            if (n > 2047) enabled = false
            return n
        }

        fun clockSweep() {
            if (--sweepTimer > 0) return
            sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
            if (sweepEnabled && sweepPeriod != 0) {
                val n = sweepCalc()
                if (n <= 2047 && sweepShift != 0) {
                    shadow = n; freq = n
                    regs[0x03] = n and 0xFF
                    regs[0x04] = (regs[0x04] and 0xF8) or (n shr 8)
                    sweepCalc()
                }
            }
        }

        fun writeSweep(v: Int) {
            val wasNegate = sweepNegate
            sweepPeriod = (v shr 4) and 7; sweepNegate = v and 8 != 0; sweepShift = v and 7
            // Leaving negate mode after a negated calculation disables the channel.
            if (wasNegate && !sweepNegate && negateUsed) enabled = false
        }

        override fun onTrigger() {
            timer = (2048 - freq) * 4
            volume = envInitial
            envTimer = if (envPeriod == 0) 8 else envPeriod
            if (hasSweep) {
                shadow = freq
                negateUsed = false
                sweepTimer = if (sweepPeriod == 0) 8 else sweepPeriod
                sweepEnabled = sweepPeriod != 0 || sweepShift != 0
                if (sweepShift != 0) sweepCalc()
            }
        }
    }

    private inner class Wave : Channel(256) {
        var timer = 0
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

        override fun onTrigger() {
            timer = (2048 - freq) * 2 + 6
            pos = 0
        }
    }

    private inner class Noise : Channel(64) {
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

        fun clockEnvelope() {
            if (--envTimer > 0) return
            envTimer = if (envPeriod == 0) 8 else envPeriod
            if (envPeriod == 0) return
            if (envUp && volume < 15) volume++ else if (!envUp && volume > 0) volume--
        }

        override fun onTrigger() {
            timer = period()
            lfsr = 0x7FFF
            volume = envInitial
            envTimer = if (envPeriod == 0) 8 else envPeriod
        }
    }

    private val sq1 = Square(true)
    private val sq2 = Square(false)
    private val wave = Wave()
    private val noise = Noise()

    /**
     * Clocked by the timer on each falling edge of DIV bit 4 (every 8192 cycles), as on the
     * hardware: length counters, sweep and envelopes.
     */
    fun frameSequencerStep() {
        if (!powered) return
        when (frameSeqStep) {
            0, 4 -> clockLengths()
            2, 6 -> { clockLengths(); sq1.clockSweep() }
            7 -> { sq1.clockEnvelope(); sq2.clockEnvelope(); noise.clockEnvelope() }
        }
        frameSeqStep = (frameSeqStep + 1) and 7
    }

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
                // Powering off clears every register (the DMG keeps the length counters).
                for (a in 0xFF10..0xFF25) if (a !in LENGTH_REGS) write(a, 0)
                for (a in LENGTH_REGS) regs[a - 0xFF10] = 0
                listOf(sq1, sq2, wave, noise).forEach { it.enabled = false }
                sq1.duty = 0; sq2.duty = 0
            }
            if (!powered && on) {
                frameSeqStep = 0
                sq1.dutyPos = 0; sq2.dutyPos = 0
            }
            powered = on
            return
        }
        if (!powered) {
            // While off, only the length counters can be written (on the DMG).
            when (addr) {
                0xFF11 -> sq1.writeLength(v and 0x3F)
                0xFF16 -> sq2.writeLength(v and 0x3F)
                0xFF1B -> wave.writeLength(v)
                0xFF20 -> noise.writeLength(v and 0x3F)
            }
            return
        }
        regs[i] = v
        when (addr) {
            0xFF10 -> sq1.writeSweep(v)
            0xFF11 -> { sq1.duty = v shr 6; sq1.writeLength(v and 0x3F) }
            0xFF12 -> envelope(sq1, v)
            0xFF13 -> sq1.freq = (sq1.freq and 0x700) or v
            0xFF14 -> sq1.writeControl(v)
            0xFF16 -> { sq2.duty = v shr 6; sq2.writeLength(v and 0x3F) }
            0xFF17 -> envelope(sq2, v)
            0xFF18 -> sq2.freq = (sq2.freq and 0x700) or v
            0xFF19 -> sq2.writeControl(v)
            0xFF1A -> { wave.dacOn = v and 0x80 != 0; if (!wave.dacOn) wave.enabled = false }
            0xFF1B -> wave.writeLength(v)
            0xFF1C -> wave.volumeCode = (v shr 5) and 3
            0xFF1D -> wave.freq = (wave.freq and 0x700) or v
            0xFF1E -> wave.writeControl(v)
            0xFF20 -> noise.writeLength(v and 0x3F)
            0xFF21 -> {
                noise.envInitial = v shr 4; noise.envUp = v and 8 != 0; noise.envPeriod = v and 7
                noise.dacOn = v and 0xF8 != 0; if (!noise.dacOn) noise.enabled = false
            }
            0xFF22 -> { noise.shift = v shr 4; noise.narrow = v and 8 != 0; noise.divisorCode = v and 7 }
            0xFF23 -> noise.writeControl(v, setsFreq = false)
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
            // DAC per channel: level 0..15 -> -15..15 (×1/15 later), summed per side by NR51.
            val nr51 = regs[0x15]
            var l = 0; var r = 0
            if (sq1.dacOn) { val d = sq1.output() * 2 - 15; if (nr51 and 0x10 != 0) l += d; if (nr51 and 0x01 != 0) r += d }
            if (sq2.dacOn) { val d = sq2.output() * 2 - 15; if (nr51 and 0x20 != 0) l += d; if (nr51 and 0x02 != 0) r += d }
            if (wave.dacOn) { val d = wave.output() * 2 - 15; if (nr51 and 0x40 != 0) l += d; if (nr51 and 0x04 != 0) r += d }
            if (noise.dacOn) { val d = noise.output() * 2 - 15; if (nr51 and 0x80 != 0) l += d; if (nr51 and 0x08 != 0) r += d }
            accL += l.toLong() * cycles
            accR += r.toLong() * cycles
        }
        accCycles += cycles
        sampleClock += cycles
        while (sampleClock >= cyclesPerSample) {
            sampleClock -= cyclesPerSample
            emitSample()
        }
    }

    private fun clockLengths() { sq1.clockLength(); sq2.clockLength(); wave.clockLength(); noise.clockLength() }

    private fun emitSample() {
        val nr50 = regs[0x14]
        val left = if (accCycles > 0) accL.toDouble() / accCycles / 15.0 * (((nr50 shr 4) and 7) + 1) / 8.0 else 0.0
        val right = if (accCycles > 0) accR.toDouble() / accCycles / 15.0 * ((nr50 and 7) + 1) / 8.0 else 0.0
        accL = 0; accR = 0; accCycles = 0
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
        private val LENGTH_REGS = setOf(0xFF11, 0xFF16, 0xFF1B, 0xFF20)
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
