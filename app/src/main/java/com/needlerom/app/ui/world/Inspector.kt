@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.world

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.ByteField
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.Picker
import com.needlerom.core.world.Dialogue
import com.needlerom.core.world.Gen1Map
import com.needlerom.core.world.Gen1Text
import com.needlerom.core.world.Gen1World
import com.needlerom.core.world.Movement
import com.needlerom.core.world.NpcKind

/** Towns and routes load a fixed set of sprites per area; other maps load what they need. */
private fun usesSpriteSets(map: Gen1Map) = map.ref.id < 0x25

@Composable
fun Inspector(vm: AppViewModel, world: Gen1World, map: Gen1Map, st: EditorState, gfx: GfxCache) {
    var editingText by remember { mutableStateOf<Int?>(null) }
    var pickingSprite by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val sel = st.selection) {
            is Selection.Npc -> {
                val o = map.objects.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.clickable { pickingSprite = true }) { SpriteThumb(gfx, o.sprite, 48.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (o.kind) { NpcKind.PERSON -> "NPC"; NpcKind.TRAINER -> "Trainer"; NpcKind.ITEM -> "Item ball" } + " #${o.index + 1}",
                            fontWeight = FontWeight.Bold,
                        )
                        Hint("Step (${o.x}, ${o.y}) · tap the sprite to change it")
                    }
                    TextButton(onClick = { st.selection = null }) { Text("Done") }
                }
                Picker("Movement", movementLabel(o.movement, o.range), Movement.entries, { it.label }, Modifier.fillMaxWidth()) {
                    map.setMovement(o, it); vm.edited()
                }
                when (o.kind) {
                    NpcKind.ITEM -> Picker("Contains", world.itemName(o.extra1), world.itemIds, { world.itemName(it) }, Modifier.fillMaxWidth()) {
                        map.setItem(o, it); vm.edited("Item ball now holds ${world.itemName(it)}")
                    }
                    NpcKind.TRAINER -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ByteField("Team #", o.extra2, Modifier.weight(1f), max = 63) { map.setTrainerParty(o, it); vm.edited() }
                        Hint("Which of this trainer class's teams they use")
                    }
                    NpcKind.PERSON -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.kind != NpcKind.ITEM) OutlinedButton(onClick = { editingText = o.textId }) { Text("Edit dialogue") }
                    if (o.index >= map.firstAddedIndex) {
                        TextButton(onClick = {
                            map.removeObject(o)?.let(vm::toast) ?: run { st.selection = null; vm.edited("NPC removed") }
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            is Selection.SignPost -> {
                val g = map.signs.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sign #${g.index + 1}", fontWeight = FontWeight.Bold)
                        Hint("Step (${g.x}, ${g.y}) · drag on the map to move")
                    }
                    TextButton(onClick = { st.selection = null }) { Text("Done") }
                }
                OutlinedButton(onClick = { editingText = g.textId }) { Text("Edit sign text") }
            }
            is Selection.Door -> {
                val w = map.warps.getOrNull(sel.index) ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Door / warp #${w.index + 1}", fontWeight = FontWeight.Bold)
                        Hint("Step (${w.x}, ${w.y}) · drag on the map to move")
                    }
                    TextButton(onClick = { st.selection = null }) { Text("Done") }
                }
                val destName = if (w.destMap == 0xFF) "Back outside (previous map)" else Gen1World.mapName(w.destMap)
                Picker("Leads to", destName, listOf(-1) + world.maps.map { it.id },
                    { if (it < 0) "Back outside (previous map)" else Gen1World.mapName(it) }, Modifier.fillMaxWidth()) {
                    map.setWarpDestination(w, if (it < 0) 0xFF else it, w.destWarp); vm.edited("Door now leads to ${if (it < 0) "the previous map" else Gen1World.mapName(it)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ByteField("Arrive at door #", w.destWarp + 1, Modifier.weight(1f), max = 32) {
                        if (it >= 1) { map.setWarpDestination(w, w.destMap, it - 1); vm.edited() }
                    }
                    Hint("Doors work in pairs: also edit the door on the other side so the player can come back.")
                }
            }
            null -> {}
        }
    }

    editingText?.let { id ->
        DialogueEditor(map.dialogue(id), onDismiss = { editingText = null }) { d, text ->
            val err = map.setDialogue(d, text)
            if (err == null) { vm.edited("Dialogue saved"); editingText = null }
            err
        }
    }

    if (pickingSprite) {
        val o = (st.selection as? Selection.Npc)?.let { map.objects.getOrNull(it.index) }
        if (o != null) {
            SpritePicker(world, map, gfx, current = o.sprite, onDismiss = { pickingSprite = false }) {
                map.setSprite(o, it); vm.edited(); pickingSprite = false
            }
        } else pickingSprite = false
    }
}

@Composable
fun SpritePicker(world: Gen1World, map: Gen1Map, gfx: GfxCache, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val used = remember(map) { map.objects.map { it.sprite }.toSet() }
    val all = (1..maxOf(world.spriteCount, 1)).sortedByDescending { it in used }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a sprite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usesSpriteSets(map)) {
                    Hint("Towns and routes can only show sprites from their area's sprite set. The ones already on this map (shown first, outlined) are always safe; others may look glitched in game.")
                }
                LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.heightIn(max = 360.dp)) {
                    items(all) { id ->
                        val outline = when {
                            id == current -> MaterialTheme.colorScheme.secondary
                            id in used -> MaterialTheme.colorScheme.primary
                            else -> Color.Transparent
                        }
                        Column(
                            Modifier.padding(3.dp).border(2.dp, outline, RoundedCornerShape(8.dp)).clickable { onPick(id) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SpriteThumb(gfx, id, 40.dp)
                            Text("$id", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun AddNpcDialog(world: Gen1World, map: Gen1Map, gfx: GfxCache, onDismiss: () -> Unit, onAdd: (sprite: Int, movement: Movement, textId: Int) -> Unit) {
    val textIds = remember(map) { map.usedTextIds.filter { map.dialogue(it).editable } }
    var sprite by remember { mutableStateOf(map.objects.firstOrNull()?.sprite ?: 1) }
    var movement by remember { mutableStateOf(Movement.WANDER) }
    var textId by remember { mutableStateOf(textIds.firstOrNull() ?: map.usedTextIds.firstOrNull() ?: 1) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New NPC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.clickable { picking = true }) { SpriteThumb(gfx, sprite, 56.dp) }
                    Hint("Tap to choose the sprite")
                }
                Picker("Movement", movement.label, Movement.entries, { it.label }, Modifier.fillMaxWidth()) { movement = it }
                if (textIds.isNotEmpty()) {
                    Picker("Says", preview(map.dialogue(textId)), textIds, { preview(map.dialogue(it)) }, Modifier.fillMaxWidth()) { textId = it }
                    Hint("New NPCs share a line of dialogue with someone already on this map. Edit it afterwards to change what both say.")
                } else {
                    Hint("Nobody on this map has plain dialogue to share, so this NPC will reuse text #$textId.")
                }
                Hint("The game's map data gets moved to free space in the ROM to make room. Up to ${Gen1Map.MAX_OBJECTS} NPCs per map.")
            }
        },
        confirmButton = { Button(onClick = { onAdd(sprite, movement, textId) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) SpritePicker(world, map, gfx, sprite, onDismiss = { picking = false }) { sprite = it; picking = false }
}

private fun preview(d: Dialogue): String =
    if (!d.editable) "Text #${d.textId} (scripted)" else d.text.replace('\n', ' ').take(34).ifBlank { "Text #${d.textId}" }

@Composable
fun DialogueEditor(d: Dialogue, onDismiss: () -> Unit, onSave: (Dialogue, String) -> String?) {
    var text by remember(d) { mutableStateOf(d.text) }
    var error by remember(d) { mutableStateOf<String?>(null) }
    val used = Gen1Text.encode(text).getOrNull()?.size
    val room = d.length - 1
    val long = Gen1Text.longLines(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dialogue #${d.textId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!d.editable) {
                    Text(d.note ?: "This text can't be edited here.")
                    return@Column
                }
                OutlinedTextField(
                    text, { text = it; error = null }, Modifier.fillMaxWidth(), minLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null || (used != null && used > room),
                    supportingText = { Text(error ?: "${used ?: "?"}/$room bytes") },
                )
                if (long.isNotEmpty()) Hint("⚠ Lines over ${Gen1Text.LINE_WIDTH} characters run off the text box: \"${long.first().take(24)}…\"")
                Hint("New line = next line. Blank line = new text box. Codes: <PLAYER> <RIVAL> <POKE>. Shared by every NPC that uses text #${d.textId}.")
            }
        },
        confirmButton = {
            if (d.editable) Button(onClick = { error = onSave(d, text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (d.editable) "Cancel" else "Close") } },
    )
}
