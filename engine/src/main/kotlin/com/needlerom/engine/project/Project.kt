package com.needlerom.engine.project

import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.rom.Rom
import java.io.File
import java.util.Properties

/**
 * A hacking project on disk:
 *
 *     project.properties   name, notes, flag names
 *     base.rom             the user's original ROM (never modified)
 *     work.rom             the hack in progress
 *     play.sav             battery save used by playtesting
 *     state-N.bin          playtest save states
 */
class Project private constructor(val dir: File) {
    private val props = Properties()

    var name: String
        get() = props.getProperty("name", dir.name)
        set(v) { props.setProperty("name", v) }

    var notes: String
        get() = props.getProperty("notes", "")
        set(v) { props.setProperty("notes", v) }

    val gameName: String get() = props.getProperty("game", "Pokémon")
    val modified: Long get() = workFile.lastModified()

    /** User-facing names for script flags (flag number -> name). */
    val flagNames: MutableMap<Int, String> = mutableMapOf()

    /** Names of maps made in the editor (map id -> name). */
    val mapNames: MutableMap<Int, String> = mutableMapOf()

    private val metaFile get() = File(dir, "project.properties")
    private val baseFile get() = File(dir, "base.rom")
    private val workFile get() = File(dir, "work.rom")
    val saveFile: File get() = File(dir, "play.sav")
    fun stateFile(slot: Int) = File(dir, "state-$slot.bin")

    init {
        if (metaFile.exists()) metaFile.inputStream().use { props.load(it) }
        props.stringPropertyNames().filter { it.startsWith("flag.") }.forEach { k ->
            k.removePrefix("flag.").toIntOrNull()?.let { flagNames[it] = props.getProperty(k) }
        }
        props.stringPropertyNames().filter { it.startsWith("map.") }.forEach { k ->
            k.removePrefix("map.").toIntOrNull()?.let { mapNames[it] = props.getProperty(k) }
        }
    }

    /** Opens the game with the saved working copy loaded. */
    fun open(): PokeRed {
        val rom = Rom(baseFile.readBytes())
        if (workFile.exists()) rom.restore(workFile.readBytes())
        return PokeRed(rom)
    }

    /** Saves the working ROM and metadata. */
    fun save(game: PokeRed) {
        writeAtomically(workFile, game.rom.data)
        saveMeta()
    }

    fun saveMeta() {
        props.stringPropertyNames().filter { it.startsWith("flag.") || it.startsWith("map.") }.forEach(props::remove)
        flagNames.forEach { (k, v) -> props.setProperty("flag.$k", v) }
        mapNames.forEach { (k, v) -> props.setProperty("map.$k", v) }
        val tmp = File(dir, "project.properties.tmp")
        tmp.outputStream().use { props.store(it, "NeedleROM project") }
        tmp.renameTo(metaFile)
    }

    fun delete() = dir.deleteRecursively()

    companion object {
        /** Creates a project from a ROM the user owns. Throws if it isn't a supported game. */
        fun create(root: File, name: String, romBytes: ByteArray): Project {
            val game = PokeRed(Rom(romBytes)) // validates the ROM
            root.mkdirs()
            var dir = File(root, safeName(name))
            var n = 2
            while (dir.exists()) dir = File(root, safeName(name) + "-" + n++)
            dir.mkdirs()
            File(dir, "base.rom").writeBytes(romBytes)
            val p = Project(dir)
            p.name = name
            p.props.setProperty("game", game.version.name)
            p.saveMeta()
            return p
        }

        fun open(dir: File): Project = Project(dir)

        fun list(root: File): List<Project> =
            (root.listFiles() ?: emptyArray()).filter { File(it, "base.rom").exists() }.map(::Project).sortedByDescending { it.modified }

        private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "hack" }.take(40)

        private fun writeAtomically(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
