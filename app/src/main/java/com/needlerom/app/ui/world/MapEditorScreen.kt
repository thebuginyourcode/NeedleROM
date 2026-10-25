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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.needlerom.app.ui.toImage
import com.needlerom.engine.pokered.GameMap
import com.needlerom.engine.pokered.World
import kotlin.math.ceil
import kotlin.math.floor

enum class Tool(val label: String) { MOVE("Move"), PAINT("Paint"), FILL("Fill"), PICK("Pick"), EVENTS("NPCs & events") }

sealed interface Selection {
    data class Npc(val index: Int) : Selection
    data class Door(val index: Int) : Selection
    data class SignPost(val index: Int) : Selection
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

@Composable
fun MapEditorScreen(state: AppState, mapId: Int) {
    val game = state.game ?: return
    val rev = state.revision
    val map = remember(mapId, rev) { game.world.map(mapId) }
    val st = remember(mapId) { EditorState() }
    val gfx = remember(game) { GfxCache(game.world) }
    val mapState = rememberUpdatedState(map)
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(map.info.name, maxLines = 1)
                        Text("${map.npcs.size} NPCs · ${map.signs.size} signs · ${map.warps.size} doors",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { state.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { state.undo() }, enabled = game.rom.canUndo) { Text("Undo") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Toggle("Grid", st.showGrid) { st.showGrid = it }
                            Toggle("Show blocked tiles", st.showCollision) { st.showCollision = it }
                            DropdownMenuItem(text = { Text("Use selected block as border") }, enabled = map.eventsValid,
                                onClick = { menu = false; state.edit("Border block set") { map.setBorderBlock(st.block) } })
                            DropdownMenuItem(text = { Text("Fit map to screen") }, onClick = { menu = false; st.fit(map) })
                            map.connections.forEach { (dir, target) ->
                                DropdownMenuItem(text = { Text("Go $dir: ${game.mapName(target)}") },
                                    onClick = { menu = false; state.replace(com.needlerom.app.Route.MapEditor(target)) })
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF09080C))) {
                MapCanvas(state, st, gfx, mapState)
                if (!map.eventsValid) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(8.dp)) {
                        Text("This map's event data couldn't be read; only tiles can be edited.", Modifier.padding(8.dp))
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
                    when {
                        st.tool == Tool.EVENTS && st.selection != null -> Inspector(state, game, map, st, gfx)
                        st.tool == Tool.PAINT || st.tool == Tool.FILL || st.tool == Tool.PICK -> Palette(map, st, gfx)
                        st.tool == Tool.EVENTS -> Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tap to select, drag to move.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            FilledTonalButton(onClick = { adding = "npc" }, enabled = map.canAddNpc, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ NPC") }
                            FilledTonalButton(onClick = { adding = "sign" }, enabled = map.eventsValid, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ Sign") }
                            FilledTonalButton(onClick = { adding = "door" }, enabled = map.eventsValid, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+ Door") }
                        }
                        else -> Text("Drag to look around, pinch to zoom.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tool.entries.forEach { t ->
                            FilterChip(selected = st.tool == t, onClick = { st.tool = t; if (t != Tool.EVENTS) st.selection = null }, label = { Text(t.label) })
                        }
                    }
                }
            }
        }
    }

    when (adding) {
        "npc" -> AddNpcDialog(game, map, gfx, onDismiss = { adding = null }) { sprite, movement, behavior ->
            adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("NPC added — drag it into place") { idx = map.addNpc(sprite, x, y, movement, behavior) }) st.selection = Selection.Npc(idx)
        }
        "sign" -> {
            adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("Sign added — drag it into place") { idx = map.addSign(x, y, "A new sign!") }) st.selection = Selection.SignPost(idx)
        }
        "door" -> {
            adding = null
            val (x, y) = freeStepNear(map, st.centerStep(map))
            var idx = -1
            if (state.edit("Door added — set where it leads") { idx = map.addWarp(x, y, 0xFF, 1) }) st.selection = Selection.Door(idx)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) }, onClick = { onChange(!checked) })
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
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(gfx.block(ts, st.block), null, Modifier.size(36.dp), filterQuality = FilterQuality.None)
        Text(when (st.tool) {
            Tool.FILL -> "Tap an area to fill it with block ${st.block}"
            Tool.PICK -> "Tap the map to copy a block"
            else -> "Block ${st.block}: drag to paint · two fingers to move"
        }, style = MaterialTheme.typography.bodySmall)
    }
    LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        items((0 until count).toList()) { id ->
            val on = id == st.block
            Image(gfx.block(ts, id), "Block $id",
                Modifier.size(48.dp).border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { st.block = id; if (st.tool == Tool.PICK) st.tool = Tool.PAINT },
                filterQuality = FilterQuality.None)
        }
    }
}
