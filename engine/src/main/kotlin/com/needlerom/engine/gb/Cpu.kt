package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/** The Sharp SM83 CPU. [step] runs one instruction (or interrupt dispatch) and returns T-cycles. */
class Cpu(private val bus: Bus) {
    var a = 0x01; var f = 0xB0
    var b = 0x00; var c = 0x13
    var d = 0x00; var e = 0xD8
    var h = 0x01; var l = 0x4D
    var sp = 0xFFFE
    var pc = 0x0100
    var ime = false
    private var imeDelay = 0
    var halted = false
    private var haltBug = false

    // Flags
    private var zf: Boolean get() = f and 0x80 != 0; set(v) { f = if (v) f or 0x80 else f and 0x7F }
    private var nf: Boolean get() = f and 0x40 != 0; set(v) { f = if (v) f or 0x40 else f and 0xBF }
    private var hf: Boolean get() = f and 0x20 != 0; set(v) { f = if (v) f or 0x20 else f and 0xDF }
    private var cf: Boolean get() = f and 0x10 != 0; set(v) { f = if (v) f or 0x10 else f and 0xEF }

    private var bc: Int get() = (b shl 8) or c; set(v) { b = (v shr 8) and 0xFF; c = v and 0xFF }
    private var de: Int get() = (d shl 8) or e; set(v) { d = (v shr 8) and 0xFF; e = v and 0xFF }
    var hl: Int get() = (h shl 8) or l; set(v) { h = (v shr 8) and 0xFF; l = v and 0xFF }
    private var af: Int get() = (a shl 8) or f; set(v) { a = (v shr 8) and 0xFF; f = v and 0xF0 }

    private fun rd(addr: Int) = bus.read(addr and 0xFFFF)
    private fun wr(addr: Int, v: Int) = bus.write(addr and 0xFFFF, v and 0xFF)

    private fun fetch(): Int {
        val v = rd(pc)
        if (haltBug) haltBug = false else pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetch16(): Int { val lo = fetch(); val hi = fetch(); return (hi shl 8) or lo }

    private fun push(v: Int) {
        sp = (sp - 1) and 0xFFFF; wr(sp, v shr 8)
        sp = (sp - 1) and 0xFFFF; wr(sp, v)
    }

    private fun pop(): Int {
        val lo = rd(sp); sp = (sp + 1) and 0xFFFF
        val hi = rd(sp); sp = (sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    private fun reg(i: Int): Int = when (i) {
        0 -> b; 1 -> c; 2 -> d; 3 -> e; 4 -> h; 5 -> l; 6 -> rd(hl); else -> a
    }

    private fun setReg(i: Int, v: Int) {
        val x = v and 0xFF
        when (i) { 0 -> b = x; 1 -> c = x; 2 -> d = x; 3 -> e = x; 4 -> h = x; 5 -> l = x; 6 -> wr(hl, x); else -> a = x }
    }

    // ---- ALU ----
    private fun add(v: Int, carry: Int = 0) {
        val r = a + v + carry
        hf = (a and 0xF) + (v and 0xF) + carry > 0xF
        cf = r > 0xFF
        a = r and 0xFF
        zf = a == 0; nf = false
    }

    private fun sub(v: Int, carry: Int = 0, store: Boolean = true) {
        val r = a - v - carry
        hf = (a and 0xF) - (v and 0xF) - carry < 0
        cf = r < 0
        zf = r and 0xFF == 0; nf = true
        if (store) a = r and 0xFF
    }

    private fun alu(op: Int, v: Int) {
        when (op) {
            0 -> add(v)
            1 -> add(v, if (cf) 1 else 0)
            2 -> sub(v)
            3 -> sub(v, if (cf) 1 else 0)
            4 -> { a = a and v; zf = a == 0; nf = false; hf = true; cf = false }
            5 -> { a = (a xor v) and 0xFF; zf = a == 0; nf = false; hf = false; cf = false }
            6 -> { a = (a or v) and 0xFF; zf = a == 0; nf = false; hf = false; cf = false }
            else -> sub(v, store = false)
        }
    }

    private fun inc8(v: Int): Int { val r = (v + 1) and 0xFF; zf = r == 0; nf = false; hf = v and 0xF == 0xF; return r }
    private fun dec8(v: Int): Int { val r = (v - 1) and 0xFF; zf = r == 0; nf = true; hf = v and 0xF == 0; return r }

    private fun addHl(v: Int) {
        val r = hl + v
        nf = false
        hf = (hl and 0xFFF) + (v and 0xFFF) > 0xFFF
        cf = r > 0xFFFF
        hl = r and 0xFFFF
    }

    private fun spPlusE8(): Int {
        val e8 = fetch().toByte().toInt()
        zf = false; nf = false
        hf = (sp and 0xF) + (e8 and 0xF) > 0xF
        cf = (sp and 0xFF) + (e8 and 0xFF) > 0xFF
        return (sp + e8) and 0xFFFF
    }

    private fun daa() {
        var x = a
        if (!nf) {
            if (cf || x > 0x99) { x += 0x60; cf = true }
            if (hf || (x and 0x0F) > 0x09) x += 0x06
        } else {
            if (cf) x -= 0x60
            if (hf) x -= 0x06
        }
        a = x and 0xFF
        zf = a == 0; hf = false
    }

    private fun cond(i: Int): Boolean = when (i) { 0 -> !zf; 1 -> zf; 2 -> !cf; else -> cf }

    // ---- Execution ----
    fun step(): Int {
        val pending = bus.ie and bus.iflag and 0x1F
        if (halted) {
            if (pending == 0) return 4
            halted = false
        }
        if (ime && pending != 0) {
            val bit = Integer.numberOfTrailingZeros(pending)
            bus.iflag = bus.iflag and (1 shl bit).inv()
            ime = false
            push(pc)
            pc = 0x40 + bit * 8
            return 20
        }
        if (imeDelay > 0 && --imeDelay == 0) ime = true
        return execute(fetch())
    }

    private fun execute(op: Int): Int {
        // LD r, r'
        if (op in 0x40..0x7F) {
            if (op == 0x76) { halt(); return 4 }
            val dst = (op shr 3) and 7
            val src = op and 7
            setReg(dst, reg(src))
            return if (dst == 6 || src == 6) 8 else 4
        }
        // ALU A, r
        if (op in 0x80..0xBF) {
            alu((op shr 3) and 7, reg(op and 7))
            return if (op and 7 == 6) 8 else 4
        }
        when (op) {
            0x00 -> return 4
            0x10 -> { fetch(); return 4 } // STOP
            0x01 -> { bc = fetch16(); return 12 }
            0x11 -> { de = fetch16(); return 12 }
            0x21 -> { hl = fetch16(); return 12 }
            0x31 -> { sp = fetch16(); return 12 }
            0x02 -> { wr(bc, a); return 8 }
            0x12 -> { wr(de, a); return 8 }
            0x22 -> { wr(hl, a); hl = (hl + 1) and 0xFFFF; return 8 }
            0x32 -> { wr(hl, a); hl = (hl - 1) and 0xFFFF; return 8 }
            0x0A -> { a = rd(bc); return 8 }
            0x1A -> { a = rd(de); return 8 }
            0x2A -> { a = rd(hl); hl = (hl + 1) and 0xFFFF; return 8 }
            0x3A -> { a = rd(hl); hl = (hl - 1) and 0xFFFF; return 8 }
            0x03 -> { bc = (bc + 1) and 0xFFFF; return 8 }
            0x13 -> { de = (de + 1) and 0xFFFF; return 8 }
            0x23 -> { hl = (hl + 1) and 0xFFFF; return 8 }
            0x33 -> { sp = (sp + 1) and 0xFFFF; return 8 }
            0x0B -> { bc = (bc - 1) and 0xFFFF; return 8 }
            0x1B -> { de = (de - 1) and 0xFFFF; return 8 }
            0x2B -> { hl = (hl - 1) and 0xFFFF; return 8 }
            0x3B -> { sp = (sp - 1) and 0xFFFF; return 8 }
            0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C -> {
                val r = (op shr 3) and 7
                setReg(r, inc8(reg(r)))
                return if (r == 6) 12 else 4
            }
            0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x35, 0x3D -> {
                val r = (op shr 3) and 7
                setReg(r, dec8(reg(r)))
                return if (r == 6) 12 else 4
            }
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E -> {
                val r = (op shr 3) and 7
                setReg(r, fetch())
                return if (r == 6) 12 else 8
            }
            0x07 -> { cf = a and 0x80 != 0; a = ((a shl 1) or (a shr 7)) and 0xFF; zf = false; nf = false; hf = false; return 4 }
            0x0F -> { cf = a and 1 != 0; a = ((a shr 1) or (a shl 7)) and 0xFF; zf = false; nf = false; hf = false; return 4 }
            0x17 -> { val c0 = if (cf) 1 else 0; cf = a and 0x80 != 0; a = ((a shl 1) or c0) and 0xFF; zf = false; nf = false; hf = false; return 4 }
            0x1F -> { val c0 = if (cf) 0x80 else 0; cf = a and 1 != 0; a = (a shr 1) or c0; zf = false; nf = false; hf = false; return 4 }
            0x08 -> { val addr = fetch16(); wr(addr, sp); wr(addr + 1, sp shr 8); return 20 }
            0x09 -> { addHl(bc); return 8 }
            0x19 -> { addHl(de); return 8 }
            0x29 -> { addHl(hl); return 8 }
            0x39 -> { addHl(sp); return 8 }
            0x18 -> { val e8 = fetch().toByte().toInt(); pc = (pc + e8) and 0xFFFF; return 12 }
            0x20, 0x28, 0x30, 0x38 -> {
                val e8 = fetch().toByte().toInt()
                if (cond((op shr 3) and 3)) { pc = (pc + e8) and 0xFFFF; return 12 }
                return 8
            }
            0x27 -> { daa(); return 4 }
            0x2F -> { a = a.inv() and 0xFF; nf = true; hf = true; return 4 }
            0x37 -> { nf = false; hf = false; cf = true; return 4 }
            0x3F -> { nf = false; hf = false; cf = !cf; return 4 }

            0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE -> { alu((op shr 3) and 7, fetch()); return 8 }

            0xC0, 0xC8, 0xD0, 0xD8 -> { if (cond((op shr 3) and 3)) { pc = pop(); return 20 }; return 8 }
            0xC9 -> { pc = pop(); return 16 }
            0xD9 -> { pc = pop(); ime = true; imeDelay = 0; return 16 }
            0xC2, 0xCA, 0xD2, 0xDA -> { val t = fetch16(); if (cond((op shr 3) and 3)) { pc = t; return 16 }; return 12 }
            0xC3 -> { pc = fetch16(); return 16 }
            0xE9 -> { pc = hl; return 4 }
            0xC4, 0xCC, 0xD4, 0xDC -> {
                val t = fetch16()
                if (cond((op shr 3) and 3)) { push(pc); pc = t; return 24 }
                return 12
            }
            0xCD -> { val t = fetch16(); push(pc); pc = t; return 24 }
            0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> { push(pc); pc = op and 0x38; return 16 }

            0xC1 -> { bc = pop(); return 12 }
            0xD1 -> { de = pop(); return 12 }
            0xE1 -> { hl = pop(); return 12 }
            0xF1 -> { af = pop(); return 12 }
            0xC5 -> { push(bc); return 16 }
            0xD5 -> { push(de); return 16 }
            0xE5 -> { push(hl); return 16 }
            0xF5 -> { push(af); return 16 }

            0xE0 -> { wr(0xFF00 + fetch(), a); return 12 }
            0xF0 -> { a = rd(0xFF00 + fetch()); return 12 }
            0xE2 -> { wr(0xFF00 + c, a); return 8 }
            0xF2 -> { a = rd(0xFF00 + c); return 8 }
            0xEA -> { wr(fetch16(), a); return 16 }
            0xFA -> { a = rd(fetch16()); return 16 }
            0xE8 -> { sp = spPlusE8(); return 16 }
            0xF8 -> { hl = spPlusE8(); return 12 }
            0xF9 -> { sp = hl; return 8 }

            0xF3 -> { ime = false; imeDelay = 0; return 4 }
            0xFB -> { if (!ime && imeDelay == 0) imeDelay = 1; return 4 }

            0xCB -> return executeCb(fetch())
            else -> return 4 // illegal opcodes lock up real hardware; treat as NOP
        }
    }

    private fun halt() {
        val pending = bus.ie and bus.iflag and 0x1F
        if (!ime && pending != 0) haltBug = true else halted = true
    }

    private fun executeCb(op: Int): Int {
        val r = op and 7
        val v = reg(r)
        val bit = (op shr 3) and 7
        when (op shr 6) {
            0 -> {
                val res = when (bit) {
                    0 -> { cf = v and 0x80 != 0; ((v shl 1) or (v shr 7)) and 0xFF }
                    1 -> { cf = v and 1 != 0; ((v shr 1) or (v shl 7)) and 0xFF }
                    2 -> { val c0 = if (cf) 1 else 0; cf = v and 0x80 != 0; ((v shl 1) or c0) and 0xFF }
                    3 -> { val c0 = if (cf) 0x80 else 0; cf = v and 1 != 0; (v shr 1) or c0 }
                    4 -> { cf = v and 0x80 != 0; (v shl 1) and 0xFF }
                    5 -> { cf = v and 1 != 0; (v shr 1) or (v and 0x80) }
                    6 -> { cf = false; ((v and 0x0F) shl 4) or (v shr 4) }
                    else -> { cf = v and 1 != 0; v shr 1 }
                }
                zf = res == 0; nf = false; hf = false
                setReg(r, res)
                return if (r == 6) 16 else 8
            }
            1 -> {
                zf = (v shr bit) and 1 == 0; nf = false; hf = true
                return if (r == 6) 12 else 8
            }
            2 -> { setReg(r, v and (1 shl bit).inv()); return if (r == 6) 16 else 8 }
            else -> { setReg(r, v or (1 shl bit)); return if (r == 6) 16 else 8 }
        }
    }

    fun save(out: DataOutputStream) {
        for (x in intArrayOf(a, f, b, c, d, e, h, l, sp, pc, imeDelay)) out.writeInt(x)
        out.writeBoolean(ime); out.writeBoolean(halted); out.writeBoolean(haltBug)
    }

    fun load(inp: DataInputStream) {
        a = inp.readInt(); f = inp.readInt(); b = inp.readInt(); c = inp.readInt(); d = inp.readInt()
        e = inp.readInt(); h = inp.readInt(); l = inp.readInt(); sp = inp.readInt(); pc = inp.readInt()
        imeDelay = inp.readInt()
        ime = inp.readBoolean(); halted = inp.readBoolean(); haltBug = inp.readBoolean()
    }
}
