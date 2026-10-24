package com.needlerom.app.ui.tools

import androidx.compose.runtime.Composable
import com.needlerom.app.AppViewModel
import com.needlerom.app.ui.FileActions
import com.needlerom.app.ui.world.WorldBuilderTool
import com.needlerom.core.TextTable
import com.needlerom.core.Tool

@Composable
fun ToolHost(vm: AppViewModel, tool: Tool, actions: FileActions) {
    val s = vm.session ?: return
    val back = { vm.back(); Unit }
    when (tool) {
        Tool.WORLD_BUILDER -> WorldBuilderTool(vm, s, back)
        Tool.POKEMON_STATS -> PokemonStatsTool(vm, s, back)
        Tool.POKEMON_NAMES -> PokemonNamesTool(vm, s, back)
        Tool.POKEMON_BULK -> PokemonBulkTool(vm, s, back)
        Tool.GAME_TEXT -> TextTool(vm, s, s.textTable ?: TextTable.ASCII, tool.title, back)
        Tool.TEXT_ASCII -> TextTool(vm, s, TextTable.ASCII, tool.title, back)
        Tool.SMB_LIVES -> SmbLivesTool(vm, s, back)
        Tool.SMB_COLORS -> SmbColorsTool(vm, s, back)
        Tool.SMW_LIVES -> SmwLivesTool(vm, s, back)
        Tool.ROM_INFO -> RomInfoTool(vm, s, back)
        Tool.RELATIVE_SEARCH -> RelativeSearchTool(vm, s, back)
        Tool.HEX_EDITOR -> HexEditorTool(vm, s, back)
        Tool.APPLY_PATCH -> ApplyPatchTool(vm, s, actions, back)
    }
}
