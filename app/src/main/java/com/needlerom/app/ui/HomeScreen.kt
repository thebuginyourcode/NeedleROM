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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppState
import com.needlerom.engine.project.Project
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(state: AppState, files: Files) {
    val cs = MaterialTheme.colorScheme
    var deleting by remember { mutableStateOf<Project?>(null) }
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 12.dp)
                        .background(Brush.linearGradient(listOf(cs.primaryContainer, Color30)), RoundedCornerShape(24.dp))
                        .padding(22.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("NeedleROM Engine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Text("Build your own Pokémon Red, Blue or Yellow hack: maps, NPCs, events, trainers and wild Pokémon, then playtest it right here.",
                            color = cs.onPrimaryContainer)
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = files.openRom, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, null); Text("  New project from a ROM")
                        }
                        Hint("Use a ROM of a game you own. Everything stays on this device.")
                    }
                }
            }
            if (state.projects.isEmpty()) {
                item { Hint("No projects yet. Start one from your Pokémon Red, Blue or Yellow ROM (English).", Modifier.padding(8.dp)) }
            } else {
                item { Text("Projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            items(state.projects, key = { it.dir.path }) { p ->
                Card(
                    onClick = { state.openProject(p) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Hint("${p.gameName} · edited ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.modified))}")
                        }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("This removes the project and its saves from this device. Export it first if you want to keep it.") },
            confirmButton = { TextButton(onClick = { state.deleteProject(p); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private val Color30 = androidx.compose.ui.graphics.Color(0xFF1C1A21)
