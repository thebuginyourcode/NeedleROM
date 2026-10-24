package com.needlerom.core.pokemon

import com.needlerom.core.Bytes
import com.needlerom.core.TextTable

data class PokeType(val id: Int, val name: String)

enum class PokemonGen(
    val label: String,
    val table: TextTable,
    val statsStride: Int,
    /** Offset of HP inside a base stats entry (Gen 1/2 lead with the Pokédex number). */
    val hpOffset: Int,
    val hasSplitSpecial: Boolean,
    val nameLength: Int,
    val types: List<PokeType>,
    /** Bulbasaur's entry: stats, types and catch rate, used to find the table in any revision. */
    val bulbasaurSig: ByteArray,
    val ivysaurSig: ByteArray,
) {
    GEN1(
        "Generation I", TextTable.POKEMON_GB, statsStride = 28, hpOffset = 1, hasSplitSpecial = false,
        nameLength = 10, types = gen1Types(),
        bulbasaurSig = Bytes.of(0x01, 45, 49, 49, 45, 65, 0x16, 0x03, 45),
        ivysaurSig = Bytes.of(0x02, 60, 62, 63, 60, 80, 0x16, 0x03, 45),
    ),
    GEN2(
        "Generation II", TextTable.POKEMON_GB, statsStride = 32, hpOffset = 1, hasSplitSpecial = true,
        nameLength = 10, types = gen1Types() + listOf(PokeType(0x09, "Steel"), PokeType(0x1B, "Dark")),
        bulbasaurSig = Bytes.of(0x01, 45, 49, 49, 45, 65, 65, 0x16, 0x03, 45),
        ivysaurSig = Bytes.of(0x02, 60, 62, 63, 60, 80, 80, 0x16, 0x03, 45),
    ),
    GEN3(
        "Generation III", TextTable.POKEMON_GBA, statsStride = 28, hpOffset = 0, hasSplitSpecial = true,
        nameLength = 10,
        types = listOf(
            "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "???",
            "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
        ).mapIndexed { i, n -> PokeType(i, n) },
        bulbasaurSig = Bytes.of(45, 49, 49, 45, 65, 65, 0x0C, 0x03, 45),
        ivysaurSig = Bytes.of(60, 62, 63, 60, 80, 80, 0x0C, 0x03, 45),
    );

    /** Field offsets inside one base-stats entry. */
    val atk get() = hpOffset + 1
    val def get() = hpOffset + 2
    val spd get() = hpOffset + 3
    val spAtk get() = hpOffset + 4
    val spDef get() = if (hasSplitSpecial) hpOffset + 5 else hpOffset + 4
    val type1 get() = if (hasSplitSpecial) hpOffset + 6 else hpOffset + 5
    val type2 get() = type1 + 1
    val catchRate get() = type1 + 2
    val baseExp get() = type1 + 3

    fun typeName(id: Int): String = types.firstOrNull { it.id == id }?.name ?: "0x${Bytes.hex(id)}"
}

private fun gen1Types() = listOf(
    PokeType(0x00, "Normal"), PokeType(0x01, "Fighting"), PokeType(0x02, "Flying"),
    PokeType(0x03, "Poison"), PokeType(0x04, "Ground"), PokeType(0x05, "Rock"),
    PokeType(0x07, "Bug"), PokeType(0x08, "Ghost"), PokeType(0x14, "Fire"),
    PokeType(0x15, "Water"), PokeType(0x16, "Grass"), PokeType(0x17, "Electric"),
    PokeType(0x18, "Psychic"), PokeType(0x19, "Ice"), PokeType(0x1A, "Dragon"),
)

/** Gen 1 stores base stats in Pokédex order but names in internal order, so we label by dex. */
internal val KANTO_NAMES = listOf(
    "Bulbasaur", "Ivysaur", "Venusaur", "Charmander", "Charmeleon", "Charizard", "Squirtle",
    "Wartortle", "Blastoise", "Caterpie", "Metapod", "Butterfree", "Weedle", "Kakuna", "Beedrill",
    "Pidgey", "Pidgeotto", "Pidgeot", "Rattata", "Raticate", "Spearow", "Fearow", "Ekans", "Arbok",
    "Pikachu", "Raichu", "Sandshrew", "Sandslash", "Nidoran♀", "Nidorina", "Nidoqueen", "Nidoran♂",
    "Nidorino", "Nidoking", "Clefairy", "Clefable", "Vulpix", "Ninetales", "Jigglypuff",
    "Wigglytuff", "Zubat", "Golbat", "Oddish", "Gloom", "Vileplume", "Paras", "Parasect", "Venonat",
    "Venomoth", "Diglett", "Dugtrio", "Meowth", "Persian", "Psyduck", "Golduck", "Mankey",
    "Primeape", "Growlithe", "Arcanine", "Poliwag", "Poliwhirl", "Poliwrath", "Abra", "Kadabra",
    "Alakazam", "Machop", "Machoke", "Machamp", "Bellsprout", "Weepinbell", "Victreebel",
    "Tentacool", "Tentacruel", "Geodude", "Graveler", "Golem", "Ponyta", "Rapidash", "Slowpoke",
    "Slowbro", "Magnemite", "Magneton", "Farfetch'd", "Doduo", "Dodrio", "Seel", "Dewgong", "Grimer",
    "Muk", "Shellder", "Cloyster", "Gastly", "Haunter", "Gengar", "Onix", "Drowzee", "Hypno",
    "Krabby", "Kingler", "Voltorb", "Electrode", "Exeggcute", "Exeggutor", "Cubone", "Marowak",
    "Hitmonlee", "Hitmonchan", "Lickitung", "Koffing", "Weezing", "Rhyhorn", "Rhydon", "Chansey",
    "Tangela", "Kangaskhan", "Horsea", "Seadra", "Goldeen", "Seaking", "Staryu", "Starmie",
    "Mr. Mime", "Scyther", "Jynx", "Electabuzz", "Magmar", "Pinsir", "Tauros", "Magikarp",
    "Gyarados", "Lapras", "Ditto", "Eevee", "Vaporeon", "Jolteon", "Flareon", "Porygon", "Omanyte",
    "Omastar", "Kabuto", "Kabutops", "Aerodactyl", "Snorlax", "Articuno", "Zapdos", "Moltres",
    "Dratini", "Dragonair", "Dragonite", "Mewtwo", "Mew",
)
