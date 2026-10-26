package com.needlerom.engine

import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.project.Project
import com.needlerom.engine.rom.Bps
import com.needlerom.engine.rom.GbChecksum
import com.needlerom.engine.rom.Ips
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectTest {
    private fun edited(root: java.io.File, bytes: ByteArray): Project {
        val p = Project.create(root, "My hack", bytes)
        val g = p.open()
        g.world.map(0x26).addNpc(4, 2, 3, Movement.FACE_DOWN, Behavior.Talk("Saved!"))
        p.mapNames[0x26] = "Bedroom"
        p.testLevel = 12
        p.save(g)
        return p
    }

    @Test
    fun backupAndRestoreKeepEverything() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val root = Files.createTempDirectory("needle").toFile()
        val p = edited(root, bytes)
        java.io.File(p.dir, "play.sav").writeBytes(ByteArray(0x8000) { 7 })
        val zip = ByteArrayOutputStream().also { p.backup(it) }.toByteArray()

        val other = Files.createTempDirectory("needle2").toFile()
        val r = Project.restore(other, zip.inputStream())
        assertEquals("My hack", r.name)
        assertEquals("Bedroom", r.mapNames[0x26])
        assertEquals(12, r.testLevel)
        assertContentEquals(p.open().rom.data, r.open().rom.data)
        assertContentEquals(ByteArray(0x8000) { 7 }, r.saveFile.readBytes())
        assertTrue(r.open().exact, "still recognized as the original game with edits")
    }

    @Test
    fun patchesBecomeProjects() {
        val bytes = TestRoms.bytes("pokered.gbc") ?: return
        val root = Files.createTempDirectory("needle").toFile()
        val g = edited(root, bytes).open()
        val exported = g.rom.data.copyOf().also(GbChecksum::fix)
        for (patch in listOf(Bps.create(bytes, exported), Ips.create(bytes, exported))) {
            val p = Project.fromPatch(root, "From patch", bytes, patch)
            val h = p.open()
            assertTrue(h.exact)
            assertEquals(g.world.map(0x26).npcs.size, h.world.map(0x26).npcs.size, "the added NPC is there")
            assertEquals(Behavior.Talk("Saved!"), h.world.map(0x26).behavior(h.world.map(0x26).npcs.last().textId))
        }
    }
}

class PatchTest {
    @Test
    fun bpsAndIpsRoundTrip() {
        val r = java.util.Random(1)
        for (size in listOf(100, 1000, 70000, 1 shl 20)) {
            val a = ByteArray(size).also { r.nextBytes(it) }
            val b = a.copyOf(); for (k in 0 until 50) b[r.nextInt(size)] = r.nextInt().toByte()
            assertContentEquals(b, Bps.apply(a, Bps.create(a, b, "meta")).first, "BPS $size")
            if (size < (1 shl 24)) assertContentEquals(b, Ips.apply(a, Ips.create(a, b)), "IPS $size")
        }
    }
}
