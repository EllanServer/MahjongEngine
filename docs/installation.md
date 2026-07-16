# Installation and Upgrade Guide

Languages: **English** · [简体中文](./installation.zh-CN.md) · [日本語](./ja-JP/installation.md)

This is the server-owner guide for a production MahjongPaper installation. Gameplay rules are documented separately: [Riichi](./riichi-round-flow.md), [GB / MCR](./gb-mahjong-rules.md), and [Sichuan](./sichuan-rules.md).

## Requirements

- **Java 21 or newer. Java 17 is not supported.** Both Gradle builds and the server runtime use Java 21 bytecode.
- Paper or Folia. The development baseline is Paper 1.20.1 and the plugin declares `api-version: 1.20`.
- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) **26.7 or newer**. It is a required server plugin and must load before MahjongPaper.
- InvSync 2.x is optional. It can store player rank profiles; current automatic rank/stat updates are limited to completed four-human Riichi matches. It does not replace table persistence or history storage.

AntiGriefLib and the Sparrow libraries are runtime libraries, not additional server plugins. Depending on the library, MahjongPaper either embeds and relocates it in the release or asks Paper's library loader to resolve it. The release also contains the GB native bridge; server owners do not build JNI themselves.

## Install

1. Stop the server.
2. Install CraftEngine 26.7+ in `plugins/`.
3. Copy the `*-universal.jar` MahjongPaper release into `plugins/`. Do not install `-dev.jar`, `-sources.jar`, or a single-platform build unless the release notes explicitly require one.
4. Optionally install a compatible InvSync 2.x build.
5. Start the server with Java 21 and wait for dependency resolution and resource export to finish.
6. Check that `plugins/MahjongPaper/config.yml` was generated and that no MahjongPaper or CraftEngine compatibility error appears in the log.

The first start needs network access for Paper's Maven library resolver. MahjongPaper exports its CraftEngine bundle to:

```text
plugins/CraftEngine/resources/mahjongpaper
```

The bundle contains the tile items, table/seat furniture definitions, hitboxes, and resource-pack assets. If seats or tiles cannot be interacted with, verify this directory and CraftEngine's resource-pack delivery before debugging the table logic.

## First Game Room and Table

Production servers should create a game room before placing public tables. The default configuration enables game rooms and restricts new tables to their bounds.

```text
/mahjong room wand
# Left-click one corner, right-click the opposite corner.
/mahjong room create main-hall Main Hall
/mahjong room info main-hall
# Stand inside the room.
/mahjong create
```

For a quick test, stand at the room center and run `/mahjong room create quick-room`; without a wand selection the configured default radius and height are used.

After creating a table, players click a fixed East/South/West/North seat, the owner adds bots if needed, and every occupied human seat uses `/mahjong start` to ready. Use `/mahjong mode <MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN>` before the next match.

## Storage and InvSync

Local H2 is enabled by default; MariaDB/MySQL can be configured under `database`. When SQL is enabled and healthy, it owns:

- persistent table, owner, mode, and rule configuration;
- `round_history` and `rank_history`;
- leaderboard projections.

InvSync, when present, stores player rank profiles. With the default fallback policy, an absent, disabled, incompatible, or runtime-failing InvSync switches that ownership to MahjongPaper's SQL backend only if SQL is enabled and healthy. If fallback is disabled, or SQL cannot start, the rank backend becomes unavailable instead of silently writing elsewhere. The default `database.failOnError: false` lets the plugin continue without persistence after a database startup failure; production servers that require durable tables/history/ranks should set it to `true` and monitor startup.

Current match settlement updates ranks and personal statistics only for completed Riichi matches with four human players. `GB` and `SICHUAN` leaderboard selectors may read existing or migrated rows but those matches do not populate them. InvSync's public addon API writes during `onSave`; a crash between an in-game rank update and the next InvSync save can therefore lose that latest update. Enable InvSync auto-save and world-save. In InvSync mode, `/mahjong rank` is limited to online players already synchronized into the local cache.

See [InvSync player-rank integration](./invsync-player-rank.zh-CN.md) for migration and failure-mode details.

## Language

Player-facing messages follow the player's Minecraft locale and include English, Simplified Chinese, Traditional Chinese variants, and Japanese (`ja-JP`). Configuration comments are currently available in English, Simplified Chinese, and Traditional Chinese; Japanese config comments are not yet provided.

## Reload or Restart?

`/mahjong reload` is suitable for ordinary configuration changes and re-rendering. Perform a full stop/start after changing:

- the MahjongPaper, CraftEngine, or InvSync JAR;
- the Java or Paper/Folia version;
- the InvSync ownership/fallback strategy;
- dependency or resource-bundle delivery.

Do not replace plugin JARs while a server is running. An in-progress hand, wall, or reaction window is not restored after restart; persistent empty/lobby tables and their rule configuration are restored.

## Upgrade Checklist

1. Stop the server cleanly.
2. Back up `plugins/MahjongPaper/`, the SQL database, and `plugins/CraftEngine/resources/mahjongpaper` if it contains local overrides.
3. Replace only the release JAR and update required dependencies.
4. Start with Java 21, inspect the log, and confirm the CraftEngine bundle was regenerated.
5. Run `/mahjong room list`, `/mahjong list`, and a four-bot smoke match for each enabled mode.

## Troubleshooting

- **Unsupported class version / Java error:** verify the actual server process uses Java 21+, not merely the shell used for Gradle.
- **MahjongPaper refuses to load:** verify CraftEngine is installed, enabled, at least 26.7, and API-compatible.
- **Seats or tiles do not respond:** verify CraftEngine loaded first, its bundle exists, and the resource pack reached the client.
- **`/mahjong create` is rejected:** stand inside a saved game room or adjust `gameRooms.restrictNewTables`.
- **Rank commands are unavailable:** verify `ranking.enabled`, SQL health, or InvSync synchronization/fallback state.
- **GB cannot start:** use the release JAR for a supported platform; do not use the development JAR or manually replace the bundled native library.

Configuration sources: [template](../src/main/config-template/config.template.yml), [Bukkit descriptor](../src/main/resources/plugin.yml), and [Paper descriptor](../src/main/resources/paper-plugin.yml).
