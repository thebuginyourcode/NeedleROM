package com.needlerom.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.needlerom.engine.pokered.EditException
import com.needlerom.engine.pokered.PokeRed
import com.needlerom.engine.project.Project
import com.needlerom.engine.rom.Bps
import com.needlerom.engine.rom.GbChecksum
import com.needlerom.engine.rom.Ips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the user is. The back stack is a list of these. */
sealed interface Route {
    data object Home : Route
    data class Workspace(val tab: Tab = Tab.WORLD) : Route
    data class MapEditor(val mapId: Int) : Route
    data class ScriptEditor(val mapId: Int, val textId: Int, val title: String) : Route
    data class PokemonEditor(val dex: Int) : Route
    data class TrainerEditor(val trainerClass: Int) : Route
    data class WildEditor(val mapId: Int) : Route
    data object Playtest : Route
}

enum class Tab(val label: String) { WORLD("World"), EVENTS("Events"), DATA("Data"), PLAY("Play") }

enum class ExportKind(val ext: String, val label: String) { ROM("gb", "ROM"), IPS("ips", "IPS patch"), BPS("bps", "BPS patch") }

class AppState(app: Application) : AndroidViewModel(app) {
    private val root = File(app.filesDir, "projects")

    val projects = mutableStateListOf<Project>()
    var project: Project? by mutableStateOf(null)
        private set
    var game: PokeRed? by mutableStateOf(null)
        private set

    /** Bumped after every edit so screens re-read ROM-backed data. */
    var revision by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var message: String? by mutableStateOf(null)

    private val stack = mutableStateListOf<Route>(Route.Home)
    val route: Route get() = stack.last()

    init {
        refreshProjects()
    }

    fun go(r: Route) { stack.add(r) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        if (stack.last() == Route.Home) closeProject()
        return true
    }
    /** Replaces the current screen (e.g. switching workspace tabs). */
    fun replace(r: Route) { stack[stack.lastIndex] = r }

    fun toast(m: String) { message = m }

    // ---- Projects ---------------------------------------------------------------------

    fun refreshProjects() {
        projects.clear()
        projects.addAll(Project.list(root))
    }

    private val resolver get() = getApplication<Application>().contentResolver

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "My hack"

    fun createProject(uri: Uri) = work("Couldn't create the project") {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
        val name = displayName(uri).substringBeforeLast('.').ifBlank { "My hack" } + " hack"
        val p = Project.create(root, name, bytes)
        withContext(Dispatchers.Main) { refreshProjects(); openProjectNow(p) }
    }

    fun openProject(p: Project) = work("Couldn't open the project") {
        val g = p.open()
        withContext(Dispatchers.Main) { project = p; game = g; revision++; go(Route.Workspace()) }
    }

    private suspend fun openProjectNow(p: Project) {
        val g = withContext(Dispatchers.IO) { p.open() }
        project = p; game = g; revision++; go(Route.Workspace())
    }

    fun deleteProject(p: Project) {
        p.delete(); refreshProjects()
    }

    fun renameProject(name: String) {
        val p = project ?: return
        p.name = name; p.saveMeta(); refreshProjects()
    }

    private fun closeProject() {
        saveNow()
        project = null; game = null
        refreshProjects()
    }

    // ---- Editing ----------------------------------------------------------------------

    private var saveJob: Job? = null

    /** Runs an edit; shows its error message if it fails, otherwise schedules an autosave. */
    fun edit(note: String? = null, block: (PokeRed) -> Unit): Boolean {
        val g = game ?: return false
        return try {
            block(g)
            edited(note)
            true
        } catch (e: EditException) {
            message = e.message; false
        } catch (e: IllegalArgumentException) {
            message = e.message ?: "That can't be done"; false
        }
    }

    fun edited(note: String? = null) {
        revision++
        if (note != null) message = note
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            withContext(Dispatchers.IO) { saveNow() }
        }
    }

    @Synchronized
    fun saveNow() {
        val p = project ?: return
        val g = game ?: return
        p.save(g)
    }

    fun undo() {
        val label = game?.rom?.undo()
        edited(if (label != null) "Undid: $label" else "Nothing to undo")
    }

    // ---- Export -------------------------------------------------------------------------

    fun export(uri: Uri, kind: ExportKind) = work("Export failed") {
        val g = game ?: return@work
        val rom = g.rom.data.copyOf().also(GbChecksum::fix)
        val bytes = when (kind) {
            ExportKind.ROM -> rom
            ExportKind.IPS -> Ips.create(g.rom.original, rom)
            ExportKind.BPS -> Bps.create(g.rom.original, rom, "Made with NeedleROM")
        }
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Couldn't write the file")
        withContext(Dispatchers.Main) { message = "${kind.label} saved" }
    }

    fun suggestedName(kind: ExportKind): String = (project?.name ?: "hack").replace(' ', '_') + "." + kind.ext

    private fun work(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message = e.message ?: failure
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        saveNow()
        super.onCleared()
    }
}
