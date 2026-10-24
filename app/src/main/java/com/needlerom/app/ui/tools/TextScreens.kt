@file:OptIn(ExperimentalMaterial3Api::class)

package com.needlerom.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.EmptyState
import com.needlerom.app.ui.Hint
import com.needlerom.app.ui.Mono
import com.needlerom.app.ui.SectionCard
import com.needlerom.app.ui.ToolScaffold
import com.needlerom.core.Bytes
import com.needlerom.core.HackSession
import com.needlerom.core.RelativeMatch
import com.needlerom.core.RelativeSearch
import com.needlerom.core.TextHit
import com.needlerom.core.TextTable
import com.needlerom.core.TextTools

@Composable
fun TextTool(vm: AppViewModel, s: HackSession, table: TextTable, title: String, onBack: () -> Unit) {
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TextHit>?>(null) }
    var searched by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ToolScaffold(title, "Character table: ${table.name}", onBack) {
        SectionCard("1. Find text") {
            val err = error
            OutlinedTextField(
                find, { find = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Text in the game") },
                singleLine = true, isError = err != null,
                supportingText = if (err != null) { { Text(err) } } else null,
            )
            if (table.upperCaseOnly) Hint("This game's font is uppercase only.")
            Button(onClick = {
                val bad = table.unsupportedChars(find)
                error = when {
                    find.length < TextTools.MIN_QUERY -> "Type at least ${TextTools.MIN_QUERY} characters"
                    bad.isNotEmpty() -> "Not in this game's font: ${bad.joinToString(" ")}"
                    else -> null
                }
                if (error == null) { hits = TextTools.findAll(s.rom, table, find); searched = find }
            }) { Text("Search") }
        }

        hits?.let { list ->
            SectionCard("${list.size} match(es) for \"$searched\"") {
                if (list.isEmpty()) {
                    Hint("Not found. Check spelling and capitalisation exactly as it appears in game, or try a shorter phrase. Text may also be compressed in this game — try Relative search.")
                }
                list.take(40).forEach { h ->
                    Column {
                        Hint("0x${Bytes.hex(h.offset, 6)}")
                        Mono(h.context)
                    }
                }
                if (list.size > 40) Hint("…and ${list.size - 40} more")
            }
            if (list.isNotEmpty()) {
                SectionCard("2. Replace") {
                    OutlinedTextField(
                        replacement, { replacement = it.take(searched.length) }, Modifier.fillMaxWidth(),
                        label = { Text("New text") }, singleLine = true,
                        supportingText = { Text("${replacement.length}/${searched.length} · shorter text is padded with spaces") },
                    )
                    FilledTonalButton(onClick = {
                        when (val r = TextTools.replace(s.rom, table, searched, replacement, list.map { it.offset })) {
                            is TextTools.ReplaceResult.Done -> {
                                vm.edited("Replaced ${r.count} occurrence(s)")
                                hits = TextTools.findAll(s.rom, table, searched)
                            }
                            is TextTools.ReplaceResult.Error -> vm.toast(r.message)
                        }
                    }) { Text("Replace all ${list.size}") }
                }
            }
        }
    }
}

@Composable
fun RelativeSearchTool(vm: AppViewModel, s: HackSession, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<RelativeMatch>?>(null) }
    var editing by remember { mutableStateOf<RelativeMatch?>(null) }

    ToolScaffold("Relative text search", "Works without knowing the game's font", onBack) {
        SectionCard {
            Hint("Type a word you've seen in the game (letters only, e.g. MARIO, PRINCESS, WORLD). NeedleROM looks for the same letter spacing in any encoding, then works out the game's alphabet for you.")
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Word from the game") }, singleLine = true)
            Button(
                enabled = RelativeSearch.normalize(query).length >= RelativeSearch.MIN_LETTERS,
                onClick = { searched = RelativeSearch.normalize(query); matches = RelativeSearch.search(s.rom, searched) },
            ) { Text("Search") }
        }
        matches?.let { list ->
            SectionCard("${list.size} candidate(s)") {
                if (list.isEmpty()) Hint("Nothing found. Try a different word, or the game may compress its text.")
                list.take(60).forEach { m ->
                    Column(Modifier.fillMaxWidth().clickable { editing = m }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("0x${Bytes.hex(m.offset, 6)}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Hint("A = 0x${Bytes.hex(m.valueOfA)}")
                        }
                        Mono(m.preview)
                    }
                    HorizontalDivider()
                }
                Hint("Real text usually shows readable letters around it in the preview. Tap one to rewrite it.")
            }
        }
    }

    editing?.let { m ->
        var text by remember(m) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rewrite \"$searched\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Letters only, exactly ${searched.length} long, using this match's alphabet (A = 0x${Bytes.hex(m.valueOfA)}).")
                    OutlinedTextField(
                        text, { t -> text = RelativeSearch.normalize(t).take(searched.length) }, singleLine = true,
                        supportingText = { Text("${text.length}/${searched.length}") },
                    )
                }
            },
            confirmButton = {
                Button(enabled = text.length == searched.length, onClick = {
                    val err = RelativeSearch.replace(s.rom, m, searched.length, text)
                    if (err != null) vm.toast(err) else {
                        vm.edited("Wrote \"$text\" at 0x${Bytes.hex(m.offset, 6)}")
                        matches = RelativeSearch.search(s.rom, searched)
                        editing = null
                    }
                }) { Text("Write") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
