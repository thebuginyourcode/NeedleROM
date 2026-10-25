package com.needlerom.engine.pokered

import com.needlerom.engine.rom.Gb

/**
 * Oak's speech at the start of a new game: its pictures (the professor, the player and the
 * rival), the Pokémon he shows and the lines he says.
 */
object Intro {
    enum class Role(val label: String) { PROFESSOR("Professor"), PLAYER("Player"), RIVAL("Rival") }

    /** A picture the intro can show: compressed pic data at [ptr] in [bank]. */
    data class Picture(val name: String, val bank: Int, val ptr: Int)

    private fun range(g: PokeRed) = g.sym("OakSpeech") until g.sym("OakSpeechText1")

    /**
     * Offsets of the `ld de, pic / lb bc, bank, 0` pairs that show pictures, in order:
     * professor, player, rival, player again, then the two shrinking frames.
     */
    private fun slots(g: PokeRed, data: ByteArray = g.rom.data): List<Int> {
        val r = range(g)
        return (r.first until r.last - 6).filter { o ->
            data[o].toInt() == 0x11 && data[o + 3].toInt() == 0x01 && data[o + 4].toInt() == 0x00
        }
    }

    private fun slotsFor(g: PokeRed, role: Role): List<Int> {
        val s = slots(g)
        require(s.size >= 4) { "This ROM's intro code has been changed" }
        return when (role) { Role.PROFESSOR -> listOf(s[0]); Role.PLAYER -> listOf(s[1], s[3]); Role.RIVAL -> listOf(s[2]) }
    }

    fun supported(g: PokeRed): Boolean = runCatching { slots(g).size >= 4 && speciesOffset(g) != null }.getOrDefault(false)

    fun picture(g: PokeRed, role: Role): Picture {
        val o = slotsFor(g, role).first()
        val ptr = g.rom.u16(o + 1); val bank = g.rom.u8(o + 5)
        return choices(g).firstOrNull { it.bank == bank && it.ptr == ptr } ?: Picture("Custom", bank, ptr)
    }

    fun setPicture(g: PokeRed, role: Role, p: Picture) = g.rom.batch {
        for (o in slotsFor(g, role)) {
            g.rom.write16(o + 1, p.ptr, "Intro ${role.label.lowercase()} picture")
            g.rom.write8(o + 5, p.bank, "Intro ${role.label.lowercase()} picture")
        }
    }

    /** Every picture that can be shown: the original three, then each trainer class. */
    fun choices(g: PokeRed): List<Picture> {
        val orig = slots(g, g.rom.original)
        fun at(o: Int) = (g.rom.original[o + 1].toInt() and 0xFF) or ((g.rom.original[o + 2].toInt() and 0xFF) shl 8)
        val trainerBank = g.rom.original[orig[0] + 5].toInt() and 0xFF // trainer pics share Oak's bank
        val base = listOf(
            Picture("Prof. Oak", trainerBank, at(orig[0])),
            Picture("Player (Red)", g.rom.original[orig[1] + 5].toInt() and 0xFF, at(orig[1])),
            Picture("Rival (first)", trainerBank, at(orig[2])),
        )
        val table = g.sym("TrainerPicAndMoneyPointers")
        val classes = (1..PokeRed.NUM_TRAINER_CLASSES).map { c ->
            Picture(g.trainerClassNames.getOrElse(c) { "Class $c" }, trainerBank, g.rom.u16(table + (c - 1) * 5))
        }
        // The speech copies pictures to the screen without centering them, so only full-size
        // (7×7 tile) pictures show correctly.
        return (base + classes).distinctBy { it.bank to it.ptr }
            .filter { p -> Pics.decode(g.rom, p.bank, p.ptr)?.let { it.widthTiles == 7 && it.heightTiles == 7 } == true }
    }

    // ---- The Pokémon Oak shows ------------------------------------------------------------------

    /** Offset of `ld a, species` before `ld [wCurSpecies], a` in the speech. */
    private fun speciesOffset(g: PokeRed): Int? {
        val cur = g.sym("wCurSpecies")
        return range(g).firstOrNull { o ->
            g.rom.u8(o) == 0x3E && g.rom.u8(o + 2) == 0xEA && g.rom.u16(o + 3) == cur
        }
    }

    fun species(g: PokeRed): Int = speciesOffset(g)?.let { g.rom.u8(it + 1) } ?: 0

    fun setSpecies(g: PokeRed, internal: Int) {
        val o = speciesOffset(g) ?: throw EditException("This ROM's intro code has been changed")
        g.rom.write8(o + 1, internal, "Intro Pokémon")
    }

    /** The cry in Oak's second text (a text command: 0x14-0x16 cries, 0x0A just a pause). */
    val CRIES = listOf(0x14 to "Nidorina's cry", 0x15 to "Pidgeot's cry", 0x16 to "Dewgong's cry", 0x0A to "No cry (a short pause)")

    private fun cryOffset(g: PokeRed): Int? {
        // OakSpeechText2: text_far (4 bytes), then the sound command.
        val o = g.sym("OakSpeechText2") + 4
        return o.takeIf { g.rom.u8(g.sym("OakSpeechText2")) == 0x17 && g.rom.u8(it) in listOf(0x0A, 0x14, 0x15, 0x16) }
    }

    fun cry(g: PokeRed): Int? = cryOffset(g)?.let { g.rom.u8(it) }

    fun setCry(g: PokeRed, command: Int) {
        val o = cryOffset(g) ?: throw EditException("This ROM's intro text has been changed")
        g.rom.write8(o, command, "Intro cry")
    }

    // ---- Lines ----------------------------------------------------------------------------------

    /** The speech's texts, in the order they're shown. */
    fun texts(g: PokeRed): List<Pair<String, List<GameLine>>> = listOf(
        "Welcome" to "OakSpeechText1",
        "About Pokémon" to "OakSpeechText2",
        "Introducing the player" to "IntroducePlayerText",
        "Introducing the rival" to "IntroduceRivalText",
        "Sending the player off" to "OakSpeechText3",
    ).map { (label, sym) -> label to GameScripts.textLines(g, g.sym(sym)) }
}
