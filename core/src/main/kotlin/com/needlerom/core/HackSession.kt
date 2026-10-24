package com.needlerom.core

import com.needlerom.core.mario.Smb1
import com.needlerom.core.mario.Smw
import com.needlerom.core.patch.Bps
import com.needlerom.core.patch.Ips
import com.needlerom.core.patch.PatchException
import com.needlerom.core.pokemon.PokemonEditor

enum class Mode { AUTO, OPTIMIZED }

data class ToolState(val tool: Tool, val available: Boolean, val reason: String? = null)

/**
 * Everything about one hacking session: the ROM, what it was detected as, and which
 * profile (if any) the user chose. The Android UI is a thin layer on top of this.
 */
class HackSession(val rom: Rom, val mode: Mode, val profile: GameProfile?) {
    val info: RomInfo = SystemDetector.detect(rom)
    val detectedProfile: GameProfile? = GameProfiles.detect(rom, info)

    /** True if the user picked a profile that doesn't match what we detected. */
    val profileMismatch: Boolean = profile != null && !profile.matches(rom, info)

    val pokemon: PokemonEditor? by lazy { profile?.pokemonGen?.let { PokemonEditor(rom, it) } }

    val textTable: TextTable? get() = profile?.textTable

    val tools: List<Tool> = when (mode) {
        Mode.AUTO -> GameProfiles.autoTools(info)
        Mode.OPTIMIZED -> profile?.tools ?: GameProfiles.autoTools(info)
    }

    fun toolStates(): List<ToolState> = tools.map { t ->
        when (t) {
            Tool.POKEMON_STATS, Tool.POKEMON_BULK ->
                if (pokemon?.hasStats == true) ToolState(t, true)
                else ToolState(t, false, "Couldn't find the base stats table in this ROM")
            Tool.POKEMON_NAMES ->
                if (pokemon?.hasNames == true) ToolState(t, true)
                else ToolState(t, false, "Couldn't find the Pokémon name table in this ROM")
            Tool.SMB_LIVES ->
                if (Smb1.livesOffset(rom) != null) ToolState(t, true)
                else ToolState(t, false, "Lives code not found — not an SMB1 ROM?")
            Tool.SMB_COLORS ->
                if (Smb1.paletteOffset(rom) != null) ToolState(t, true)
                else ToolState(t, false, "Player colour table not found — not an SMB1 ROM?")
            Tool.SMW_LIVES ->
                if (Smw.livesOffset(rom, info) != null) ToolState(t, true)
                else ToolState(t, false, "Needs an unmodified-code US Super Mario World (LoROM)")
            Tool.GAME_TEXT ->
                if (textTable != null) ToolState(t, true) else ToolState(t, false, "No text table for this game")
            else -> ToolState(t, true)
        }
    }

    /** The bytes to save: the working ROM with checksums optionally recalculated. */
    fun exportBytes(fixChecksums: Boolean): Pair<ByteArray, List<String>> {
        val out = rom.data.copyOf()
        val notes = if (fixChecksums) Checksums.fixAll(out, info) else emptyList()
        return out to notes
    }

    fun makeIps(fixChecksums: Boolean): ByteArray = Ips.create(rom.original, exportBytes(fixChecksums).first)

    fun makeBps(fixChecksums: Boolean): ByteArray =
        Bps.create(rom.original, exportBytes(fixChecksums).first, "Made with NeedleROM")

    fun suggestedName(ext: String? = null): String {
        val base = rom.fileName.substringBeforeLast('.', rom.fileName)
        val romExt = when {
            info.platform == Platform.N64 -> "z64"
            rom.fileName.contains('.') -> rom.fileName.substringAfterLast('.')
            else -> "bin"
        }
        return "$base (hack).${ext ?: romExt}"
    }

    /** Applies an IPS or BPS patch to the working ROM. Returns a warning message, if any. */
    fun applyPatch(patch: ByteArray, patchName: String): String? {
        val magic = String(patch.copyOfRange(0, minOf(5, patch.size)), Charsets.US_ASCII)
        return when {
            magic.startsWith("PATCH") -> {
                rom.replaceAll(Ips.apply(rom.data, patch), "Applied $patchName"); null
            }
            magic.startsWith("BPS1") -> {
                val r = Bps.apply(rom.data, patch)
                rom.replaceAll(r.output, "Applied $patchName"); r.warning
            }
            else -> throw PatchException("Unsupported patch format (IPS and BPS are supported)")
        }
    }
}
