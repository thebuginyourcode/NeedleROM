@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.core.world.Gen1Map
import com.needlerom.core.world.Gen1World
import com.needlerom.core.world.MapRef
import com.needlerom.core.world.Movement
import kotlin.math.floor

enum class EditTool(val label: String) {
    PAN("Move"), PAINT("Paint"), FILL("Fill"), PICK("Pick"), EVENTS("NPCs & doors"),
}

sealed interface Selection {
    data class Npc(val index: Int) : Selection
    data class Door(val index: Int) : Selection
    data class SignPost(val index: Int) : Selection
}

@Stable
class EditorState {
    var tool by mutableStateOf(EditTool.PAINT)
    var block by mutableIntStateOf(0)
    var selection by mutableStateOf<Selection?>(null)
    var scale by mutableFloatStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)
    var showGrid by mutableStateOf(false)
    var showCollision by mutableStateOf(false)
    var showEvents by mutableStateOf(true)
    /** Bumped while painting so the canvas redraws without re-parsing the map. */
    var tick by mutableIntStateOf(0)
    /** Step an event is being dragged to. */
    var dragTo by mutableStateOf<Pair<Int, Int>?>(null)
    var viewport by mutableStateOf(IntSize.Zero)

    /** Zooms so the whole map (plus its border ring) fits, centred. */
    fun fit(map: Gen1Map) {
        if (viewport.width <= 0) return
        val k = minOf(viewport.width / ((map.width + 2) * BLOCK), viewport.height / ((map.height + 2) * BLOCK)).coerceIn(0.5f, 8f)
        scale = k
        offset = Offset((viewport.width - map.width * BLOCK * k) / 2f, (viewport.height - map.height * BLOCK * k) / 2f)
    }
}

private const val BLOCK = 32f
private const val STEP = 16f

@Composable
fun MapEditor(vm: AppViewModel, world: Gen1World, ref: MapRef, onBack: () -> Unit) {
    val rev = vm.revision
    val map = remember(ref, rev) { world.map(ref) }
    val st = remember(ref) { EditorState() }
    val gfx = remember(world) { GfxCache(world) }
    val currentMap = rememberUpdatedState(map)
    var menu by remember { mutableStateOf(false) }
    var addingNpc by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ref.name, maxLines = 1)
                        Text("${map.width}×${map.height} · ${map.objects.size} NPCs · ${map.warps.size} doors",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { vm.undo() }, enabled = world.rom.canUndo) { Text("Undo") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "View options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ToggleItem("Grid", st.showGrid) { st.showGrid = it }
                            if (map.tileset.hasCollision) ToggleItem("Show blocked tiles", st.showCollision) { st.showCollision = it }
                            ToggleItem("Show NPCs & doors", st.showEvents) { st.showEvents = it }
                            DropdownMenuItem(
                                text = { Text("Use selected block as border") },
                                enabled = map.eventsValid,
                                onClick = { map.setBorderBlock(st.block); vm.edited("Border block set"); menu = false },
                            )
                            DropdownMenuItem(text = { Text("Fit map to screen") }, onClick = { st.scale = 0f; menu = false })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF0E0C14))) {
                MapCanvas(vm, st, gfx, currentMap)
                if (!map.eventsValid && st.tool == EditTool.EVENTS) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(8.dp)) {
                        Text("This map's NPC data couldn't be read, so only tile painting is available.", Modifier.padding(8.dp))
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
                    if (st.tool == EditTool.EVENTS && st.selection != null) {
                        Inspector(vm, world, map, st, gfx)
                    } else if (st.tool == EditTool.PAINT || st.tool == EditTool.FILL || st.tool == EditTool.PICK) {
                        Palette(map, st, gfx)
                    } else if (st.tool == EditTool.EVENTS) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tap to select, drag to move.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            FilledTonalButton(onClick = { addingNpc = true }, enabled = map.canAddObject) { Text("+ NPC") }
                        }
                    } else {
                        Text("Drag to look around, pinch to zoom.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditTool.entries.forEach { t ->
                            FilterChip(selected = st.tool == t, onClick = { st.tool = t; if (t != EditTool.EVENTS) st.selection = null }, label = { Text(t.label) })
                        }
                    }
                }
            }
        }
    }

    if (addingNpc) {
        AddNpcDialog(world, map, gfx, onDismiss = { addingNpc = false }) { sprite, movement, textId ->
            val (x, y) = freeStepNear(map, centerStep(st, map))
            val err = map.addObject(sprite, x, y, textId, movement)
            if (err != null) vm.toast(err) else {
                vm.edited("NPC added — drag it where you want it")
                st.selection = Selection.Npc(map.objects.size)
            }
            addingNpc = false
        }
    }
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

/** The step in the middle of the viewport, used to drop new NPCs where the user is looking. */
private fun centerStep(st: EditorState, map: Gen1Map): Pair<Int, Int> {
    if (st.scale <= 0f) return map.stepWidth / 2 to map.stepHeight / 2
    val cx = ((st.viewport.width / 2f - st.offset.x) / (STEP * st.scale)).toInt()
    val cy = ((st.viewport.height / 2f - st.offset.y) / (STEP * st.scale)).toInt()
    return cx.coerceIn(0, map.stepWidth - 1) to cy.coerceIn(0, map.stepHeight - 1)
}

private fun freeStepNear(map: Gen1Map, start: Pair<Int, Int>): Pair<Int, Int> {
    for (r in 0..8) for (dy in -r..r) for (dx in -r..r) {
        val x = start.first + dx
        val y = start.second + dy
        if (x !in 0 until map.stepWidth || y !in 0 until map.stepHeight) continue
        if (map.objectAt(x, y) == null && map.warpAt(x, y) == null && map.signAt(x, y) == null) return x to y
    }
    return start
}

@Composable
private fun MapCanvas(vm: AppViewModel, st: EditorState, gfx: GfxCache, mapState: androidx.compose.runtime.State<Gen1Map>) {
    val cs = MaterialTheme.colorScheme
    val selColor = cs.secondary
    LaunchedEffect(st.viewport, st.scale <= 0f) {
        if (st.scale <= 0f) st.fit(mapState.value)
    }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { st.viewport = it }
            .pointerInput(st) { mapGestures(vm, st, mapState) },
    ) {
        val map = mapState.value
        @Suppress("UNUSED_VARIABLE") val redraw = st.tick
        if (st.scale <= 0f) return@Canvas
        val b = BLOCK * st.scale
        val s = STEP * st.scale
        val o = st.offset
        val px = IntSize(kotlin.math.ceil(b).toInt(), kotlin.math.ceil(b).toInt())

        // Border ring (what the player sees past the map edge), dimmed.
        if (map.eventsValid) {
            val border = gfx.block(map.tilesetId, map.borderBlock)
            for (y in -1..map.height) for (x in -1..map.width) {
                if (x in 0 until map.width && y in 0 until map.height) continue
                drawImage(border, IntOffset.Zero, IntSize(32, 32), IntOffset((o.x + x * b).toInt(), (o.y + y * b).toInt()), px,
                    alpha = 0.35f, filterQuality = FilterQuality.None)
            }
        }
        for (y in 0 until map.height) {
            val top = o.y + y * b
            if (top > size.height || top + b < 0) continue
            for (x in 0 until map.width) {
                val left = o.x + x * b
                if (left > size.width || left + b < 0) continue
                drawImage(gfx.block(map.tilesetId, map.block(x, y)), IntOffset.Zero, IntSize(32, 32),
                    IntOffset(left.toInt(), top.toInt()), px, filterQuality = FilterQuality.None)
            }
        }
        if (st.showCollision && map.tileset.hasCollision) {
            for (sy in 0 until map.stepHeight) for (sx in 0 until map.stepWidth) {
                if (!map.isWalkable(sx, sy)) drawRect(Color(0x66FF3B30), Offset(o.x + sx * s, o.y + sy * s), Size(s, s))
            }
        }
        if (st.showGrid) {
            val line = Color(0x40FFFFFF)
            for (x in 0..map.width) drawLine(line, Offset(o.x + x * b, o.y), Offset(o.x + x * b, o.y + map.height * b))
            for (y in 0..map.height) drawLine(line, Offset(o.x, o.y + y * b), Offset(o.x + map.width * b, o.y + y * b))
        }
        if (st.showEvents && map.eventsValid) drawEvents(map, st, gfx, s, o, selColor)
    }
}

private fun DrawScope.drawEvents(map: Gen1Map, st: EditorState, gfx: GfxCache, s: Float, o: Offset, sel: Color) {
    fun cell(x: Int, y: Int) = Offset(o.x + x * s, o.y + y * s)
    val stroke = Stroke(width = maxOf(2f, s / 10f))
    val dragging = st.dragTo
    map.warps.forEach { w ->
        val (x, y) = if (st.selection == Selection.Door(w.index) && dragging != null) dragging else w.x to w.y
        drawRect(Color(0x669B59D0), cell(x, y), Size(s, s))
        drawRect(if (st.selection == Selection.Door(w.index)) sel else Color(0xFFB57EDC), cell(x, y), Size(s, s), style = stroke)
    }
    map.signs.forEach { g ->
        val (x, y) = if (st.selection == Selection.SignPost(g.index) && dragging != null) dragging else g.x to g.y
        drawRect(Color(0x88F5C542), cell(x, y) + Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f))
        if (st.selection == Selection.SignPost(g.index)) drawRect(sel, cell(x, y), Size(s, s), style = stroke)
    }
    map.objects.forEach { n ->
        val (x, y) = if (st.selection == Selection.Npc(n.index) && dragging != null) dragging else n.x to n.y
        val img = gfx.sprite(n.sprite)
        if (img != null) {
            drawImage(img, IntOffset.Zero, IntSize(16, 16), IntOffset(cell(x, y).x.toInt(), cell(x, y).y.toInt()),
                IntSize(s.toInt(), s.toInt()), filterQuality = FilterQuality.None)
        } else {
            drawCircle(Color(0xFFFF5C8A), s * 0.4f, cell(x, y) + Offset(s / 2, s / 2))
        }
        if (st.selection == Selection.Npc(n.index)) drawRect(sel, cell(x, y), Size(s, s), style = stroke)
    }
}

/** One finger = current tool, two fingers = pan/zoom (cancelling any stroke in progress). */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mapGestures(
    vm: AppViewModel,
    st: EditorState,
    mapState: androidx.compose.runtime.State<Gen1Map>,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val map = mapState.value
        val rom = map.world.rom
        var multi = false
        var lastPos = down.position
        val startUndo = rom.changes.size
        fun blockAt(p: Offset): Pair<Int, Int> {
            val b = BLOCK * st.scale
            return floor((p.x - st.offset.x) / b).toInt() to floor((p.y - st.offset.y) / b).toInt()
        }
        fun stepAt(p: Offset): Pair<Int, Int> {
            val s = STEP * st.scale
            return floor((p.x - st.offset.x) / s).toInt() to floor((p.y - st.offset.y) / s).toInt()
        }
        fun paint(p: Offset) {
            val (x, y) = blockAt(p)
            if (x in 0 until map.width && y in 0 until map.height && map.block(x, y) != st.block) {
                map.setBlock(x, y, st.block)
                st.tick++
            }
        }
        // Event tool: grab whatever is under the finger.
        var grabbed: Selection? = null
        when (st.tool) {
            EditTool.PAINT -> { rom.beginGroup(); paint(down.position) }
            EditTool.EVENTS -> if (map.eventsValid) {
                val (sx, sy) = stepAt(down.position)
                grabbed = map.objectAt(sx, sy)?.let { Selection.Npc(it.index) }
                    ?: map.signAt(sx, sy)?.let { Selection.SignPost(it.index) }
                    ?: map.warpAt(sx, sy)?.let { Selection.Door(it.index) }
                st.selection = grabbed
            }
            else -> {}
        }
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size > 1 || st.tool == EditTool.PAN || (st.tool == EditTool.EVENTS && grabbed == null)) {
                if (pressed.size > 1 && !multi) {
                    multi = true
                    st.dragTo = null
                    if (st.tool == EditTool.PAINT) {
                        rom.endGroup()
                        if (rom.changes.size > startUndo) rom.undo()
                        st.tick++
                    }
                }
                val zoom = ev.calculateZoom()
                val pan = ev.calculatePan()
                val c = ev.calculateCentroid(useCurrent = true)
                val newScale = (st.scale * zoom).coerceIn(0.4f, 12f)
                val k = newScale / st.scale
                st.offset = Offset(c.x - (c.x - st.offset.x) * k, c.y - (c.y - st.offset.y) * k) + pan
                st.scale = newScale
            } else if (!multi) {
                val p = pressed[0].position
                if ((p - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                lastPos = p
                when (st.tool) {
                    EditTool.PAINT -> paint(p)
                    EditTool.EVENTS -> if (grabbed != null && moved) {
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
            EditTool.PAINT -> { rom.endGroup(); vm.edited() }
            EditTool.FILL -> {
                val (x, y) = blockAt(lastPos)
                if (map.fill(x, y, st.block) > 0) vm.edited()
            }
            EditTool.PICK -> {
                val (x, y) = blockAt(lastPos)
                if (x in 0 until map.width && y in 0 until map.height) {
                    st.block = map.block(x, y)
                    st.tool = EditTool.PAINT
                }
            }
            EditTool.EVENTS -> {
                val to = st.dragTo
                st.dragTo = null
                if (to != null) {
                    when (val g = grabbed) {
                        is Selection.Npc -> map.objects.getOrNull(g.index)?.let { map.moveObject(it, to.first, to.second) }
                        is Selection.SignPost -> map.signs.getOrNull(g.index)?.let { map.moveSign(it, to.first, to.second) }
                        is Selection.Door -> map.warps.getOrNull(g.index)?.let { map.moveWarp(it, to.first, to.second) }
                        null -> {}
                    }
                    vm.edited()
                }
            }
            EditTool.PAN -> {}
        }
    }
}

@Composable
private fun Palette(map: Gen1Map, st: EditorState, gfx: GfxCache) {
    val ts = map.tilesetId
    val blocks = remember(ts) { (0 until map.tileset.blockCount).toList() }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(gfx.block(ts, st.block), null, Modifier.size(40.dp), filterQuality = FilterQuality.None)
        Text(
            when (st.tool) {
                EditTool.FILL -> "Tap an area to fill it with block ${st.block}"
                EditTool.PICK -> "Tap the map to copy a block"
                else -> "Block ${st.block} · drag to paint, two fingers to move"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
        items(blocks) { id ->
            val selected = id == st.block
            Image(
                gfx.block(ts, id), "Block $id",
                Modifier.size(48.dp)
                    .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { st.block = id; if (st.tool == EditTool.PICK) st.tool = EditTool.PAINT },
                filterQuality = FilterQuality.None,
            )
        }
    }
}

@Composable
internal fun SpriteThumb(gfx: GfxCache, sprite: Int, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val img = gfx.sprite(sprite)
    if (img != null) {
        Image(img, "Sprite $sprite", Modifier.size(size).background(Color(0xFFE0F8D0), RoundedCornerShape(6.dp)), filterQuality = FilterQuality.None)
    } else {
        Box(Modifier.size(size).background(Color(0xFFFF5C8A), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text("$sprite", fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

internal fun movementLabel(movement: Int, range: Int): String =
    Movement.of(movement, range)?.label ?: if (movement == 0xFE) "Walks around" else "Stands still"
