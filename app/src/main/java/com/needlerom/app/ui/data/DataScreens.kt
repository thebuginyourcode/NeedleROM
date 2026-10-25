@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.Route
import com.needlerom.app.ui.Chooser
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.NumberField
import com.needlerom.app.ui.Screen
import com.needlerom.app.ui.Section
import com.needlerom.engine.pokered.Evolution
import com.needlerom.engine.pokered.LearnedMove
import com.needlerom.engine.pokered.MoveData
import com.needlerom.engine.pokered.Party
import com.needlerom.engine.pokered.PartyMon
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.pokered.WildEncounters

private enum class DataSection(val label: String) { POKEMON("Pokémon"), TRAINERS("Trainers"), WILD("Wild"), STARTERS("Starters"), ITEMS("Items"), MOVES("Moves") }

private fun PokeRed.monLabel(internal: Int) = "#${dexOf(internal).toString().padStart(3, '0')} ${monName(internal)}"

@Composable
fun DataTab(state: AppState, game: PokeRed) {
    var section by remember { mutableStateOf(DataSection.POKEMON) }
    val rev = state.revision
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { state.go(Route.Intro) }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Intro: Oak's speech, pictures & names →") }
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataSection.entries.take(3).forEach { s -> FilterChip(selected = s == section, onClick = { section = s }, label = { Text(s.label) }) }
        }
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataSection.entries.drop(3).forEach { s -> FilterChip(selected = s == section, onClick = { section = s }, label = { Text(s.label) }) }
        }
        when (section) {
            DataSection.POKEMON -> {
                val list = remember(game, rev) { game.species }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(list, key = { it.first }) { (internal, dex) ->
                        val st = remember(dex, rev) { game.pokemon.stats(dex) }
                        Row(Modifier.fillMaxWidth().clickable { state.go(Route.PokemonEditor(dex)) }.padding(vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(game.monLabel(internal), fontWeight = FontWeight.SemiBold)
                                Hint("${PokeRed.typeName(st.type1)}${if (st.type2 != st.type1) "/" + PokeRed.typeName(st.type2) else ""} · ${st.hp}/${st.attack}/${st.defense}/${st.speed}/${st.special}")
                            }
                            Text("${st.total}", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }
            }
            DataSection.TRAINERS -> {
                val classes = remember(game, rev) { game.trainers.classes }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(classes, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { state.go(Route.TrainerEditor(c.id)) }.padding(vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                Hint("${c.parties.size} team(s) · up to Lv${c.parties.flatMap { it.mons }.maxOfOrNull { it.level } ?: 0}")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            DataSection.WILD -> {
                val maps = remember(game) { game.world.maps.filter { it.id <= 0xF7 } }
                val withWild = remember(game, rev) { game.wild.mapsWithEncounters().toSet() }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    item { Hint("Maps with wild Pokémon are listed first. You can add encounters to any map.", Modifier.padding(vertical = 6.dp)) }
                    items(maps.sortedByDescending { it.id in withWild }, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().clickable { state.go(Route.WildEditor(m.id)) }.padding(vertical = 10.dp)) {
                            Text(m.name, Modifier.weight(1f), fontWeight = if (m.id in withWild) FontWeight.SemiBold else FontWeight.Normal)
                            if (m.id in withWild) com.needlerom.app.ui.Badge("Wild")
                        }
                        HorizontalDivider()
                    }
                }
            }
            DataSection.STARTERS -> Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!game.starters.supported) {
                    Text("In Yellow your starter is always Pikachu.")
                } else {
                    val current = remember(game, rev) { game.starters.current() }
                    var updateRival by remember { mutableStateOf(true) }
                    listOf("Left ball", "Middle ball", "Right ball").forEachIndexed { i, label ->
                        Chooser(label, game.monLabel(current[i]), game.species.map { it.first }, { game.monLabel(it) }, Modifier.fillMaxWidth()) {
                            state.edit("${label}: ${game.monName(it)}") { g -> g.starters.set(i + 1, it, updateRival) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(updateRival, { updateRival = it }); Text("Also change the rival's matching Pokémon")
                    }
                    Hint("Every place the game refers to the starters is updated: Oak's lab, the rival's choice and the title screen.")
                }
            }
            DataSection.ITEMS -> {
                val ids = remember(game) { (1..PokeRed.NUM_ITEMS).filter { !game.itemName(it).startsWith("?") } }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(ids) { id ->
                        val price = remember(id, rev) { game.items.price(id) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(game.itemName(id), Modifier.weight(1f))
                            NumberField("Price ¥", price, Modifier.weight(0.8f), 0..999999) { v -> state.edit { it.items.setPrice(id, v) } }
                        }
                    }
                }
            }
            DataSection.MOVES -> {
                var editing by remember { mutableStateOf<Int?>(null) }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items((1..PokeRed.NUM_MOVES).toList()) { id ->
                        val m = remember(id, rev) { game.moves.move(id) }
                        Row(Modifier.fillMaxWidth().clickable { editing = id }.padding(vertical = 10.dp)) {
                            Text(game.moveName(id), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Hint("${PokeRed.typeName(m.type)} · ${m.power} pow · ${m.accuracyPercent}% · ${m.pp} PP")
                        }
                        HorizontalDivider()
                    }
                }
                editing?.let { id -> MoveDialog(state, game, id) { editing = null } }
            }
        }
    }
}

@Composable
private fun MoveDialog(state: AppState, game: PokeRed, id: Int, onDismiss: () -> Unit) {
    var m by remember { mutableStateOf(game.moves.move(id)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(game.moveName(id)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chooser("Type", PokeRed.typeName(m.type), PokeRed.TYPES, { it.name }, Modifier.fillMaxWidth()) { m = m.copy(type = it.id) }
                NumberField("Power", m.power, Modifier.fillMaxWidth()) { m = m.copy(power = it) }
                NumberField("Accuracy %", m.accuracyPercent, Modifier.fillMaxWidth(), 0..100) { m = m.copy(accuracy = MoveData.accuracyFromPercent(it)) }
                NumberField("PP", m.pp, Modifier.fillMaxWidth(), 1..40) { m = m.copy(pp = it) }
                Hint("The move's effect (sleep, burn, …) stays the same.")
            }
        },
        confirmButton = { Button(onClick = { state.edit("${game.moveName(id)} saved") { it.moves.setMove(id, m) }; onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Pokémon ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PokemonEditorScreen(state: AppState, dex: Int) {
    val game = state.game ?: return
    val internal = remember(game, dex) { game.internalOfDex(dex) }
    val rev = state.revision
    var s by remember(dex, rev) { mutableStateOf(game.pokemon.stats(dex)) }
    var evo by remember(dex, rev) { mutableStateOf(game.pokemon.evosMoves(internal)) }
    var name by remember(dex, rev) { mutableStateOf(game.monName(internal)) }
    val moveIds = (0..PokeRed.NUM_MOVES).toList()

    Screen(
        title = game.monLabel(internal),
        subtitle = "Base stat total ${s.total}",
        onBack = { state.back() },
        actions = {
            Button(onClick = {
                state.edit("${name} saved") { g ->
                    g.rom.batch {
                        g.pokemon.setStats(dex, s)
                        if (evo != g.pokemon.evosMoves(internal)) g.pokemon.setEvosMoves(internal, evo)
                        if (name != g.monName(internal)) g.pokemon.rename(internal, name)
                    }
                }
            }) { Text("Save") }
        },
    ) {
        Section("Name") {
            OutlinedTextField(name, { name = it.uppercase().take(10) }, Modifier.fillMaxWidth(), singleLine = true)
        }
        Section("Base stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("HP", s.hp, Modifier.weight(1f)) { s = s.copy(hp = it) }
                NumberField("Attack", s.attack, Modifier.weight(1f)) { s = s.copy(attack = it) }
                NumberField("Defense", s.defense, Modifier.weight(1f)) { s = s.copy(defense = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Speed", s.speed, Modifier.weight(1f)) { s = s.copy(speed = it) }
                NumberField("Special", s.special, Modifier.weight(1f)) { s = s.copy(special = it) }
                NumberField("Catch rate", s.catchRate, Modifier.weight(1f)) { s = s.copy(catchRate = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chooser("Type 1", PokeRed.typeName(s.type1), PokeRed.TYPES, { it.name }, Modifier.weight(1f)) { s = s.copy(type1 = it.id) }
                Chooser("Type 2", PokeRed.typeName(s.type2), PokeRed.TYPES, { it.name }, Modifier.weight(1f)) { s = s.copy(type2 = it.id) }
            }
            Hint("Set both types the same for a single type.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Base EXP", s.baseExp, Modifier.weight(1f)) { s = s.copy(baseExp = it) }
                Chooser("Growth", GROWTH.getOrElse(s.growthRate) { "?" }, GROWTH.indices.toList(), { GROWTH[it] }, Modifier.weight(1f)) { s = s.copy(growthRate = it) }
            }
        }
        Section("Moves known at level 1") {
            s.startMoves.forEachIndexed { i, mv ->
                Chooser("Move ${i + 1}", game.moveName(mv), moveIds, game::moveName, Modifier.fillMaxWidth()) { v ->
                    s = s.copy(startMoves = s.startMoves.toMutableList().also { it[i] = v })
                }
            }
        }
        Section("Level-up moves") {
            evo.learnset.forEachIndexed { i, lm ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Lv", lm.level, Modifier.weight(0.5f), 1..100) { v -> evo = evo.copy(learnset = evo.learnset.toMutableList().also { it[i] = lm.copy(level = v) }) }
                    Chooser("Move", game.moveName(lm.move), moveIds.drop(1), game::moveName, Modifier.weight(1.2f)) { v -> evo = evo.copy(learnset = evo.learnset.toMutableList().also { it[i] = lm.copy(move = v) }) }
                    TextButton(onClick = { evo = evo.copy(learnset = evo.learnset.toMutableList().also { it.removeAt(i) }) }) { Text("✕") }
                }
            }
            TextButton(onClick = { evo = evo.copy(learnset = evo.learnset + LearnedMove((evo.learnset.lastOrNull()?.level ?: 5) + 5, 1)) }) { Text("+ Add move") }
        }
        Section("Evolutions") {
            evo.evolutions.forEachIndexed { i, e -> EvolutionRow(game, e, onChange = { n ->
                evo = evo.copy(evolutions = evo.evolutions.toMutableList().also { if (n == null) it.removeAt(i) else it[i] = n })
            }) }
            Row {
                TextButton(onClick = { evo = evo.copy(evolutions = evo.evolutions + Evolution.Level(30, internal)) }) { Text("+ By level") }
                TextButton(onClick = { evo = evo.copy(evolutions = evo.evolutions + Evolution.Item(0x0A, 1, internal)) }) { Text("+ By item") }
                TextButton(onClick = { evo = evo.copy(evolutions = evo.evolutions + Evolution.Trade(1, internal)) }) { Text("+ By trade") }
            }
        }
        Section("TM / HM compatibility") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.tmhm.forEachIndexed { i, on ->
                    val label = if (i < 50) "TM" + (i + 1).toString().padStart(2, '0') else "HM0${i - 49}"
                    FilterChip(selected = on, onClick = { s = s.copy(tmhm = s.tmhm.toMutableList().also { it[i] = !on }) }, label = { Text(label) })
                }
            }
        }
    }
}

private val GROWTH = listOf("Medium fast", "Slightly fast", "Slightly slow", "Medium slow", "Fast", "Slow")

@Composable
private fun EvolutionRow(game: PokeRed, e: Evolution, onChange: (Evolution?) -> Unit) {
    val species = game.species.map { it.first }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (e) {
            is Evolution.Level -> NumberField("At Lv", e.level, Modifier.weight(0.5f), 1..100) { onChange(e.copy(level = it)) }
            is Evolution.Item -> Chooser("Item", game.itemName(e.item), game.allItemIds, game::itemName, Modifier.weight(0.8f)) { onChange(e.copy(item = it)) }
            is Evolution.Trade -> Text("By trade", Modifier.weight(0.5f))
        }
        Chooser("Into", game.monLabel(e.species), species, { game.monLabel(it) }, Modifier.weight(1f)) { sp ->
            onChange(when (e) { is Evolution.Level -> e.copy(species = sp); is Evolution.Item -> e.copy(species = sp); is Evolution.Trade -> e.copy(species = sp) })
        }
        TextButton(onClick = { onChange(null) }) { Text("✕") }
    }
}

// ---- Trainers --------------------------------------------------------------------------------

@Composable
fun TrainerEditorScreen(state: AppState, trainerClass: Int) {
    val game = state.game ?: return
    val rev = state.revision
    val saved = remember(trainerClass, rev) { game.trainers.parties(trainerClass) }
    var parties by remember(trainerClass, rev) { mutableStateOf(saved) }
    val species = remember(game) { game.species.map { it.first } }
    Screen(
        title = game.trainerClassNames.getOrElse(trainerClass) { "Trainer" },
        subtitle = "${parties.size} team(s)",
        onBack = { state.back() },
        actions = {
            Button(enabled = parties != saved, onClick = { state.edit("Teams saved") { it.trainers.setParties(trainerClass, parties) } }) { Text("Save") }
        },
    ) {
        Hint("Each trainer on a map uses one of these teams by number. Teams can have 1 to 6 Pokémon.")
        parties.forEachIndexed { t, party ->
            Section("Team ${t + 1}") {
                party.mons.forEachIndexed { i, mon ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField("Lv", mon.level, Modifier.weight(0.45f), 1..100) { v -> parties = update(parties, t) { it.toMutableList().also { l -> l[i] = mon.copy(level = v) } } }
                        Chooser("Pokémon", game.monLabel(mon.species), species, { game.monLabel(it) }, Modifier.weight(1.2f)) { v ->
                            parties = update(parties, t) { it.toMutableList().also { l -> l[i] = mon.copy(species = v) } }
                        }
                        if (party.mons.size > 1) TextButton(onClick = { parties = update(parties, t) { it.toMutableList().also { l -> l.removeAt(i) } } }) { Text("✕") }
                    }
                }
                if (party.mons.size < 6) TextButton(onClick = {
                    parties = update(parties, t) { it + PartyMon(it.last().level, it.last().species) }
                }) { Text("+ Add Pokémon") }
            }
        }
    }
}

private fun update(parties: List<Party>, team: Int, f: (List<PartyMon>) -> List<PartyMon>): List<Party> =
    parties.toMutableList().also { it[team] = Party(f(it[team].mons)) }

// ---- Wild ------------------------------------------------------------------------------------

@Composable
fun WildEditorScreen(state: AppState, mapId: Int) {
    val game = state.game ?: return
    val rev = state.revision
    val saved = remember(mapId, rev) { game.wild.encounters(mapId) }
    var e by remember(mapId, rev) { mutableStateOf(saved) }
    val species = remember(game) { game.species.map { it.first } }
    Screen(
        title = "Wild Pokémon",
        subtitle = game.mapName(mapId),
        onBack = { state.back() },
        actions = { Button(enabled = e != saved, onClick = { state.edit("Wild Pokémon saved") { it.wild.setEncounters(mapId, e) } }) { Text("Save") } },
    ) {
        Hint("Encounter rate 0 = none. Higher = more often (Route 1 uses 25). Slot chances: ${WildEncounters.SLOT_CHANCE.joinToString("/")}%.")
        WildTable("Grass & caves", e.grassRate, e.grass, species, game,
            onRate = { r -> e = e.copy(grassRate = r, grass = if (r > 0 && e.grass.isEmpty()) defaultSlots(game) else e.grass) },
            onSlots = { e = e.copy(grass = it) })
        WildTable("Surfing", e.waterRate, e.water, species, game,
            onRate = { r -> e = e.copy(waterRate = r, water = if (r > 0 && e.water.isEmpty()) defaultSlots(game) else e.water) },
            onSlots = { e = e.copy(water = it) })
    }
}

private fun defaultSlots(game: PokeRed) = List(10) { PartyMon(5, game.internalOfDex(19)) }

@Composable
private fun WildTable(title: String, rate: Int, slots: List<PartyMon>, species: List<Int>, game: PokeRed, onRate: (Int) -> Unit, onSlots: (List<PartyMon>) -> Unit) {
    Section(title) {
        NumberField("Encounter rate", rate, Modifier.fillMaxWidth()) { onRate(it) }
        if (rate > 0) slots.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${WildEncounters.SLOT_CHANCE[i]}%", Modifier.weight(0.3f), style = MaterialTheme.typography.labelMedium)
                NumberField("Lv", m.level, Modifier.weight(0.45f), 1..100) { v -> onSlots(slots.toMutableList().also { it[i] = m.copy(level = v) }) }
                Chooser("Pokémon", game.monLabel(m.species), species, { game.monLabel(it) }, Modifier.weight(1.2f)) { v -> onSlots(slots.toMutableList().also { it[i] = m.copy(species = v) }) }
            }
        }
    }
}

