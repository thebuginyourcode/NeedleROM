package com.needlerom.engine

import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.gb.Ppu
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Real ROMs are never committed. Integration tests look for them in the folder named by
 * NEEDLE_TEST_ROMS (e.g. pokered.gbc built from the pret/pokered disassembly, and the
 * Blargg test ROMs) and are skipped when it isn't set.
 */
object TestRoms {
    val dir: File? = System.getenv("NEEDLE_TEST_ROMS")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    fun file(name: String): File? = dir?.resolve(name)?.takeIf { it.isFile }

    fun bytes(name: String): ByteArray? = file(name)?.readBytes()

    private val SHADES = intArrayOf(0xE0F8D0, 0x88C070, 0x346856, 0x081820)

    /** Writes the current frame as a PNG (scaled 2x) for eyeballing test runs. */
    fun screenshot(gb: GameBoy, name: String) {
        val out = File(dir ?: return, "shots").apply { mkdirs() }
        val img = BufferedImage(Ppu.WIDTH * 2, Ppu.HEIGHT * 2, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until Ppu.HEIGHT) for (x in 0 until Ppu.WIDTH) {
            val c = SHADES[gb.bus.ppu.frame[y * Ppu.WIDTH + x].toInt()]
            img.setRGB(x * 2, y * 2, c); img.setRGB(x * 2 + 1, y * 2, c)
            img.setRGB(x * 2, y * 2 + 1, c); img.setRGB(x * 2 + 1, y * 2 + 1, c)
        }
        ImageIO.write(img, "png", File(out, "$name.png"))
    }
}
