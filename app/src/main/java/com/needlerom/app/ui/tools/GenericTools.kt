@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.FileActions
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.InfoRow
import com.needlerom.app.ui.Mono
import com.needlerom.app.ui.SectionCard
import com.needlerom.app.ui.ToolScaffold
import com.needlerom.core.Bytes
import com.needlerom.core.HackSession
import com.needlerom.core.SystemDetector
import java.util.zip.CRC32

@Composable
fun RomInfoTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val rev = vm.revision
    // Re-detect so checksum status reflects current edits.
    val live = remember(s, rev) { SystemDetector.detect(s.rom) }
    val crcOrig = remember(s) { CRC32().apply { update(s.rom.original) }.value }
    val crcNow = remember(s, rev) { CRC32().apply { update(s.rom.data) }.value }

    ToolScaffold("ROM info", s.rom.fileName, onBack) {
        SectionCard("Header") {
            InfoRow("System", live.platform.label)
            live.details.forEach { (k, v) -> InfoRow(k, v) }
        }
        SectionCard("Hashes") {
            InfoRow("Original CRC32", Bytes.hex(crcOrig.toInt(), 8))
            InfoRow("Current CRC32", Bytes.hex(crcNow.toInt(), 8))
            InfoRow("Modified", if (s.rom.isModified) "Yes" else "No")
        }
        SectionCard("Profile") {
            InfoRow("Mode", s.mode.name.lowercase().replaceFirstChar { it.uppercase() })
            InfoRow("Profile", s.profile?.name ?: "—")
            InfoRow("Detected game", s.detectedProfile?.name ?: "Unknown")
            Hint("Checksums that say \"Invalid\" or \"Needs fixing\" are repaired automatically on export while \"Fix checksums\" is ticked.")
        }
    }
}

private const val HEX_ROWS = 16
private const val HEX_COLS = 8

@Composable
fun HexEditorTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    val rev = vm.revision
    var base by remember { mutableIntStateOf(0) }
    var gotoText by remember { mutableStateOf("0") }
    var writeOffset by remember { mutableStateOf("") }
    var writeBytes by remember { mutableStateOf("") }
    var findText by remember { mutableStateOf("") }
    val page = HEX_ROWS * HEX_COLS
    val size = s.rom.size

    ToolScaffold("Hex editor", "Size 0x${Bytes.hex(size, 6)}", onBack) {
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(gotoText, { gotoText = it }, Modifier.weight(1f), label = { Text("Go to offset (hex)") }, singleLine = true)
                Button(onClick = {
                    Bytes.parseOffset(gotoText)?.let { base = (it - it % HEX_COLS).coerceIn(0, maxOf(0, size - 1)) }
                        ?: vm.toast("Enter a hex offset like 1E25")
                }) { Text("Go") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { base = (base - page).coerceAtLeast(0) }) { Text("◀ Prev") }
                OutlinedButton(onClick = { if (base + page < size) base += page }) { Text("Next ▶") }
            }
            // Read `rev` so edits show immediately.
            val hexDump = remember(base, rev) { dump(s, base) }
            Row(Modifier.horizontalScroll(rememberScrollState())) { Mono(hexDump) }
        }
        SectionCard("Write bytes") {
            OutlinedTextField(writeOffset, { writeOffset = it }, Modifier.fillMaxWidth(), label = { Text("Offset (hex)") }, singleLine = true)
            OutlinedTextField(writeBytes, { writeBytes = it }, Modifier.fillMaxWidth(), label = { Text("Bytes (hex, e.g. A9 09)") }, singleLine = true)
            FilledTonalButton(onClick = {
                val off = Bytes.parseOffset(writeOffset)
                val bytes = Bytes.parseHex(writeBytes)
                when {
                    off == null -> vm.toast("Invalid offset")
                    bytes == null -> vm.toast("Invalid hex bytes")
                    !s.rom.inBounds(off, bytes.size) -> vm.toast("That would write past the end of the ROM")
                    else -> {
                        s.rom.write(off, bytes, "Hex edit at 0x${Bytes.hex(off, 6)}")
                        base = off - off % HEX_COLS
                        vm.edited("Wrote ${bytes.size} byte(s)")
                    }
                }
            }) { Text("Write") }
        }
        SectionCard("Find bytes") {
            OutlinedTextField(findText, { findText = it }, Modifier.fillMaxWidth(), label = { Text("Hex pattern") }, singleLine = true)
            FilledTonalButton(onClick = {
                val pat = Bytes.parseHex(findText)
                if (pat == null) { vm.toast("Invalid hex pattern"); return@FilledTonalButton }
                val at = s.rom.indexOf(pat, base + 1).takeIf { it >= 0 } ?: s.rom.indexOf(pat, 0)
                if (at < 0) vm.toast("Not found") else { base = at - at % HEX_COLS; gotoText = Bytes.hex(at, 6) }
            }) { Text("Find next") }
        }
    }
}

private fun dump(s: HackSession, base: Int): String = buildString {
    val rom = s.rom
    for (row in 0 until HEX_ROWS) {
        val o = base + row * HEX_COLS
        if (o >= rom.size) break
        append(Bytes.hex(o, 6)).append("  ")
        val ascii = StringBuilder()
        for (c in 0 until HEX_COLS) {
            val p = o + c
            if (p < rom.size) {
                val b = rom.u8(p)
                val changed = b != (rom.original.getOrNull(p)?.toInt()?.and(0xFF) ?: -1)
                append(Bytes.hex(b)).append(if (changed) '*' else ' ')
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            } else append("   ")
        }
        append(' ').append(ascii).append('\n')
    }
}.trimEnd()

@Composable
fun ApplyPatchTool(vm: AppViewModel, s: HackSession, actions: FileActions, onBack: () -> Unit) {
    ToolScaffold("Apply patch", s.rom.fileName, onBack) {
        SectionCard("Stack an existing hack") {
            Hint("Apply an .ips or .bps patch (a translation, bug fix or someone else's hack) to the ROM you're editing. Your later edits go on top, and exported patches include everything.")
            Hint("BPS patches check that they match your ROM and will warn you if they were made for a different version.")
            Button(onClick = actions.openPatch) { Text("Choose patch file") }
        }
        val applied = remember(s, vm.revision) { s.rom.changes.filter { it.label.startsWith("Applied ") } }
        if (applied.isNotEmpty()) {
            SectionCard("Applied") { applied.forEach { Text(it.label.removePrefix("Applied ")) } }
        }
    }
}
