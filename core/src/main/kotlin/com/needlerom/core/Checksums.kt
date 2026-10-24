package com.needlerom.core

/**
 * Header/global checksums for each supported system. Edits made by the tools can
 * invalidate these, and some emulators and all real hardware/flash carts care.
 */
object Checksums {

    fun gbHeader(d: ByteArray): Int {
        var x = 0
        for (i in 0x134..0x14C) x = x - (d[i].toInt() and 0xFF) - 1
        return x and 0xFF
    }

    fun gbGlobal(d: ByteArray): Int {
        var sum = 0
        for (i in d.indices) if (i != 0x14E && i != 0x14F) sum += d[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    fun gbaComplement(d: ByteArray): Int {
        var chk = 0
        for (i in 0xA0..0xBC) chk -= d[i].toInt() and 0xFF
        return (chk - 0x19) and 0xFF
    }

    /** CRC-16/MODBUS of the DS header, as stored at 0x15E. */
    fun ndsHeaderCrc(d: ByteArray): Int {
        var crc = 0xFFFF
        for (i in 0 until 0x15E) {
            crc = crc xor (d[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    /**
     * SNES internal checksum (16-bit byte sum). Non power-of-two ROMs mirror their
     * trailing part up to the next power of two, as the official tools did.
     */
    fun snes(d: ByteArray, copierHeader: Int, headerBase: Int? = null): Int {
        val size = d.size - copierHeader
        val base = headerBase ?: snesHeaderBase(d, copierHeader)
        fun byteAt(i: Int): Int {
            // Treat the checksum fields as complement=0xFFFF, checksum=0x0000.
            val rel = i - (base - copierHeader)
            return when (rel) {
                0x1C, 0x1D -> 0xFF
                0x1E, 0x1F -> 0x00
                else -> d[copierHeader + i].toInt() and 0xFF
            }
        }
        val main = Integer.highestOneBit(size)
        if (main == size) {
            var sum = 0L
            for (i in 0 until size) sum += byteAt(i)
            return (sum and 0xFFFF).toInt()
        }
        var sum = 0L
        for (i in 0 until main) sum += byteAt(i)
        val rest = size - main
        var restSum = 0L
        for (i in main until size) restSum += byteAt(i)
        // Mirror the remainder until it fills the same size as the main block.
        var mirrored = rest
        var restTotal = restSum
        while (mirrored < main) { restTotal += restSum; mirrored += rest }
        return ((sum + restTotal) and 0xFFFF).toInt()
    }

    fun snesHeaderBase(d: ByteArray, copierHeader: Int): Int {
        val lo = 0x7FC0 + copierHeader
        val hi = 0xFFC0 + copierHeader
        fun valid(b: Int) = b + 0x20 <= d.size &&
            (((d[b + 0x1C].toInt() and 0xFF) or ((d[b + 0x1D].toInt() and 0xFF) shl 8)) xor
                ((d[b + 0x1E].toInt() and 0xFF) or ((d[b + 0x1F].toInt() and 0xFF) shl 8))) == 0xFFFF
        return when {
            valid(lo) -> lo
            valid(hi) -> hi
            hi + 0x20 <= d.size && (d[hi + 0x15].toInt() and 0xEF) in listOf(0x21, 0x25) -> hi
            else -> lo
        }
    }

    /**
     * Recomputes and writes every checksum the platform uses. Operates on [d] in place
     * and returns human readable notes about what was updated.
     */
    fun fixAll(d: ByteArray, info: RomInfo): List<String> {
        val notes = mutableListOf<String>()
        when (info.platform) {
            Platform.GB, Platform.GBC -> {
                if (d.size < 0x150) return notes
                d[0x14D] = gbHeader(d).toByte()
                val g = gbGlobal(d)
                d[0x14E] = (g shr 8).toByte()
                d[0x14F] = g.toByte()
                notes += "Game Boy header + global checksum updated"
            }
            Platform.GBA -> {
                if (d.size < 0xC0) return notes
                d[0xBD] = gbaComplement(d).toByte()
                notes += "GBA header complement updated"
            }
            Platform.NDS -> {
                if (d.size < 0x160) return notes
                val c = ndsHeaderCrc(d)
                d[0x15E] = c.toByte()
                d[0x15F] = (c shr 8).toByte()
                notes += "DS header CRC updated"
            }
            Platform.SNES -> {
                val base = snesHeaderBase(d, info.headerSize)
                if (base + 0x20 > d.size) return notes
                val sum = snes(d, info.headerSize, base)
                val comp = sum xor 0xFFFF
                d[base + 0x1C] = comp.toByte(); d[base + 0x1D] = (comp shr 8).toByte()
                d[base + 0x1E] = sum.toByte(); d[base + 0x1F] = (sum shr 8).toByte()
                notes += "SNES checksum updated"
            }
            Platform.N64 -> {
                val crc = N64.computeCrc(d) ?: return notes
                N64.writeBe32(d, 0x10, crc.first)
                N64.writeBe32(d, 0x14, crc.second)
                notes += "N64 CRC1/CRC2 updated (CIC-${N64.detectCic(d)})"
            }
            Platform.NES, Platform.UNKNOWN -> {}
        }
        return notes
    }
}
