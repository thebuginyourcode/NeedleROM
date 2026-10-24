@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.SectionCard
import com.needlerom.app.ui.ToolScaffold
import com.needlerom.core.Bytes
import com.needlerom.core.HackSession
import com.needlerom.core.mario.NES_PALETTE
import com.needlerom.core.mario.Smb1
import com.needlerom.core.mario.Smw

@Composable
private fun LivesEditor(title: String, current: Int, note: String, onSave: (Int) -> Unit, onBack: () -> Unit) {
    var lives by remember(current) { mutableIntStateOf(current) }
    ToolScaffold(title, "Currently $current", onBack) {
        SectionCard {
            Text("$lives", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                OutlinedButton(onClick = { lives = (lives - 10).coerceAtLeast(1) }) { Text("−10") }
                OutlinedButton(onClick = { lives = (lives - 1).coerceAtLeast(1) }) { Text("−1") }
                OutlinedButton(onClick = { lives = (lives + 1).coerceAtMost(99) }) { Text("+1") }
                OutlinedButton(onClick = { lives = (lives + 10).coerceAtMost(99) }) { Text("+10") }
            }
            Hint(note)
            Button(onClick = { onSave(lives) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
fun SmbLivesTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val current = remember(s, vm.revision) { Smb1.getLives(s.rom) } ?: return
    LivesEditor(
        "Starting lives", current,
        "SMB1 can only draw a single digit, so counts above 9 show odd symbols on the lives screen (the count itself still works).",
        onSave = { Smb1.setLives(s.rom, it); vm.edited("Mario now starts with $it lives") },
        onBack = onBack,
    )
}

@Composable
fun SmwLivesTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val current = remember(s, vm.revision) { Smw.getLives(s.rom, s.info) } ?: return
    LivesEditor(
        "Starting lives", current,
        "Applies to both Mario and Luigi when starting a new file.",
        onSave = { Smw.setLives(s.rom, s.info, it); vm.edited("Starting lives set to $it") },
        onBack = onBack,
    )
}

private fun nesColor(i: Int) = Color(0xFF000000 or NES_PALETTE[i and 0x3F].toLong())

// Small Mario standing frame: 1 = cap/shirt, 2 = skin, 3 = hair/overalls/shoes.
private val MARIO = listOf(
    "000111110000",
    "001111111110",
    "003332232000",
    "032322232220",
    "032332223222",
    "033222233330",
    "000222222200",
    "003313330000",
    "033313313330",
    "333311113333",
    "223121121322",
    "222111111222",
    "221111111122",
    "001110011100",
    "033300003330",
    "333300003333",
)

@Composable
private fun SpritePreview(colors: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(12f / 16f)) {
        val px = size.width / 12f
        drawRect(nesColor(colors[0]))
        MARIO.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val v = c - '0'
                if (v > 0) drawRect(nesColor(colors[v]), Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f))
            }
        }
    }
}

@Composable
fun SmbColorsTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val palettes = remember(s, vm.revision) { Smb1.getPalettes(s.rom) } ?: return
    var picking by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    ToolScaffold("Mario & Luigi colours", "NES palette", onBack) {
        Hint("Tap a colour swatch to change it. Slot 1 is the cap & shirt, 2 is skin, 3 is hair, overalls & shoes.")
        palettes.forEachIndexed { p, pal ->
            SectionCard(Smb1.paletteNames[p]) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpritePreview(pal, Modifier.size(72.dp, 96.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { slot ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier.size(40.dp)
                                        .background(nesColor(pal[slot]), RoundedCornerShape(8.dp))
                                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp))
                                        .clickable { picking = p to slot },
                                )
                                Text("Slot $slot · $${Bytes.hex(pal[slot])}")
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(onClick = {
            // Classic "swap" preset: Mario in Luigi's colours and vice versa.
            val m = palettes[0]; val l = palettes[1]
            (1..3).forEach { Smb1.setColor(s.rom, 0, it, l[it]); Smb1.setColor(s.rom, 1, it, m[it]) }
            vm.edited("Swapped Mario & Luigi colours")
        }) { Text("Swap Mario ↔ Luigi") }
    }

    picking?.let { (p, slot) ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("${Smb1.paletteNames[p]} · slot $slot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 8 per row keeps swatches tappable on phones.
                    for (row in 0 until 8) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 8) {
                                val idx = row * 8 + col
                                Swatch(idx, Modifier.weight(1f)) { Smb1.setColor(s.rom, p, slot, idx); vm.edited(); picking = null }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Swatch(idx: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.aspectRatio(1f)
            .background(nesColor(idx), RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    )
}
