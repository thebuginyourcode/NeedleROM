@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.data

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.Route
import com.needlerom.app.ui.Chooser
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.Pixel
import com.needlerom.app.ui.Screen
import com.needlerom.app.ui.Section
import com.needlerom.app.ui.play.Playtest
import com.needlerom.app.ui.toImage
import com.needlerom.app.ui.world.TextDialog
import com.needlerom.engine.pokered.GameLine
import com.needlerom.engine.pokered.GameText
import com.needlerom.engine.pokered.Intro
import com.needlerom.engine.pokered.Pics
import com.needlerom.engine.pokered.PokeRed

/** Oak's speech: who is shown, the Pokémon, and every line. */
@Composable
fun IntroScreen(state: AppState) {
    val game = state.game ?: return
    val rev = state.revision
    val supported = remember(game) { Intro.supported(game) }
    val pics = remember(game) { PicImages(game) }
    var editing by remember { mutableStateOf<GameLine?>(null) }
    var picking by remember { mutableStateOf<Intro.Role?>(null) }

    Screen(title = "Intro", subtitle = "Oak's speech at the start of a new game", onBack = { state.back() }) {
        if (!supported) {
            Hint("This ROM's intro code has been changed by another tool, so it can't be edited here.")
            return@Screen
        }
        val texts = remember(game, rev) { Intro.texts(game) }
        fun lines(i: Int) = texts.getOrNull(i)?.second.orEmpty()

        Section("Professor") {
            PictureRow(state, game, pics, Intro.Role.PROFESSOR, rev) { picking = Intro.Role.PROFESSOR }
            Lines(texts.getOrNull(0)?.first, lines(0)) { editing = it }
        }
        Section("Pokémon he shows") {
            val species = remember(game, rev) { Intro.species(game) }
            Chooser("Pokémon", game.monName(species), game.species.map { it.first }, { game.monName(it) }, Modifier.fillMaxWidth()) { sp ->
                state.edit("Intro Pokémon: ${game.monName(sp)}") { Intro.setSpecies(it, sp) }
            }
            val cry = remember(game, rev) { Intro.cry(game) }
            if (cry != null) Chooser("Sound", Intro.CRIES.firstOrNull { it.first == cry }?.second ?: "?", Intro.CRIES, { it.second }, Modifier.fillMaxWidth()) { c ->
                state.edit { Intro.setCry(it, c.first) }
            }
            Hint("The original game plays Nidorina's cry for its Nidorino too; only these three cries are built into the intro.")
            Lines(texts.getOrNull(1)?.first, lines(1)) { editing = it }
        }
        Section("Player") {
            PictureRow(state, game, pics, Intro.Role.PLAYER, rev) { picking = Intro.Role.PLAYER }
            Hint("Shown when you pick the player's name and before the game starts. The shrinking animation at the very end stays the original player.")
            Lines(texts.getOrNull(2)?.first, lines(2)) { editing = it }
            Lines(texts.getOrNull(4)?.first, lines(4)) { editing = it }
        }
        Section("Rival") {
            PictureRow(state, game, pics, Intro.Role.RIVAL, rev) { picking = Intro.Role.RIVAL }
            Lines(texts.getOrNull(3)?.first, lines(3)) { editing = it }
        }
        Hint("To see it, use Play from the toolbar and choose NEW GAME.")
    }

    editing?.let { line ->
        TextDialog("Edit line", line.text, onDismiss = { editing = null }) { text ->
            if (state.edit("Intro line saved") { GameText.write(game, line, text) }) editing = null
        }
    }
    picking?.let { role ->
        val current = remember(game, rev, role) { Intro.picture(game, role) }
        val choices = remember(game) { Intro.choices(game) }
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("${role.label} picture") },
            text = {
                LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.heightIn(max = 460.dp)) {
                    items(choices) { p ->
                        val on = p == current
                        Column(
                            Modifier.padding(4.dp).border(2.dp, if (on) MaterialTheme.colorScheme.secondary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    state.edit("${role.label}: ${p.name}") { Intro.setPicture(it, role, p) }
                                    picking = null
                                }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Pixel(pics[p], 64.dp)
                            Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PictureRow(state: AppState, game: PokeRed, pics: PicImages, role: Intro.Role, rev: Int, onPick: () -> Unit) {
    val p = remember(game, rev, role) { Intro.picture(game, role) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pixel(pics[p], 84.dp, Modifier.clickable(onClick = onPick))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onPick) { Text("Change picture") }
        }
    }
}

@Composable
private fun Lines(label: String?, lines: List<GameLine>, onEdit: (GameLine) -> Unit) {
    if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    lines.forEach { line ->
        Row(Modifier.fillMaxWidth().clickable { onEdit(line) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("“${line.text.replace('\n', ' ')}”", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Filled.Edit, "Edit line", Modifier.padding(start = 8.dp))
        }
    }
}

/** Decoded pictures as images, cached. */
private class PicImages(private val game: PokeRed) {
    private val cache = HashMap<Pair<Int, Int>, ImageBitmap?>()
    operator fun get(p: Intro.Picture): ImageBitmap? = cache.getOrPut(p.bank to p.ptr) {
        Pics.decode(game.rom, p.bank, p.ptr)?.centered()?.let { px -> IntArray(px.size) { Playtest.SHADES[px[it]] }.toImage(56, 56) }
    }
}
