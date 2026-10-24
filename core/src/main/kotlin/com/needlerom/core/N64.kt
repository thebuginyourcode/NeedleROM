package com.needlerom.core

import java.util.zip.CRC32

/** Nintendo 64 byte-order handling and boot CRC calculation (as in the classic n64crc tool). */
object N64 {

    fun isBigEndianN64(d: ByteArray): Boolean =
        d.size >= 4 && (d[0].toInt() and 0xFF) == 0x80 && d[1].toInt() == 0x37 &&
            d[2].toInt() == 0x12 && d[3].toInt() == 0x40

    /** Converts .v64 (byte-swapped) and .n64 (little-endian) dumps to .z64 big-endian. */
    fun normalizeByteOrder(d: ByteArray): Pair<ByteArray, String?> {
        if (d.size < 4 || d.size % 4 != 0) return d.copyOf() to null
        val b0 = d[0].toInt() and 0xFF
        val b1 = d[1].toInt() and 0xFF
        val b2 = d[2].toInt() and 0xFF
        val b3 = d[3].toInt() and 0xFF
        return when {
            b0 == 0x37 && b1 == 0x80 && b2 == 0x40 && b3 == 0x12 -> {
                val out = d.copyOf()
                for (i in out.indices step 2) { val t = out[i]; out[i] = out[i + 1]; out[i + 1] = t }
                out to "Converted from byte-swapped .v64 to .z64"
            }
            b0 == 0x40 && b1 == 0x12 && b2 == 0x37 && b3 == 0x80 -> {
                val out = d.copyOf()
                for (i in out.indices step 4) {
                    val a = out[i]; val b = out[i + 1]
                    out[i] = out[i + 3]; out[i + 1] = out[i + 2]; out[i + 2] = b; out[i + 3] = a
                }
                out to "Converted from little-endian .n64 to .z64"
            }
            else -> d.copyOf() to null
        }
    }

    private fun be32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    fun writeBe32(d: ByteArray, o: Int, v: Int) {
        d[o] = (v ushr 24).toByte(); d[o + 1] = (v ushr 16).toByte()
        d[o + 2] = (v ushr 8).toByte(); d[o + 3] = v.toByte()
    }

    fun detectCic(d: ByteArray): Int {
        if (d.size < 0x1000) return 6102
        val crc = CRC32().apply { update(d, 0x40, 0x1000 - 0x40) }.value
        return when (crc) {
            0x6170A4A1L -> 6101
            0x90BB6CB5L -> 6102
            0x0B050EE0L -> 6103
            0x98BC2C86L -> 6105
            0xACC8580AL -> 6106
            else -> 6102
        }
    }

    /** Returns (CRC1, CRC2) for the ROM, or null if it is too small to be a real N64 image. */
    fun computeCrc(d: ByteArray): Pair<Int, Int>? {
        val start = 0x1000
        val length = 0x100000
        if (d.size < start + length) return null
        val cic = detectCic(d)
        val seed = when (cic) {
            6103 -> 0xA3886759.toInt()
            6105 -> 0xDF26F436.toInt()
            6106 -> 0x1FEA617A
            else -> 0xF8CA4DDC.toInt()
        }
        var t1 = seed; var t2 = seed; var t3 = seed
        var t4 = seed; var t5 = seed; var t6 = seed
        var i = start
        while (i < start + length) {
            val dw = be32(d, i)
            if (Integer.compareUnsigned(t6 + dw, t6) < 0) t4++
            t6 += dw
            t3 = t3 xor dw
            val r = Integer.rotateLeft(dw, dw and 0x1F)
            t5 += r
            t2 = if (Integer.compareUnsigned(t2, dw) > 0) t2 xor r else t2 xor (t6 xor dw)
            t1 += if (cic == 6105) be32(d, 0x0750 + (i and 0xFF)) xor dw else t5 xor dw
            i += 4
        }
        return when (cic) {
            6103 -> ((t6 xor t4) + t3) to ((t5 xor t2) + t1)
            6106 -> ((t6 * t4) + t3) to ((t5 * t2) + t1)
            else -> (t6 xor t4 xor t3) to (t5 xor t2 xor t1)
        }
    }
}
