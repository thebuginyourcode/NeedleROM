@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.needlerom.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.core.Family
import com.needlerom.core.GameProfile
import com.needlerom.core.GameProfiles
import com.needlerom.core.Mode
import com.needlerom.core.Platform
import com.needlerom.core.SystemDetector

@Composable
fun ModeScreen(vm: AppViewModel) {
    val rom = vm.loadedRom ?: return
    val info = vm.loadedInfo ?: return
    val detected = vm.loadedDetected
    val cs = MaterialTheme.colorScheme

    ToolScaffold(title = "Choose how to hack", subtitle = rom.fileName, onBack = { vm.back() }) {
        SectionCard {
            InfoRow("System", info.platform.label)
            if (info.title.isNotBlank()) InfoRow("Internal title", info.title)
            if (info.gameCode.isNotBlank()) InfoRow("Game code", info.gameCode)
            InfoRow("Size", SystemDetector.sizeLabel(rom.size))
            InfoRow("Recognised as", detected?.name ?: "Not a game we have a profile for")
            rom.loadNote?.let { Hint(it) }
        }

        // Option 1: no choice needed.
        Card(
            onClick = { vm.start(Mode.AUTO, null) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Auto-detect (no setup)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                Text(
                    if (info.platform == Platform.UNKNOWN) "Universal toolkit for any file: relative text search, hex editing and patching."
                    else "Universal ${info.platform.shortName} toolkit: text search, hex editor, patching and automatic checksum repair. Works with any game, but with fewer tools than an optimized profile.",
                    color = cs.onPrimaryContainer,
                )
            }
        }

        Text("Or pick your game for the full toolkit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Hint("Optimized profiles know where each game keeps its data. Tools that can't find their data in your ROM are switched off rather than risking corruption.")

        Family.entries.forEach { fam ->
            Text(fam.label, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(top = 4.dp))
            // Show matching-platform profiles first.
            GameProfiles.all.filter { it.family == fam }
                .sortedByDescending { info.platform in it.platforms }
                .forEach { p -> ProfileRow(p, isDetected = p.id == detected?.id, platformOk = info.platform in p.platforms) { vm.start(Mode.OPTIMIZED, p) } }
        }
    }
}

@Composable
private fun ProfileRow(p: GameProfile, isDetected: Boolean, platformOk: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        border = if (isDetected) BorderStroke(2.dp, cs.secondary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${p.subtitle} · ${p.tools.size} tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (platformOk) cs.onSurfaceVariant else cs.error,
                )
            }
            if (isDetected) {
                Surface(color = cs.secondary, shape = MaterialTheme.shapes.small) {
                    Text("Detected", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = cs.onSecondary, style = MaterialTheme.typography.labelMedium)
                }
            } else if (!platformOk) {
                Text("Different system", color = cs.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
