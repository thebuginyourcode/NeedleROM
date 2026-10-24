# NeedleROM

An Android app for making ROM hacks on your phone from a ROM you already own.

You can use it two ways:

- **Pick your game (optimized):** choose a supported Pokémon or Mario game to get editors built for it: base stats, names, dialogue, lives, colours and randomizers.
- **Auto-detect (no setup):** NeedleROM works out the system and game for you and gives you a universal toolkit for any ROM. It has fewer tools, but it works on anything. If it recognises a supported game, it offers to switch you to that game's optimized tools, and your edits are kept.

Everything runs on the device. Nothing is uploaded.

## Get the APK

Every push runs the **Build APK** GitHub Action, which uploads an artifact called `NeedleROM-apk`:

- `NeedleROM.apk`: the release build (minified, signed with the debug key so it installs directly)
- `NeedleROM-debug.apk`: the debug build

Pushing a tag like `v1.0.0` also attaches both APKs to a GitHub Release. You'll need to allow "Install unknown apps" on your phone.

To build it yourself, use JDK 17 and the Android SDK:

```sh
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew :core:test             # ROM engine unit tests (no Android SDK needed)
```

## Optimized games

| Game | System | Tools |
|---|---|---|
| Pokémon Red / Blue / Green, Yellow | GB | Base stats/types/catch rate/EXP editor, renaming, max catch rates, stat scaling, stat & type randomizer, dialogue editor |
| Pokémon Gold / Silver, Crystal | GBC | same as above (adds Steel/Dark, split Sp. Atk / Sp. Def) |
| Pokémon Ruby / Sapphire, Emerald, FireRed / LeafGreen | GBA | same as above, Gen III font and types |
| Super Mario Bros. | NES | Starting lives, Mario/Luigi/Fire Mario colours (with sprite preview and Mario↔Luigi swap), in-game text |
| Super Mario World | SNES | Starting lives (LoROM, verified before editing) |
| Super Mario 64 | N64 | ASCII text, automatic `.v64`/`.n64` → `.z64` conversion and N64 CRC repair |
| Super Mario Land 1/2, Super Mario Advance 1–4 | GB / GBA | Universal toolkit with that game's checksum handling |

Optimized tools don't rely on fixed offsets. They **find their data by signature** (for example, Bulbasaur's and Ivysaur's real base stats next to each other), so they work across revisions and on many existing hacks. If a tool can't find its data, it's switched off and shows why. It never writes to a guessed location.

## Universal toolkit (all modes, any ROM)

- **ROM info & checksums:** detects NES (iNES), SNES (LoROM/HiROM, copier headers), GB/GBC, GBA, N64 and DS headers.
- **Relative text search:** finds text in games whose character table you don't know, works out the alphabet, and lets you rewrite it.
- **ASCII text search** (N64 / GBA / DS).
- **Hex editor:** browse, find and write bytes; changed bytes are marked with `*`.
- **Apply IPS / BPS patches:** stack translations or other hacks under your own edits. BPS warns you if a patch was made for a different ROM.

## Export

- **Save ROM:** a playable hacked ROM.
- **BPS / IPS patch:** share your hack without sharing the ROM.
- **Fix checksums on export** (on by default) recalculates GB/GBC header and global checksums, the GBA complement, the SNES checksum, the DS header CRC and the N64 CRC1/CRC2 (CIC 6101/6102/6103/6105/6106).

## Project layout

- `core/`: the pure Kotlin ROM engine (detection, profiles, editors, text tables, checksums, IPS/BPS), with JVM unit tests.
- `app/`: the Jetpack Compose Android UI on top of `core`.

Only use ROMs dumped from games you own.
