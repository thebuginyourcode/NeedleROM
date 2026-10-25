@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.ExportKind
import com.needlerom.app.Route
import com.needlerom.app.Tab
import com.needlerom.app.ui.data.DataTab
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.script.ScriptEngine

@Composable
fun WorkspaceScreen(state: AppState, files: Files, tab: Tab) {
    val game = state.game ?: return
    val project = state.project ?: return
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Screen(
        title = project.name,
        subtitle = game.version.name + if (!game.exact) " (modified ROM)" else "",
        onBack = { state.back() },
        scroll = false,
        actions = {
            TextButton(onClick = { state.undo() }, enabled = game.rom.canUndo.also { state.revision }) { Text("Undo") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ExportKind.entries.forEach { k ->
                        DropdownMenuItem(text = { Text("Export ${k.label}") }, onClick = { menu = false; files.export(k) })
                    }
                    DropdownMenuItem(text = { Text("Rename project") }, onClick = { menu = false; renaming = true })
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { if (t == Tab.PLAY) state.go(Route.Playtest) else state.replace(Route.Workspace(t)) },
                        icon = { Icon(tabIcon(t), null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) {
        when (tab) {
            Tab.WORLD -> WorldTab(state, game)
            Tab.EVENTS -> EventsTab(state, game)
            Tab.DATA -> DataTab(state, game)
            Tab.PLAY -> {}
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { state.renameProject(name.ifBlank { project.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.WORLD -> Icons.Filled.Place
    Tab.EVENTS -> Icons.AutoMirrored.Filled.List
    Tab.DATA -> Icons.Filled.Build
    Tab.PLAY -> Icons.Filled.PlayArrow
}

@Composable
private fun WorldTab(state: AppState, game: PokeRed) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val rev = state.revision
    val created = remember(game, rev) { game.world.createdMaps().toSet() }
    val maps = remember(game, rev) { game.world.maps.sortedByDescending { it.id in created } }
    fun name(id: Int, default: String) = state.project?.mapNames?.get(id) ?: default
    val shown = maps.filter { query.isBlank() || name(it.id, it.name).contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Find a map (${maps.size})") })
            Button(onClick = { creating = true }) { Text("+ New map") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().clickable { state.go(Route.MapEditor(m.id)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name(m.id, m.name), fontWeight = FontWeight.SemiBold)
                        Hint("${m.width}×${m.height} blocks")
                    }
                    if (m.id in created) Badge("New") else if (m.id <= 0x24) Badge("Outdoor")
                }
                HorizontalDivider()
            }
        }
    }
    if (creating) NewMapDialog(state, game, onDismiss = { creating = false })
}

@Composable
private fun NewMapDialog(state: AppState, game: PokeRed, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("My new room") }
    var tileset by remember { mutableStateOf(1) }
    var width by remember { mutableStateOf(6) }
    var height by remember { mutableStateOf(5) }
    val tilesets = remember(game) { (0 until game.world.tilesetCount).toList() }
    fun tsName(id: Int) = com.needlerom.engine.pokered.Names.TILESETS[id] ?: "Tileset $id"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New map") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                Chooser("Tileset", tsName(tileset), tilesets, ::tsName, Modifier.fillMaxWidth()) { tileset = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Width", width, Modifier.weight(1f), 1..30) { width = it }
                    NumberField("Height", height, Modifier.weight(1f), 1..30) { height = it }
                }
                Hint("Sizes are in blocks (2×2 steps). The map starts with one door at the bottom that leads back to wherever the player came from. To get in, point a door on another map at it.")
            }
        },
        confirmButton = {
            Button(onClick = {
                var id = -1
                if (state.edit("Map created") { g -> id = g.world.createMap(tileset, width, height, g.world.commonBlock(tileset)) }) {
                    state.project?.let { it.mapNames[id] = name.ifBlank { "New map" }; it.saveMeta() }
                    onDismiss()
                    state.go(Route.MapEditor(id))
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EventsTab(state: AppState, game: PokeRed) {
    val rev = state.revision
    val scripts = remember(game, rev) { ScriptEngine.scripts(game) }
    val supported = remember(game) { ScriptEngine.isSupported(game) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Section("Event scripts") {
                Text("Events run when the player talks to an NPC or reads a sign: dialogue, yes/no questions, item and Pokémon gifts, flags, healing, money and battles.")
                Hint("To add one: World → pick a map → NPCs tool → select or add an NPC/sign → Behaviour: Event script.")
                if (!supported) Text("This ROM's game code has been modified, so event scripts are unavailable.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (scripts.isEmpty()) item { Hint("No event scripts yet.", Modifier.padding(8.dp)) }
        items(scripts) { (map, textId) ->
            val title = "${game.mapName(map)} · text #$textId"
            Row(Modifier.fillMaxWidth().clickable { state.go(Route.ScriptEditor(map, textId, title)) }.padding(vertical = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(game.mapName(map), fontWeight = FontWeight.SemiBold)
                    Hint("Text #$textId")
                }
                Button(onClick = { state.go(Route.MapEditor(map)) }) { Text("Map") }
            }
            HorizontalDivider()
        }
        item {
            Section("Flags") {
                Hint("Flags remember things (\"got the gift\", \"beat the rival\") and are saved with the player's game. Name them here to keep track.")
                val project = state.project
                (0 until 8).forEach { f ->
                    var name by remember(project, f) { mutableStateOf(project?.flagNames?.get(f) ?: "") }
                    OutlinedTextField(name, {
                        name = it
                        project?.flagNames?.set(f, it); project?.saveMeta()
                    }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Flag $f") })
                }
                Hint("Flags 0–127 are available.")
            }
        }
    }
}
