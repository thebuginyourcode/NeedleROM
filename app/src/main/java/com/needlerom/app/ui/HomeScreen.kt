@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.needlerom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.Screen
import com.needlerom.core.Family
import com.needlerom.core.GameProfiles

@Composable
fun HomeScreen(vm: AppViewModel, actions: FileActions) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(cs.primaryContainer, cs.secondaryContainer)),
                        RoundedCornerShape(28.dp),
                    )
                    .padding(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NeedleROM", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = cs.onPrimaryContainer)
                    Text(
                        "Make your own ROM hack from a ROM you already own — right on your phone.",
                        style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = actions.openRom, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Add, null)
                        Text("  Open ROM file")
                    }
                    if (vm.loadedRom != null) {
                        OutlinedButton(
                            onClick = { vm.navigate(if (vm.session != null) Screen.Workspace else Screen.ChooseMode) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Continue with ${vm.loadedRom?.fileName}") }
                    }
                }
            }

            Feature(Icons.Filled.Star, "Optimized for your game",
                "Pick a supported Pokémon or Mario game to unlock deep editors: base stats, names, dialogue, lives, colours and randomizers.")
            Feature(Icons.Filled.Create, "Build your world",
                "In Pokémon Red, Blue and Yellow, paint maps with the game's real tiles, place and move NPCs, change what they say, swap item ball contents and rewire doors.")
            Feature(Icons.Filled.Search, "Or just auto-detect",
                "Don't want to choose? NeedleROM identifies the system and game for you and gives you a universal toolkit that works on anything.")
            Feature(Icons.Filled.Build, "Share it as a patch",
                "Export a playable ROM, or an IPS / BPS patch so others can apply your hack to their own copy.")

            SectionCard("Optimized games") {
                Family.entries.forEach { fam ->
                    Text(fam.label, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    GameProfiles.all.filter { it.family == fam }.forEach {
                        Text("• ${it.name}  ·  ${it.subtitle}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Hint("Auto-detect also supports any other NES, SNES, GB/GBC, GBA, N64 and DS ROM.")
            }

            Hint("Only use ROMs dumped from games you own. NeedleROM never uploads your files — everything happens on-device.")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
