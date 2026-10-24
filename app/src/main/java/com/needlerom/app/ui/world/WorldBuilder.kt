@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.world

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.ToolScaffold
import com.needlerom.core.Bytes
import com.needlerom.core.HackSession
import com.needlerom.core.world.Gen1World

/** Entry point: pick a map, then edit it. */
@Composable
fun WorldBuilderTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val world = s.world ?: return
    var mapId by rememberSaveable { mutableStateOf<Int?>(null) }
    val ref = mapId?.let { world.mapRef(it) }
    if (ref != null) {
        BackHandler { mapId = null }
        MapEditor(vm, world, ref, onBack = { mapId = null })
    } else {
        MapList(world, onBack) { mapId = it }
    }
}

@Composable
private fun MapList(world: Gen1World, onBack: () -> Unit, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = world.maps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    ToolScaffold("World builder", "${world.maps.size} maps", onBack, scroll = false) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Find a map") }, singleLine = true,
        )
        Hint("Towns and routes come first, then buildings, caves and dungeons.")
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Hint("${m.width}×${m.height} blocks · tileset ${m.tileset}")
                    }
                    Text("#${Bytes.hex(m.id)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun IntArray.toImage(w: Int, h: Int): ImageBitmap =
    Bitmap.createBitmap(this, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/** Caches rendered blocks and sprites; they never change while editing maps. */
internal class GfxCache(private val world: Gen1World) {
    private val blocks = HashMap<Long, ImageBitmap>()
    private val sprites = HashMap<Int, ImageBitmap?>()

    fun block(tileset: Int, id: Int): ImageBitmap =
        blocks.getOrPut((tileset.toLong() shl 16) or id.toLong()) { world.tileset(tileset).renderBlock(id).toImage(32, 32) }

    fun sprite(id: Int): ImageBitmap? = sprites.getOrPut(id) { world.spriteImage(id)?.toImage(16, 16) }
}
