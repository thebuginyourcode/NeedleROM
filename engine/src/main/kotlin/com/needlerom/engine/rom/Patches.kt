package com.needlerom.engine.rom

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class PatchException(message: String) : Exception(message)

/** IPS: the classic patch format (files up to 16 MB). */
object Ips {
    private const val EOF_MARK = 0x454F46

    fun create(source: ByteArray, target: ByteArray): ByteArray {
        if (target.size > 0x1000000) throw PatchException("IPS can't address files over 16 MB")
        val out = ByteArrayOutputStream()
        out.write("PATCH".toByteArray())
        fun differs(i: Int) = i >= source.size || source[i] != target[i]
        var i = 0
        while (i < target.size) {
            if (!differs(i)) { i++; continue }
            val start = if (i == EOF_MARK) i - 1 else i
            var end = i
            while (end < target.size && end - start < 0xFFFF) {
                if (differs(end)) { end++; continue }
                var gap = end
                while (gap < target.size && !differs(gap) && gap - end < 6) gap++
                if (gap < target.size && differs(gap) && gap - start < 0xFFFF) end = gap else break
            }
            val len = end - start
            out.write(start ushr 16); out.write(start ushr 8); out.write(start)
            out.write(len ushr 8); out.write(len)
            out.write(target, start, len)
            i = end
        }
        out.write("EOF".toByteArray())
        if (target.size < source.size) { val s = target.size; out.write(s ushr 16); out.write(s ushr 8); out.write(s) }
        return out.toByteArray()
    }

    fun apply(source: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 8 || String(patch, 0, 5) != "PATCH") throw PatchException("Not an IPS patch")
        fun u8(p: Int) = patch[p].toInt() and 0xFF
        var out = source.copyOf()
        fun ensure(n: Int) { if (n > out.size) out = out.copyOf(n) }
        var p = 5
        while (true) {
            if (p + 3 > patch.size) throw PatchException("IPS patch is truncated")
            val off = (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
            if (off == EOF_MARK) { p += 3; break }
            if (p + 5 > patch.size) throw PatchException("IPS patch is truncated")
            val size = (u8(p + 3) shl 8) or u8(p + 4)
            p += 5
            if (size == 0) {
                val run = (u8(p) shl 8) or u8(p + 1)
                ensure(off + run); out.fill(patch[p + 2], off, off + run); p += 3
            } else {
                if (p + size > patch.size) throw PatchException("IPS patch is truncated")
                ensure(off + size); patch.copyInto(out, off, p, p + size); p += size
            }
        }
        if (p + 3 <= patch.size) {
            val t = (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
            if (t < out.size) out = out.copyOf(t)
        }
        return out
    }
}

/** BPS: CRC-checked patches of any size. */
object Bps {
    private fun crc(b: ByteArray, len: Int = b.size) = CRC32().apply { update(b, 0, len) }.value

    private fun ByteArrayOutputStream.vlq(value: Long) {
        var d = value
        while (true) {
            val x = (d and 0x7F).toInt(); d = d ushr 7
            if (d == 0L) { write(0x80 or x); break }
            write(x); d--
        }
    }

    private fun ByteArrayOutputStream.u32(v: Long) { for (s in 0 until 4) write(((v shr (8 * s)) and 0xFF).toInt()) }

    fun create(source: ByteArray, target: ByteArray, metadata: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("BPS1".toByteArray())
        out.vlq(source.size.toLong()); out.vlq(target.size.toLong())
        val meta = metadata.toByteArray(); out.vlq(meta.size.toLong()); out.write(meta)
        fun same(i: Int) = i < source.size && source[i] == target[i]
        var i = 0
        while (i < target.size) {
            val start = i
            if (same(i)) {
                while (i < target.size && same(i)) i++
                out.vlq(((i - start - 1).toLong() shl 2))
            } else {
                while (i < target.size) {
                    if (!same(i)) { i++; continue }
                    var j = i
                    while (j < target.size && same(j) && j - i < 4) j++
                    if (j - i >= 4 || j == target.size) break
                    i = j
                }
                out.vlq(((i - start - 1).toLong() shl 2) or 1)
                out.write(target, start, i - start)
            }
        }
        out.u32(crc(source)); out.u32(crc(target))
        out.u32(crc(out.toByteArray()))
        return out.toByteArray()
    }

    fun apply(source: ByteArray, patch: ByteArray): Pair<ByteArray, String?> {
        if (patch.size < 16 || String(patch, 0, 4) != "BPS1") throw PatchException("Not a BPS patch")
        val end = patch.size - 12
        var p = 4
        fun vlq(): Long {
            var d = 0L; var shift = 1L
            while (true) {
                if (p >= end) throw PatchException("BPS patch is truncated")
                val x = patch[p++].toInt() and 0xFF
                d += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7; d += shift
            }
            return d
        }
        fun u32(at: Int): Long = (0 until 4).fold(0L) { acc, s -> acc or ((patch[at + s].toLong() and 0xFF) shl (8 * s)) }
        vlq()
        val targetSize = vlq().toInt()
        p += vlq().toInt()
        val warn = if (u32(end) != crc(source)) "This patch was made for a different ROM" else null
        if (u32(end + 8) != crc(patch, end + 8)) throw PatchException("BPS patch is corrupt")
        val out = ByteArray(targetSize)
        var o = 0; var srcRel = 0; var tgtRel = 0
        while (p < end) {
            val cmd = vlq()
            val len = ((cmd ushr 2) + 1).toInt()
            if (o + len > out.size) throw PatchException("BPS patch is corrupt")
            when ((cmd and 3).toInt()) {
                0 -> { repeat(len) { out[o] = source.getOrElse(o) { 0 }; o++ } }
                1 -> { patch.copyInto(out, o, p, p + len); p += len; o += len }
                2 -> { val d = vlq(); srcRel += (if (d and 1L != 0L) -(d ushr 1) else d ushr 1).toInt(); repeat(len) { out[o++] = source.getOrElse(srcRel++) { 0 } } }
                else -> { val d = vlq(); tgtRel += (if (d and 1L != 0L) -(d ushr 1) else d ushr 1).toInt(); repeat(len) { out[o++] = out[tgtRel++] } }
            }
        }
        return out to warn
    }
}

/** Game Boy header and global checksums. */
object GbChecksum {
    fun fix(d: ByteArray) {
        var x = 0
        for (i in 0x134..0x14C) x = x - (d[i].toInt() and 0xFF) - 1
        d[0x14D] = x.toByte()
        var sum = 0
        for (i in d.indices) if (i != 0x14E && i != 0x14F) sum += d[i].toInt() and 0xFF
        d[0x14E] = (sum shr 8).toByte()
        d[0x14F] = sum.toByte()
    }
}
