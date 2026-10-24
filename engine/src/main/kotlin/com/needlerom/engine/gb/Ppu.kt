package com.needlerom.engine.gb

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * DMG picture processing unit, rendered one scanline at a time (accurate enough for
 * every commercial game that doesn't do mid-scanline raster tricks).
 */
class Ppu(private val requestInterrupt: (Int) -> Unit) {
    val vram = ByteArray(0x2000)
    val oam = ByteArray(0xA0)

    /** Shade index (0 = lightest .. 3 = darkest) for each of the 160x144 pixels. */
    val frame = ByteArray(WIDTH * HEIGHT)
    /** Set when a full frame has been drawn (start of VBlank). */
    var frameReady = false

    var lcdc = 0x91; private set
    var stat = 0x85; private set
    var scy = 0; var scx = 0
    var ly = 0; private set
    var lyc = 0
    var bgp = 0xFC; var obp0 = 0xFF; var obp1 = 0xFF
    var wy = 0; var wx = 0

    private var dot = 0
    private var windowLine = 0
    private var statLine = false

    private val lcdOn get() = lcdc and 0x80 != 0

    val mode: Int get() = stat and 3

    fun writeLcdc(v: Int) {
        val wasOn = lcdOn
        lcdc = v and 0xFF
        if (wasOn && !lcdOn) {
            ly = 0; dot = 0; windowLine = 0
            setMode(0)
        } else if (!wasOn && lcdOn) {
            dot = 0; ly = 0
            setMode(2)
            checkStat()
        }
    }

    fun readStat(): Int = (stat or 0x80) and (if (lcdOn) 0xFF else 0xFC)
    fun writeStat(v: Int) { stat = (stat and 0x07) or (v and 0x78); checkStat() }

    private fun setMode(m: Int) { stat = (stat and 0xFC) or m }

    private fun checkStat() {
        if (!lcdOn) { statLine = false; return }
        val coincidence = ly == lyc
        stat = if (coincidence) stat or 0x04 else stat and 0x04.inv()
        val m = mode
        val line = (coincidence && stat and 0x40 != 0) ||
            (m == 0 && stat and 0x08 != 0) || (m == 1 && stat and 0x10 != 0) || (m == 2 && stat and 0x20 != 0)
        if (line && !statLine) requestInterrupt(Interrupts.STAT)
        statLine = line
    }

    fun tick(cycles: Int) {
        if (!lcdOn) return
        dot += cycles
        while (true) {
            if (ly < 144) {
                val target = when {
                    dot < 80 -> 2
                    dot < 252 -> 3
                    dot < 456 -> 0
                    else -> -1
                }
                if (target == -1) { nextLine(); continue }
                if (target != mode) {
                    if (target == 0) renderLine()
                    setMode(target)
                    checkStat()
                }
                return
            } else {
                if (dot >= 456) { nextLine(); continue }
                return
            }
        }
    }

    private fun nextLine() {
        dot -= 456
        ly++
        if (ly == 144) {
            setMode(1)
            requestInterrupt(Interrupts.VBLANK)
            frameReady = true
        } else if (ly > 153) {
            ly = 0
            windowLine = 0
            setMode(2)
        } else if (ly < 144) {
            setMode(2)
        }
        checkStat()
    }

    private fun vram(addr: Int): Int = vram[addr - 0x8000].toInt() and 0xFF

    private val bgShadeIndex = IntArray(WIDTH)
    private val picked = IntArray(10)

    private fun renderLine() {
        val y = ly
        val base = y * WIDTH
        // Background + window
        val bgOn = lcdc and 0x01 != 0
        val winOn = bgOn && lcdc and 0x20 != 0 && wy <= y && wx <= 166
        val signedTiles = lcdc and 0x10 == 0
        var drewWindow = false
        for (x in 0 until WIDTH) {
            if (!bgOn) { bgShadeIndex[x] = 0; frame[base + x] = shade(bgp, 0).toByte(); continue }
            val useWin = winOn && x >= wx - 7
            val mapBase: Int
            val px: Int
            val py: Int
            if (useWin) {
                drewWindow = true
                mapBase = if (lcdc and 0x40 != 0) 0x9C00 else 0x9800
                px = x - (wx - 7)
                py = windowLine
            } else {
                mapBase = if (lcdc and 0x08 != 0) 0x9C00 else 0x9800
                px = (x + scx) and 0xFF
                py = (y + scy) and 0xFF
            }
            val tileNum = vram(mapBase + (py / 8) * 32 + (px / 8))
            val tileAddr = if (signedTiles) 0x9000 + (tileNum.toByte().toInt()) * 16 else 0x8000 + tileNum * 16
            val row = py % 8
            val lo = vram(tileAddr + row * 2)
            val hi = vram(tileAddr + row * 2 + 1)
            val bit = 7 - (px % 8)
            val c = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
            bgShadeIndex[x] = c
            frame[base + x] = shade(bgp, c).toByte()
        }
        if (drewWindow) windowLine++

        // Sprites
        if (lcdc and 0x02 == 0) return
        val tall = lcdc and 0x04 != 0
        val h = if (tall) 16 else 8
        var count = 0
        for (i in 0 until 40) {
            val sy = (oam[i * 4].toInt() and 0xFF) - 16
            if (y >= sy && y < sy + h) {
                picked[count++] = i
                if (count == 10) break
            }
        }
        // Lower X wins; ties go to the lower OAM index. Sort, then draw lowest priority first.
        for (a in 1 until count) {
            val v = picked[a]
            val vx = oam[v * 4 + 1].toInt() and 0xFF
            var b = a - 1
            while (b >= 0 && (oam[picked[b] * 4 + 1].toInt() and 0xFF) > vx) { picked[b + 1] = picked[b]; b-- }
            picked[b + 1] = v
        }
        for (n in count - 1 downTo 0) {
            val i = picked[n]
            val sy = (oam[i * 4].toInt() and 0xFF) - 16
            val sx = (oam[i * 4 + 1].toInt() and 0xFF) - 8
            var tile = oam[i * 4 + 2].toInt() and 0xFF
            val attr = oam[i * 4 + 3].toInt() and 0xFF
            if (tall) tile = tile and 0xFE
            var row = y - sy
            if (attr and 0x40 != 0) row = h - 1 - row
            val addr = 0x8000 + tile * 16 + row * 2
            val lo = vram(addr)
            val hi = vram(addr + 1)
            val pal = if (attr and 0x10 != 0) obp1 else obp0
            for (col in 0 until 8) {
                val x = sx + col
                if (x !in 0 until WIDTH) continue
                val bit = if (attr and 0x20 != 0) col else 7 - col
                val c = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (c == 0) continue
                if (attr and 0x80 != 0 && bgShadeIndex[x] != 0) continue
                frame[base + x] = shade(pal, c).toByte()
            }
        }
    }

    private fun shade(palette: Int, color: Int) = (palette shr (color * 2)) and 3

    fun save(out: DataOutputStream) {
        out.write(vram); out.write(oam)
        for (v in intArrayOf(lcdc, stat, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx, dot, windowLine)) out.writeInt(v)
        out.writeBoolean(statLine)
    }

    fun load(inp: DataInputStream) {
        inp.readFully(vram); inp.readFully(oam)
        lcdc = inp.readInt(); stat = inp.readInt(); scy = inp.readInt(); scx = inp.readInt(); ly = inp.readInt()
        lyc = inp.readInt(); bgp = inp.readInt(); obp0 = inp.readInt(); obp1 = inp.readInt(); wy = inp.readInt()
        wx = inp.readInt(); dot = inp.readInt(); windowLine = inp.readInt()
        statLine = inp.readBoolean()
    }

    companion object {
        const val WIDTH = 160
        const val HEIGHT = 144
    }
}
