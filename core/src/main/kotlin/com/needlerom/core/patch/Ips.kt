package com.needlerom.core.patch

import java.io.ByteArrayOutputStream

class PatchException(message: String) : Exception(message)

/** IPS: the oldest and most widely supported patch format (24-bit offsets, max 16 MB). */
object Ips {
    private const val EOF_MARK = 0x454F46
    private const val MAX_OFFSET = 0xFFFFFF
    private const val MAX_CHUNK = 0xFFFF

    fun canCreate(target: ByteArray): Boolean = target.size <= MAX_OFFSET + 1

    fun create(source: ByteArray, target: ByteArray): ByteArray {
        if (!canCreate(target)) throw PatchException("IPS can't address files over 16 MB; use BPS instead")
        val out = ByteArrayOutputStream()
        out.write("PATCH".toByteArray(Charsets.US_ASCII))
        fun differs(i: Int) = i >= source.size || source[i] != target[i]
        var i = 0
        while (i < target.size) {
            if (!differs(i)) { i++; continue }
            var start = i
            // Records may not begin at an offset that spells "EOF".
            if (start == EOF_MARK) start--
            var end = i
            // Extend the run, bridging small identical gaps (cheaper than a new record header).
            while (end < target.size && end - start < MAX_CHUNK) {
                if (differs(end)) { end++; continue }
                var gap = end
                while (gap < target.size && !differs(gap) && gap - end < 6) gap++
                if (gap < target.size && differs(gap) && gap - start < MAX_CHUNK) end = gap else break
            }
            val len = end - start
            out.write(start ushr 16); out.write(start ushr 8); out.write(start)
            out.write(len ushr 8); out.write(len)
            out.write(target, start, len)
            i = end
        }
        out.write("EOF".toByteArray(Charsets.US_ASCII))
        if (target.size < source.size) {
            val s = target.size
            out.write(s ushr 16); out.write(s ushr 8); out.write(s)
        }
        return out.toByteArray()
    }

    fun apply(source: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 8 || String(patch, 0, 5, Charsets.US_ASCII) != "PATCH") {
            throw PatchException("Not an IPS patch")
        }
        fun u8(p: Int) = patch[p].toInt() and 0xFF
        var out = source.copyOf()
        fun ensure(size: Int) { if (size > out.size) out = out.copyOf(size) }
        var p = 5
        while (true) {
            if (p + 3 > patch.size) throw PatchException("IPS patch is truncated")
            val offset = (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
            if (offset == EOF_MARK) { p += 3; break }
            if (p + 5 > patch.size) throw PatchException("IPS patch is truncated")
            val size = (u8(p + 3) shl 8) or u8(p + 4)
            p += 5
            if (size == 0) {
                if (p + 3 > patch.size) throw PatchException("IPS patch is truncated")
                val run = (u8(p) shl 8) or u8(p + 1)
                val value = patch[p + 2]
                p += 3
                ensure(offset + run)
                out.fill(value, offset, offset + run)
            } else {
                if (p + size > patch.size) throw PatchException("IPS patch is truncated")
                ensure(offset + size)
                patch.copyInto(out, offset, p, p + size)
                p += size
            }
        }
        if (p + 3 <= patch.size) {
            val truncate = (u8(p) shl 16) or (u8(p + 1) shl 8) or u8(p + 2)
            if (truncate < out.size) out = out.copyOf(truncate)
        }
        return out
    }
}
