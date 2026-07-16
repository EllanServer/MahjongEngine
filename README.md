# MahjongPaper

> This project was created entirely by AI.

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> `Mahjong Soul` / `雀魂` is referenced only to describe compatible rules and visual style. MahjongPaper is not affiliated with or endorsed by Mahjong Soul or its rightsholders. All product names and trademarks belong to their respective owners.

Languages: **English** · [简体中文](./README.zh-CN.md) · [日本語](./README.ja-JP.md)

Server installation: [English](./docs/installation.md) · [简体中文](./docs/installation.zh-CN.md) · [日本語](./docs/ja-JP/installation.md)

Chinese gameplay and operations wiki: [docs/wiki.zh-CN.md](./docs/wiki.zh-CN.md)

Contributor notes: [CONTRIBUTING.md](./CONTRIBUTING.md)

In-game messages support English, Simplified Chinese, Traditional Chinese (Taiwan, Hong Kong, and Macau), and Japanese (`ja-JP`). Server configuration comments remain available in English, Simplified Chinese, and Traditional Chinese.

`MahjongPaper` is a Paper plugin rewrite of `MahjongCraft` built around:

- Paper display entities
- CraftEngine-managed resource bundle, custom items, furniture hitboxes, and culling integration

## Current Scope

The current branch already supports playable Riichi Mahjong, GB Mahjong, and Sichuan Mahjong flows on Paper/Folia:

- lobby-style tables whose location, owner, variant, and rule configuration persist across restart; an in-progress hand, wall, or reaction window is not resumed
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
- seated players use the right-side **View River** action during an active hand; overhead mode exposes a centered **Return to seat** control, with Shift retained as a recovery fallback, and requires no client mod
- optional InvSync 2.x storage for player rank profiles; currently only completed four-human Riichi matches update ranks and personal statistics. With the default fallback policy, an absent, disabled, incompatible, or runtime-failing InvSync hands ownership to the configured self-hosted SQL backend only when that backend is enabled and healthy
- CraftEngine-backed seat/table interaction and CraftEngine bundle export
- H2 SQL is enabled by default (with optional MariaDB/MySQL) and remains responsible for persistent tables, round/rank history, and leaderboard projections
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
- Riichi dora-timing profile: `/mahjong rule riichiProfile <MAJSOUL|EARLY_KAN_DORA>` (`EARLY_KAN_DORA` is not a complete WRC ruleset; legacy saved `TOURNAMENT` values remain readable)
- `/mahjong state`: show the current table summary
- `/mahjong riichi <index>`, `/mahjong tsumo`, `/mahjong ron`, `/mahjong pon`, `/mahjong minkan`, `/mahjong chii <tileA> <tileB>`, `/mahjong kan <tile>`, `/mahjong skip`, `/mahjong kyuushu`: round actions
- `/mahjong settlement`: reopen the latest settlement UI
- `/mahjong rank`: show your Mahjong Soul-style rank summary when ranking is enabled
- `/mahjong leaderboard [RIICHI|GB|SICHUAN]`: select a mode's stored leaderboard; current match settlement automatically populates only `RIICHI`

- `/mahjong render`, `/mahjong clear`, `/mahjong inspect`: table render maintenance and diagnostics
- `/mahjong forceend [tableId]`: admin command to stop a running match
- `/mahjong deletetable [tableId]`: admin command to delete a table
- `/mahjong reload`: admin command to reload configuration and re-render active tables

Optional InvSync 2.x integration stores online player rank profiles. Persistent tables, `round_history`, `rank_history`, and the non-authoritative leaderboard projection remain in MahjongPaper's configured SQL database; the default configuration enables local H2. Missing, disabled, incompatible, or runtime-failing InvSync falls back only when fallback is enabled and the SQL backend is enabled and healthy; otherwise rank storage is unavailable. Current settlement updates ranks and personal statistics only for completed Riichi matches with four human players. `GB` and `SICHUAN` leaderboard selectors can read existing or migrated rows but are not automatically populated by those matches. The public addon API exposes writes through `onSave`, so a process crash after a rank update but before the next InvSync save can lose that update; enable InvSync auto-save and world-save. Without linking the paid API jar, `/mahjong rank` is limited to online players already synchronized into the local cache. No InvSync Maven coordinate or API classes are invented or bundled. See the [Chinese deployment and migration guide](./docs/invsync-player-rank.zh-CN.md).

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

## Installation and Rule Guides

Start with the [installation and upgrade guide](./docs/installation.md). The plugin ships three independent rule engines; table creation, seating, readying, discarding, and settlement are shared, but opening phases, legal calls, win priority, and scoring are not.

| Preset | Tiles and opening | Match length | Win threshold and calls | Full guide |
| --- | --- | --- | --- | --- |
| `MAJSOUL_TONPUU` / `MAJSOUL_HANCHAN` | 136 tiles, three red fives, 14-tile dead wall | Base 4/8 hands; dealer repeats and end-game extensions can add hands | Yaku required; default 1 yaku-han; chii/pon/kan; configurable multi-ron/head-bump | [Riichi rules](./docs/riichi-round-flow.md) |
| `GB` | 144 tiles including all eight flowers; optional flower exposure/replacement | Strict MCR preset: net 0, fixed 16 hands, no dealer repeat | At least 8 non-flower fan; chii/pon/kan; one winner | [GB / MCR rules](./docs/gb-mahjong-rules.md) |
| `SICHUAN` | 108 suited tiles; direct dingque, no exchange-three | T/TFMJ room preset: 8 hands, net score from 0 | Ping Hu 0 fan; three-fan cap; no chii; Bloody Battle with up to three winners | [Sichuan rules](./docs/sichuan-rules.md) |

Each full guide includes written real-world references, a video learning path, and a Bilibili backup tutorial. External tutorials demonstrate physical/common play and may use different room rules; the documented **MahjongPaper profile** is authoritative for plugin behavior.

Important profile boundaries:

- Riichi `MAJSOUL` follows the official ranked-rule baseline; `EARLY_KAN_DORA` changes only kan-dora timing and is not WRC.
- GB is governed by the EMA/WMO Green Book. The vendored `GB-Mahjong` backend is corrected when it conflicts with that authority.
- Sichuan follows T/TFMJ 01—2024: no default exchange-three or final-four compulsion, a three-fan/eight-unit cap, self-draw base bonus, call transfer, and cha-jiao. MIL/local profiles are kept separate.

Developer evidence: [cross-variant verification matrix](./docs/rule-verification-matrix.zh-CN.md). Operations: [Chinese wiki](./docs/wiki.zh-CN.md).

## Build

MahjongPaper now requires Java 21 or newer for both the build and the server runtime. Release artifacts use Java 21 bytecode while retaining `api-version: 1.20` and the Paper 1.20.1 API baseline.

```powershell
.\gradlew.bat build
```

The plugin declares CraftEngine as a required dependency in [plugin.yml](./src/main/resources/plugin.yml). Install CraftEngine 26.7 or newer; startup and `/mahjong reload` reject older or API-incompatible builds before constructing the direct bridge.

## Configuration

The generated default config is sourced from [src/main/config-template/config.template.yml](./src/main/config-template/config.template.yml).

The current human-friendly layout is:

- `database.connection`: database type and MariaDB/MySQL connection target
- `database.credentials`: MariaDB/MySQL username and password
- `database.h2`: local embedded H2 settings
- `database.pool`: connection pool sizing
- `tables.persistence`: SQL-backed persistent-table restore switch
- `gameRooms`: game room system — spatial containers for tables, creation restriction, enter/exit messages, and leave countdown
- `ranking`: Mahjong Soul-style room presets and rank persistence
- `integrations.craftengine`: CraftEngine export and interaction preferences
- `debug`: debug logging switches

Notes:

- configuration is snapshotted on load
- `/mahjong reload` reloads config, rebuilds CraftEngine bridges, and re-renders active tables
- older flat keys are still accepted for backward compatibility

## CraftEngine

MahjongPaper requires CraftEngine 26.7 or newer. The compatibility check compares numeric version segments (`26.10` is newer than `26.7`) and reflectively probes the required 26.7 public Bukkit/core API through CraftEngine's own class loader before registering the integration.

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
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | Required external runtime plugin (26.7+) for custom items, furniture, interaction, culling, and resource delivery; not bundled | [GPL-3.0](https://github.com/Xiao-MoMi/craft-engine/blob/main/LICENSE) |
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

The build declares the following direct runtime libraries; they are separate works and remain under their own licenses: [MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) (LGPL-2.1), [MySQL Connector/J](https://github.com/mysql/mysql-connector-j) (GPL-2.0 with Oracle's additional permissions and Universal FOSS Exception), [H2](https://github.com/h2database/h2database) (MPL-2.0 or EPL-1.0), [HikariCP](https://github.com/brettwooldridge/HikariCP) (Apache-2.0), [Caffeine](https://github.com/ben-manes/caffeine) (Apache-2.0), [Kotlin](https://github.com/JetBrains/kotlin) (Apache-2.0), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Apache-2.0).

The upstream synchronization also uses [AntiGriefLib](https://github.com/Xiao-MoMi/AntiGriefLib) (MIT) for protection-plugin decisions, [sparrow-heart](https://github.com/Xiao-MoMi/sparrow-heart) (MIT) for the client-side overhead camera entity, [sparrow-reflection](https://github.com/Xiao-MoMi/sparrow-reflection) (GPL-3.0) with [ASM](https://gitlab.ow2.org/asm/asm) (BSD-3-Clause) for version-tolerant packet access, and [sparrow-yaml](https://github.com/Xiao-MoMi/sparrow-yaml) (GPL-3.0) for configuration loading. Sparrow Reflection, its shaded [Mapping-IO](https://github.com/FabricMC/mapping-io) 0.8.0 code (Apache-2.0), and ASM are embedded and package-relocated in the release jar, as required by Sparrow Reflection's integration guidance; the other Maven libraries are resolved by Paper's plugin loader as listed in [MahjongPaperLoader.java](./src/main/java/top/ellan/mahjong/bootstrap/MahjongPaperLoader.java). [build.gradle.kts](./build.gradle.kts) is the authoritative direct dependency/version list.

`sparrow-metadata`, `sparrow-nbt`, and `sparrow-redis-message-broker` are intentionally not declared as unused dependencies. Global persistent tables, round/rank history, and the leaderboard projection remain transactional SQL, while optional InvSync 2.x stores synchronized player rank profiles through its public addon events; current automatic rank/stat updates are Riichi-only. The metadata/broker projects model a different Redis/MongoDB stack, and existing Bukkit persistent-data markers are not equivalent to the generic NBT document API.

Platform-native releases also include the compiled GB-Mahjong JNI bridge. A Windows release may include `libwinpthread-1.dll` from [mingw-w64 winpthreads](https://github.com/mingw-w64/mingw-w64/tree/master/mingw-w64-libraries/winpthreads), under its upstream MIT/BSD-style notice. GCC runtime portions, when linked by the native build, are covered by GPL-3.0 plus the [GCC Runtime Library Exception 3.1](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html).

### Trademark and affiliation notice

Minecraft is a trademark of Microsoft. MahjongPaper is independently developed and is not an official Minecraft product, nor approved by or associated with Mojang or Microsoft. References to Mahjong Soul / 雀魂 describe rules or style only and do not imply affiliation, sponsorship, or endorsement. The names and marks of all upstream projects remain the property of their respective owners.

## Community

- Contributing guide: [.github/CONTRIBUTING.md](./.github/CONTRIBUTING.md)
- Code of conduct: [.github/CODE_OF_CONDUCT.md](./.github/CODE_OF_CONDUCT.md)
- Security policy: [.github/SECURITY.md](./.github/SECURITY.md)
- Support: [.github/SUPPORT.md](./.github/SUPPORT.md)

## License

Original MahjongPaper source code and project-created assets remain licensed under the [MIT License](./LICENSE), except where a file or the notices above identify different terms. Because the runnable plugin links to GPL Sparrow YAML and embeds GPL Sparrow Reflection, the combined runnable software distribution is conveyed under GPL-3.0-only; binary redistributors must provide the corresponding source and build scripts required by that license. Third-party notices and licenses must be retained. See [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md); this summary is not legal advice and does not replace the governing texts.
