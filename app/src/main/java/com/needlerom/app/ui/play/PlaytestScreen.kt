@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.play

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.app.ui.Screen
import com.needlerom.engine.gb.Button
import com.needlerom.engine.pokered.QuickStart
import kotlinx.coroutines.delay

@Composable
fun PlaytestScreen(state: AppState, start: Triple<Int, Int, Int>? = null) {
    val game = state.game ?: return
    val project = state.project ?: return
    var session by remember { mutableIntStateOf(0) } // bump to restart with the latest edits
    val play = remember(session) {
        state.saveNow()
        if (start == null) Playtest(game.rom.data.copyOf(), project.saveFile)
        else {
            // A fresh game that begins on the chosen tile; it never touches the real playtest save.
            val (map, x, y) = start
            val sav = java.io.File(project.saveFile.parentFile, "quick.sav").apply { delete() }
            Playtest(QuickStart.rom(game, map, x, y), sav) { gb, cancelled -> QuickStart.skipIntro(gb, game, map, x, y, cancelled) }
        }
    }
    var preparing by remember(play) { mutableStateOf(play.preparing) }
    LaunchedEffect(play) { while (play.preparing) delay(100); preparing = false }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var fast by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }

    DisposableEffect(play) {
        play.onFrame = { bmp -> frame = bmp.asImageBitmap() }
        play.start()
        onDispose { play.release() }
    }

    Screen(
        title = "Playtest",
        subtitle = project.name,
        onBack = { state.back() },
        scroll = false,
        actions = {
            TextButton(onClick = { session++ }) { Text("Restart") }
        },
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1B1A20)).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().padding(12.dp).aspectRatio(160f / 144f).background(Color(0xFFE0F8D0), RoundedCornerShape(6.dp))) {
                if (preparing) Text("Skipping the intro…", Modifier.align(Alignment.Center), color = Color(0xFF346856))
                else frame?.let {
                    Image(it, "Game screen", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(fast, { fast = !fast; play.fastForward = fast }, label = { Text("Fast ×4") })
                FilterChip(sound, { sound = !sound; play.soundOn = sound }, label = { Text("Sound") })
                TextButton(onClick = { play.saveState(project.stateFile(1)); state.toast("State saved") }) { Text("Save state") }
                TextButton(onClick = {
                    val f = project.stateFile(1)
                    if (f.exists()) runCatching { play.loadState(f) }.onFailure { state.toast("That state is from a different version of your hack") }
                    else state.toast("No saved state yet")
                }) { Text("Load") }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                DPad(play)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("B", Button.B, play, Modifier.padding(top = 30.dp))
                    RoundButton("A", Button.A, play, Modifier.padding(bottom = 30.dp))
                }
            }
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PillButton("SELECT", Button.SELECT, play)
                PillButton("START", Button.START, play)
            }
            Text("Edits apply when you tap Restart. Your in-game save is kept between playtests.",
                Modifier.padding(bottom = 8.dp, start = 16.dp, end = 16.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A96A0))
        }
    }
}

/** A button that holds [mask] while pressed (multi-touch friendly). */
private fun Modifier.hold(play: Playtest, mask: Int): Modifier = pointerInput(mask) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        play.buttons = play.buttons or mask
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.changes.none { it.pressed }) break
        }
        play.buttons = play.buttons and mask.inv()
    }
}

@Composable
private fun RoundButton(label: String, mask: Int, play: Playtest, modifier: Modifier = Modifier) {
    Box(modifier.size(68.dp).background(Color(0xFF8B2E4E), CircleShape).hold(play, mask), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PillButton(label: String, mask: Int, play: Playtest) {
    Box(Modifier.width(76.dp).height(28.dp).background(Color(0xFF4A4752), RoundedCornerShape(14.dp)).hold(play, mask), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** D-pad: slide your thumb between directions; diagonals press two directions. */
@Composable
private fun DPad(play: Playtest) {
    val dirs = Button.UP or Button.DOWN or Button.LEFT or Button.RIGHT
    Box(
        Modifier.size(150.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun apply(x: Float, y: Float) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val dx = x - cx; val dy = y - cy
                    val dead = size.width * 0.12f
                    var m = 0
                    if (dx > dead && dx > -dy / 2.4f && dx > dy / 2.4f) m = m or Button.RIGHT
                    if (dx < -dead && -dx > -dy / 2.4f && -dx > dy / 2.4f) m = m or Button.LEFT
                    if (dy > dead && dy > -dx / 2.4f && dy > dx / 2.4f) m = m or Button.DOWN
                    if (dy < -dead && -dy > -dx / 2.4f && -dy > dx / 2.4f) m = m or Button.UP
                    play.buttons = (play.buttons and dirs.inv()) or m
                }
                apply(down.position.x, down.position.y)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    apply(c.position.x, c.position.y)
                }
                play.buttons = play.buttons and dirs.inv()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val c = Color(0xFF34313A)
        Box(Modifier.size(width = 50.dp, height = 150.dp).background(c, RoundedCornerShape(8.dp)))
        Box(Modifier.size(width = 150.dp, height = 50.dp).background(c, RoundedCornerShape(8.dp)))
    }
}
