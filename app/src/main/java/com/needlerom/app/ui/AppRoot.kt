package com.needlerom.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ExportKind
import com.needlerom.app.Screen
import com.needlerom.app.ui.tools.ToolHost

/** File-picker actions, provided by the root so any screen can trigger them. */
class FileActions(
    val openRom: () -> Unit,
    val openPatch: () -> Unit,
    val export: (ExportKind) -> Unit,
)

private val ANY_FILE = arrayOf("*/*")

@Composable
fun AppRoot(vm: AppViewModel, onExit: () -> Unit) {
    val openRom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadRom)
    }
    val openPatch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::applyPatch)
    }
    val saveRom = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.ROM.mime)) { uri ->
        uri?.let { vm.export(it, ExportKind.ROM) }
    }
    val saveIps = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.IPS.mime)) { uri ->
        uri?.let { vm.export(it, ExportKind.IPS) }
    }
    val saveBps = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.BPS.mime)) { uri ->
        uri?.let { vm.export(it, ExportKind.BPS) }
    }
    val actions = remember(vm) {
        FileActions(
            openRom = { openRom.launch(ANY_FILE) },
            openPatch = { openPatch.launch(ANY_FILE) },
            export = { kind ->
                val name = vm.session?.suggestedName(kind.ext) ?: "hack.bin"
                when (kind) {
                    ExportKind.ROM -> saveRom.launch(name)
                    ExportKind.IPS -> saveIps.launch(name)
                    ExportKind.BPS -> saveBps.launch(name)
                }
            },
        )
    }

    BackHandler(enabled = vm.screen != Screen.Home) {
        if (!vm.back()) onExit()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message = null
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm, actions)
            Screen.ChooseMode -> ModeScreen(vm)
            Screen.Workspace -> WorkspaceScreen(vm, actions)
            is Screen.ToolScreen -> ToolHost(vm, s.tool, actions)
        }
        if (vm.busy) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
        )
    }
}
