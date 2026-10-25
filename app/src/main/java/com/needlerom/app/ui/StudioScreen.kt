@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.ExportKind
import com.needlerom.app.Route
import com.needlerom.app.ui.data.DataTab
import com.needlerom.app.ui.data.PokemonEditorScreen
import com.needlerom.app.ui.data.TrainerEditorScreen
import com.needlerom.app.ui.data.WildEditorScreen
import com.needlerom.app.ui.play.PlaytestScreen
import com.needlerom.app.ui.script.ScriptEditorScreen
import com.needlerom.app.ui.world.GfxCache
import com.needlerom.app.ui.world.MapInspector
import com.needlerom.app.ui.world.MapTiles
import com.needlerom.app.ui.world.MapTools
import com.needlerom.app.ui.world.MapView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import com.needlerom.app.ui.world.editor
import com.needlerom.app.ui.world.playHere
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.script.ScriptEngine

/** Panels that can sit in a dock. */
private enum class Pane(val label: String) {
    INSPECTOR("Inspector"), TILES("Tiles"), MAPS("Maps"), EVENTS("Events"), DATABASE("Database"), OUTPUT("Output")
}

/**
 * The whole editor in one window, laid out like a game engine: toolbar, a scene dock on the
 * left (maps, events, database), open documents in the middle, the inspector on the right and
 * tiles/output at the bottom. Narrow screens fold the docks into one tabbed panel.
 */
@Composable
fun StudioScreen(state: AppState, files: Files) {
    val game = state.game ?: return
    val gfx = remember(game) { GfxCache(game.world) }
    var renaming by remember { mutableStateOf(false) }
    var creatingMap by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).statusBarsPadding().navigationBarsPadding()) {
        val wide = maxWidth >= 840.dp
        val sideDock = !wide && maxWidth > maxHeight && maxWidth >= 560.dp
        val dockHeight = maxHeight * 0.42f
        val labels = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, files, labels = labels, onRename = { renaming = true }, onNewMap = { creatingMap = true })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                wide -> Row(Modifier.weight(1f)) {
                    Dock(listOf(Pane.MAPS, Pane.EVENTS, Pane.DATABASE), Modifier.width(260.dp).fillMaxHeight()) { PaneContent(it, state, game, gfx) { creatingMap = true } }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f)) {
                        DocTabs(state)
                        Box(Modifier.weight(1f)) { DocView(state, gfx) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Dock(bottomPanes(state), Modifier.fillMaxWidth().height(180.dp)) { PaneContent(it, state, game, gfx) { creatingMap = true } }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Dock(listOf(Pane.INSPECTOR), Modifier.width(310.dp).fillMaxHeight()) { PaneContent(it, state, game, gfx) { creatingMap = true } }
                }
                sideDock -> Row(Modifier.weight(1f)) {
                    Column(Modifier.weight(1f)) {
                        DocTabs(state)
                        Box(Modifier.weight(1f)) { DocView(state, gfx) }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Dock(compactPanes(state), Modifier.width(320.dp).fillMaxHeight()) { PaneContent(it, state, game, gfx) { creatingMap = true } }
                }
                else -> {
                    var open by rememberSaveable { mutableStateOf(true) }
                    DocTabs(state)
                    Box(Modifier.weight(1f)) { DocView(state, gfx) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Dock(compactPanes(state), Modifier.fillMaxWidth().then(if (open) Modifier.height(dockHeight) else Modifier),
                        collapsed = !open, onToggle = { open = !open }) { PaneContent(it, state, game, gfx) { creatingMap = true } }
                }
            }
        }
    }

    if (renaming) {
        val project = state.project ?: return
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { state.renameProject(name.ifBlank { project.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (creatingMap) NewMapDialog(state, game, onDismiss = { creatingMap = false })
}

private fun bottomPanes(state: AppState) = if (state.doc is Route.MapEditor) listOf(Pane.TILES, Pane.OUTPUT) else listOf(Pane.OUTPUT)

private fun compactPanes(state: AppState) =
    (if (state.doc is Route.MapEditor) listOf(Pane.INSPECTOR, Pane.TILES) else listOf(Pane.INSPECTOR)) +
        listOf(Pane.MAPS, Pane.EVENTS, Pane.DATABASE, Pane.OUTPUT)

// ---- Toolbar ---------------------------------------------------------------------------------

@Composable
private fun Toolbar(state: AppState, files: Files, labels: Boolean, onRename: () -> Unit, onNewMap: () -> Unit) {
    val game = state.game ?: return
    val project = state.project ?: return
    var menu by remember { mutableStateOf(false) }
    val mapDoc = state.doc as? Route.MapEditor
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Menu, "Project menu", Modifier.size(20.dp))
                Column(Modifier.padding(start = 8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(game.version.name + if (!game.exact) " · modified" else "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New map…") }, onClick = { menu = false; onNewMap() })
                HorizontalDivider()
                ExportKind.entries.forEach { k -> DropdownMenuItem(text = { Text("Export ${k.label}…") }, onClick = { menu = false; files.export(k) }) }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Rename project") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Close project") }, onClick = { menu = false; state.go(Route.Home) })
            }
        }
        IconButton(onClick = { state.undo() }, enabled = game.rom.canUndo.also { state.revision }) { Icon(NeedleIcons.Undo, "Undo", Modifier.size(20.dp)) }
        if (mapDoc != null) MapTools(state, mapDoc.mapId, labels)
        Spacer(Modifier.width(4.dp))
        if (mapDoc != null) OutlinedButton(onClick = { playHere(state, game.world.map(mapDoc.mapId), state.editor(mapDoc.mapId)) },
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Here", Modifier.padding(start = 4.dp))
        }
        Button(onClick = { state.go(Route.Playtest()) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Play", Modifier.padding(start = 4.dp))
        }
    }
}

/** A tab with an underline when selected, used for documents and dock panels. */
@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit, onClose: (() -> Unit)? = null) {
    Column(Modifier.clickable(onClick = onClick).background(if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)) {
        Row(Modifier.padding(start = 12.dp, end = if (onClose != null) 2.dp else 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClose != null) Icon(Icons.Filled.Close, "Close", Modifier.padding(start = 6.dp).size(16.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
    }
}

// ---- Documents -------------------------------------------------------------------------------

private fun docLabel(state: AppState, d: Route): String {
    val g = state.game
    return when (d) {
        is Route.MapEditor -> state.mapName(d.mapId)
        is Route.ScriptEditor -> "Event: " + d.title
        is Route.PokemonEditor -> g?.monName(g.internalOfDex(d.dex)) ?: "Pokémon"
        is Route.TrainerEditor -> "Trainer: " + (g?.trainerClassNames?.getOrNull(d.trainerClass) ?: "?")
        is Route.WildEditor -> "Wild: " + state.mapName(d.mapId)
        is Route.Playtest -> if (d.start == null) "Game" else "Game · ${state.mapName(d.start.first)}"
        else -> "?"
    }
}

@Composable
private fun DocTabs(state: AppState) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).horizontalScroll(rememberScrollState())) {
        state.docs.forEachIndexed { i, d ->
            TabLabel(docLabel(state, d), i == state.activeDoc, onClick = { state.activeDoc = i }, onClose = { state.closeDoc(i) })
        }
    }
}

@Composable
private fun DocView(state: AppState, gfx: GfxCache) {
    val d = state.doc
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint("Open a map from the Maps panel, or press ▶ Play.") }
        return
    }
    key(d) {
        when (d) {
            is Route.MapEditor -> MapView(state, d.mapId, gfx)
            else -> CompositionLocalProvider(LocalEmbedded provides true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    when (d) {
                        is Route.ScriptEditor -> ScriptEditorScreen(state, d)
                        is Route.PokemonEditor -> PokemonEditorScreen(state, d.dex)
                        is Route.TrainerEditor -> TrainerEditorScreen(state, d.trainerClass)
                        is Route.WildEditor -> WildEditorScreen(state, d.mapId)
                        is Route.Playtest -> PlaytestScreen(state, d.start)
                        else -> {}
                    }
                }
            }
        }
    }
}

// ---- Docks -----------------------------------------------------------------------------------

@Composable
private fun Dock(panes: List<Pane>, modifier: Modifier, collapsed: Boolean = false, onToggle: (() -> Unit)? = null, content: @Composable (Pane) -> Unit) {
    var picked by rememberSaveable(panes.joinToString()) { mutableStateOf(panes.first().name) }
    val pane = panes.firstOrNull { it.name == picked } ?: panes.first()
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                panes.forEach { p ->
                    TabLabel(p.label, p == pane && !collapsed, onClick = { picked = p.name; if (collapsed) onToggle?.invoke() })
                }
            }
            if (onToggle != null) IconButton(onClick = onToggle) {
                Icon(if (collapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (collapsed) "Show panel" else "Hide panel")
            }
        }
        if (!collapsed) Box(Modifier.weight(1f).fillMaxWidth()) { content(pane) }
    }
}

@Composable
private fun PaneContent(pane: Pane, state: AppState, game: PokeRed, gfx: GfxCache, onNewMap: () -> Unit) {
    val mapDoc = state.doc as? Route.MapEditor
    when (pane) {
        Pane.INSPECTOR -> when (val d = state.doc) {
            is Route.MapEditor -> key(d.mapId) { MapInspector(state, d.mapId, gfx) }
            null -> Hint("Nothing open.", Modifier.padding(12.dp))
            else -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(docLabel(state, d), fontWeight = FontWeight.Bold)
                Hint("This document's properties are shown in the editor. Select a map tab to inspect maps and objects.")
            }
        }
        Pane.TILES -> if (mapDoc != null) key(mapDoc.mapId) { MapTiles(state, mapDoc.mapId, gfx) } else Hint("Open a map to paint tiles.", Modifier.padding(12.dp))
        Pane.MAPS -> MapsPanel(state, game, onNewMap)
        Pane.EVENTS -> EventsPanel(state, game)
        Pane.DATABASE -> DataTab(state, game)
        Pane.OUTPUT -> OutputPanel(state)
    }
}

@Composable
private fun MapsPanel(state: AppState, game: PokeRed, onNewMap: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val rev = state.revision
    val created = remember(game, rev) { game.world.createdMaps().toSet() }
    val maps = remember(game, rev) { game.world.maps.sortedByDescending { it.id in created } }
    val shown = maps.filter { query.isBlank() || state.mapName(it.id).contains(query, ignoreCase = true) }
    val open = (state.doc as? Route.MapEditor)?.mapId
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Filter ${maps.size} maps") },
                textStyle = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onNewMap) { Text("+ New") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().background(if (m.id == open) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { state.go(Route.MapEditor(m.id)) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (m.id <= 0x24) "▤" else "▦", Modifier.width(18.dp), color = MaterialTheme.colorScheme.primary)
                    Text(state.mapName(m.id), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.id in created) Badge("New")
                    Text(" ${m.width}×${m.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventsPanel(state: AppState, game: PokeRed) {
    val rev = state.revision
    val scripts = remember(game, rev) { ScriptEngine.scripts(game) }
    val supported = remember(game) { ScriptEngine.isSupported(game) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Hint("Event scripts run when the player talks to an NPC, reads a sign or steps on a trigger. Add one from a map's Inspector.", Modifier.padding(vertical = 6.dp))
            if (!supported) Text("This ROM's game code has been modified, so event scripts are unavailable.", color = MaterialTheme.colorScheme.error)
        }
        if (scripts.isEmpty()) item { Hint("No event scripts yet.") }
        items(scripts) { (map, textId) ->
            val trigger = remember(map, textId, rev) { game.world.map(map).triggers.any { it.textId == textId } }
            Row(Modifier.fillMaxWidth().clickable { state.go(Route.ScriptEditor(map, textId, "${state.mapName(map)} · ${if (trigger) "trigger" else "text #$textId"}")) }
                .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (trigger) "⚑" else "✎", Modifier.width(20.dp), color = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f)) {
                    Text(state.mapName(map), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Hint(if (trigger) "Trigger" else "Text #$textId")
                }
                TextButton(onClick = { state.go(Route.MapEditor(map)) }) { Text("Map") }
            }
        }
        item {
            Text("FLAGS", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Hint("Flags remember things (\"got the gift\") in the player's save. Name them to keep track.")
            val project = state.project
            (0 until 8).forEach { f ->
                var name by remember(project, f) { mutableStateOf(project?.flagNames?.get(f) ?: "") }
                OutlinedTextField(name, { name = it; project?.flagNames?.set(f, it); project?.saveMeta() },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("Flag $f") }, textStyle = MaterialTheme.typography.bodySmall)
            }
            Hint("Flags 0–127 are available.", Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun OutputPanel(state: AppState) {
    val lines = state.log
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp), reverseLayout = true) {
        items(lines.asReversed()) { line -> Text("› $line", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        if (lines.isEmpty()) item { Hint("Messages from your edits show up here.") }
    }
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
