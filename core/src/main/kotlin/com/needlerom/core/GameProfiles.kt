package com.needlerom.core

import com.needlerom.core.mario.Smb1
import com.needlerom.core.pokemon.PokemonGen

enum class Family(val label: String) { POKEMON("Pokémon"), MARIO("Mario") }

enum class Tool(val title: String, val description: String) {
    // Game-specific (optimized) tools
    WORLD_BUILDER("World builder", "Paint maps, place NPCs and item balls, rewire doors and write dialogue"),
    POKEMON_STATS("Pokémon stats editor", "Edit base stats, types, catch rate and EXP for every Pokémon"),
    POKEMON_NAMES("Pokémon names", "Rename any Pokémon in the game's own font"),
    POKEMON_BULK("Quick hacks & randomizer", "Max catch rates, scale difficulty, randomize stats or types"),
    GAME_TEXT("Dialogue & text editor", "Find and rewrite in-game text using the game's character table"),
    SMB_LIVES("Starting lives", "Change how many lives you start with"),
    SMB_COLORS("Mario & Luigi colours", "Recolour Mario, Luigi and Fire Mario with the NES palette"),
    SMW_LIVES("Starting lives", "Change how many lives Mario and Luigi start with"),

    // Generic tools (work on anything)
    ROM_INFO("ROM info & checksums", "Header details, sizes, checksum status"),
    TEXT_ASCII("ASCII text search", "Find and replace plain-ASCII strings (common in N64/GBA/DS games)"),
    RELATIVE_SEARCH("Relative text search", "Find text in any game without knowing its character table"),
    HEX_EDITOR("Hex editor", "View and patch raw bytes at any offset"),
    APPLY_PATCH("Apply IPS/BPS patch", "Stack an existing hack or translation patch onto this ROM"),
}

class GameProfile(
    val id: String,
    val name: String,
    val family: Family,
    val platforms: Set<Platform>,
    val subtitle: String,
    val tools: List<Tool>,
    val pokemonGen: PokemonGen? = null,
    val textTable: TextTable? = null,
    private val matcher: (Rom, RomInfo) -> Boolean,
) {
    fun matches(rom: Rom, info: RomInfo): Boolean = info.platform in platforms && matcher(rom, info)
}

object GameProfiles {
    private val GENERIC = listOf(Tool.ROM_INFO, Tool.RELATIVE_SEARCH, Tool.HEX_EDITOR, Tool.APPLY_PATCH)
    private val POKEMON_TOOLS = listOf(Tool.POKEMON_STATS, Tool.POKEMON_NAMES, Tool.POKEMON_BULK, Tool.GAME_TEXT)
    private val GEN1_TOOLS = listOf(Tool.WORLD_BUILDER) + POKEMON_TOOLS

    private fun titleStarts(vararg prefixes: String): (Rom, RomInfo) -> Boolean =
        { _, info -> prefixes.any { info.title.uppercase().startsWith(it) } }

    private fun codeStarts(vararg prefixes: String): (Rom, RomInfo) -> Boolean =
        { _, info -> prefixes.any { info.gameCode.uppercase().startsWith(it) } }

    private val GB = setOf(Platform.GB, Platform.GBC)

    val all: List<GameProfile> = listOf(
        GameProfile(
            "pkmn_rbg", "Pokémon Red / Blue / Green", Family.POKEMON, GB, "Game Boy · Gen I",
            GEN1_TOOLS + GENERIC, PokemonGen.GEN1, TextTable.POKEMON_GB,
            titleStarts("POKEMON RED", "POKEMON BLUE", "POKEMON GREEN"),
        ),
        GameProfile(
            "pkmn_yellow", "Pokémon Yellow", Family.POKEMON, GB, "Game Boy · Gen I",
            GEN1_TOOLS + GENERIC, PokemonGen.GEN1, TextTable.POKEMON_GB,
            titleStarts("POKEMON YELLOW"),
        ),
        GameProfile(
            "pkmn_gs", "Pokémon Gold / Silver", Family.POKEMON, GB, "Game Boy Color · Gen II",
            POKEMON_TOOLS + GENERIC, PokemonGen.GEN2, TextTable.POKEMON_GB,
            titleStarts("POKEMON_GLD", "POKEMON_SLV"),
        ),
        GameProfile(
            "pkmn_crystal", "Pokémon Crystal", Family.POKEMON, GB, "Game Boy Color · Gen II",
            POKEMON_TOOLS + GENERIC, PokemonGen.GEN2, TextTable.POKEMON_GB,
            titleStarts("PM_CRYSTAL"),
        ),
        GameProfile(
            "pkmn_rs", "Pokémon Ruby / Sapphire", Family.POKEMON, setOf(Platform.GBA), "Game Boy Advance · Gen III",
            POKEMON_TOOLS + GENERIC, PokemonGen.GEN3, TextTable.POKEMON_GBA,
            codeStarts("AXV", "AXP"),
        ),
        GameProfile(
            "pkmn_emerald", "Pokémon Emerald", Family.POKEMON, setOf(Platform.GBA), "Game Boy Advance · Gen III",
            POKEMON_TOOLS + GENERIC, PokemonGen.GEN3, TextTable.POKEMON_GBA,
            codeStarts("BPE"),
        ),
        GameProfile(
            "pkmn_frlg", "Pokémon FireRed / LeafGreen", Family.POKEMON, setOf(Platform.GBA), "Game Boy Advance · Gen III",
            POKEMON_TOOLS + GENERIC, PokemonGen.GEN3, TextTable.POKEMON_GBA,
            codeStarts("BPR", "BPG"),
        ),
        GameProfile(
            "smb1", "Super Mario Bros.", Family.MARIO, setOf(Platform.NES), "NES",
            listOf(Tool.SMB_LIVES, Tool.SMB_COLORS, Tool.GAME_TEXT) + GENERIC,
            textTable = TextTable.SMB,
            matcher = { rom, _ -> Smb1.looksLikeSmb1(rom) },
        ),
        GameProfile(
            "smw", "Super Mario World", Family.MARIO, setOf(Platform.SNES), "Super Nintendo",
            listOf(Tool.SMW_LIVES) + GENERIC,
            matcher = titleStarts("SUPER MARIOWORLD", "SUPER MARIO WORLD"),
        ),
        GameProfile(
            "sm64", "Super Mario 64", Family.MARIO, setOf(Platform.N64), "Nintendo 64",
            listOf(Tool.TEXT_ASCII) + GENERIC,
            matcher = { _, info -> info.title.uppercase().startsWith("SUPER MARIO 64") || info.gameCode.uppercase().startsWith("NSM") },
        ),
        GameProfile(
            "sml", "Super Mario Land 1 / 2", Family.MARIO, GB, "Game Boy",
            GENERIC,
            matcher = titleStarts("SUPER MARIOLAND", "MARIOLAND2"),
        ),
        GameProfile(
            "sma", "Super Mario Advance 1–4", Family.MARIO, setOf(Platform.GBA), "Game Boy Advance",
            listOf(Tool.TEXT_ASCII) + GENERIC,
            matcher = codeStarts("AMA", "AA2", "A3A", "AX4"),
        ),
    )

    fun byId(id: String): GameProfile? = all.firstOrNull { it.id == id }

    fun detect(rom: Rom, info: RomInfo): GameProfile? = all.firstOrNull { it.matches(rom, info) }

    /** Tools offered in auto-detect mode: generic, plus text search suited to the platform. */
    fun autoTools(info: RomInfo): List<Tool> = buildList {
        add(Tool.ROM_INFO)
        if (info.platform in setOf(Platform.N64, Platform.GBA, Platform.NDS, Platform.UNKNOWN)) add(Tool.TEXT_ASCII)
        add(Tool.RELATIVE_SEARCH)
        add(Tool.HEX_EDITOR)
        add(Tool.APPLY_PATCH)
    }
}
