package com.needlerom.core

import com.needlerom.core.mario.Smb1
import com.needlerom.core.patch.Bps
import com.needlerom.core.patch.Ips
import com.needlerom.core.pokemon.PokemonEditor
import com.needlerom.core.pokemon.PokemonGen
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {

    private fun put(d: ByteArray, at: Int, vararg v: Int) = v.forEachIndexed { i, b -> d[at + i] = b.toByte() }
    private fun put(d: ByteArray, at: Int, b: ByteArray) = b.copyInto(d, at)

    private fun fakeGen1(): ByteArray {
        val d = ByteArray(0x10000)
        put(d, 0x104, 0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B)
        put(d, 0x134, "POKEMON RED".toByteArray())
        // Base stats: Bulbasaur at 0x4000, Ivysaur right after.
        put(d, 0x4000, 0x01, 45, 49, 49, 45, 65, 0x16, 0x03, 45, 64)
        put(d, 0x4000 + 28, 0x02, 60, 62, 63, 60, 80, 0x16, 0x03, 45, 141)
        put(d, 0x9000, 151, 100, 100, 100, 100, 100, 0x18, 0x18, 45, 64)
        // Name table (internal order): RHYDON, KANGASKHAN
        val t = TextTable.POKEMON_GB
        val names = ByteArray(20) { 0x50 }
        t.encode("RHYDON")!!.copyInto(names, 0)
        t.encode("KANGASKHAN")!!.copyInto(names, 10)
        put(d, 0x7000, names)
        // Some dialogue
        put(d, 0x8000, t.encode("Hello there! Welcome to the world of POKEMON!")!!)
        return d
    }

    private fun fakeGen3(): ByteArray {
        val d = ByteArray(0x40000)
        put(d, 0x04, 0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)
        put(d, 0xA0, "POKEMON FIRE".toByteArray())
        put(d, 0xAC, "BPRE".toByteArray())
        put(d, 0x10000 + 28, 45, 49, 49, 45, 65, 65, 0x0C, 0x03, 45, 64)
        put(d, 0x10000 + 56, 60, 62, 63, 60, 80, 80, 0x0C, 0x03, 45, 141)
        val t = TextTable.POKEMON_GBA
        val names = ByteArray(33) { 0xFF.toByte() }
        t.encode("??????????")!!.copyInto(names, 0)
        t.encode("BULBASAUR")!!.copyInto(names, 11)
        t.encode("IVYSAUR")!!.copyInto(names, 22)
        put(d, 0x20000, names)
        return d
    }

    @Test
    fun detectsGameBoyPokemonAndLocatesTables() {
        val rom = Rom("red.gb", fakeGen1())
        val s = HackSession(rom, Mode.OPTIMIZED, GameProfiles.byId("pkmn_rbg"))
        assertEquals(Platform.GB, s.info.platform)
        assertEquals("pkmn_rbg", s.detectedProfile?.id)
        val ed = s.pokemon!!
        assertEquals(0x4000, ed.bulbasaurStats)
        assertEquals(0x7000, ed.nameTable)
        val species = ed.species()
        assertEquals("Bulbasaur", species[0].name)
        assertEquals("Mew", species.last().name)
        assertEquals(0x9000, species.last().statsOffset)
        val b = ed.read(species[0])
        assertEquals(45, b.hp); assertEquals(65, b.spAtk); assertEquals(65, b.spDef)
        assertEquals("Grass", PokemonGen.GEN1.typeName(b.type1))

        ed.write(species[0], b.copy(hp = 99, spAtk = 120, catchRate = 255))
        val b2 = ed.read(species[0])
        assertEquals(99, b2.hp); assertEquals(120, b2.spAtk); assertEquals(255, b2.catchRate)
        assertEquals(0x16, b2.type1) // untouched fields stay intact
        assertTrue(s.toolStates().filter { it.tool != Tool.WORLD_BUILDER }.all { it.available })
    }

    @Test
    fun renamesInGameFont() {
        val rom = Rom("red.gb", fakeGen1())
        val ed = PokemonEditor(rom, PokemonGen.GEN1)
        val names = ed.names()
        assertEquals("RHYDON", names[0].name)
        assertEquals("KANGASKHAN", names[1].name)
        assertNull(ed.rename(names[0], "DRILLER"))
        assertEquals("DRILLER", ed.names()[0].name)
        assertEquals("Max 10 characters", ed.rename(names[0], "WAYTOOLONGNAME"))
    }

    @Test
    fun gen3TablesAndNames() {
        val rom = Rom("fr.gba", fakeGen3())
        val s = HackSession(rom, Mode.OPTIMIZED, GameProfiles.byId("pkmn_frlg"))
        assertEquals(Platform.GBA, s.info.platform)
        assertEquals("pkmn_frlg", s.detectedProfile?.id)
        val ed = s.pokemon!!
        assertEquals(0x20000, ed.nameTable)
        val sp = ed.species()
        assertEquals("Bulbasaur", sp[0].name)
        assertEquals("Ivysaur", sp[1].name)
        assertEquals(1, ed.maxCatchRates() .coerceAtMost(1))
        assertEquals(255, ed.read(sp[0]).catchRate)
    }

    @Test
    fun randomizerKeepsStatTotals() {
        val rom = Rom("fr.gba", fakeGen3())
        val ed = PokemonEditor(rom, PokemonGen.GEN3)
        val sp = ed.species()[0]
        val before = ed.read(sp).total(true)
        ed.randomizeStats(42)
        val after = ed.read(sp).total(true)
        assertTrue(kotlin.math.abs(before - after) <= 6, "BST drifted: $before -> $after")
    }

    @Test
    fun textFindAndReplace() {
        val rom = Rom("red.gb", fakeGen1())
        val t = TextTable.POKEMON_GB
        val hits = TextTools.findAll(rom, t, "POKEMON!")
        assertEquals(1, hits.size)
        val r = TextTools.replace(rom, t, "POKEMON!", "MONSTER", hits.map { it.offset })
        assertEquals(TextTools.ReplaceResult.Done(1), r)
        assertEquals(1, TextTools.findAll(rom, t, "MONSTER ").size)
        val tooLong = TextTools.replace(rom, t, "Hello", "Greetings", listOf(0x8000))
        assertTrue(tooLong is TextTools.ReplaceResult.Error)
    }

    @Test
    fun relativeSearchFindsUnknownEncoding() {
        val d = ByteArray(4096)
        // A made-up table where 'A' = 0x31
        "THANK YOU MARIO".forEachIndexed { i, c -> d[1000 + i] = (if (c == ' ') 0xFF else 0x31 + (c - 'A')).toByte() }
        val rom = Rom("x.bin", d)
        val m = RelativeSearch.search(rom, "MARIO")
        assertEquals(1, m.size)
        assertEquals(1010, m[0].offset)
        assertEquals(0x31, m[0].valueOfA)
        assertNull(RelativeSearch.replace(rom, m[0], 5, "LUIGI"))
        assertEquals(1, RelativeSearch.search(rom, "LUIGI").size)
    }

    @Test
    fun gameBoyChecksumsFix() {
        val d = fakeGen1()
        val rom = Rom("red.gb", d)
        val info = SystemDetector.detect(rom)
        val out = rom.data.copyOf()
        Checksums.fixAll(out, info)
        assertEquals(Checksums.gbHeader(out), out[0x14D].toInt() and 0xFF)
        val g = ((out[0x14E].toInt() and 0xFF) shl 8) or (out[0x14F].toInt() and 0xFF)
        assertEquals(Checksums.gbGlobal(out), g)
    }

    @Test
    fun ipsAndBpsRoundTrip() {
        val rnd = Random(7)
        val src = ByteArray(200_000).also { rnd.nextBytes(it) }
        val tgt = src.copyOf(210_000)
        for (i in 0 until 500) tgt[rnd.nextInt(tgt.size)] = rnd.nextInt().toByte()
        for (i in 200_000 until 210_000) tgt[i] = rnd.nextInt().toByte()
        // Force a change exactly at the IPS "EOF" offset.
        tgt[0x454F46 % tgt.size] = (tgt[0x454F46 % tgt.size] + 1).toByte()
        assertContentEquals(tgt, Ips.apply(src, Ips.create(src, tgt)))
        val bps = Bps.apply(src, Bps.create(src, tgt))
        assertNull(bps.warning)
        assertContentEquals(tgt, bps.output)

        val shorter = src.copyOf(150_000).also { it[10] = 1; it[11] = 2 }
        assertContentEquals(shorter, Ips.apply(src, Ips.create(src, shorter)))
        assertContentEquals(shorter, Bps.apply(src, Bps.create(src, shorter)).output)
    }

    @Test
    fun ipsHandlesEofOffset() {
        val src = ByteArray(0x460000)
        val tgt = src.copyOf()
        tgt[0x454F46] = 9
        val patched = Ips.apply(src, Ips.create(src, tgt))
        assertContentEquals(tgt, patched)
    }

    @Test
    fun smb1Tools() {
        val d = ByteArray(16 + 32768 + 8192)
        put(d, 0, 'N'.code, 'E'.code, 'S'.code, 0x1A, 2, 1)
        put(d, 0x1000, 0xA9, 0x02, 0x8D, 0x5A, 0x07, 0x8D, 0x61, 0x07)
        put(d, 0x2000, 0x22, 0x16, 0x27, 0x18, 0x22, 0x1A, 0x30, 0x27, 0x22, 0x37, 0x27, 0x16)
        val rom = Rom("smb.nes", d)
        val s = HackSession(rom, Mode.AUTO, null)
        assertEquals(Platform.NES, s.info.platform)
        assertEquals("smb1", s.detectedProfile?.id)
        assertEquals(3, Smb1.getLives(rom))
        Smb1.setLives(rom, 9)
        assertEquals(9, Smb1.getLives(rom))
        Smb1.setColor(rom, 0, 1, 0x2A)
        assertEquals(0x2A, Smb1.getPalettes(rom)!![0][1])
        assertNotNull(Smb1.paletteOffset(rom))
    }

    @Test
    fun n64ByteOrderAndCrc() {
        val z64 = ByteArray(0x101000 + 0x1000)
        Random(3).nextBytes(z64)
        put(z64, 0, 0x80, 0x37, 0x12, 0x40)
        put(z64, 0x20, "SUPER MARIO 64      ".toByteArray())
        val v64 = z64.copyOf().also { for (i in it.indices step 2) { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t } }
        val rom = Rom("sm64.v64", v64)
        assertContentEquals(z64, rom.original)
        val s = HackSession(rom, Mode.AUTO, null)
        assertEquals(Platform.N64, s.info.platform)
        assertEquals("sm64", s.detectedProfile?.id)
        val (fixed, notes) = s.exportBytes(true)
        assertTrue(notes.isNotEmpty())
        val crc = N64.computeCrc(fixed)!!
        assertEquals(crc.first, Rom("a.z64", fixed).u32be(0x10))
    }

    @Test
    fun snesDetectionWithCopierHeader() {
        val d = ByteArray(512 + 0x80000)
        val base = 512 + 0x7FC0
        put(d, base, "SUPER MARIOWORLD     ".toByteArray())
        d[base + 0x15] = 0x20
        put(d, 512 + 0x1E24, 0xA9, 0x04)
        // Give it a valid checksum pair so it is recognised.
        put(d, base + 0x1C, 0xFF, 0xFF, 0x00, 0x00)
        val rom = Rom("smw.smc", d)
        val s = HackSession(rom, Mode.OPTIMIZED, GameProfiles.byId("smw"))
        assertEquals(Platform.SNES, s.info.platform)
        assertEquals(512, s.info.headerSize)
        assertEquals("smw", s.detectedProfile?.id)
        assertEquals(5, com.needlerom.core.mario.Smw.getLives(rom, s.info))
        val (fixed, _) = s.exportBytes(true)
        val sum = (fixed[base + 0x1E].toInt() and 0xFF) or ((fixed[base + 0x1F].toInt() and 0xFF) shl 8)
        val comp = (fixed[base + 0x1C].toInt() and 0xFF) or ((fixed[base + 0x1D].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, sum xor comp)
        assertEquals(Checksums.snes(fixed, 512), sum)
    }

    @Test
    fun mismatchedProfileDisablesToolsInsteadOfCorrupting() {
        val rom = Rom("random.bin", ByteArray(0x8000))
        val s = HackSession(rom, Mode.OPTIMIZED, GameProfiles.byId("pkmn_emerald"))
        assertTrue(s.profileMismatch)
        assertTrue(s.toolStates().first { it.tool == Tool.POKEMON_STATS }.available.not())
    }
}
