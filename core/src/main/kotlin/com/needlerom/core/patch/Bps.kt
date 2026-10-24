package com.needlerom.core.patch

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** BPS (beat) patches: any file size, with CRC32 checks that catch the wrong base ROM. */
object Bps {
    private const val SOURCE_READ = 0
    private const val TARGET_READ = 1
    private const val SOURCE_COPY = 2
    private const val TARGET_COPY = 3

    private fun crc(b: ByteArray, len: Int = b.size): Long = CRC32().apply { update(b, 0, len) }.value

    private fun ByteArrayOutputStream.vlq(value: Long) {
        var data = value
        while (true) {
            val x = (data and 0x7F).toInt()
            data = data ushr 7
            if (data == 0L) { write(0x80 or x); break }
            write(x)
            data--
        }
    }

    private fun ByteArrayOutputStream.u32le(v: Long) {
        write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
    }

    fun create(source: ByteArray, target: ByteArray, metadata: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("BPS1".toByteArray(Charsets.US_ASCII))
        out.vlq(source.size.toLong())
        out.vlq(target.size.toLong())
        val meta = metadata.toByteArray(Charsets.UTF_8)
        out.vlq(meta.size.toLong())
        out.write(meta)

        fun same(i: Int) = i < source.size && source[i] == target[i]
        var i = 0
        while (i < target.size) {
            val start = i
            if (same(i)) {
                while (i < target.size && same(i)) i++
                out.vlq(((i - start - 1).toLong() shl 2) or SOURCE_READ.toLong())
            } else {
                // Keep short identical gaps inside the literal run to avoid tiny commands.
                while (i < target.size) {
                    if (!same(i)) { i++; continue }
                    var j = i
                    while (j < target.size && same(j) && j - i < 4) j++
                    if (j - i >= 4 || j == target.size) break
                    i = j
                }
                out.vlq(((i - start - 1).toLong() shl 2) or TARGET_READ.toLong())
                out.write(target, start, i - start)
            }
        }
        out.u32le(crc(source))
        out.u32le(crc(target))
        val body = out.toByteArray()
        out.u32le(crc(body))
        return out.toByteArray()
    }

    data class Result(val output: ByteArray, val warning: String?)

    fun apply(source: ByteArray, patch: ByteArray): Result {
        if (patch.size < 16 || String(patch, 0, 4, Charsets.US_ASCII) != "BPS1") {
            throw PatchException("Not a BPS patch")
        }
        val end = patch.size - 12
        var p = 4
        fun vlq(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                if (p >= end) throw PatchException("BPS patch is truncated")
                val x = patch[p++].toInt() and 0xFF
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                data += shift
            }
            return data
        }
        fun u32(at: Int): Long =
            (patch[at].toLong() and 0xFF) or ((patch[at + 1].toLong() and 0xFF) shl 8) or
                ((patch[at + 2].toLong() and 0xFF) shl 16) or ((patch[at + 3].toLong() and 0xFF) shl 24)

        val sourceSize = vlq()
        val targetSize = vlq()
        if (targetSize > Int.MAX_VALUE - 8) throw PatchException("BPS target too large")
        val metaSize = vlq()
        p += metaSize.toInt()

        val warnings = mutableListOf<String>()
        if (sourceSize != source.size.toLong()) warnings += "Base ROM size doesn't match the patch"
        if (u32(end) != crc(source)) warnings += "Base ROM CRC doesn't match (this patch was made for a different ROM version)"
        if (u32(end + 8) != crc(patch, end + 8)) throw PatchException("BPS patch is corrupt (CRC mismatch)")

        val out = ByteArray(targetSize.toInt())
        var outPos = 0
        var srcRel = 0
        var tgtRel = 0
        while (p < end) {
            val cmd = vlq()
            val action = (cmd and 3).toInt()
            val length = ((cmd ushr 2) + 1).toInt()
            if (outPos + length > out.size) throw PatchException("BPS patch writes past the end of the target")
            when (action) {
                SOURCE_READ -> {
                    for (k in 0 until length) out[outPos + k] = source.getOrElse(outPos + k) { 0 }
                    outPos += length
                }
                TARGET_READ -> {
                    if (p + length > end) throw PatchException("BPS patch is truncated")
                    patch.copyInto(out, outPos, p, p + length)
                    p += length; outPos += length
                }
                SOURCE_COPY, TARGET_COPY -> {
                    val data = vlq()
                    val delta = (if (data and 1L != 0L) -(data ushr 1) else (data ushr 1)).toInt()
                    if (action == SOURCE_COPY) {
                        srcRel += delta
                        repeat(length) { out[outPos++] = source.getOrElse(srcRel++) { 0 } }
                    } else {
                        tgtRel += delta
                        repeat(length) {
                            if (tgtRel !in 0 until outPos) throw PatchException("BPS patch is corrupt")
                            out[outPos++] = out[tgtRel++]
                        }
                    }
                }
            }
        }
        if (u32(end + 4) != crc(out)) warnings += "Result CRC doesn't match what the patch expected"
        return Result(out, warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
