package com.needlerom.core.world

/** Game Boy banked addressing and 2bpp graphics. */
object Gb {
    const val BANK_SIZE = 0x4000

    /** Converts a bank + CPU address into a file offset. */
    fun toOffset(bank: Int, ptr: Int): Int = if (ptr < 0x4000) ptr else bank * BANK_SIZE + (ptr - 0x4000)

    fun bankOf(offset: Int): Int = offset / BANK_SIZE

    /** CPU address an offset is mapped to when its bank is switched in. */
    fun toPtr(offset: Int): Int = if (offset < BANK_SIZE) offset else 0x4000 + offset % BANK_SIZE

    fun bankEnd(bank: Int, size: Int): Int = minOf((bank + 1) * BANK_SIZE, size)

    fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF
    fun u16(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8)
    fun inRom(d: ByteArray, o: Int, len: Int = 1) = o >= 0 && o + len <= d.size

    /** Classic DMG green, lightest to darkest. */
    val SHADES = intArrayOf(0xFFE0F8D0.toInt(), 0xFF88C070.toInt(), 0xFF346856.toInt(), 0xFF081820.toInt())

    /** Background palette (BGP = %11100100): colour n is shade n. */
    val BG_PALETTE = SHADES

    /** Overworld sprite palette (OBP0 = %11010000); colour 0 is transparent. */
    val SPRITE_PALETTE = intArrayOf(0, SHADES[0], SHADES[1], SHADES[3])

    /** Draws one 8x8 2bpp tile from [d] at [tileOffset] into an ARGB buffer. */
    fun drawTile(d: ByteArray, tileOffset: Int, out: IntArray, outWidth: Int, x: Int, y: Int, palette: IntArray) {
        if (!inRom(d, tileOffset, 16)) return
        for (row in 0 until 8) {
            val lo = u8(d, tileOffset + row * 2)
            val hi = u8(d, tileOffset + row * 2 + 1)
            for (col in 0 until 8) {
                val bit = 7 - col
                val c = ((hi shr bit) and 1 shl 1) or ((lo shr bit) and 1)
                val argb = palette[c]
                if (argb != 0) out[(y + row) * outWidth + x + col] = argb
            }
        }
    }
}

/** Finds unused padding at the end of a ROM bank for relocated data. */
object FreeSpace {
    /**
     * Returns an offset in [bank] with [size] free bytes, keeping [margin] bytes of padding
     * on both sides. Only the run of 0x00/0xFF filler at the very end of the bank is used,
     * which is where assemblers leave unused space.
     */
    fun findInBank(d: ByteArray, bank: Int, size: Int, margin: Int = 16): Int? {
        val end = Gb.bankEnd(bank, d.size)
        if (end <= bank * Gb.BANK_SIZE) return null
        val fill = d[end - 1]
        if (fill != 0x00.toByte() && fill != 0xFF.toByte()) return null
        var start = end
        while (start > bank * Gb.BANK_SIZE && d[start - 1] == fill) start--
        val run = end - start
        return if (run >= size + margin * 2) start + margin else null
    }
}
