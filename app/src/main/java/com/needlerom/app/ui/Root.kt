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
import com.needlerom.app.AppState
import com.needlerom.app.ExportKind
import com.needlerom.app.Route

/** File pickers, created once at the root so any screen can use them. */
class Files(val openRom: () -> Unit, val export: (ExportKind) -> Unit)

@Composable
fun Root(state: AppState, onExit: () -> Unit) {
    val openRom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(state::createProject) }
    val saveRom = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { state.export(it, ExportKind.ROM) } }
    val saveIps = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { state.export(it, ExportKind.IPS) } }
    val saveBps = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { state.export(it, ExportKind.BPS) } }
    val files = remember(state) {
        Files(
            openRom = { openRom.launch(arrayOf("*/*")) },
            export = { k ->
                val name = state.suggestedName(k)
                when (k) { ExportKind.ROM -> saveRom.launch(name); ExportKind.IPS -> saveIps.launch(name); ExportKind.BPS -> saveBps.launch(name) }
            },
        )
    }

    BackHandler(enabled = state.route != Route.Home) { if (!state.back()) onExit() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        state.message = null
    }

    Box(Modifier.fillMaxSize()) {
        when (state.route) {
            Route.Home -> HomeScreen(state, files)
            else -> StudioScreen(state, files)
        }
        if (state.busy) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}
