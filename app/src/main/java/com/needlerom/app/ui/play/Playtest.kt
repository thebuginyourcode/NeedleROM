package com.needlerom.app.ui.play

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.needlerom.engine.gb.GameBoy
import com.needlerom.engine.gb.Ppu
import java.io.File

/**
 * Runs a [GameBoy] on its own thread at ~59.7 fps, publishing frames as bitmaps and
 * streaming sound to an [AudioTrack].
 */
class Playtest(rom: ByteArray, private val saveFile: File, private val prepare: ((gb: GameBoy, cancelled: () -> Boolean) -> Unit)? = null) {
    val gb = GameBoy(rom, savedRam = saveFile.takeIf { it.exists() }?.readBytes())

    /** True while [prepare] (e.g. skipping the intro) runs before play starts. */
    @Volatile var preparing = prepare != null

    @Volatile var buttons = 0
    @Volatile var fastForward = false
    @Volatile var soundOn = true
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Called on the emulator thread with each finished frame. */
    var onFrame: ((Bitmap) -> Unit)? = null

    private val pixels = IntArray(Ppu.WIDTH * Ppu.HEIGHT)
    private val bitmaps = Array(2) { Bitmap.createBitmap(Ppu.WIDTH, Ppu.HEIGHT, Bitmap.Config.ARGB_8888) }
    private var which = 0

    private val audio: AudioTrack? = runCatching {
        val rate = gb.bus.apu.sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, rate)) // ~0.25 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

    fun start() {
        if (running) return
        running = true
        audio?.play()
        thread = Thread({ loop() }, "Playtest").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        audio?.pause()
        saveBattery()
    }

    fun release() {
        stop()
        audio?.release()
    }

    fun saveBattery() {
        if (preparing) return
        if (gb.cart.ram.isNotEmpty() && gb.cart.ramDirty) {
            synchronized(gb) { saveFile.writeBytes(gb.saveRam.copyOf()); gb.cart.ramDirty = false }
        }
    }

    fun saveState(f: File) { if (!preparing) synchronized(gb) { f.writeBytes(gb.saveState()) } }
    fun loadState(f: File) { if (!preparing) synchronized(gb) { gb.loadState(f.readBytes()) } }

    private fun loop() {
        prepare?.let { p ->
            synchronized(gb) { gb.bus.apu.muted = true; p(gb) { !running }; gb.bus.apu.drain(ShortArray(65536)) }
            preparing = false
        }
        val frameNs = 16_742_706L // 1 / 59.73 Hz
        val samples = ShortArray(8192)
        var next = System.nanoTime()
        var frames = 0
        while (running) {
            val n = if (fastForward) 4 else 1
            synchronized(gb) {
                gb.buttons = buttons
                gb.bus.apu.muted = !soundOn || fastForward
                repeat(n) { gb.runFrame() }
            }
            publish()
            // Audio paces nothing; we pace on the clock and drop audio if it backs up.
            val count = gb.bus.apu.drain(samples)
            if (count > 0 && !fastForward) audio?.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
            if (++frames % 600 == 0) saveBattery()
            next += frameNs
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            else if (wait < -frameNs * 10) next = System.nanoTime() // fell far behind: resync
        }
    }

    private fun publish() {
        val frame = gb.bus.ppu.frame
        for (i in pixels.indices) pixels[i] = SHADES[frame[i].toInt()]
        val bmp = bitmaps[which]
        which = 1 - which
        bmp.setPixels(pixels, 0, Ppu.WIDTH, 0, 0, Ppu.WIDTH, Ppu.HEIGHT)
        onFrame?.invoke(bmp)
    }

    companion object {
        val SHADES = intArrayOf(0xFFE0F8D0.toInt(), 0xFF88C070.toInt(), 0xFF346856.toInt(), 0xFF081820.toInt())
    }
}
