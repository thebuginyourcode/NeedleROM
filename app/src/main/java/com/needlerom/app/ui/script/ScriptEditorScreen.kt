@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.Route
import com.needlerom.app.ui.Chooser
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.NumberField
import com.needlerom.app.ui.Screen
import com.needlerom.app.ui.world.TextDialog
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.script.EventScript
import com.needlerom.engine.script.Flags
import com.needlerom.engine.script.Step
import com.needlerom.engine.script.Templates

/** Names and pickers shared by the step editors. */
private class Ctx(val game: PokeRed, val flagNames: Map<Int, String>) {
    val species = game.species.map { it.first }
    fun mon(internal: Int) = "#${game.dexOf(internal).toString().padStart(3, '0')} ${game.monName(internal)}"
    fun item(id: Int) = game.itemName(id)
    fun flag(n: Int) = flagNames[n]?.takeIf { it.isNotBlank() }?.let { "$it (flag $n)" } ?: "Flag $n"
    fun trainer(c: Int) = game.trainerClassNames.getOrElse(c) { "Class $c" }
}

@Composable
fun ScriptEditorScreen(state: AppState, r: Route.ScriptEditor) {
    val game = state.game ?: return
    val map = remember(r.mapId, state.revision) { game.world.map(r.mapId) }
    val saved = remember(r) { map.script(r.textId) ?: EventScript(emptyList()) }
    var steps by remember(r) { mutableStateOf(saved.steps) }
    val dirty = steps != saved.steps
    val ctx = remember(game, state.project) { Ctx(game, state.project?.flagNames ?: emptyMap()) }
    var templates by remember { mutableStateOf(false) }

    Screen(
        title = "Event script",
        subtitle = r.title,
        onBack = { state.back() },
        actions = {
            TextButton(onClick = { templates = true }) { Text("Templates") }
            Button(enabled = dirty, onClick = {
                state.edit("Event saved") { map.setScript(r.textId, EventScript(steps)) }
            }) { Text("Save") }
        },
    ) {
        Hint("Runs when the player talks to this NPC or reads this sign. Steps run top to bottom; battles start when the text box closes.")
        StepList(ctx, steps, depth = 0) { steps = it }
        if (dirty) Hint("Unsaved changes. Tap Save, then playtest.")
    }

    if (templates) {
        val freeFlag = (0 until Flags.COUNT).firstOrNull { it !in usedFlags(steps) } ?: 0
        AlertDialog(
            onDismissRequest = { templates = false },
            title = { Text("Start from a template") },
            text = {
                Column {
                    val opts = listOf(
                        "Item gift (once)" to { Templates.itemGift(1, ctx.item(1), freeFlag) },
                        "Pokémon gift (once)" to { val m = game.internalOfDex(151); Templates.pokemonGift(m, game.monName(m), 10, freeFlag) },
                        "Healer" to { Templates.healer() },
                        "Legendary encounter (once)" to { val m = game.internalOfDex(146); Templates.legendary(m, game.monName(m), 50, freeFlag) },
                        "Trainer battle (once)" to { Templates.trainer(1, 1, freeFlag) },
                    )
                    opts.forEach { (label, make) ->
                        TextButton(onClick = { steps = make().steps; templates = false }) { Text(label) }
                    }
                    Hint("Uses flag $freeFlag to remember it happened. Replaces the current steps.")
                }
            },
            confirmButton = { TextButton(onClick = { templates = false }) { Text("Cancel") } },
        )
    }
}

private fun usedFlags(steps: List<Step>): Set<Int> = steps.flatMap { s ->
    when (s) {
        is Step.SetFlag -> listOf(s.flag)
        is Step.ClearFlag -> listOf(s.flag)
        is Step.IfFlag -> listOf(s.flag) + usedFlags(s.then) + usedFlags(s.otherwise)
        is Step.Ask -> usedFlags(s.yes) + usedFlags(s.no)
        is Step.IfHasItem -> usedFlags(s.then) + usedFlags(s.otherwise)
        is Step.GiveItem -> usedFlags(s.ifBagFull)
        is Step.GivePokemon -> usedFlags(s.ifNoRoom)
        else -> emptyList()
    }
}.toSet()

@Composable
private fun StepList(ctx: Ctx, steps: List<Step>, depth: Int, onChange: (List<Step>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            StepCard(ctx, step, depth,
                onChange = { new -> onChange(steps.toMutableList().also { l -> if (new == null) l.removeAt(i) else l[i] = new }) },
                onMove = { delta ->
                    val j = i + delta
                    if (j in steps.indices) onChange(steps.toMutableList().also { l -> val t = l[i]; l[i] = l[j]; l[j] = t })
                },
            )
        }
        TextButton(onClick = { adding = true }) { Text(if (steps.isEmpty()) "+ Add a step" else "+ Add step") }
    }
    if (adding) AddStepDialog(ctx, onDismiss = { adding = false }) { onChange(steps + it); adding = false }
}

private val BLOCK_COLORS = listOf(Color(0xFF2C4A22), Color(0xFF3A2C4A), Color(0xFF22404A), Color(0xFF4A3A22))

@Composable
private fun StepCard(ctx: Ctx, step: Step, depth: Int, onChange: (Step?) -> Unit, onMove: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, BLOCK_COLORS[depth % BLOCK_COLORS.size]),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title(ctx, step), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    detail(ctx, step)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (hasEditor(step)) TextButton(onClick = { editing = true }) { Text("Edit") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Step options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onChange(null) })
                    }
                }
            }
            when (step) {
                is Step.Ask -> {
                    Branch("If YES") { StepList(ctx, step.yes, depth + 1) { onChange(step.copy(yes = it)) } }
                    Branch("If NO") { StepList(ctx, step.no, depth + 1) { onChange(step.copy(no = it)) } }
                }
                is Step.IfFlag -> {
                    Branch("If set") { StepList(ctx, step.then, depth + 1) { onChange(step.copy(then = it)) } }
                    Branch("Otherwise") { StepList(ctx, step.otherwise, depth + 1) { onChange(step.copy(otherwise = it)) } }
                }
                is Step.IfHasItem -> {
                    Branch("If they have it") { StepList(ctx, step.then, depth + 1) { onChange(step.copy(then = it)) } }
                    Branch("Otherwise") { StepList(ctx, step.otherwise, depth + 1) { onChange(step.copy(otherwise = it)) } }
                }
                is Step.GiveItem -> Branch("If the bag is full (then stop)") { StepList(ctx, step.ifBagFull, depth + 1) { onChange(step.copy(ifBagFull = it)) } }
                is Step.GivePokemon -> Branch("If there's no room (then stop)") { StepList(ctx, step.ifNoRoom, depth + 1) { onChange(step.copy(ifNoRoom = it)) } }
                else -> {}
            }
        }
    }
    if (editing) StepDialog(ctx, step, onDismiss = { editing = false }) { onChange(it); editing = false }
}

@Composable
private fun Branch(label: String, content: @Composable () -> Unit) {
    Row {
        Box(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            content()
        }
    }
}

private fun title(c: Ctx, s: Step): String = when (s) {
    is Step.Say -> "Say"
    is Step.Ask -> "Ask yes / no"
    is Step.GiveItem -> "Give ${c.item(s.item)} ×${s.quantity}"
    is Step.GivePokemon -> "Give ${c.mon(s.species)} Lv${s.level}"
    is Step.TakeItem -> "Take ${c.item(s.item)}"
    is Step.IfHasItem -> "If player has ${c.item(s.item)}"
    is Step.SetFlag -> "Set ${c.flag(s.flag)}"
    is Step.ClearFlag -> "Clear ${c.flag(s.flag)}"
    is Step.IfFlag -> "If ${c.flag(s.flag)} is set"
    is Step.WildBattle -> "Wild battle: ${c.mon(s.species)} Lv${s.level}"
    is Step.TrainerBattle -> "Trainer battle: ${c.trainer(s.trainerClass)} team ${s.team}"
    Step.Heal -> "Heal the party"
    is Step.GiveMoney -> "Give ¥${s.amount}"
    is Step.PlaySound -> "Play sound ${s.sound}"
}

private fun detail(c: Ctx, s: Step): String? = when (s) {
    is Step.Say -> "“${s.text.replace('\n', ' ')}”"
    is Step.Ask -> "“${s.question.replace('\n', ' ')}”"
    is Step.TrainerBattle -> "Win: “${s.winText.replace('\n', ' ')}”"
    else -> null
}

private fun hasEditor(s: Step) = s != Step.Heal

@Composable
private fun AddStepDialog(ctx: Ctx, onDismiss: () -> Unit, onAdd: (Step) -> Unit) {
    val options: List<Pair<String, () -> Step>> = listOf(
        "Say something" to { Step.Say("Hello!") },
        "Ask yes / no" to { Step.Ask("Do you want it?", listOf(Step.Say("Great!")), listOf(Step.Say("Oh well."))) },
        "Give item" to { Step.GiveItem(4, 1) },
        "Give Pokémon" to { Step.GivePokemon(ctx.species.first(), 5) },
        "Take item" to { Step.TakeItem(4) },
        "If player has item" to { Step.IfHasItem(4, emptyList(), emptyList()) },
        "Set flag" to { Step.SetFlag(0) },
        "Clear flag" to { Step.ClearFlag(0) },
        "If flag is set" to { Step.IfFlag(0, emptyList(), emptyList()) },
        "Heal the party" to { Step.Heal },
        "Give money" to { Step.GiveMoney(500) },
        "Wild battle" to { Step.WildBattle(ctx.species.first(), 5) },
        "Trainer battle" to { Step.TrainerBattle(1, 1) },
        "Play sound" to { Step.PlaySound(0x86) },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add step") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (label, make) -> TextButton(onClick = { onAdd(make()) }) { Text(label) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Edits the fields of one step. Branch contents are edited inline in the card. */
@Composable
private fun StepDialog(ctx: Ctx, step: Step, onDismiss: () -> Unit, onSave: (Step) -> Unit) {
    when (step) {
        is Step.Say -> { TextDialog("Say", step.text, onDismiss) { onSave(Step.Say(it)) }; return }
        is Step.Ask -> { TextDialog("Question", step.question, onDismiss) { onSave(step.copy(question = it)) }; return }
        else -> {}
    }
    var s by remember { mutableStateOf(step) }
    var winEdit by remember { mutableStateOf(false) }
    var loseEdit by remember { mutableStateOf(false) }
    val flags = (0 until Flags.COUNT).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title(ctx, s)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val v = s) {
                    is Step.GiveItem -> {
                        Chooser("Item", ctx.item(v.item), ctx.game.allItemIds, ctx::item, Modifier.fillMaxWidth()) { s = v.copy(item = it) }
                        NumberField("Quantity", v.quantity, Modifier.fillMaxWidth(), 1..99) { s = v.copy(quantity = it) }
                    }
                    is Step.GivePokemon -> {
                        Chooser("Pokémon", ctx.mon(v.species), ctx.species, ctx::mon, Modifier.fillMaxWidth()) { s = v.copy(species = it) }
                        NumberField("Level", v.level, Modifier.fillMaxWidth(), 1..100) { s = v.copy(level = it) }
                    }
                    is Step.TakeItem -> Chooser("Item", ctx.item(v.item), ctx.game.allItemIds, ctx::item, Modifier.fillMaxWidth()) { s = v.copy(item = it) }
                    is Step.IfHasItem -> Chooser("Item", ctx.item(v.item), ctx.game.allItemIds, ctx::item, Modifier.fillMaxWidth()) { s = v.copy(item = it) }
                    is Step.SetFlag -> Chooser("Flag", ctx.flag(v.flag), flags, ctx::flag, Modifier.fillMaxWidth()) { s = v.copy(flag = it) }
                    is Step.ClearFlag -> Chooser("Flag", ctx.flag(v.flag), flags, ctx::flag, Modifier.fillMaxWidth()) { s = v.copy(flag = it) }
                    is Step.IfFlag -> Chooser("Flag", ctx.flag(v.flag), flags, ctx::flag, Modifier.fillMaxWidth()) { s = v.copy(flag = it) }
                    is Step.WildBattle -> {
                        Chooser("Pokémon", ctx.mon(v.species), ctx.species, ctx::mon, Modifier.fillMaxWidth()) { s = v.copy(species = it) }
                        NumberField("Level", v.level, Modifier.fillMaxWidth(), 1..100) { s = v.copy(level = it) }
                    }
                    is Step.TrainerBattle -> {
                        Chooser("Trainer class", ctx.trainer(v.trainerClass), (1..PokeRed.NUM_TRAINER_CLASSES).toList(), ctx::trainer, Modifier.fillMaxWidth()) { s = v.copy(trainerClass = it) }
                        NumberField("Team #", v.team, Modifier.fillMaxWidth(), 1..64) { s = v.copy(team = it) }
                        TextButton(onClick = { winEdit = true }) { Text("When the player wins: “${v.winText.replace('\n', ' ')}”") }
                        TextButton(onClick = { loseEdit = true }) { Text("When the player loses: “${v.loseText.replace('\n', ' ')}”") }
                        Hint("Teams are edited in Data → Trainers.")
                    }
                    is Step.GiveMoney -> NumberField("Amount (¥)", v.amount, Modifier.fillMaxWidth(), 0..999999) { s = v.copy(amount = it) }
                    is Step.PlaySound -> {
                        NumberField("Sound id", v.sound, Modifier.fillMaxWidth(), 0..255) { s = v.copy(sound = it) }
                        Hint("A sound effect number from the game's sound list.")
                    }
                    else -> {}
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(s) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    val tb = s as? Step.TrainerBattle
    if (winEdit && tb != null) TextDialog("Winning text", tb.winText, { winEdit = false }) { s = tb.copy(winText = it); winEdit = false }
    if (loseEdit && tb != null) TextDialog("Losing text", tb.loseText, { loseEdit = false }) { s = tb.copy(loseText = it); loseEdit = false }
}
