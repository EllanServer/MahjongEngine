# MahjongPaper

> This project was created entirely by AI.

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> `Mahjong Soul` / `雀魂` is referenced only to describe compatible rules and visual style. MahjongPaper is not affiliated with or endorsed by Mahjong Soul or its rightsholders. All product names and trademarks belong to their respective owners.

Chinese documentation: [README.zh-CN.md](./README.zh-CN.md)
Chinese gameplay and operations wiki: [docs/wiki.zh-CN.md](./docs/wiki.zh-CN.md)
Contributor notes: [CONTRIBUTING.md](./CONTRIBUTING.md)

`MahjongPaper` is a Paper plugin rewrite of `MahjongCraft` built around:

- Paper display entities
- CraftEngine-managed resource bundle, custom items, furniture hitboxes, and culling integration

## Current Scope

MahjongPaper requires a Java 21 or newer server runtime and build JDK.

The current branch already supports playable Riichi Mahjong, GB Mahjong, and Sichuan Mahjong flows on Paper/Folia:

- lobby-style tables that can persist across restart
- empty-table creation, fixed east/south/west/north seats, click-to-join, and click-to-ready
- automatic round start once 4 seats are filled and all seated players are ready
- bots for unattended seats, with bots treated as ready by default
- table-owner permissions plus a table control GUI for common lobby actions
- leaving before the round starts, or deferred leave after the current hand ends
- dealing, drawing, discarding, riichi, tsumo, ron, chii, pon, minkan, ankan, and kakan
- opening dice roll animation and live public table state displays
- Riichi scoring through `mahjong-utils`
- GB Mahjong rule evaluation through the bundled JNI bridge and vendored `GB-Mahjong` source
- spectator mode, private hand visibility, HUD overlays, and localized prompts
- Mahjong Soul-style rank persistence and per-mode leaderboards when database-backed ranking is enabled
- CraftEngine-backed seat/table interaction and CraftEngine bundle export
- round history persistence through H2 by default, with optional MariaDB/MySQL
- game room system: spatial containers for tables, with optional table creation restriction, enter/exit messages, and leave countdown for active matches

## Recommended Setup: Create a Game Room First

For normal server operation, create at least one game room before opening public tables. A game room is the allowed play area for Mahjong tables; when `gameRooms.restrictNewTables` is enabled, `/mahjong create` only works while the admin is standing inside a game room.

Quick tutorial:

1. Give yourself admin permission: `mahjongpaper.admin`.
2. Run `/mahjong room wand` to get the selection wand.
3. Left-click one corner of the room, then right-click the opposite corner.
4. Check the cyan particle outline and make sure the entire play area is inside the cuboid.
5. Run `/mahjong room create main-hall Main Hall`.
6. Stand inside that room and run `/mahjong create` to place a Mahjong table.
7. Use `/mahjong room list` and `/mahjong room info main-hall` to confirm the saved room.

If you only need a quick test room, stand at the room center and run `/mahjong room create quick-room`; without a wand selection, the plugin uses `gameRooms.defaultRadius` and `gameRooms.defaultHeight`.

For the longer operations guide, see [Game room system](./docs/wiki.zh-CN.md#棋牌室系统) in the Chinese wiki.

## Command Summary

- `/mahjong help`: show in-game command help
- `/mahjong room wand`: get the game-room selection wand
- `/mahjong room create <id> [name]`: create a game room from the current wand selection, or around your current position
- `/mahjong room list`: list saved game rooms
- `/mahjong room info <id>`: show a game room's world, bounds, size, and owner
- `/mahjong create`: create a new empty table at your location
- `/mahjong botmatch [MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN]`: create a 4-bot test match and spectate it
- `/mahjong mode <MAJSOUL_TONPUU|MAJSOUL_HANCHAN|GB|SICHUAN>`: apply a preset before the next start
- `/mahjong join <tableId>`: join a table as a player
- `/mahjong leave`: leave immediately before the hand starts, or queue a leave after the current hand
- `/mahjong list`: list active tables and their locations
- `/mahjong start`: toggle ready status for your seat
- `/mahjong spectate <tableId>`: spectate a table
- `/mahjong unspectate`: stop spectating
- `/mahjong table [tableId]`: open the table control panel
- `/mahjong table owner <player> [tableId]`: transfer table ownership to a seated player
- `/mahjong addbot` and `/mahjong removebot`: table owner/admin commands to manage bots before the round starts
- `/mahjong rule [key] [value]`: open the rule GUI, or let the table owner/admin change rules before the next start
- Riichi multi-ron mode: `/mahjong rule ronMode <HEAD_BUMP|MULTI_RON>`
- Riichi flow profile: `/mahjong rule riichiProfile <MAJSOUL|TOURNAMENT>`
- `/mahjong state`: show the current table summary
- `/mahjong riichi <index>`, `/mahjong tsumo`, `/mahjong ron`, `/mahjong pon`, `/mahjong minkan`, `/mahjong chii <tileA> <tileB>`, `/mahjong kan <tile>`, `/mahjong skip`, `/mahjong kyuushu`: round actions
- `/mahjong settlement`: reopen the latest settlement UI
- `/mahjong rank`: show your Mahjong Soul-style rank summary when ranking is enabled
- `/mahjong leaderboard [RIICHI|GB|SICHUAN]`: show the ranked leaderboard for one mode
- `/mahjong render`, `/mahjong clear`, `/mahjong inspect`: table render maintenance and diagnostics
- `/mahjong forceend [tableId]`: admin command to stop a running match
- `/mahjong deletetable [tableId]`: admin command to delete a table
- `/mahjong reload`: admin command to reload configuration and re-render active tables

Admin targeting details:

- `forceend` and `deletetable` accept an explicit `tableId`
- if omitted, they first try the table you are in or spectating
- if you are not attached to a table, they fall back to the nearest table within range

## Lobby Flow

- On production servers, create the game room first, then stand inside it before running `/mahjong create`.
- `/mahjong create` creates an empty table only; it does not auto-seat the creator
- the creator becomes the table owner and can manage rules, bots, refresh, start, and delete from `/mahjong table`
- the table owner/admin can transfer ownership with `/mahjong table owner <player> [tableId]`
- players join a fixed seat by interacting with that seat's floating label
- the same seat label is used to toggle ready before the match starts
- a round starts automatically only when all 4 seats are filled and all seated players are ready
- after a hand or a full match ends, players must ready again before the next start

## Rule References

- Riichi round flow rules: [docs/riichi-round-flow.md](./docs/riichi-round-flow.md)
- GB Mahjong rule source: [docs/gb-mahjong-rules.md](./docs/gb-mahjong-rules.md)
- Chinese gameplay and operations wiki: [docs/wiki.zh-CN.md](./docs/wiki.zh-CN.md)

## Gameplay Positioning

- Newly created tables default to Mahjong Soul-style Riichi hanchan rules.
- `MAJSOUL_TONPUU` and `MAJSOUL_HANCHAN` are the primary presets; both use three red fives, open tanyao, multi-ron, and the Mahjong Soul kan-dora reveal profile.
- `GB` remains available as an optional ruleset, backed by the vendored `GB-Mahjong` native bridge.
- `SICHUAN` is available as a suited-tile-only Bloody Battle ruleset.

## Game Modes Explained (with Examples)

The plugin ships three independent rulesets. Switch between them before a hand starts with `/mahjong mode <MODE>`. Each is explained below with concrete tile examples.

### Shared Round Flow

Every mode uses the same play loop:

1. Create a game room first on production servers; then stand inside it and run `/mahjong create` to create an empty table.
2. Click one of the east/south/west/north floating seat labels to sit down, or use `/mahjong join <tableId>`.
3. Fill empty seats with `/mahjong addbot` (bots count as ready).
4. `/mahjong start` toggles ready; the hand auto-starts once all 4 seats are filled and ready.
5. **Discard**: on your turn, click a tile in your hand to discard it.
6. **React to others**: after someone discards, a reaction window opens. Use commands to call:
   - `/mahjong pon`, `/mahjong chii <tileA> <tileB>` (from the player to your left), `/mahjong minkan`
   - `/mahjong ron` (win on a discard), `/mahjong skip` (pass)
7. **On your own turn**: `/mahjong tsumo` (self-draw win), `/mahjong kan <tile>` (closed or added kan).
8. A settlement screen pops up when the hand ends; reopen it with `/mahjong settlement`.

> Riichi-only commands: `/mahjong riichi <handIndex>` (declare riichi and discard that tile) and `/mahjong kyuushu` (nine-terminals abortive draw on the first turn). Both work on Riichi tables only.

### Mode 1: Mahjong Soul Riichi (default)

The primary mode, aimed at players familiar with Japanese / Mahjong Soul rules.

- **Match length**: `MAJSOUL_HANCHAN` is a half-game (East 1 through South 4, 8 hands); `MAJSOUL_TONPUU` is East-only (East 1 through East 4, 4 hands).
- **Points**: everyone starts at 25,000 with a 30,000 return target; final placement is by score.
- **Red fives**: one red 5 each in man/pin/sou (3 total), each worth one dora.
- **Winning floor**: 1 han minimum, and you must have a yaku (open tanyao is enabled, so melded hands can still score).
- **Riichi**: declare when closed and the wall still has draws left; you pay 1000 points and may only discard the tile you just drew.

**Example**: you self-draw a red 5-man after declaring riichi.

```
Menzen tsumo (1 han) + Riichi (1 han) + your hand's yaku + red-five dora (1 han) ...
```

Stacking more yaku pushes the han higher; yakuman hands (kokushi, daisangen, etc.) pay a fixed large score. The appeal is betting on riichi, reading discards, and combining yaku.

### Mode 2: GB Mahjong (Chinese Official)

China's official competition rules, the richest in fan types and the highest barrier. Switch with `/mahjong mode GB`.

- **Tiles**: man/pin/sou plus honor tiles (winds and dragons), with flower tiles (plum/orchid/bamboo/chrysanthemum) as bonus draws.
- **Winning floor**: **8 fan minimum** — hands worth fewer than 8 fan cannot win. This is the biggest difference from the other modes.
- **Scoring**: fan accumulate per the official fan table; more fan means more points.
- **Evaluation**: fan are judged by the bundled `GB-Mahjong` native library, following the official interpretation strictly.

**Example**: you build "Half Flush + All Triplets".

```
Half Flush (6 fan) + All Triplets (6 fan) = 12 fan >= 8 fan -> win allowed
```

With only "All Triplets (6 fan)" you fall short of 8 fan and **cannot win**, so you must keep building toward a bigger hand. GB pushes you toward complex, high-value hands — ideal for players who want depth.

### Mode 3: Sichuan Mahjong (Bloody Battle)

A fast, intense regional style. Switch with `/mahjong mode SICHUAN`.

- **Suited tiles only**: man/pin/sou, **no honor tiles and no flowers**.
- **Missing-a-suit (ding que)**: your winning hand must drop one entire suit (e.g. only man and pin, zero sou tiles).
- **Bloody battle (xue zhan dao di)**: a win does **not** end the hand immediately. Winners step out and the rest keep playing until the third player also wins, then the whole hand settles. So a single hand can produce up to 3 winners.
- **Any-fan win**: unlike GB's 8-fan floor, Sichuan lets you win once you complete a basic shape.
- **Fan cap**: capped at 5 fan (32x scoring).

**Main fan types**:

| Fan | Value | Notes |
| --- | --- | --- |
| Chicken Hand | 1 | basic shape |
| All Triplets | 1 | all triplets (pon/kan) |
| Full Flush | 2 | one suit only |
| Seven Pairs | 2 | seven pairs |
| Dragon Seven Pairs | 3 | seven pairs with 1 "root" (four identical) |
| Double Dragon Seven Pairs | 4 | seven pairs with 2 roots |
| Deluxe Dragon Seven Pairs | 5 | seven pairs with 3 roots (cap) |
| All 2-5-8 Pairs | +2 | every tile is 2/5/8 (stacks with all-triplets or seven pairs) |
| Root | +1 each | each kong adds one fan |
| Golden Hook | +1 | all-triplets waiting on a single pair tile |
| Under the Sea / Kong Bloom / Robbing the Kong, etc. | +1 | special winning methods |

**Example**: you drop sou, build a full-flush seven-pair hand, and one set is four pin tiles (1 root).

```
Full Flush (2 fan) + seven pairs upgraded to Dragon Seven Pairs (3 fan, includes 1 root) = 5 fan -> capped, scored at 32x
```

**Scoring**: fan map to a base of $2^{fan}$. On a self-draw the other three each pay a share; on a discard win only the discarder pays. That "one player pays for the deal-in" rule makes Sichuan especially tense.

## Build

```powershell
.\gradlew.bat build
```

The plugin currently declares CraftEngine as a required dependency in [plugin.yml](./src/main/resources/plugin.yml), so CraftEngine must be present at runtime.

## Configuration

The default config lives at [src/main/resources/config.yml](./src/main/resources/config.yml).

The current human-friendly layout is:

- `database.connection`: database type and MariaDB/MySQL connection target
- `database.credentials`: MariaDB/MySQL username and password
- `database.h2`: local embedded H2 settings
- `database.pool`: connection pool sizing
- `tables.persistence`: persistent table restore file
- `gameRooms`: game room system — spatial containers for tables, creation restriction, enter/exit messages, and leave countdown
- `ranking`: Mahjong Soul-style room presets and rank persistence
- `integrations.craftengine`: CraftEngine export and interaction preferences
- `debug`: debug logging switches

Notes:

- configuration is snapshotted on load
- `/mahjong reload` reloads config, rebuilds CraftEngine bridges, and re-renders active tables
- older flat keys are still accepted for backward compatibility

## CraftEngine

When CraftEngine is installed, MahjongPaper exports a bundle to:

- `plugins/CraftEngine/resources/mahjongpaper`

The exported bundle includes:

- `pack.yml`
- `configuration/items/mahjong_tiles.yml`
- `resourcepack/assets/mahjongcraft/...`

MahjongPaper currently uses CraftEngine for:

- custom mahjong tile items
- table and seat hitbox furniture
- tracked entity culling integration
- furniture interaction routing for table interaction

## Credits, Assets, and Upstream Projects

MahjongPaper is an independent rewrite/port and does not claim ownership of third-party code, artwork, recordings, names, or trademarks. The project-level MIT license applies only to material for which this repository's authors can grant that license; third-party components remain under their own licenses or terms.

### Code, rules, and platforms

| Project | Relationship to MahjongPaper | License / terms |
| --- | --- | --- |
| [MahjongCraft](https://github.com/doublemoon1119/MahjongCraft), by `doublemoon1119` | Original Fabric mod; primary gameplay/architecture inspiration and source of reused tile textures and base item models | [MIT](https://github.com/doublemoon1119/MahjongCraft/blob/main/LICENSE) |
| [MahjongPlay](https://github.com/7yunluo/MahjongPlay), by `7yunluo` | Paper-plugin implementation reference | [MIT](https://github.com/7yunluo/MahjongPlay/blob/main/LICENSE) |
| [mahjong-utils](https://github.com/ssttkkl/mahjong-utils), by `ssttkkl` | Direct runtime library for Riichi hand evaluation and scoring | [MIT](https://github.com/ssttkkl/mahjong-utils/blob/main/LICENSE) |
| [GB-Mahjong](https://github.com/zheng-fan/GB-Mahjong), by Zheng Fan | Vendored C++ source compiled into the bundled GB rules JNI library | [MIT; local copy](./native/gbmahjong/vendor/GB-Mahjong/LICENSE) |
| [Paper](https://github.com/PaperMC/Paper) / [Folia](https://github.com/PaperMC/Folia) | Supported server platforms and APIs; supplied separately by the server operator | Their respective upstream licenses |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | Required external runtime plugin for custom items, furniture, interaction, culling, and resource delivery; not bundled | [GPL-3.0](https://github.com/Xiao-MoMi/craft-engine/blob/main/LICENSE) |
| [Adventure](https://github.com/PaperMC/adventure) | Text/component API supplied by Paper | [Apache-2.0](https://github.com/PaperMC/adventure/blob/main/5/license.txt) |

### Resource-pack artwork and sound

The assets in [resourcepack](./resourcepack) are packaged into the exported CraftEngine bundle. The authoritative per-file/source notice is [resourcepack/ATTRIBUTION.md](./resourcepack/ATTRIBUTION.md); keep that file with every redistributed bundle.

- Mahjong tile textures and base item models are reused from [MahjongCraft](https://github.com/doublemoon1119/MahjongCraft) under MIT. The related tile-art lineage also credits [mahjong_graphic](https://github.com/lietxia/mahjong_graphic) by `lietxia`, released under the M+ Fonts License; its documentation identifies partial lineage from [I.Mahjong](https://github.com/SyaoranHinata/I.Mahjong) and GL-MahjongTile.
- Tile/table sound recordings are adapted from Freesound uploads `329098`, `329099`, and `329100` by **Macif**, `197868` by **Millavsb**, and `745024` by **poenia**, each marked [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Direct source links and file mappings are in the attribution file.
- Riichi action voices use `chii_01.wav`, `pon_01.wav`, `kan_01.wav`, `ri-chi_01.wav`, and `ron_01.wav` from [Amitaro's Voice Material Studio](https://amitaro.net/voice/game_01/) under the studio's [custom terms](https://amitaro.net/voice/voice_rule/), not under MIT or CC0.

Required voice credit:

> Voice: Amitaro's Voice Material Studio (<https://amitaro.net/>)<br>
> 音声素材：あみたろの声素材工房 (<https://amitaro.net/>)

Redistribution of the Amitaro voice files is permitted only as part of a work such as this plugin/resource bundle, subject to the current Amitaro terms. Redistributors must preserve the credit and terms link/readme, must not offer the recordings as a standalone voice/sound pack, and should complete the required post-release usage report within the period stated by those terms. A Japanese report draft is maintained at [docs/amitaro-usage-report.ja.md](./docs/amitaro-usage-report.ja.md).

### Runtime libraries

The build declares the following direct runtime libraries; they are separate works and remain under their own licenses: [MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) (LGPL-2.1), [MySQL Connector/J](https://github.com/mysql/mysql-connector-j) (GPL-2.0 with Oracle's additional permissions and Universal FOSS Exception), [H2](https://github.com/h2database/h2database) (MPL-2.0 or EPL-1.0), [HikariCP](https://github.com/brettwooldridge/HikariCP) (Apache-2.0), [Caffeine](https://github.com/ben-manes/caffeine) (Apache-2.0), [Kotlin](https://github.com/JetBrains/kotlin) (Apache-2.0), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Apache-2.0). Paper's plugin loader resolves the Maven libraries listed in [MahjongPaperLoader.java](./src/main/java/top/ellan/mahjong/bootstrap/MahjongPaperLoader.java); [build.gradle.kts](./build.gradle.kts) is the authoritative direct dependency/version list.

Platform-native releases also include the compiled GB-Mahjong JNI bridge. A Windows release may include `libwinpthread-1.dll` from [mingw-w64 winpthreads](https://github.com/mingw-w64/mingw-w64/tree/master/mingw-w64-libraries/winpthreads), under its upstream MIT/BSD-style notice. GCC runtime portions, when linked by the native build, are covered by GPL-3.0 plus the [GCC Runtime Library Exception 3.1](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html).

### Trademark and affiliation notice

Minecraft is a trademark of Microsoft. MahjongPaper is independently developed and is not an official Minecraft product, nor approved by or associated with Mojang or Microsoft. References to Mahjong Soul / 雀魂 describe rules or style only and do not imply affiliation, sponsorship, or endorsement. The names and marks of all upstream projects remain the property of their respective owners.

## Community

- Contributing guide: [.github/CONTRIBUTING.md](./.github/CONTRIBUTING.md)
- Code of conduct: [.github/CODE_OF_CONDUCT.md](./.github/CODE_OF_CONDUCT.md)
- Security policy: [.github/SECURITY.md](./.github/SECURITY.md)
- Support: [.github/SUPPORT.md](./.github/SUPPORT.md)

## License

Original MahjongPaper code and project-created assets are licensed under the [MIT License](./LICENSE), except where a file or the notices above identify different terms. Third-party notices and licenses must be retained when redistributing their material. This summary is provided for attribution and project hygiene; it is not legal advice and does not replace the governing license texts or service terms.
