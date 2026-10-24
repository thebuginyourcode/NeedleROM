package com.needlerom.core

enum class Platform(val label: String, val shortName: String) {
    NES("Nintendo Entertainment System", "NES"),
    SNES("Super Nintendo", "SNES"),
    GB("Game Boy", "GB"),
    GBC("Game Boy Color", "GBC"),
    GBA("Game Boy Advance", "GBA"),
    N64("Nintendo 64", "N64"),
    NDS("Nintendo DS", "NDS"),
    UNKNOWN("Unknown system", "???"),
}

enum class SnesMapping { LOROM, HIROM }

data class RomInfo(
    val platform: Platform,
    val title: String,
    val gameCode: String,
    /** Size of a copier header in front of the real ROM (SNES SMC headers, iNES header). */
    val headerSize: Int = 0,
    val snesMapping: SnesMapping? = null,
    val details: List<Pair<String, String>> = emptyList(),
)

object SystemDetector {
    private val GB_LOGO = Bytes.of(0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B)
    // The GBA and DS share the same compressed Nintendo logo.
    private val GBA_LOGO = Bytes.of(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)

    fun detect(rom: Rom): RomInfo {
        val d = rom.data
        return when {
            d.size >= 0x40 && N64.isBigEndianN64(d) -> n64(rom)
            d.size >= 16 && d[0] == 'N'.code.toByte() && d[1] == 'E'.code.toByte() &&
                d[2] == 'S'.code.toByte() && d[3] == 0x1A.toByte() -> nes(rom)
            d.size >= 0xC0 && matchesAt(d, 0x04, GBA_LOGO) -> gba(rom)
            d.size >= 0x200 && matchesAt(d, 0xC0, GBA_LOGO) -> nds(rom)
            d.size >= 0x150 && matchesAt(d, 0x104, GB_LOGO) -> gb(rom)
            else -> snes(rom) ?: RomInfo(
                Platform.UNKNOWN, rom.fileName.substringBeforeLast('.'), "",
                details = listOf("Size" to sizeLabel(d.size)),
            )
        }
    }

    private fun matchesAt(d: ByteArray, at: Int, sig: ByteArray): Boolean {
        if (at + sig.size > d.size) return false
        for (i in sig.indices) if (d[at + i] != sig[i]) return false
        return true
    }

    fun ascii(rom: Rom, offset: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 0 until length) {
            if (!rom.inBounds(offset + i)) break
            val c = rom.u8(offset + i)
            if (c == 0) break
            sb.append(if (c in 0x20..0x7E) c.toChar() else ' ')
        }
        return sb.toString().trim()
    }

    fun sizeLabel(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }

    private fun nes(rom: Rom): RomInfo {
        val prg = rom.u8(4) * 16
        val chr = rom.u8(5) * 8
        val mapper = (rom.u8(6) shr 4) or (rom.u8(7) and 0xF0)
        return RomInfo(
            Platform.NES, rom.fileName.substringBeforeLast('.'), "", headerSize = 16,
            details = listOf(
                "Format" to "iNES",
                "PRG ROM" to "$prg KB",
                "CHR ROM" to if (chr == 0) "CHR RAM" else "$chr KB",
                "Mapper" to mapper.toString(),
                "Mirroring" to if (rom.u8(6) and 1 == 1) "Vertical" else "Horizontal",
                "Battery" to if (rom.u8(6) and 2 != 0) "Yes" else "No",
            ),
        )
    }

    private fun gb(rom: Rom): RomInfo {
        val cgbFlag = rom.u8(0x143)
        val isColor = cgbFlag == 0x80 || cgbFlag == 0xC0
        val title = ascii(rom, 0x134, if (isColor) 15 else 16)
        val headerOk = Checksums.gbHeader(rom.data) == rom.u8(0x14D)
        val code = if (isColor) ascii(rom, 0x13F, 4).takeIf { it.length == 4 } ?: "" else ""
        return RomInfo(
            if (isColor) Platform.GBC else Platform.GB, title, code,
            details = listOf(
                "Title" to title,
                "Color support" to when (cgbFlag) { 0xC0 -> "GBC only"; 0x80 -> "GB + GBC"; else -> "No" },
                "Super Game Boy" to if (rom.u8(0x146) == 0x03) "Yes" else "No",
                "Cartridge type" to "0x${Bytes.hex(rom.u8(0x147))}",
                "Size" to sizeLabel(rom.size),
                "Header checksum" to if (headerOk) "Valid" else "Invalid",
            ),
        )
    }

    private fun gba(rom: Rom): RomInfo {
        val title = ascii(rom, 0xA0, 12)
        val code = ascii(rom, 0xAC, 4)
        val maker = ascii(rom, 0xB0, 2)
        val ok = Checksums.gbaComplement(rom.data) == rom.u8(0xBD)
        return RomInfo(
            Platform.GBA, title, code,
            details = listOf(
                "Title" to title, "Game code" to code, "Maker" to maker,
                "Version" to rom.u8(0xBC).toString(),
                "Size" to sizeLabel(rom.size),
                "Header checksum" to if (ok) "Valid" else "Invalid",
            ),
        )
    }

    private fun nds(rom: Rom): RomInfo {
        val title = ascii(rom, 0x00, 12)
        val code = ascii(rom, 0x0C, 4)
        val ok = Checksums.ndsHeaderCrc(rom.data) == rom.u16le(0x15E)
        return RomInfo(
            Platform.NDS, title, code,
            details = listOf(
                "Title" to title, "Game code" to code, "Maker" to ascii(rom, 0x10, 2),
                "Size" to sizeLabel(rom.size),
                "Header CRC" to if (ok) "Valid" else "Invalid",
                "Note" to "DS games store data in compressed archives; use hex / relative search tools.",
            ),
        )
    }

    private fun n64(rom: Rom): RomInfo {
        val title = ascii(rom, 0x20, 20)
        val code = ascii(rom, 0x3B, 4)
        val crc = N64.computeCrc(rom.data)
        val stored = rom.u32be(0x10) to rom.u32be(0x14)
        val details = mutableListOf(
            "Title" to title, "Game code" to code,
            "Size" to sizeLabel(rom.size),
            "Boot chip" to "CIC-${N64.detectCic(rom.data)}",
            "CRC" to if (crc != null && crc == stored) "Valid" else "Needs fixing",
        )
        rom.loadNote?.let { details += "Loaded as" to it }
        return RomInfo(Platform.N64, title, code, details = details)
    }

    private fun snes(rom: Rom): RomInfo? {
        val size = rom.size
        if (size < 0x8000) return null
        val copier = if (size % 1024 == 512) 512 else 0
        data class Candidate(val mapping: SnesMapping, val base: Int, val score: Int)
        val candidates = listOf(SnesMapping.LOROM to 0x7FC0, SnesMapping.HIROM to 0xFFC0).mapNotNull { (m, b) ->
            val base = b + copier
            if (!rom.inBounds(base, 0x20)) return@mapNotNull null
            var score = 0
            val comp = rom.u16le(base + 0x1C)
            val sum = rom.u16le(base + 0x1E)
            if ((comp xor sum) == 0xFFFF) score += 4
            val mode = rom.u8(base + 0x15)
            if (m == SnesMapping.LOROM && (mode and 0xEF) == 0x20) score += 2
            if (m == SnesMapping.HIROM && ((mode and 0xEF) == 0x21 || (mode and 0xEF) == 0x25)) score += 2
            val printable = (0 until 21).count { rom.u8(base + it) in 0x20..0x7E }
            if (printable >= 19) score += 2
            Candidate(m, base, score)
        }
        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.score < 4) return null
        val title = ascii(rom, best.base, 21)
        val stored = rom.u16le(best.base + 0x1E)
        return RomInfo(
            Platform.SNES, title, "", headerSize = copier, snesMapping = best.mapping,
            details = listOf(
                "Title" to title,
                "Mapping" to if (best.mapping == SnesMapping.LOROM) "LoROM" else "HiROM",
                "Copier header" to if (copier > 0) "Yes (512 bytes)" else "No",
                "Size" to sizeLabel(size - copier),
                "Checksum" to if (Checksums.snes(rom.data, copier) == stored) "Valid" else "Needs fixing",
            ),
        )
    }
}
