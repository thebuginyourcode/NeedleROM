package com.needlerom.core.mario

import com.needlerom.core.Bytes
import com.needlerom.core.Rom
import com.needlerom.core.RomInfo
import com.needlerom.core.SnesMapping

/**
 * Super Mario Bros. (NES). Locations come from the well known SMB disassembly and are
 * found by their code/data signature, so they work on clean dumps and most hacks.
 */
object Smb1 {
    // lda #$02 / sta NumberofLives ($075A) / sta OffScr_NumberofLives ($0761)
    private val LIVES_SIG = Bytes.of(0xA9, 0x02, 0x8D, 0x5A, 0x07, 0x8D, 0x61, 0x07)
    private val LIVES_SIG_ANY = Bytes.of(0x8D, 0x5A, 0x07, 0x8D, 0x61, 0x07)

    // PlayerColors: Mario, Luigi, Fiery (each: background + 3 sprite colours)
    private val PALETTE_SIG = Bytes.of(0x22, 0x16, 0x27, 0x18, 0x22, 0x1A, 0x30, 0x27)

    val paletteNames = listOf("Mario", "Luigi", "Fire flower")

    /** Offset of the "lives" immediate value (lives - 1), or null. */
    fun livesOffset(rom: Rom): Int? {
        rom.indexOf(LIVES_SIG).takeIf { it >= 0 }?.let { return it + 1 }
        // Already edited by us or another tool: look for any `lda #imm` before the stores.
        var at = rom.indexOf(LIVES_SIG_ANY)
        while (at >= 2) {
            if (rom.u8(at - 2) == 0xA9) return at - 1
            at = rom.indexOf(LIVES_SIG_ANY, at + 1)
        }
        return null
    }

    fun getLives(rom: Rom): Int? = livesOffset(rom)?.let { rom.u8(it) + 1 }

    fun setLives(rom: Rom, lives: Int) {
        val o = livesOffset(rom) ?: return
        rom.write8(o, (lives.coerceIn(1, 99)) - 1, "Starting lives = $lives")
    }

    /**
     * Start of the PlayerColors table. Once colours are edited the signature changes, so we
     * also remember the location of the original ROM's table.
     */
    fun paletteOffset(rom: Rom): Int? =
        Bytes.indexOf(rom.original, PALETTE_SIG).takeIf { it >= 0 }
            ?: rom.indexOf(PALETTE_SIG).takeIf { it >= 0 }

    /** 3 palettes x 4 NES colour indices. Index 0 of each is the shared background colour. */
    fun getPalettes(rom: Rom): List<IntArray>? {
        val o = paletteOffset(rom) ?: return null
        return (0 until 3).map { p -> IntArray(4) { rom.u8(o + p * 4 + it) } }
    }

    fun setColor(rom: Rom, palette: Int, slot: Int, nesColor: Int) {
        require(slot in 1..3) { "Slot 0 is the background colour" }
        val o = paletteOffset(rom) ?: return
        rom.write8(o + palette * 4 + slot, nesColor and 0x3F, "${paletteNames[palette]} colour $slot")
    }

    fun looksLikeSmb1(rom: Rom): Boolean = paletteOffset(rom) != null && livesOffset(rom) != null
}

/** Super Mario World (SNES). */
object Smw {
    /** SNES address $00:9E25 holds the starting lives minus one (`lda #$04`). */
    fun livesOffset(rom: Rom, info: RomInfo): Int? {
        if (info.snesMapping != SnesMapping.LOROM) return null
        val o = 0x1E25 + info.headerSize
        if (!rom.inBounds(o)) return null
        // Verify the instruction is still an immediate load before we touch it.
        if (rom.u8(o - 1) != 0xA9 || rom.u8(o) !in 0..98) return null
        return o
    }

    fun getLives(rom: Rom, info: RomInfo): Int? = livesOffset(rom, info)?.let { rom.u8(it) + 1 }

    fun setLives(rom: Rom, info: RomInfo, lives: Int) {
        val o = livesOffset(rom, info) ?: return
        rom.write8(o, lives.coerceIn(1, 99) - 1, "Starting lives = $lives")
    }
}

/** The common NES 2C02 palette as 0xRRGGBB, used for colour pickers. */
val NES_PALETTE: IntArray = intArrayOf(
    0x7C7C7C, 0x0000FC, 0x0000BC, 0x4428BC, 0x940084, 0xA80020, 0xA81000, 0x881400,
    0x503000, 0x007800, 0x006800, 0x005800, 0x004058, 0x000000, 0x000000, 0x000000,
    0xBCBCBC, 0x0078F8, 0x0058F8, 0x6844FC, 0xD800CC, 0xE40058, 0xF83800, 0xE45C10,
    0xAC7C00, 0x00B800, 0x00A800, 0x00A844, 0x008888, 0x000000, 0x000000, 0x000000,
    0xF8F8F8, 0x3CBCFC, 0x6888FC, 0x9878F8, 0xF878F8, 0xF85898, 0xF87858, 0xFCA044,
    0xF8B800, 0xB8F818, 0x58D854, 0x58F898, 0x00E8D8, 0x787878, 0x000000, 0x000000,
    0xFCFCFC, 0xA4E4FC, 0xB8B8F8, 0xD8B8F8, 0xF8B8F8, 0xF8A4C0, 0xF0D0B0, 0xFCE0A8,
    0xF8D878, 0xD8F878, 0xB8F8B8, 0xB8F8D8, 0x00FCFC, 0xF8D8F8, 0x000000, 0x000000,
)
