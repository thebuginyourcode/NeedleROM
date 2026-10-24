@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.ByteField
import com.needlerom.app.ui.EmptyState
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.Picker
import com.needlerom.app.ui.SectionCard
import com.needlerom.app.ui.ToolScaffold
import com.needlerom.core.HackSession
import com.needlerom.core.pokemon.BaseStats
import com.needlerom.core.pokemon.NameEntry
import com.needlerom.core.pokemon.Species
import kotlin.random.Random

@Composable
fun PokemonStatsTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val ed = s.pokemon ?: return
    val rev = vm.revision
    val all = remember(s, rev) { ed.species() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Species?>(null) }
    val shown = all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.index.toString() == query }

    ToolScaffold("Pokémon stats", "${ed.gen.label} · ${all.size} species", onBack, scroll = false) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search by name or number") }, singleLine = true,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.index }) { sp ->
                val st = remember(sp, rev) { ed.read(sp) }
                Column(Modifier.fillMaxWidth().clickable { editing = sp }.padding(vertical = 10.dp)) {
                    Row {
                        Text("#${sp.index}  ${sp.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("BST ${st.total(ed.gen.hasSplitSpecial)}", color = MaterialTheme.colorScheme.primary)
                    }
                    val types = if (st.type1 == st.type2) ed.gen.typeName(st.type1) else "${ed.gen.typeName(st.type1)} / ${ed.gen.typeName(st.type2)}"
                    Hint("$types · ${statLine(st, ed.gen.hasSplitSpecial)}")
                }
                HorizontalDivider()
            }
            if (shown.isEmpty()) item { EmptyState("No Pokémon match \"$query\"") }
        }
    }

    editing?.let { sp ->
        StatEditorDialog(vm, s, sp, onDismiss = { editing = null })
    }
}

private fun statLine(s: BaseStats, split: Boolean) =
    if (split) "${s.hp}/${s.atk}/${s.def}/${s.spAtk}/${s.spDef}/${s.spd}"
    else "${s.hp}/${s.atk}/${s.def}/${s.spAtk}/${s.spd}"

@Composable
private fun StatEditorDialog(vm: AppViewModel, s: HackSession, sp: Species, onDismiss: () -> Unit) {
    val ed = s.pokemon ?: return
    var st by remember(sp) { mutableStateOf(ed.read(sp)) }
    val split = ed.gen.hasSplitSpecial
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#${sp.index} ${sp.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ByteField("HP", st.hp, Modifier.weight(1f)) { st = st.copy(hp = it) }
                    ByteField("Attack", st.atk, Modifier.weight(1f)) { st = st.copy(atk = it) }
                    ByteField("Defense", st.def, Modifier.weight(1f)) { st = st.copy(def = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (split) {
                        ByteField("Sp. Atk", st.spAtk, Modifier.weight(1f)) { st = st.copy(spAtk = it) }
                        ByteField("Sp. Def", st.spDef, Modifier.weight(1f)) { st = st.copy(spDef = it) }
                    } else {
                        ByteField("Special", st.spAtk, Modifier.weight(1f)) { st = st.copy(spAtk = it, spDef = it) }
                    }
                    ByteField("Speed", st.spd, Modifier.weight(1f)) { st = st.copy(spd = it) }
                }
                Text("Total: ${st.total(split)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker("Type 1", ed.gen.typeName(st.type1), ed.gen.types, { it.name }, Modifier.weight(1f)) { st = st.copy(type1 = it.id) }
                    Picker("Type 2", ed.gen.typeName(st.type2), ed.gen.types, { it.name }, Modifier.weight(1f)) { st = st.copy(type2 = it.id) }
                }
                Hint("Set both types the same for a single-type Pokémon.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ByteField("Catch rate", st.catchRate, Modifier.weight(1f)) { st = st.copy(catchRate = it) }
                    ByteField("Base EXP", st.baseExp, Modifier.weight(1f)) { st = st.copy(baseExp = it) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                ed.write(sp, st)
                vm.edited("Saved ${sp.name}")
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PokemonNamesTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val ed = s.pokemon ?: return
    val rev = vm.revision
    val names = remember(s, rev) { ed.names().filter { it.name.isNotBlank() } }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NameEntry?>(null) }
    val shown = names.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

    ToolScaffold("Pokémon names", "Max ${ed.gen.nameLength} characters", onBack, scroll = false) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search") }, singleLine = true,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.index }) { n ->
                Row(Modifier.fillMaxWidth().clickable { editing = n }.padding(vertical = 12.dp)) {
                    Text(n.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Hint("slot ${n.index}")
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { n ->
        var text by remember(n) { mutableStateOf(n.name) }
        var error by remember(n) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename ${n.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        text, { text = it.take(ed.gen.nameLength); error = null }, singleLine = true,
                        isError = error != null, supportingText = { Text(error ?: "${text.length}/${ed.gen.nameLength}") },
                    )
                    Hint("The original games use ALL CAPS names.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    error = ed.rename(n, text)
                    if (error == null) { vm.edited("Renamed to $text"); editing = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PokemonBulkTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val ed = s.pokemon ?: return
    var scale by remember { mutableFloatStateOf(120f) }
    var seedText by remember { mutableStateOf(Random.nextInt(1, 999_999).toString()) }
    val seed = seedText.toLongOrNull() ?: 0L

    ToolScaffold("Quick hacks & randomizer", ed.gen.label, onBack) {
        SectionCard("Easy catching") {
            Hint("Sets every Pokémon's catch rate to the maximum (255), like a Poké Ball that almost always works.")
            FilledTonalButton(onClick = { vm.edited("Max catch rate for ${ed.maxCatchRates()} Pokémon") }) { Text("Max all catch rates") }
        }
        SectionCard("Difficulty: scale all base stats") {
            Text("${scale.toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Slider(value = scale, onValueChange = { scale = (it / 5).toInt() * 5f }, valueRange = 50f..200f)
            Hint("Affects wild, trainer and your own Pokémon alike. Values are capped at 255.")
            FilledTonalButton(onClick = { vm.edited("Scaled ${ed.scaleStats(scale.toInt())} Pokémon to ${scale.toInt()}%") }) { Text("Apply scaling") }
        }
        SectionCard("Randomizer") {
            OutlinedTextField(
                seedText, { seedText = it.filter(Char::isDigit).take(9) }, label = { Text("Seed") },
                singleLine = true, supportingText = { Text("Same seed + same ROM = same result, so you can share it.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.edited("Randomized stats of ${ed.randomizeStats(seed)} Pokémon") }) { Text("Stats") }
                FilledTonalButton(onClick = { vm.edited("Randomized types of ${ed.randomizeTypes(seed)} Pokémon") }) { Text("Types") }
            }
            Hint("Stat randomizing keeps each Pokémon's total, so legendaries stay strong and Magikarp stays weak.")
        }
    }
}
