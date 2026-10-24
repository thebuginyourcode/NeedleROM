package com.needlerom.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.needlerom.core.GameProfile
import com.needlerom.core.GameProfiles
import com.needlerom.core.HackSession
import com.needlerom.core.Mode
import com.needlerom.core.Rom
import com.needlerom.core.RomInfo
import com.needlerom.core.SystemDetector
import com.needlerom.core.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object ChooseMode : Screen
    data object Workspace : Screen
    data class ToolScreen(val tool: Tool) : Screen
}

enum class ExportKind(val mime: String, val ext: String?) {
    ROM("application/octet-stream", null),
    IPS("application/octet-stream", "ips"),
    BPS("application/octet-stream", "bps"),
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()

    /** Loaded but not yet started (the user is choosing auto vs. a specific game). */
    var loadedRom: Rom? by mutableStateOf(null)
        private set
    var loadedInfo: RomInfo? by mutableStateOf(null)
        private set
    var loadedDetected: GameProfile? by mutableStateOf(null)
        private set

    var session: HackSession? by mutableStateOf(null)
        private set

    /** Bumped after every edit so Compose re-reads ROM-backed values. */
    var revision by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set
    var message: String? by mutableStateOf(null)
    var fixChecksums by mutableStateOf(true)

    private val resolver get() = getApplication<Application>().contentResolver

    fun navigate(to: Screen) {
        backStack.addLast(screen)
        screen = to
    }

    /** Returns false when there is nowhere left to go back to (let the system close the app). */
    fun back(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        screen = prev
        return true
    }

    private fun resetTo(s: Screen) {
        backStack.clear()
        screen = s
    }

    fun toast(text: String) { message = text }

    fun edited(note: String? = null) {
        revision++
        if (note != null) message = note
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"

    private fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open file")

    fun loadRom(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val (rom, info, detected) = withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val bytes = readBytes(uri)
                    require(bytes.size in 1024..(128 * 1024 * 1024)) { "That file doesn't look like a ROM (size ${bytes.size} bytes)" }
                    val rom = Rom(name, bytes)
                    val info = SystemDetector.detect(rom)
                    Triple(rom, info, GameProfiles.detect(rom, info))
                }
                loadedRom = rom
                loadedInfo = info
                loadedDetected = detected
                session = null
                resetTo(Screen.Home)
                navigate(Screen.ChooseMode)
            } catch (e: Exception) {
                message = e.message ?: "Couldn't read that file"
            } catch (e: OutOfMemoryError) {
                message = "ROM is too large for this device's memory"
            } finally {
                busy = false
            }
        }
    }

    fun start(mode: Mode, profile: GameProfile?) {
        val rom = loadedRom ?: return
        viewModelScope.launch {
            busy = true
            val s = withContext(Dispatchers.Default) {
                HackSession(rom, mode, profile).also { it.toolStates() /* warm lazy table lookups */ }
            }
            session = s
            busy = false
            navigate(Screen.Workspace)
        }
    }

    fun switchToOptimized() {
        val s = session ?: return
        val p = s.detectedProfile ?: return
        session = HackSession(s.rom, Mode.OPTIMIZED, p)
        revision++
        message = "Switched to ${p.name} toolkit"
    }

    fun undo() {
        val label = session?.rom?.undo()
        edited(if (label != null) "Undid: $label" else "Nothing to undo")
    }

    fun revertAll() {
        session?.rom?.revertAll()
        edited("All changes reverted")
    }

    fun export(uri: Uri, kind: ExportKind) {
        val s = session ?: return
        viewModelScope.launch {
            busy = true
            try {
                val notes = withContext(Dispatchers.IO) {
                    val (bytes, notes) = when (kind) {
                        ExportKind.ROM -> s.exportBytes(fixChecksums)
                        ExportKind.IPS -> s.makeIps(fixChecksums) to emptyList()
                        ExportKind.BPS -> s.makeBps(fixChecksums) to emptyList()
                    }
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write file")
                    notes
                }
                message = buildString {
                    append(when (kind) { ExportKind.ROM -> "Hacked ROM saved"; ExportKind.IPS -> "IPS patch saved"; ExportKind.BPS -> "BPS patch saved" })
                    if (notes.isNotEmpty()) append(" · ").append(notes.joinToString(", "))
                }
            } catch (e: Exception) {
                message = e.message ?: "Export failed"
            } finally {
                busy = false
            }
        }
    }

    fun applyPatch(uri: Uri) {
        val s = session ?: return
        viewModelScope.launch {
            busy = true
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val bytes = withContext(Dispatchers.IO) { readBytes(uri) }
                val warning = withContext(Dispatchers.Default) { s.applyPatch(bytes, name) }
                edited(if (warning != null) "Patch applied with warnings:\n$warning" else "Applied $name")
            } catch (e: Exception) {
                message = e.message ?: "Couldn't apply patch"
            } finally {
                busy = false
            }
        }
    }

    fun closeRom() {
        session = null
        loadedRom = null
        loadedInfo = null
        loadedDetected = null
        resetTo(Screen.Home)
    }
}
