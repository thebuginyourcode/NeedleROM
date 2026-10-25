#!/usr/bin/env python3
"""
Generates engine/src/main/kotlin/com/needlerom/engine/pokered/KnownVersions.kt.

Inputs (never committed): ROMs + .sym files built from the pret/pokered and
pret/pokeyellow disassemblies, plus a second Red/Blue build with different
STARTER1-3 constants so every starter reference can be found by diffing.

usage: gen_versions.py ROMS_DIR STARTER_DIFF_DIR OUT.kt [POKERED_SRC]
"""
import re
import sys

ROMS, DIFF, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
SRC = sys.argv[4] if len(sys.argv) > 4 else None

SYMBOLS = [
    # data tables (ROM)
    "MapHeaderPointers", "MapHeaderBanks", "Tilesets", "SpriteSheetPointerTable",
    "ItemNames", "ItemPrices", "MonsterNames", "BaseStats", "MewBaseStats",
    "TrainerDataPointers", "TrainerNames", "WildDataPointers", "EvosMovesPointerTable",
    "MoveNames", "Moves", "PokedexOrder", "TypeNames", "Overworld_Coll", "MapSongBanks", "NewGameWarp",
    "ToggleableObjectStates", "MarkTownVisitedAndLoadToggleableObjects", "wObtainedBadges",
    # home routines used by the script engine
    "TextScriptEnd", "Bankswitch", "PrintText", "GiveItem", "GivePokemon", "YesNoChoice",
    "IsItemInBag", "Predef", "PlaySound", "PlayCry", "GetCryData", "PlayMusic", "WaitForSoundToFinish", "DelayFrames",
    "AddAmountSoldToMoney", "RemoveItemByID", "UpdateSprites", "LoadGBPal",
    "GBPalWhiteOutWithDelay3", "TextBoxBorder", "DisplayTextID", "PickUpItemText", "TalkToTrainer",
    # RAM
    "wCurMap", "wYCoord", "wXCoord", "wEventFlags", "wCurOpponent", "wTrainerNo",
    "wCurEnemyLevel", "hLoadedROMBank", "hTextID", "wPlayerMoney", "wStringBuffer",
    "wPartyCount", "wNumBagItems", "SaveEndBattleTextPointers", "wCurPartySpecies", "wStatusFlags3", "wStatusFlags4",
    "wStatusFlags5", "wStatusFlags6", "wStatusFlags7", "wCurrentMenuItem", "hMoney",
    "wPlayerName", "wRivalName", "wMissableObjectFlags", "wNamedObjectIndex",
    "wItemQuantity", "wPartyMon1Species", "wJoyIgnore", "wDoNotWaitForButtonPressAfterDisplayingText",
    "wOverworldMap", "wNumSprites", "wMapSpriteData", "wTextBoxID", "wDestinationMap", "wLastMap", "wWarpedFromWhichMap", "wPlayerDirection",
]


def load_sym(path):
    syms = {}
    for line in open(path):
        line = line.strip()
        if not line or line.startswith(";") or ":" not in line.split()[0]:
            continue
        addr, name = line.split()[:2]
        bank, a = addr.split(":")
        syms[name] = (int(bank, 16), int(a, 16))
    return syms


def offset(bank, addr):
    if addr < 0x4000:
        return addr
    if addr < 0x8000:
        return bank * 0x4000 + addr - 0x4000
    return addr  # RAM/HRAM: keep the CPU address


def trainer_party_counts(rom, syms):
    """Parties per trainer class, parsed between each class's label and the next symbol."""
    if "TrainerDataPointers" not in syms:
        return []
    bank, table = syms["TrainerDataPointers"]
    addrs = sorted(a for (b, a) in syms.values() if b == bank)
    counts = []
    for i in range(47):
        o = offset(bank, table) + i * 2
        ptr = rom[o] | (rom[o + 1] << 8)
        end_ptr = next((a for a in addrs if a > ptr), 0x8000)
        p, end, n = offset(bank, ptr), offset(bank, end_ptr), 0
        while p < end:
            if rom[p] == 0xFF:
                p += 1
                while rom[p] != 0:
                    p += 2
            else:
                p += 1
                while rom[p] != 0:
                    p += 1
            p += 1
            n += 1
        counts.append(n)
    return counts


def text_table_sizes(rom, syms):
    """Entries in each map's text pointer table, measured to the next label in the sym file."""
    hdr = offset(*syms["MapHeaderPointers"])
    banks = offset(*syms["MapHeaderBanks"])
    by_bank = {}
    for (b, a) in syms.values():
        by_bank.setdefault(b, []).append(a)
    for b in by_bank:
        by_bank[b].sort()
    sizes = []
    for m in range(0xF8):
        ptr = rom[hdr + m * 2] | (rom[hdr + m * 2 + 1] << 8)
        bank = rom[banks + m]
        if not (0x4000 <= ptr < 0x8000) or bank == 0:
            sizes.append(0)
            continue
        h = offset(bank, ptr)
        tp = rom[h + 5] | (rom[h + 6] << 8)
        nxt = next((a for a in by_bank.get(bank, []) if a > tp), None)
        n = (nxt - tp) // 2 if nxt else 0
        sizes.append(n if 0 < n <= 64 else 0)
    return sizes


def pretty(name):
    special = {"REDS": "Red's", "BLUES": "Blue's", "OAKS": "Oak's", "BILLS": "Bill's", "SS": "S.S.",
               "POKEMON": "Pokémon", "POKECENTER": "Pokécenter", "MT": "Mt.", "SILPH": "Silph",
               "CO": "Co.", "HQ": "HQ", "TV": "TV", "PC": "PC"}
    words = []
    for w in name.split("_"):
        if w in special:
            words.append(special[w])
        elif re.fullmatch(r"B?\d+F|\d+", w):
            words.append(w)
        else:
            words.append(w.capitalize())
    return " ".join(words)


def constant_names(path, prefix_strip=""):
    names = {}
    v = 0
    for line in open(path):
        line = line.split(";")[0].strip()
        m = re.match(r"const_def\s*(\S*)", line)
        if m:
            v = int(m.group(1).replace("$", "0x"), 0) if m.group(1) else 0
            continue
        m = re.match(r"const_next\s+(\S+)", line)
        if m:
            v = int(m.group(1).replace("$", "0x"), 0)
            continue
        if line.startswith("const_skip"):
            parts = line.split()
            v += int(parts[1]) if len(parts) > 1 else 1
            continue
        m = re.match(r"(?:map_const|const)\s+(\w+)", line)
        if m:
            n = m.group(1)
            if n.startswith(prefix_strip):
                n = n[len(prefix_strip):]
            names[v] = n
            v += 1
    return names


def starter_refs(original, changed):
    a = open(original, "rb").read()
    b = open(changed, "rb").read()
    slot = {0xB0: 1, 0xB1: 2, 0x99: 3}  # Charmander, Squirtle, Bulbasaur
    refs = []
    for i in range(len(a)):
        if a[i] != b[i] and i not in (0x14E, 0x14F):
            refs.append((i, slot[a[i]]))
    return refs


versions = [
    ("RED", "pokered", "Pokémon Red", True),
    ("BLUE", "pokeblue", "Pokémon Blue", True),
    ("YELLOW", "pokeyellow", "Pokémon Yellow", False),
]

out = []
out.append("// Generated by tools/gen_versions.py from the pret disassemblies. Do not edit.")
out.append("package com.needlerom.engine.pokered")
out.append("")
out.append("internal object KnownVersions {")
for key, base, title, has_starters in versions:
    rom = open(f"{ROMS}/{base}.gbc", "rb").read()
    syms = load_sym(f"{ROMS}/{base}.sym")
    checksum = (rom[0x14E] << 8) | rom[0x14F]
    entries = []
    for s in SYMBOLS:
        if s in syms:
            entries.append(f'"{s}" to 0x{offset(*syms[s]):X}')
    # The first 8 bytes of each home routine, to confirm a hack hasn't moved them.
    sigs = []
    for s in SYMBOLS:
        if s in syms and syms[s][1] < 0x4000 and syms[s][0] == 0:
            o = syms[s][1]
            sigs.append(f'"{s}" to "{rom[o:o + 8].hex()}"')
    starters = starter_refs(f"{ROMS}/{base}.gbc", f"{DIFF}/{base}.gbc") if has_starters else []
    out.append(f"    val {key} = VersionInfo(")
    out.append(f'        name = "{title}",')
    out.append(f"        globalChecksum = 0x{checksum:04X},")
    out.append(f"        isYellow = {'true' if key == 'YELLOW' else 'false'},")
    out.append("        symbols = mapOf(")
    for e in entries:
        out.append(f"            {e},")
    out.append("        ),")
    out.append("        homeSignatures = mapOf(")
    for e in sigs:
        out.append(f"            {e},")
    out.append("        ),")
    counts = trainer_party_counts(rom, syms)
    tsizes = text_table_sizes(rom, syms)
    out.append("        textTableSizes = intArrayOf(" + ", ".join(map(str, tsizes)) + "),")
    out.append("        trainerPartyCounts = listOf(" + ", ".join(map(str, counts)) + "),")
    out.append("        starterRefs = listOf(")
    for i in range(0, len(starters), 6):
        chunk = ", ".join(f"0x{o:X} to {s}" for o, s in starters[i:i + 6])
        out.append(f"            {chunk},")
    out.append("        ),")
    out.append("    )")
    out.append("")
out.append("    val ALL = listOf(RED, BLUE, YELLOW)")
out.append("}")
if SRC:
    maps = constant_names(f"{SRC}/constants/map_constants.asm")
    sprites = constant_names(f"{SRC}/constants/sprite_constants.asm", "SPRITE_")
    tilesets = constant_names(f"{SRC}/constants/tileset_constants.asm")
    unused_maps = [k for k, v in constant_names(f"{SRC}/constants/map_constants.asm").items() if v.startswith("UNUSED") and k >= 0x25]
    # Yellow reuses some of Red's unused slots (e.g. the Beach House); keep ones free in both.
    ysrc = SRC.replace("pokered", "pokeyellow")
    try:
        yellow_unused = {k for k, v in constant_names(f"{ysrc}/constants/map_constants.asm").items() if v.startswith("UNUSED")}
        unused_maps = [k for k in unused_maps if k in yellow_unused]
    except FileNotFoundError:
        pass
    out.append("")
    out.append("object Names {")
    out.append("    val MAPS: Map<Int, String> = mapOf(")
    for k in sorted(maps):
        if maps[k].startswith("UNUSED"):
            continue
        out.append(f'        0x{k:02X} to "{pretty(maps[k])}",')
    out.append("    )")
    out.append("    val TILESETS: Map<Int, String> = mapOf(")
    for k in sorted(tilesets):
        out.append(f'        {k} to "{pretty(tilesets[k])}",')
    out.append("    )")
    out.append("    /** Indoor map ids the games never use; new maps take these. */")
    out.append("    val UNUSED_MAPS: List<Int> = listOf(" + ", ".join(f"0x{k:02X}" for k in sorted(unused_maps)) + ")")
    out.append("    val SPRITES: Map<Int, String> = mapOf(")
    for k in sorted(sprites):
        if k == 0:
            continue
        out.append(f'        0x{k:02X} to "{pretty(sprites[k])}",')
    out.append("    )")
    out.append("}")
open(OUT, "w").write("\n".join(out) + "\n")
print("wrote", OUT)
