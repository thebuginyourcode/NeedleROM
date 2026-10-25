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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
private class Ctx(val game: PokeRed, val flagNames: Map<Int, String>, val npcs: List<com.needlerom.engine.pokered.Npc>, val isTrigger: Boolean,
                  val mapW: Int, val mapH: Int) {
    fun npc(i: Int) = npcs.getOrNull(i)?.let { "NPC #${i + 1} (${game.spriteName(it.sprite)})" } ?: "NPC #${i + 1}"
    val species = game.species.map { it.first }
    fun mon(internal: Int) = "#${game.dexOf(internal).toString().padStart(3, '0')} ${game.monName(internal)}"
    fun item(id: Int) = game.itemName(id)
    fun flag(n: Int) = flagNames[n]?.takeIf { it.isNotBlank() }?.let { "$it (flag $n)" } ?: "Flag $n"
    fun trainer(c: Int) = game.trainerClassNames.getOrElse(c) { "Class $c" }
    val sounds = com.needlerom.engine.script.ScriptEngine.sounds(game)
    fun sound(id: Int) = sounds.effects.firstOrNull { it.first == id }?.second ?: "Sound $id"
    fun music(id: Int, bank: Int) = sounds.music.firstOrNull { it.first == id && it.second == bank }?.third ?: "Track $id"
}

@Composable
fun ScriptEditorScreen(state: AppState, r: Route.ScriptEditor) {
    val game = state.game ?: return
    val map = remember(r.mapId, state.revision) { game.world.map(r.mapId) }
    val saved = remember(r) { map.script(r.textId) ?: EventScript(emptyList()) }
    var steps by remember(r) { mutableStateOf(saved.steps) }
    val dirty = steps != saved.steps
    val isTrigger = remember(map, r.textId) { map.triggers.any { it.textId == r.textId } }
    val ctx = remember(game, state.project, map) { Ctx(game, state.project?.flagNames ?: emptyMap(), map.npcs, isTrigger, map.stepWidth, map.stepHeight) }
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
        Hint(if (isTrigger) "Runs when the player steps on the trigger. Steps run top to bottom." else "Runs when the player talks to this NPC or reads this sign. Steps run top to bottom; battles start when the text box closes.")
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
                    ) + if (map.npcs.isNotEmpty()) listOf(
                        "Trainer spots you and walks over" to {
                            val y = game.internalOfDex(19); val p = game.internalOfDex(16)
                            Templates.ambush(map.npcs.size - 1, 1, listOf(com.needlerom.engine.pokered.PartyMon(5, y), com.needlerom.engine.pokered.PartyMon(6, p)))
                        },
                    ) else emptyList()
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
        border = BorderStroke(1.dp, if (depth == 0) MaterialTheme.colorScheme.outlineVariant else BLOCK_COLORS[depth % BLOCK_COLORS.size]),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(categoryColor(step)))
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = hasEditor(step)) { editing = true }) {
                    Text(title(ctx, step), fontWeight = FontWeight.SemiBold)
                    detail(ctx, step)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (hasEditor(step)) IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit step", Modifier.size(18.dp)) }
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
    is Step.PlaySound -> "Play sound: ${c.sound(s.sound)}"
    is Step.PlayCry -> "Cry: ${c.mon(s.species)}"
    is Step.PlayMusic -> "Change music: ${c.music(s.music, s.bank)}"
    is Step.WalkToPlayer -> "${c.npc(s.npc)} walks up to the player"
    is Step.Lead -> "${c.npc(s.npc)} says follow me, to (${s.x}, ${s.y})"
}

private fun detail(c: Ctx, s: Step): String? = when (s) {
    is Step.Say -> "“${s.text.replace('\n', ' ')}”"
    is Step.Ask -> "“${s.question.replace('\n', ' ')}”"
    is Step.TrainerBattle -> (s.customTeam?.let { t -> "Custom team: " + t.joinToString { "${c.game.monName(it.species)} Lv${it.level}" } + " · " } ?: "") +
        "Win: “${s.winText.replace('\n', ' ')}”"
    else -> null
}

private fun hasEditor(s: Step) = s != Step.Heal

@Composable
private fun AddStepDialog(ctx: Ctx, onDismiss: () -> Unit, onAdd: (Step) -> Unit) {
    val lastNpc = (ctx.npcs.size - 1).coerceAtLeast(0)
    val groups: List<Pair<String, List<Pair<String, () -> Step>>>> = listOf(
        "Talk" to listOf(
            "Say something" to { Step.Say("Hello!") },
            "Ask yes / no" to { Step.Ask("Do you want it?", listOf(Step.Say("Great!")), listOf(Step.Say("Oh well."))) },
        ),
        "Give & take" to listOf(
            "Give item" to { Step.GiveItem(4, 1) },
            "Take item" to { Step.TakeItem(4) },
            "Give Pokémon" to { Step.GivePokemon(ctx.species.first(), 5) },
            "Give money" to { Step.GiveMoney(500) },
            "Heal the party" to { Step.Heal },
        ),
        "Conditions & flags" to listOf(
            "If player has item" to { Step.IfHasItem(4, emptyList(), emptyList()) },
            "If flag is set" to { Step.IfFlag(0, emptyList(), emptyList()) },
            "Set flag" to { Step.SetFlag(0) },
            "Clear flag" to { Step.ClearFlag(0) },
        ),
        "Battles" to listOf(
            "Wild battle" to { Step.WildBattle(ctx.species.first(), 5) },
            "Trainer battle" to { Step.TrainerBattle(1, 1) },
        ),
        "Movement" to if (ctx.npcs.isEmpty()) emptyList() else listOf(
            "NPC walks up to the player" to { Step.WalkToPlayer(lastNpc) },
            "Follow me (NPC leads the player)" to { Step.Lead(lastNpc, ctx.npcs[lastNpc].x, (ctx.npcs[lastNpc].y - 3).coerceAtLeast(0)) },
        ),
        "Sound" to listOf(
            "Play sound effect" to { Step.PlaySound(ctx.sounds.effects.first().first) },
            "Play a Pokémon's cry" to { Step.PlayCry(ctx.species.first()) },
            "Change music" to { ctx.sounds.music.first().let { Step.PlayMusic(it.first, it.second) } },
        ),
    ).filter { it.second.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add step") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { (group, options) ->
                    Text(group, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium,
                        color = categoryColor(options.first().second()), fontWeight = FontWeight.Bold)
                    options.forEach { (label, make) ->
                        Text(label, Modifier.fillMaxWidth().clickable { onAdd(make()) }.padding(vertical = 9.dp, horizontal = 4.dp))
                    }
                }
                if (ctx.npcs.isEmpty()) Hint("Add an NPC to this map to use movement steps.", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A color per kind of step, so long scripts are easy to scan. */
private fun categoryColor(s: Step): Color = when (s) {
    is Step.Say, is Step.Ask -> Color(0xFF6BB3F0)
    is Step.GiveItem, is Step.TakeItem, is Step.GivePokemon, is Step.GiveMoney, Step.Heal -> Color(0xFF8EE08A)
    is Step.IfHasItem, is Step.IfFlag, is Step.SetFlag, is Step.ClearFlag -> Color(0xFFFFB454)
    is Step.WildBattle, is Step.TrainerBattle -> Color(0xFFFF7A7A)
    is Step.WalkToPlayer, is Step.Lead -> Color(0xFFC792EA)
    is Step.PlaySound, is Step.PlayCry, is Step.PlayMusic -> Color(0xFF5FD7C8)
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
                        val custom = v.customTeam
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(custom != null, { on ->
                                s = v.copy(customTeam = if (on) listOf(com.needlerom.engine.pokered.PartyMon(5, ctx.species.first())) else null)
                            })
                            Text("Custom team for this battle")
                        }
                        if (custom == null) {
                            NumberField("Team #", v.team, Modifier.fillMaxWidth(), 1..64) { s = v.copy(team = it) }
                        } else {
                            custom.forEachIndexed { i, mon ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    NumberField("Lv", mon.level, Modifier.weight(0.5f), 1..100) { lv -> s = v.copy(customTeam = custom.toMutableList().also { it[i] = mon.copy(level = lv) }) }
                                    Chooser("Pokémon", ctx.mon(mon.species), ctx.species, ctx::mon, Modifier.weight(1.2f)) { sp -> s = v.copy(customTeam = custom.toMutableList().also { it[i] = mon.copy(species = sp) }) }
                                    if (custom.size > 1) TextButton(onClick = { s = v.copy(customTeam = custom.toMutableList().also { it.removeAt(i) }) }) { Text("✕") }
                                }
                            }
                            if (custom.size < 6) TextButton(onClick = { s = v.copy(customTeam = custom + custom.last()) }) { Text("+ Add Pokémon") }
                        }
                        TextButton(onClick = { winEdit = true }) { Text("When the player wins: “${v.winText.replace('\n', ' ')}”") }
                        TextButton(onClick = { loseEdit = true }) { Text("When the player loses: “${v.loseText.replace('\n', ' ')}”") }
                        Hint("Teams are edited in Data → Trainers.")
                    }
                    is Step.WalkToPlayer -> {
                        Chooser("Who", ctx.npc(v.npc), ctx.npcs.indices.toList(), ctx::npc, Modifier.fillMaxWidth()) { s = v.copy(npc = it) }
                        Hint("They walk until they're next to the player, then the event continues.")
                    }
                    is Step.Lead -> {
                        Chooser("Who", ctx.npc(v.npc), ctx.npcs.indices.toList(), ctx::npc, Modifier.fillMaxWidth()) { s = v.copy(npc = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("To X", v.x, Modifier.weight(1f), 0 until ctx.mapW) { s = v.copy(x = it) }
                            NumberField("To Y", v.y, Modifier.weight(1f), 0 until ctx.mapH) { s = v.copy(y = it) }
                        }
                        Hint("The NPC walks to that tile and the player follows one step behind, then the event continues. " +
                            "They go in straight lines (through anything in the way), so pick a clear route. Coordinates are shown in the map's Inspector when you select something.")
                    }
                    is Step.GiveMoney -> NumberField("Amount (¥)", v.amount, Modifier.fillMaxWidth(), 0..999999) { s = v.copy(amount = it) }
                    is Step.PlaySound -> {
                        Chooser("Sound effect", ctx.sound(v.sound), ctx.sounds.effects, { it.second }, Modifier.fillMaxWidth()) { s = v.copy(sound = it.first) }
                        Hint("Every sound effect the game can play outside battle.")
                    }
                    is Step.PlayCry -> Chooser("Pokémon", ctx.mon(v.species), ctx.species, ctx::mon, Modifier.fillMaxWidth()) { s = v.copy(species = it) }
                    is Step.PlayMusic -> {
                        Chooser("Music", ctx.music(v.music, v.bank), ctx.sounds.music, { it.third }, Modifier.fillMaxWidth()) { s = Step.PlayMusic(it.first, it.second) }
                        Hint("Plays until the player changes maps.")
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
