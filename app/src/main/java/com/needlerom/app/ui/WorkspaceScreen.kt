@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ExportKind
import com.needlerom.app.Screen
import com.needlerom.core.Mode
import com.needlerom.core.ToolState
import com.needlerom.core.patch.Ips

@Composable
fun WorkspaceScreen(vm: AppViewModel, actions: FileActions) {
    val s = vm.session ?: return
    val rev = vm.revision
    val states = remember(s, rev) { s.toolStates() }
    val cs = MaterialTheme.colorScheme
    val modeLabel = if (s.mode == Mode.AUTO) "Auto-detect toolkit" else s.profile?.name ?: "Toolkit"

    ToolScaffold(
        title = s.rom.fileName,
        subtitle = "$modeLabel · ${s.info.platform.shortName}",
        onBack = { vm.back() },
        actions = { IconButton(onClick = { vm.closeRom() }) { Icon(Icons.Filled.Close, "Close ROM") } },
        bottomBar = { ExportBar(vm, actions, ipsOk = remember(s) { Ips.canCreate(s.rom.data) }) },
    ) {
        if (s.profileMismatch) {
            Banner(
                "This ROM doesn't look like ${s.profile?.name}. Tools that can't find their data are disabled, so nothing will be corrupted.",
            )
        }
        val detected = s.detectedProfile
        if (s.mode == Mode.AUTO && detected != null) {
            Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Looks like ${detected.name}!", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
                    Text(
                        "Switch to the optimized profile for ${detected.tools.size} tools instead of ${s.tools.size}. Your edits are kept.",
                        color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { vm.switchToOptimized() }) { Text("Unlock optimized tools") }
                }
            }
        }

        states.forEach { st -> ToolCard(st) { vm.navigate(Screen.ToolScreen(st.tool)) } }

        SectionCard("Your hack") {
            val changes = s.rom.changes
            if (changes.isEmpty()) {
                Hint("No changes yet. Open a tool above to start hacking.")
            } else {
                Text("${changes.size} edit(s)", fontWeight = FontWeight.Medium)
                changes.takeLast(6).reversed().forEach { Hint("• ${it.label}") }
                if (changes.size > 6) Hint("…and ${changes.size - 6} more")
                TextButton(onClick = { vm.revertAll() }) { Text("Revert everything") }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, null, tint = cs.onErrorContainer)
            Text(text, color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToolCard(st: ToolState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = st.available,
        modifier = Modifier.fillMaxWidth().alpha(if (st.available) 1f else 0.55f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.tool.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    st.reason ?: st.tool.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (st.available) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun ExportBar(vm: AppViewModel, actions: FileActions, ipsOk: Boolean) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.fixChecksums, onCheckedChange = { vm.fixChecksums = it })
                Text("Fix checksums on export", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { actions.export(ExportKind.ROM) }, modifier = Modifier.weight(1.4f)) { Text("Save ROM") }
                FilledTonalButton(onClick = { actions.export(ExportKind.BPS) }, modifier = Modifier.weight(1f)) { Text("BPS") }
                OutlinedButton(onClick = { actions.export(ExportKind.IPS) }, enabled = ipsOk, modifier = Modifier.weight(1f)) { Text("IPS") }
            }
        }
    }
}
