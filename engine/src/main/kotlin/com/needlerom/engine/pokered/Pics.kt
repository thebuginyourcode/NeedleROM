package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb
import com.needlerom.engine.rom.Rom

/**
 * A decompressed battle/intro picture: [widthTiles]×[heightTiles] tiles, [pixels] holding
 * shade 0-3 per pixel (row-major).
 */
class Pic(val widthTiles: Int, val heightTiles: Int, val pixels: IntArray) {
    val width get() = widthTiles * 8
    val height get() = heightTiles * 8

    /** The pixels centered in a 7×7-tile area, as the game shows them. */
    fun centered(): IntArray {
        val out = IntArray(56 * 56)
        val ox = (7 - widthTiles) / 2 * 8 + if ((7 - widthTiles) % 2 != 0) 8 else 0
        val oy = (7 - heightTiles) * 8
        for (y in 0 until height) for (x in 0 until width) {
            val tx = ox + x; val ty = oy + y
            if (tx in 0 until 56 && ty in 0 until 56) out[ty * 56 + tx] = pixels[y * width + x]
        }
        return out
    }
}

/** The game's picture compression (home/uncompress.asm), decoded. */
object Pics {
    private val T0 = intArrayOf(0x01, 0x32, 0x76, 0x45, 0xFE, 0xCD, 0x89, 0xBA)
    private val T1 = intArrayOf(0xFE, 0xCD, 0x89, 0xBA, 0x01, 0x32, 0x76, 0x45)

    fun decode(rom: Rom, bank: Int, ptr: Int): Pic? = runCatching { decode(rom.data, Gb.offset(bank, ptr)) }.getOrNull()

    fun decode(data: ByteArray, start: Int): Pic {
        var pos = start
        var bitsLeft = 0
        var cur = 0
        fun bit(): Int {
            if (bitsLeft == 0) { cur = data[pos++].toInt() and 0xFF; bitsLeft = 8 }
            bitsLeft--
            return (cur shr bitsLeft) and 1
        }
        val head = data[pos++].toInt() and 0xFF
        val hPx = (head and 0xF) * 8
        val wPx = (head shr 4) * 8
        require(wPx in 8..56 && hPx in 8..56) { "Not a picture" }
        val cols = wPx / 8
        val size = cols * hPx
        val buf = arrayOf(IntArray(size), IntArray(size))
        var useBuf2 = bit() == 1
        var last = false
        var mode = 0
        while (true) {
            val out = buf[if (useBuf2) 1 else 0]
            if (last) mode = if (bit() == 0) 0 else bit() + 1
            var ptr = 0; var cached = 0; var curX = 0; var curY = 0; var shift = 3
            var done = false
            fun write(v: Int) { out[ptr] = out[ptr] or (v shl (shift * 2)) }
            fun move() {
                curY++
                if (curY < hPx) { ptr++; return }
                curY = 0
                if (shift > 0) { shift--; ptr = cached; return }
                shift = 3
                curX += 8
                if (curX == wPx) { done = true; return }
                ptr++; cached = ptr
            }
            var zeros = bit() == 0
            while (!done) {
                if (zeros) {
                    var c = 0
                    while (bit() == 1) c++
                    var n = 0
                    repeat(c + 1) { n = (n shl 1) or bit() }
                    var count = n + (1 shl (c + 1)) - 1
                    while (count-- > 0 && !done) { write(0); move() }
                    zeros = false
                } else {
                    val v = (bit() shl 1) or bit()
                    if (v == 0) zeros = true else { write(v); move() }
                }
            }
            if (last) break
            useBuf2 = !useBuf2
            last = true
        }
        // useBuf2 now names the buffer the second chunk went to (the destination).
        val dest = buf[if (useBuf2) 1 else 0]
        val src = buf[if (useBuf2) 0 else 1]
        fun diff(b: IntArray) {
            for (y in 0 until hPx) {
                var e = 0
                for (x in 0 until cols) {
                    val i = x * hPx + y
                    fun nyb(n: Int): Int {
                        val t = if (e and 1 != 0) T1 else T0
                        val v = t[n shr 1]
                        e = if (n and 1 != 0) v and 0xF else v shr 4
                        return e
                    }
                    val hi = nyb(b[i] shr 4)
                    val lo = nyb(b[i] and 0xF)
                    b[i] = (hi shl 4) or lo
                }
            }
        }
        when (mode) {
            0 -> { diff(buf[0]); diff(buf[1]) }
            1 -> { diff(src); for (i in 0 until size) dest[i] = dest[i] xor src[i] }
            else -> { diff(dest); diff(src); for (i in 0 until size) dest[i] = dest[i] xor src[i] }
        }
        val px = IntArray(wPx * hPx)
        for (y in 0 until hPx) for (x in 0 until wPx) {
            val i = (x / 8) * hPx + y
            val m = 0x80 shr (x % 8)
            px[y * wPx + x] = (if (buf[0][i] and m != 0) 1 else 0) or (if (buf[1][i] and m != 0) 2 else 0)
        }
        return Pic(cols, hPx / 8, px)
    }
}
