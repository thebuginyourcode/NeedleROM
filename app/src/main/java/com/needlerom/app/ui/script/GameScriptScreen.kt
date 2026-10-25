@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.Screen
import com.needlerom.app.ui.world.TextDialog
import com.needlerom.engine.pokered.GameLine
import com.needlerom.engine.pokered.GameScripts
import com.needlerom.engine.pokered.GameScripts.Block
import com.needlerom.engine.pokered.GameScripts.Cond
import com.needlerom.engine.pokered.GameText
import com.needlerom.engine.pokered.PokeRed

/**
 * An original game script shown as read-only blocks. Its lines of dialogue can be edited
 * (the game keeps running its own code); simple scripts can become editable events.
 */
@Composable
fun GameScriptScreen(state: AppState, r: Route.GameScript) {
    val game = state.game ?: return
    val rev = state.revision
    val map = remember(r.mapId, rev) { game.world.map(r.mapId) }
    val outline = remember(map, r.textId) { GameScripts.outline(game, map, r.textId) }
    val why = remember(outline) { outline?.let { GameScripts.whyNotConvertible(game, it) } }
    var editing by remember { mutableStateOf<GameLine?>(null) }
    var converting by remember { mutableStateOf(false) }

    Screen(
        title = "Game script",
        subtitle = r.title,
        onBack = { state.back() },
        actions = { Button(onClick = { converting = true }, enabled = outline != null && why == null) { Text("Convert to event") } },
    ) {
        if (outline == null) {
            Hint("This text isn't a game script any more (it may have been converted or replaced).")
            return@Screen
        }
        Hint("This is the original game's own code, read as blocks. Tap a line of dialogue to change it; the logic stays as it is so the story keeps working.")
        if (why != null) Text("Can't be converted to an event: $why.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        BlockList(game, outline.blocks) { editing = it }
    }

    editing?.let { line ->
        TextDialog("Edit line", line.text, onDismiss = { editing = null }) { text ->
            if (state.edit("Line saved") { GameText.write(game, line, text) }) editing = null
        }
    }
    if (converting && outline != null) AlertDialog(
        onDismissRequest = { converting = false },
        title = { Text("Convert to an event?") },
        text = {
            Text("This text will run an editable event that does the same thing instead of the game's code. " +
                "If one of the map's own cutscenes shows this text, it will run the event too. You can undo this.")
        },
        confirmButton = {
            Button(onClick = {
                converting = false
                if (state.edit("Converted to an event") { map.setScript(r.textId, GameScripts.toEvent(game, outline)) }) {
                    state.replace(Route.ScriptEditor(r.mapId, r.textId, r.title))
                }
            }) { Text("Convert") }
        },
        dismissButton = { TextButton(onClick = { converting = false }) { Text("Cancel") } },
    )
}

@Composable
private fun BlockList(game: PokeRed, blocks: List<Block>, onEdit: (GameLine) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { BlockCard(game, it, onEdit) }
    }
}

@Composable
private fun BlockCard(game: PokeRed, b: Block, onEdit: (GameLine) -> Unit) {
    val (color, title, detail) = describe(game, b)
    val line = (b as? Block.Say)?.line
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).then(if (line != null) Modifier.clickable { onEdit(line) } else Modifier)) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (line != null) Icon(Icons.Filled.Edit, "Edit line", Modifier.padding(8.dp).size(18.dp).clickable { onEdit(line) })
                    else if (b is Block.Code) Icon(Icons.Filled.Lock, "Game code", Modifier.padding(8.dp).size(16.dp), tint = MaterialTheme.colorScheme.outline)
                }
                when (b) {
                    is Block.If -> {
                        Branch(if (b.cond == Cond.Yes) "If YES" else "If so") { BlockList(game, b.then, onEdit) }
                        Branch(if (b.cond == Cond.Yes) "If NO" else "Otherwise") { BlockList(game, b.otherwise, onEdit) }
                    }
                    is Block.GiveItem -> b.ifBagFull?.let { Branch("If the bag is full") { BlockList(game, it, onEdit) } }
                    is Block.GivePokemon -> b.ifNoRoom?.let { Branch("If there's no room") { BlockList(game, it, onEdit) } }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Branch(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        content()
    }
}

private fun describe(g: PokeRed, b: Block): Triple<Color, String, String?> = when (b) {
    is Block.Say -> Triple(Color(0xFF6BB3F0), "Say", "“${b.line.text.replace('\n', ' ')}”")
    is Block.TextRam -> Triple(Color(0xFF6BB3F0), "…shows a name from memory", "e.g. the item just received")
    is Block.TextSound -> Triple(Color(0xFF5FD7C8), "Play a jingle", null)
    is Block.If -> Triple(Color(0xFFFFB454), when (val c = b.cond) {
        is Cond.Event -> "If story flag “${g.eventName(c.event)}” is set"
        is Cond.HasItem -> "If player has ${g.itemName(c.item)}"
        Cond.Yes -> "Ask yes / no"
        is Cond.Other -> "If ${c.what} (game code)"
    }, null)
    is Block.SetEvent -> Triple(Color(0xFFFFB454), (if (b.value) "Set" else "Clear") + " story flag “${g.eventName(b.event)}”", null)
    is Block.GiveItem -> Triple(Color(0xFF8EE08A), "Give ${g.itemName(b.item)} ×${b.quantity}", null)
    is Block.GivePokemon -> Triple(Color(0xFF8EE08A), "Give ${g.monName(b.species)} Lv${b.level}", null)
    is Block.WildBattle -> Triple(Color(0xFFFF7A7A), "Wild battle: ${g.monName(b.species)} Lv${b.level}", null)
    is Block.PlaySound -> Triple(Color(0xFF5FD7C8), "Play sound $${b.sound.toString(16)}", null)
    is Block.Code -> Triple(Color(0xFF6B7682), "Game code", b.what)
    Block.End -> Triple(Color(0xFFA9B2BD), "End", null)
}
