@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.ui.Chooser
import com.needlerom.app.ui.NeedleIcons
import com.needlerom.app.ui.NumberField
import androidx.compose.foundation.layout.width
import com.needlerom.app.ui.PropRow
import com.needlerom.app.ui.PropSection
import com.needlerom.app.ui.Hint
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import com.needlerom.app.ui.toImage
import com.needlerom.engine.pokered.GameMap
import com.needlerom.engine.pokered.World
import kotlin.math.ceil
import kotlin.math.floor

enum class Tool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    MOVE("Move", NeedleIcons.Move), PAINT("Paint", NeedleIcons.Paint), FILL("Fill", NeedleIcons.Fill),
    PICK("Pick", NeedleIcons.Pick), EVENTS("Objects", NeedleIcons.Objects),
}

sealed interface Selection {
    data class Npc(val index: Int) : Selection
    data class Door(val index: Int) : Selection
    data class SignPost(val index: Int) : Selection
    data class Trigger(val textId: Int) : Selection
}

@Stable
class EditorState {
    var tool by mutableStateOf(Tool.EVENTS)
    var block by mutableIntStateOf(0)
    var selection by mutableStateOf<Selection?>(null)
    var scale by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(IntSize.Zero)
    var showGrid by mutableStateOf(false)
    var showCollision by mutableStateOf(false)
    var tick by mutableIntStateOf(0)
    var dragTo by mutableStateOf<Pair<Int, Int>?>(null)
    /** Which add dialog is open ("npc", "sign", "door", "trigger"). */
    var adding by mutableStateOf<String?>(null)

    fun fit(map: GameMap) {
        if (viewport.width <= 0) return
        val k = minOf(viewport.width / ((map.width + 2) * BLOCK), viewport.height / ((map.height + 2) * BLOCK)).coerceIn(0.4f, 8f)
        scale = k
        offset = Offset((viewport.width - map.width * BLOCK * k) / 2f, (viewport.height - map.height * BLOCK * k) / 2f)
    }

    fun centerStep(map: GameMap): Pair<Int, Int> {
        if (scale <= 0f) return map.stepWidth / 2 to map.stepHeight / 2
        val cx = ((viewport.width / 2f - offset.x) / (STEP * scale)).toInt()
        val cy = ((viewport.height / 2f - offset.y) / (STEP * scale)).toInt()
        return cx.coerceIn(0, map.stepWidth - 1) to cy.coerceIn(0, map.stepHeight - 1)
    }
}

/** The editing state of a map, kept while its tab (or the studio) is open. */
fun AppState.editor(mapId: Int): EditorState = editors.getOrPut(mapId) { EditorState() }

internal const val BLOCK = 32f
internal const val STEP = 16f

/** Rendered blocks and sprites, cached per world. */
class GfxCache(private val world: World) {
    private val blocks = HashMap<Long, ImageBitmap>()
    private val sprites = HashMap<Int, ImageBitmap?>()
    fun block(tileset: Int, id: Int): ImageBitmap =
        blocks.getOrPut((tileset.toLong() shl 16) or id.toLong()) { world.tileset(tileset).renderBlock(id).toImage(32, 32) }
    fun sprite(id: Int): ImageBitmap? = sprites.getOrPut(id) { world.spriteImage(id)?.toImage(16, 16) }
}

/** The map canvas (center of the studio), plus the dialogs started from the inspector. */
@Composable
fun MapView(state: AppState, mapId: Int, gfx: GfxCache) {
    val game = state.game ?: return
    val rev = state.revision
    val map = remember(mapId, rev) { game.world.map(mapId) }
    val st = state.editor(mapId)
    val mapState = rememberUpdatedState(map)
    Box(Modifier.fillMaxSize().clipToBounds().background(Color(0xFF101318))) {
        MapCanvas(state, st, gfx, mapState)
        if (!map.eventsValid) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(8.dp)) {
                Text("This map's event data couldn't be read; only tiles can be edited.", Modifier.padding(8.dp))
            }
        }
        Text(
            "${state.mapName(mapId)} · ${map.width}×${map.height} · ${st.tool.label}",
            Modifier.align(Alignment.BottomStart).background(Color(0xAA101318)).padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = Color(0xFFA9B2BD),
        )
    }
    when (st.adding) {
        "npc" -> AddNpcDialog(game, map, gfx, onDismiss = { st.adding = null }) { sprite, movement, behavior ->
            st.adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("NPC added — drag it into place") { idx = map.addNpc(sprite, x, y, movement, behavior) }) select(st, Selection.Npc(idx))
        }
        "sign" -> {
            st.adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("Sign added — drag it into place") { idx = map.addSign(x, y, "A new sign!") }) select(st, Selection.SignPost(idx))
        }
        "trigger" -> {
            st.adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var tid = -1
            if (state.edit("Trigger added: when the player steps here, its event runs") {
                    tid = map.addTrigger(x, y, com.needlerom.engine.script.EventScript(listOf(com.needlerom.engine.script.Step.Say("Something happens!")))).textId
                }) select(st, Selection.Trigger(tid))
        }
        "door" -> {
            st.adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("Door added — set where it leads") { idx = map.addWarp(x, y, 0xFF, 1) }) select(st, Selection.Door(idx))
        }
    }
}

private fun select(st: EditorState, s: Selection) { st.tool = Tool.EVENTS; st.selection = s }

/** Tool buttons for the studio toolbar. */
@Composable
fun MapTools(state: AppState, mapId: Int, labels: Boolean) {
    val st = state.editor(mapId)
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(6.dp)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Tool.entries.forEach { t ->
            val on = st.tool == t
            Row(
                Modifier.background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(5.dp))
                    .clickable { st.tool = t; if (t != Tool.EVENTS) st.selection = null }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, Modifier.size(18.dp), tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (labels) Text(t.label, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Inspector contents for a map: the selected object, or the map's own properties. */
@Composable
fun MapInspector(state: AppState, mapId: Int, gfx: GfxCache) {
    val game = state.game ?: return
    val rev = state.revision
    val map = remember(mapId, rev) { game.world.map(mapId) }
    val st = state.editor(mapId)
    if (st.selection != null) Inspector(state, game, map, st, gfx) else MapProperties(state, map, st, gfx)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MapProperties(state: AppState, map: GameMap, st: EditorState, gfx: GfxCache) {
    val game = map.world.game
    val created = remember(map) { map.id in map.world.createdMaps() }
    val sounds = remember(game) { com.needlerom.engine.script.ScriptEngine.sounds(game) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PropSection("Map") {
            if (created) {
                var name by remember(map.id) { mutableStateOf(state.mapName(map.id)) }
                OutlinedTextField(name, { name = it; state.project?.let { p -> p.mapNames[map.id] = it.ifBlank { "New map" }; p.saveMeta() } },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            } else PropRow("Name", state.mapName(map.id))
            PropRow("ID", "0x" + map.id.toString(16).uppercase().padStart(2, '0'))
            PropRow("Size", "${map.width}×${map.height} blocks")
            PropRow("Tileset", com.needlerom.engine.pokered.Names.TILESETS[map.tilesetId] ?: "Tileset ${map.tilesetId}")
            PropRow("Contents", "${map.npcs.size} NPCs, ${map.signs.size} signs, ${map.warps.size} doors, ${map.triggers.size} triggers")
        }
        PropSection("Add") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { st.adding = "npc" }, enabled = map.canAddNpc, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("NPC") }
                FilledTonalButton(onClick = { st.adding = "sign" }, enabled = map.eventsValid, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Sign") }
                FilledTonalButton(onClick = { st.adding = "door" }, enabled = map.eventsValid, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Door") }
                FilledTonalButton(onClick = { st.adding = "trigger" }, enabled = map.eventsValid, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Trigger") }
            }
            map.triggers.filter { it.x == null }.forEach { t ->
                TextButton(onClick = { select(st, Selection.Trigger(t.textId)) }) { Text("Edit the map-entry trigger") }
            }
        }
        PropSection("Music & look") {
            val (music, bank) = remember(map) { map.world.music(map.id) }
            Chooser("Music", sounds.music.firstOrNull { it.first == music && it.second == bank }?.third ?: "Track $music",
                sounds.music, { it.third }, Modifier.fillMaxWidth()) { m ->
                state.edit("Music: ${m.third}") { map.world.setMusic(map.id, m.first, m.second) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(gfx.block(map.tilesetId, map.borderBlock), null, Modifier.size(28.dp), filterQuality = FilterQuality.None)
                Text("Border: block ${map.borderBlock}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { state.edit("Border block set") { map.setBorderBlock(st.block) } }, enabled = map.eventsValid) { Text("Use block ${st.block}") }
            }
        }
        if (map.connections.isNotEmpty()) PropSection("Connections", initiallyOpen = false) {
            map.connections.forEach { (dir, target) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$dir", Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.mapName(target), Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state.go(com.needlerom.app.Route.MapEditor(target)) }) { Text("Open") }
                }
            }
        }
        PropSection("View & test") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.showGrid, { st.showGrid = !st.showGrid }, label = { Text("Grid") })
                FilterChip(st.showCollision, { st.showCollision = !st.showCollision }, label = { Text("Blocked tiles") })
                TextButton(onClick = { st.fit(map) }) { Text("Fit to view") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { state.go(com.needlerom.app.Route.WildEditor(map.id)) }) { Text("Wild Pokémon") }
                OutlinedButton(onClick = { playHere(state, map, st) }) { Text("Play here") }
            }
            TestPokemon(state)
        }
        Hint("Tip: pick the Objects tool and tap something on the map to edit it.", Modifier.padding(12.dp))
    }
}

/** The Pokémon "Play here" gives the player (a new game starts with none). */
@Composable
private fun TestPokemon(state: AppState) {
    val game = state.game ?: return
    val project = state.project ?: return
    var species by remember(project) { mutableStateOf(project.testSpecies) }
    var level by remember(project) { mutableStateOf(project.testLevel) }
    val options = remember(game) { listOf(0) + game.species.map { it.first } }
    fun label(id: Int) = when (id) {
        0 -> "None (wild battles will glitch)"
        -1 -> game.monName(game.internalOfDex(25))
        else -> game.monName(id)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chooser("Play here with", label(species), options, ::label, Modifier.weight(1f)) {
            species = it; project.testSpecies = it; project.saveMeta()
        }
        if (species != 0) NumberField("Lv", level, Modifier.width(72.dp), 1..100) {
            level = it; project.testLevel = it; project.saveMeta()
        }
    }
}

/** Starts a fresh game at a walkable tile near the middle of the view. */
fun playHere(state: AppState, map: GameMap, st: EditorState) {
    val (x, y) = startStepNear(map, st.centerStep(map))
    state.go(com.needlerom.app.Route.Playtest(Triple(map.id, x, y)))
}

/** The tile palette (bottom dock). */
@Composable
fun MapTiles(state: AppState, mapId: Int, gfx: GfxCache) {
    val game = state.game ?: return
    val map = remember(mapId, state.revision) { game.world.map(mapId) }
    val st = state.editor(mapId)
    Palette(map, st, gfx)
}

/** A walkable, empty step near [start], for "Playtest from here". */
internal fun startStepNear(map: GameMap, start: Pair<Int, Int>): Pair<Int, Int> {
    for (r in 0..12) for (dy in -r..r) for (dx in -r..r) {
        val x = start.first + dx; val y = start.second + dy
        if (x !in 0 until map.stepWidth || y !in 0 until map.stepHeight) continue
        if (map.isWalkable(x, y) && map.npcAt(x, y) == null && map.warpAt(x, y) == null && map.signAt(x, y) == null) return x to y
    }
    return freeStepNear(map, start)
}

private fun freeStepNear(map: GameMap, start: Pair<Int, Int>): Pair<Int, Int> {
    for (r in 0..10) for (dy in -r..r) for (dx in -r..r) {
        val x = start.first + dx; val y = start.second + dy
        if (x !in 0 until map.stepWidth || y !in 0 until map.stepHeight) continue
        if (map.npcAt(x, y) == null && map.warpAt(x, y) == null && map.signAt(x, y) == null) return x to y
    }
    return start
}

@Composable
private fun MapCanvas(state: AppState, st: EditorState, gfx: GfxCache, mapState: State<GameMap>) {
    val sel = MaterialTheme.colorScheme.secondary
    LaunchedEffect(st.viewport, st.scale <= 0f) { if (st.scale <= 0f) st.fit(mapState.value) }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { st.viewport = it }
            .pointerInput(st) { gestures(state, st, mapState) },
    ) {
        val map = mapState.value
        @Suppress("UNUSED_VARIABLE") val redraw = st.tick
        if (st.scale <= 0f) return@Canvas
        val b = BLOCK * st.scale
        val s = STEP * st.scale
        val o = st.offset
        val px = IntSize(ceil(b).toInt(), ceil(b).toInt())
        if (map.eventsValid) {
            val border = gfx.block(map.tilesetId, map.borderBlock)
            for (y in -1..map.height) for (x in -1..map.width) {
                if (x in 0 until map.width && y in 0 until map.height) continue
                drawImage(border, IntOffset.Zero, IntSize(32, 32), IntOffset((o.x + x * b).toInt(), (o.y + y * b).toInt()), px, alpha = 0.35f, filterQuality = FilterQuality.None)
            }
        }
        for (y in 0 until map.height) {
            val top = o.y + y * b
            if (top > size.height || top + b < 0) continue
            for (x in 0 until map.width) {
                val left = o.x + x * b
                if (left > size.width || left + b < 0) continue
                drawImage(gfx.block(map.tilesetId, map.block(x, y)), IntOffset.Zero, IntSize(32, 32), IntOffset(left.toInt(), top.toInt()), px, filterQuality = FilterQuality.None)
            }
        }
        if (st.showCollision) {
            for (sy in 0 until map.stepHeight) for (sx in 0 until map.stepWidth) {
                if (!map.isWalkable(sx, sy)) drawRect(Color(0x55FF3B30), Offset(o.x + sx * s, o.y + sy * s), Size(s, s))
            }
        }
        if (st.showGrid) {
            val line = Color(0x40FFFFFF)
            for (x in 0..map.width) drawLine(line, Offset(o.x + x * b, o.y), Offset(o.x + x * b, o.y + map.height * b))
            for (y in 0..map.height) drawLine(line, Offset(o.x, o.y + y * b), Offset(o.x + map.width * b, o.y + y * b))
        }
        if (map.eventsValid) drawEvents(map, st, gfx, s, o, sel)
    }
}

private fun DrawScope.drawEvents(map: GameMap, st: EditorState, gfx: GfxCache, s: Float, o: Offset, sel: Color) {
    fun at(x: Int, y: Int) = Offset(o.x + x * s, o.y + y * s)
    val stroke = Stroke(width = maxOf(2f, s / 10f))
    val drag = st.dragTo
    map.warps.forEach { w ->
        val selected = st.selection == Selection.Door(w.index)
        val (x, y) = if (selected && drag != null) drag else w.x to w.y
        drawRect(Color(0x669B59D0), at(x, y), Size(s, s))
        drawRect(if (selected) sel else Color(0xFFB57EDC), at(x, y), Size(s, s), style = stroke)
    }
    map.triggers.forEach { t ->
        val tx = t.x ?: return@forEach
        val ty = t.y ?: return@forEach
        val selected = st.selection == Selection.Trigger(t.textId)
        val (x, y) = if (selected && drag != null) drag else tx to ty
        val area = Size(s * t.width, s * t.height)
        drawRect(Color(0x66FF8C1A), at(x, y), area)
        drawRect(if (selected) sel else Color(0xFFFF8C1A), at(x, y), area, style = stroke)
    }
    map.signs.forEach { g ->
        val selected = st.selection == Selection.SignPost(g.index)
        val (x, y) = if (selected && drag != null) drag else g.x to g.y
        drawRect(Color(0xCCF5C542), at(x, y) + Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f))
        if (selected) drawRect(sel, at(x, y), Size(s, s), style = stroke)
    }
    map.npcs.forEach { n ->
        val selected = st.selection == Selection.Npc(n.index)
        val (x, y) = if (selected && drag != null) drag else n.x to n.y
        val img = gfx.sprite(n.sprite)
        if (img != null) drawImage(img, IntOffset.Zero, IntSize(16, 16), IntOffset(at(x, y).x.toInt(), at(x, y).y.toInt()), IntSize(s.toInt(), s.toInt()), filterQuality = FilterQuality.None)
        else drawCircle(Color(0xFFFF5C8A), s * 0.4f, at(x, y) + Offset(s / 2, s / 2))
        if (selected) drawRect(sel, at(x, y), Size(s, s), style = stroke)
    }
}

private suspend fun PointerInputScope.gestures(state: AppState, st: EditorState, mapState: State<GameMap>) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val map = mapState.value
        val rom = map.world.game.rom
        val undoMark = rom.changeCount
        var multi = false
        var last = down.position
        fun blockAt(p: Offset) = floor((p.x - st.offset.x) / (BLOCK * st.scale)).toInt() to floor((p.y - st.offset.y) / (BLOCK * st.scale)).toInt()
        fun stepAt(p: Offset) = floor((p.x - st.offset.x) / (STEP * st.scale)).toInt() to floor((p.y - st.offset.y) / (STEP * st.scale)).toInt()
        var stroke = false
        fun paint(p: Offset) {
            val (x, y) = blockAt(p)
            if (x in 0 until map.width && y in 0 until map.height && map.block(x, y) != st.block) {
                stroke = true
                map.setBlock(x, y, st.block); st.tick++
            }
        }
        var grabbed: Selection? = null
        if (st.tool == Tool.EVENTS && map.eventsValid) {
            val (sx, sy) = stepAt(down.position)
            grabbed = map.npcAt(sx, sy)?.let { Selection.Npc(it.index) }
                ?: map.signAt(sx, sy)?.let { Selection.SignPost(it.index) }
                ?: map.warpAt(sx, sy)?.let { Selection.Door(it.index) }
                ?: map.triggerAt(sx, sy)?.let { Selection.Trigger(it.textId) }
            st.selection = grabbed
        }
        // A paint stroke is a single undo step.
        if (st.tool == Tool.PAINT) { rom.beginGroup(); paint(down.position) }
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size > 1 || st.tool == Tool.MOVE || (st.tool == Tool.EVENTS && grabbed == null)) {
                if (pressed.size > 1 && !multi) {
                    multi = true
                    st.dragTo = null
                    if (st.tool == Tool.PAINT) { rom.endGroup(); if (rom.changeCount > undoMark) rom.undo(); st.tick++ }
                }
                val zoom = ev.calculateZoom()
                val pan = ev.calculatePan()
                val c = ev.calculateCentroid(useCurrent = true)
                val ns = (st.scale * zoom).coerceIn(0.3f, 12f)
                val k = ns / st.scale
                st.offset = Offset(c.x - (c.x - st.offset.x) * k, c.y - (c.y - st.offset.y) * k) + pan
                st.scale = ns
            } else if (!multi) {
                val p = pressed[0].position
                if ((p - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                last = p
                when (st.tool) {
                    Tool.PAINT -> paint(p)
                    Tool.EVENTS -> if (grabbed != null && moved) {
                        val (sx, sy) = stepAt(p)
                        st.dragTo = sx.coerceIn(0, map.stepWidth - 1) to sy.coerceIn(0, map.stepHeight - 1)
                    }
                    else -> {}
                }
            }
            ev.changes.forEach { it.consume() }
        }
        if (multi) return@awaitEachGesture
        when (st.tool) {
            Tool.PAINT -> { rom.endGroup(); if (stroke) state.edited() }
            Tool.FILL -> { val (x, y) = blockAt(last); state.edit { map.fill(x, y, st.block) } }
            Tool.PICK -> { val (x, y) = blockAt(last); if (x in 0 until map.width && y in 0 until map.height) { st.block = map.block(x, y); st.tool = Tool.PAINT } }
            Tool.EVENTS -> {
                val to = st.dragTo
                st.dragTo = null
                if (to != null) state.edit {
                    when (val g = grabbed) {
                        is Selection.Npc -> map.npcs.getOrNull(g.index)?.let { map.moveNpc(it, to.first, to.second) }
                        is Selection.SignPost -> map.signs.getOrNull(g.index)?.let { map.moveSign(it, to.first, to.second) }
                        is Selection.Door -> map.warps.getOrNull(g.index)?.let { map.moveWarp(it, to.first, to.second) }
                        is Selection.Trigger -> map.triggers.firstOrNull { it.textId == g.textId }?.let { map.moveTrigger(it, to.first, to.second) }
                        null -> {}
                    }
                }
            }
            Tool.MOVE -> {}
        }
    }
}

@Composable
private fun Palette(map: GameMap, st: EditorState, gfx: GfxCache) {
    val ts = map.tilesetId
    val count = remember(ts) { map.tileset.blockCount }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(gfx.block(ts, st.block), null, Modifier.size(28.dp), filterQuality = FilterQuality.None)
            Text(when (st.tool) {
                Tool.FILL -> "Fill with block ${st.block}: tap an area"
                Tool.PICK -> "Pick: tap the map to copy a block"
                Tool.PAINT -> "Block ${st.block}: drag to paint · two fingers to move"
                else -> "Block ${st.block} · choose Paint or Fill to use it"
            }, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items((0 until count).toList()) { id ->
                val on = id == st.block
                Image(gfx.block(ts, id), "Block $id",
                    Modifier.size(44.dp).border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(3.dp))
                        .clickable { st.block = id; if (st.tool == Tool.PICK || st.tool == Tool.MOVE || st.tool == Tool.EVENTS) st.tool = Tool.PAINT },
                    filterQuality = FilterQuality.None)
            }
        }
    }
}
