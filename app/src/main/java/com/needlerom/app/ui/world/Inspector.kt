@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.world

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.Route
import com.needlerom.app.ui.Chooser
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.NumberField
import com.needlerom.app.ui.Pixel
import com.needlerom.engine.pokered.Behavior
import com.needlerom.engine.pokered.GameMap
import com.needlerom.engine.pokered.Movement
import com.needlerom.engine.pokered.NpcKind
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.pokered.Text as GameText

/** Panel shown under the map for the selected NPC, sign or door. */
@Composable
fun Inspector(state: AppState, game: PokeRed, map: GameMap, st: EditorState, gfx: GfxCache) {
    var pickingSprite by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val sel = st.selection) {
            is Selection.Npc -> {
                val n = map.npcs.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.clickable { pickingSprite = true }) { Pixel(gfx.sprite(n.sprite), 48.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(when (n.kind) { NpcKind.PERSON -> "NPC"; NpcKind.TRAINER -> "Trainer"; NpcKind.ITEM -> "Item ball" } + " #${n.index + 1}", fontWeight = FontWeight.Bold)
                        Hint("${game.spriteName(n.sprite)} · (${n.x}, ${n.y}) · tap sprite to change")
                    }
                    androidx.compose.material3.IconButton(onClick = { st.selection = null }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Deselect") }
                }
                Chooser("Movement", Movement.of(n.movement, n.range)?.label ?: "Custom", Movement.entries.toList(), { it.label }, Modifier.fillMaxWidth()) { m ->
                    state.edit { map.setMovement(n, m) }
                }
                when (n.kind) {
                    NpcKind.ITEM -> Chooser("Contains", game.itemName(n.extra1), game.allItemIds, { game.itemName(it) }, Modifier.fillMaxWidth()) { item ->
                        state.edit("Item ball now holds ${game.itemName(item)}") { map.setItemBall(n, item) }
                    }
                    NpcKind.TRAINER -> {
                        val names = game.trainerClassNames
                        Chooser("Trainer class", names.getOrElse(n.trainerClass) { "?" }, (1..PokeRed.NUM_TRAINER_CLASSES).toList(), { names[it] }, Modifier.fillMaxWidth()) { cls ->
                            state.edit { map.setTrainer(n, cls, 1) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Team #", n.extra2, Modifier.weight(1f), 1..64) { team -> state.edit { map.setTrainer(n, n.trainerClass, team) } }
                            OutlinedButton(onClick = { state.go(Route.TrainerEditor(n.trainerClass)) }) { Text("Edit teams") }
                        }
                    }
                    NpcKind.PERSON -> BehaviorEditor(state, game, map, n.textId, isNpc = true, npcIndex = n.index)
                }
                if (n.index >= map.firstAddedNpc) {
                    TextButton(onClick = { if (state.edit("NPC removed") { map.removeNpc(n) }) st.selection = null }) {
                        Text("Delete NPC", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            is Selection.SignPost -> {
                val s = map.signs.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sign #${s.index + 1}", fontWeight = FontWeight.Bold)
                        Hint("(${s.x}, ${s.y}) · drag on the map to move")
                    }
                    androidx.compose.material3.IconButton(onClick = { st.selection = null }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Deselect") }
                }
                BehaviorEditor(state, game, map, s.textId, isNpc = false, npcIndex = -1)
                if (s.index >= map.firstAddedSign) {
                    TextButton(onClick = { if (state.edit("Sign removed") { map.removeSign(s) }) st.selection = null }) {
                        Text("Delete sign", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            is Selection.Door -> {
                val w = map.warps.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Door #${w.index + 1}", fontWeight = FontWeight.Bold)
                        Hint("(${w.x}, ${w.y}) · drag on the map to move")
                    }
                    androidx.compose.material3.IconButton(onClick = { st.selection = null }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Deselect") }
                }
                val dest = if (w.destMap == 0xFF) "Back outside (where the player came from)" else state.mapName(w.destMap)
                Chooser("Leads to", dest, listOf(0xFF) + game.world.maps.map { it.id },
                    { if (it == 0xFF) "Back outside (where the player came from)" else state.mapName(it) }, Modifier.fillMaxWidth()) { target ->
                    state.edit("Door now leads to ${if (target == 0xFF) "the previous map" else state.mapName(target)}") { map.setWarpDestination(w, target, w.destWarp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Arrive at door #", w.destWarp, Modifier.weight(1f), 1..32) { door -> state.edit { map.setWarpDestination(w, w.destMap, door) } }
                    if (w.destMap != 0xFF) OutlinedButton(onClick = { state.go(Route.MapEditor(w.destMap)) }) { Text("Open map") }
                }
                Hint("Doors work in pairs: the door on the other map should lead back here.")
                if (w.index >= map.firstAddedWarp) {
                    TextButton(onClick = { if (state.edit("Door removed") { map.removeWarp(w) }) st.selection = null }) {
                        Text("Delete door", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            is Selection.Trigger -> {
                val t = map.triggers.firstOrNull { it.textId == sel.textId } ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trigger", fontWeight = FontWeight.Bold)
                        Hint(if (t.x == null) "Runs when the player enters this map" else "(${t.x}, ${t.y}) · ${t.width}×${t.height} tiles · runs when the player steps in it · drag to move")
                    }
                    androidx.compose.material3.IconButton(onClick = { st.selection = null }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, "Deselect") }
                }
                Hint("Runs once per game (remembered with flag ${t.flag}).")
                if (t.x != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", t.width, Modifier.weight(1f), 1..16) { w -> state.edit { map.resizeTrigger(t, w, t.height) } }
                    NumberField("Height", t.height, Modifier.weight(1f), 1..16) { h -> state.edit { map.resizeTrigger(t, t.width, h) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { state.go(Route.ScriptEditor(map.id, t.textId, "${map.info.name} · trigger")) }) { Text("Edit event") }
                    OutlinedButton(onClick = {
                        state.edit { if (t.x == null) map.moveTrigger(t, map.stepWidth / 2, map.stepHeight / 2) else map.moveTrigger(t, null, null) }
                    }) { Text(if (t.x == null) "Use a tile" else "Whole map") }
                }
                TextButton(onClick = { if (state.edit("Trigger removed") { map.removeTrigger(t) }) st.selection = null }) {
                    Text("Delete trigger", color = MaterialTheme.colorScheme.error)
                }
            }
            null -> {}
        }
    }
    if (pickingSprite) {
        val n = (st.selection as? Selection.Npc)?.let { map.npcs.getOrNull(it.index) }
        if (n == null) pickingSprite = false
        else SpritePicker(game, map, gfx, n.sprite, onDismiss = { pickingSprite = false }) { sprite -> state.edit { map.setSprite(n, sprite) }; pickingSprite = false }
    }
}

/** What an NPC/sign does: talk, shop, nurse, PC or an event script. */
@Composable
private fun BehaviorEditor(state: AppState, game: PokeRed, map: GameMap, textId: Int, isNpc: Boolean, npcIndex: Int) {
    val behavior = map.behavior(textId)
    var editingText by remember { mutableStateOf(false) }
    var editingMart by remember { mutableStateOf(false) }
    val shared = map.isTextShared(textId)
    Text("Behaviour: ${behavior.label}", fontWeight = FontWeight.SemiBold)
    when (behavior) {
        is Behavior.Talk -> Text("“${behavior.text.replace("\n", " ").take(90)}”", style = MaterialTheme.typography.bodySmall)
        is Behavior.Mart -> Hint(behavior.items.joinToString { game.itemName(it) })
        Behavior.GameCode -> Hint("Runs the original game's script (a cutscene, battle or special event). Replacing it may break the story here.")
        else -> {}
    }
    if (shared && isNpc) {
        Hint("Shared with another NPC or sign, so changes affect both.")
        TextButton(onClick = { state.edit("This NPC now has its own text") { map.giveOwnText(map.npcs[npcIndex]) } }) { Text("Give it its own text") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { editingText = true }) { Text(if (behavior is Behavior.Talk) "Edit text" else "Talk") }
        OutlinedButton(onClick = { editingMart = true }) { Text("Shop") }
        OutlinedButton(onClick = {
            if (behavior != Behavior.Script) state.edit { map.setScript(textId, com.needlerom.engine.script.EventScript(listOf(com.needlerom.engine.script.Step.Say("Hello!")))) }
            state.go(Route.ScriptEditor(map.id, textId, "${map.info.name} · text #$textId"))
        }) { Text("Event") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { state.edit("Now a Pokécenter nurse") { map.setBehavior(textId, Behavior.Nurse) } }) { Text("Nurse") }
        TextButton(onClick = { state.edit("Now a Pokécenter PC") { map.setBehavior(textId, Behavior.PokecenterPC) } }) { Text("PC") }
    }
    if (editingText) {
        TextDialog("Dialogue", (behavior as? Behavior.Talk)?.text ?: "", onDismiss = { editingText = false }) { t ->
            if (state.edit("Dialogue saved") { map.setBehavior(textId, Behavior.Talk(t)) }) editingText = false
        }
    }
    if (editingMart) {
        MartDialog(game, (behavior as? Behavior.Mart)?.items ?: listOf(4, 0x14, 0x0B), onDismiss = { editingMart = false }) { items ->
            if (state.edit("Shop saved") { map.setBehavior(textId, Behavior.Mart(items)) }) editingMart = false
        }
    }
}

/** Multi-line game text editor with live font and line-width checks. */
@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val error = remember(text) { runCatching { GameText.encodeDialogue(text) }.exceptionOrNull()?.message }
    val long = remember(text) { GameText.longLines(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null, supportingText = { Text(error ?: "${text.length} characters") })
                if (long.isNotEmpty()) Text("Lines over ${GameText.LINE_WIDTH} characters run off the box: “${long.first().take(20)}…”",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Hint("Enter = next line · blank line = new text box · <PLAYER> <RIVAL> <POKE>")
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }, enabled = error == null && text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MartDialog(game: PokeRed, initial: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    val items = remember { mutableStateListOf<Int>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shop inventory") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chooser("Item ${i + 1}", game.itemName(item), game.allItemIds, { game.itemName(it) }, Modifier.weight(1f)) { items[i] = it }
                        TextButton(onClick = { items.removeAt(i) }) { Text("✕") }
                    }
                }
                if (items.size < 10) TextButton(onClick = { items += 4 }) { Text("+ Add item") }
                Hint("Prices come from the item data (Data → Items).")
            }
        },
        confirmButton = { Button(onClick = { onSave(items.toList()) }, enabled = items.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SpritePicker(game: PokeRed, map: GameMap, gfx: GfxCache, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val used = remember(map) { map.npcs.map { it.sprite }.toSet() }
    val all = (1..game.world.spriteCount).sortedByDescending { it in used }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sprite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (map.id <= 0x24) Hint("Towns and routes can only show sprites from their area's sprite set. The ones already on this map (outlined, shown first) are always safe.")
                LazyVerticalGrid(GridCells.Adaptive(60.dp), Modifier.heightIn(max = 380.dp)) {
                    items(all) { id ->
                        val outline = when (id) { current -> MaterialTheme.colorScheme.secondary; in used -> MaterialTheme.colorScheme.primary; else -> Color.Transparent }
                        Column(Modifier.padding(3.dp).border(2.dp, outline, RoundedCornerShape(8.dp)).clickable { onPick(id) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Pixel(gfx.sprite(id), 40.dp)
                            Text(game.spriteName(id), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun AddNpcDialog(game: PokeRed, map: GameMap, gfx: GfxCache, onDismiss: () -> Unit, onAdd: (Int, Movement, Behavior) -> Unit) {
    var sprite by remember { mutableStateOf(map.npcs.firstOrNull()?.sprite ?: 4) }
    var movement by remember { mutableStateOf(Movement.WANDER) }
    var text by remember { mutableStateOf("Hi! I'm new here.") }
    var picking by remember { mutableStateOf(false) }
    val error = remember(text) { runCatching { GameText.encodeDialogue(text) }.exceptionOrNull()?.message }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New NPC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.clickable { picking = true }) { Pixel(gfx.sprite(sprite), 56.dp) }
                    Hint("${game.spriteName(sprite)} · tap to change")
                }
                Chooser("Movement", movement.label, Movement.entries.toList(), { it.label }, Modifier.fillMaxWidth()) { movement = it }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("What they say") },
                    isError = error != null, supportingText = { error?.let { Text(it) } })
                Hint("You can turn them into a shopkeeper, nurse or event afterwards. Up to ${map.maxNpcs} NPCs fit on this map.")
            }
        },
        confirmButton = { Button(onClick = { onAdd(sprite, movement, Behavior.Talk(text)) }, enabled = error == null && text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) SpritePicker(game, map, gfx, sprite, onDismiss = { picking = false }) { sprite = it; picking = false }
}
