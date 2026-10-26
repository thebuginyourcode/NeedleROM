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

/**
 * Where the user is. [Home] and [Studio] are screens; everything else is a document that
 * opens as a tab inside the studio.
 */
sealed interface Route {
    data object Home : Route
    data object Studio : Route
    data class MapEditor(val mapId: Int) : Route
    data class ScriptEditor(val mapId: Int, val textId: Int, val title: String) : Route
    /** Oak's speech at the start of a new game. */
    data object Intro : Route
    /** An original game script, read as blocks. */
    data class GameScript(val mapId: Int, val textId: Int, val title: String) : Route
    data class PokemonEditor(val dex: Int) : Route
    data class TrainerEditor(val trainerClass: Int) : Route
    data class WildEditor(val mapId: Int) : Route
    /** [start] = (map, x, y) starts a fresh game there instead of booting normally. */
    data class Playtest(val start: Triple<Int, Int, Int>? = null) : Route
}

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
    private var shown: String? by mutableStateOf(null)

    /** The latest message (shown briefly); every message is also kept in [log]. */
    var message: String?
        get() = shown
        set(v) { shown = v; if (v != null) log += v }
    val log = mutableStateListOf<String>()

    var route: Route by mutableStateOf(Route.Home)
        private set

    /** Documents open in the studio, and which one is showing. */
    val docs = mutableStateListOf<Route>()
    var activeDoc by mutableIntStateOf(0)
    val doc: Route? get() = docs.getOrNull(activeDoc)

    /** Per-map editor state (tool, selection, zoom) for the open project. */
    val editors = HashMap<Int, com.needlerom.app.ui.world.EditorState>()

    init {
        refreshProjects()
    }

    /** Opens a document as a tab (or focuses it if it's already open). */
    fun go(r: Route) {
        when (r) {
            Route.Home -> { if (route != Route.Home) closeProject(); route = Route.Home }
            Route.Studio -> route = Route.Studio
            else -> {
                val i = docs.indexOf(r)
                if (i >= 0) activeDoc = i else { docs += r; activeDoc = docs.lastIndex }
            }
        }
    }

    /** Shows [r] in place of the current document tab. */
    fun replace(r: Route) {
        val i = docs.indexOf(r)
        when {
            i >= 0 -> activeDoc = i
            docs.isEmpty() -> go(r)
            else -> docs[activeDoc] = r
        }
    }

    fun closeDoc(i: Int) {
        if (i !in docs.indices) return
        docs.removeAt(i)
        if (activeDoc >= docs.size) activeDoc = maxOf(0, docs.lastIndex) else if (i < activeDoc) activeDoc--
    }

    /** Back closes the current tab; with one tab left it returns to the project list. */
    fun back(): Boolean {
        if (route == Route.Home) return false
        if (docs.size > 1) closeDoc(activeDoc) else go(Route.Home)
        return true
    }

    fun toast(m: String) { message = m }

    /** Map name, using the project's names for maps made in the editor. */
    fun mapName(id: Int): String = project?.mapNames?.get(id) ?: game?.mapName(id) ?: "Map $id"

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
        withContext(Dispatchers.Main) { project = p; game = g; revision++; enterStudio() }
    }

    private suspend fun openProjectNow(p: Project) {
        val g = withContext(Dispatchers.IO) { p.open() }
        project = p; game = g; revision++; enterStudio()
    }

    private fun enterStudio() {
        docs.clear(); log.clear(); editors.clear()
        docs += Route.MapEditor(0); activeDoc = 0
        route = Route.Studio
    }

    /** Saves the open project as one backup file. */
    fun backupProject(uri: Uri) = work("Backup failed") {
        val p = project ?: return@work
        saveNow()
        resolver.openOutputStream(uri, "wt")?.use { p.backup(it) } ?: error("Couldn't write the file")
        withContext(Dispatchers.Main) { message = "Backup saved" }
    }

    fun restoreProject(uri: Uri) = work("Couldn't restore that backup") {
        val p = resolver.openInputStream(uri)?.use { Project.restore(root, it) } ?: error("Couldn't read the file")
        withContext(Dispatchers.Main) { refreshProjects(); message = "Restored ${p.name}" }
    }

    /** The original ROM picked for "ROM + patch", waiting for the patch. */
    private var pendingOriginal: ByteArray? = null

    fun pickedOriginalForPatch(uri: Uri) = work("Couldn't read that ROM") {
        pendingOriginal = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
        withContext(Dispatchers.Main) { message = "Now pick the .bps or .ips patch" }
    }

    fun importPatch(uri: Uri) = work("Couldn't apply that patch") {
        val original = pendingOriginal ?: error("Pick the original ROM first")
        val patch = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
        val name = displayName(uri).substringBeforeLast('.').ifBlank { "Patched hack" }
        val p = Project.fromPatch(root, name, original, patch)
        pendingOriginal = null
        withContext(Dispatchers.Main) { refreshProjects(); message = "Created ${p.name} from the patch" }
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
