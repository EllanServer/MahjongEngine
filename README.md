# MahjongPaper 2.0

[简体中文](README.zh-CN.md) · [Installation details](docs/installation.zh-CN.md) · [Rule-pack authoring guide](docs/rule-pack-authoring.zh-CN.md) · [Architecture](docs/architecture.zh-CN.md)

[![Build 2.0](https://github.com/EllanStudio/MahjongEngine/actions/workflows/build.yml/badge.svg?branch=2.0)](https://github.com/EllanStudio/MahjongEngine/actions/workflows/build.yml)

MahjongPaper is a CraftEngine-based Mahjong core for Paper and Folia. The core owns tables, concurrency, authorization, persistence, and presentation. Versioned rule packs own the actual Mahjong rules.

The current runtime recognizes three rule identities:

- `riichi` — Japanese Mahjong;
- `mcr` — Mahjong Competition Rules;
- `sichuan` — Sichuan Mahjong.

> **Development status:** `2.0` is the default development branch, not a stable core release yet. Test it on a staging server first. This project is not affiliated with Mojang, Microsoft, Mahjong Soul, or any Mahjong organization.

## 1. How it works

```text
Player clicks CraftEngine furniture or runs a command
                         │
                         ▼
                  bounded TableActor
                  ├─ pinned RulePackProvider
                  ├─ SQL event/snapshot/outbox
                  └─ latest SceneGraph
                         │
                         ▼
     CraftEngine furniture, seats, resources, and client presentation
```

- Each table has one serial writer; there is no thread per table.
- Rule packs evaluate rules only. They cannot access Bukkit, files, networks, wall clocks, or threads.
- CraftEngine 26.8 owns furniture placement and recovery, chunk lifecycle, entity indexes, seats, interaction entry points, conditional elements, models, and resource-pack generation.
- Concealed hands fail closed: only an authorized viewer receives face-up private elements.
- A match pins its rule version, JAR SHA-256, profile, configuration, seed, and state schema. Recovery never silently changes the rules.
- The plugin is a thin JAR. Paper's library loader downloads HikariCP, database drivers, AntiGriefLib, Sparrow, and ASM into the server `libraries/` cache.

## 2. Server installation tutorial

### 2.1 Requirements

| Component | Required version |
|---|---|
| Java | **25** |
| Server | Paper/Folia **26.2** |
| CraftEngine | **26.8 API line**: major/minor must be 26.8; 26.8.x/build suffixes are accepted, 26.9 is not |
| Client | Must accept the CraftEngine-generated server resource pack |
| First boot network | Maven Central mirror and `repo.momirealms.net` |

CraftEngine must keep delayed configuration loading enabled:

```yaml
misc:
  delay-configuration-load: true
```

MahjongPaper registers its CE behaviors and conditions before CE parses the bundled configuration. Disabling this option makes those resources invalid.

### 2.2 Install the plugin

1. Install CraftEngine 26.8.
2. Download the unclassified `mahjong-plugin-<version>.jar` from [MahjongEngine Releases](https://github.com/EllanStudio/MahjongEngine/releases).
3. Put the JAR in the server `plugins/` directory.
4. Start the server. Let Paper download runtime libraries and let MahjongPaper install/reload its CE bundle and generate the pack.
5. Stop the server cleanly before editing the generated configuration.

> Ordinary branch artifacts are primarily CI evidence and may not contain a production rule trust root. Use a formal Release for a real server. A custom build must embed the Ed25519 public key matching its signed registry.

These directories appear on demand; not every entry exists after first boot, and the comments mark the important conditional locations:

```text
plugins/
├─ MahjongPaper/
│  ├─ config.yml
│  ├─ data/                     # default H2 database
│  ├─ game-rooms.yml            # after the first room is saved
│  └─ rules/
│     ├─ staging/
│     ├─ quarantine/
│     ├─ registry-cache.json    # after registry fetch/install
│     ├─ riichi/<version>/      # after that version is installed
│     ├─ mcr/<version>/
│     └─ sichuan/<version>/
└─ CraftEngine/resources/
   ├─ mahjongpaper/
   └─ mahjongpaper-rule-<id>-<version>-<sha-prefix>/  # after active-rule startup
```

Do not hand-edit generated bundle manifests. During startup recovery, changed managed files are atomically replaced and applied through CE's reload and `PackManager`; if a live rule swap logs that resources need a reload, a full restart is the safest path.

### 2.3 Configure storage and game rooms

An empty `database.jdbc-url` selects the H2 files under `plugins/MahjongPaper/data`, which is suitable for testing:

```yaml
database:
  jdbc-url: ""
  username: "sa"
  password: ""
  maximum-pool-size: 8
```

MariaDB example:

```yaml
database:
  jdbc-url: "jdbc:mariadb://127.0.0.1:3306/mahjong"
  username: "mahjong"
  password: "change-me"
  maximum-pool-size: 8
```

If storage is unavailable, the plugin and diagnostics remain available, but matches that require durable recovery will not start or advance.

Game rooms are enabled by default, and new tables must fit entirely within a room:

```yaml
game-rooms:
  enabled: true
  restrict-new-tables: true
  enter-exit-messages: true
  leave-countdown-seconds: 60
  default-radius: 10
  default-height: 8
  file: "game-rooms.yml"
```

Other supported operational settings are:

| Setting | Meaning |
|---|---|
| `database.maximum-pool-size` | SQL pool size, from 2 through 32. |
| `rules.registry-url` | HTTPS URL of the Ed25519-signed registry; a formal release normally supplies the correct default. |
| `ranking.enabled` | Whether to award rank points; match and score history remain when disabled. |
| `ranking.east-room` / `south-room` | East/south rank tier: `BRONZE`, `SILVER`, `GOLD`, `JADE`, or `THRONE`. |
| `game-rooms.default-radius` / `default-height` | Fallback radius/height around the player when no wand selection exists; respectively 3–127 and 4–128. |
| `game-rooms.file` | Room index file name; only a safe file-name token is accepted. |
| `craftengine.bundle-folder` | CE main-bundle installation folder, not an asset/geometry configuration surface. |
| `presentation.overhead.enabled` | Enables the read-only overhead view. |
| `presentation.overhead.height` | Camera height, from 2 through 8 blocks. |
| `presentation.overhead.transition-ticks` | Enter/exit transition, from 1 through 40 ticks. |

Set `restrict-new-tables: false` only if you deliberately want tables outside managed rooms. CE assets, geometry, wall capacities, animations, collisions, and variants are resource-bundle data, not plugin configuration.

### 2.4 Install and activate rule packs

Run as an operator in game or from the console:

```text
/mahjong rules install riichi
/mahjong rules install mcr
/mahjong rules install sichuan
/mahjong rules verify
/mahjong rules list
```

Omitting the version selects the greatest version coordinate for that ID in the signed registry; the official registry is assembled only from release-validated stable entries. Read the installed version from `rules list`, then queue activation for the rules you need:

```text
/mahjong rules activate riichi <version>
/mahjong rules activate mcr <version>
/mahjong rules activate sichuan <version>
```

For classloader safety, `activate` takes effect on the **next full JVM start**. Stop and restart the server, then confirm `[active]` with:

```text
/mahjong rules list
```

Do not use `/reload`, a plugin hot-unloader, or overwrite a rule JAR while it is in use.

For later upgrades:

- `/mahjong rules update <id> [version]` downloads and verifies an artifact;
- `/mahjong rules swap <id> <version>` uses it for new matches immediately while running matches keep their pinned generation;
- `/mahjong rules rollback <id>` returns to the previous generation;
- `/mahjong rules deactivate <id>` prevents new matches from selecting the rule;
- `/mahjong rules gc` quarantines generations no longer needed.

### 2.5 Create a game room

With the default configuration, no table can be placed until an administrator creates a room. Get the selection tool:

```text
/mahjong room wand
```

Prefer selecting both room corners with left- and right-click, then run the commands below. Without a complete two-point selection, `create` uses `default-radius`/`default-height` around the player's position:

```text
/mahjong room create lobby-1 Main Lobby
/mahjong room info lobby-1
/mahjong room list
```

The complete `7 × 4 × 7` table envelope must fit inside the room and in land where placement is allowed. Room administration also includes:

```text
/mahjong room delete lobby-1
/mahjong ops reload-rooms
```

## 3. From table placement to a match

### 3.1 Create a table

Stand at the intended table center and run:

```text
/mahjong create riichi
```

You may select an explicit profile:

```text
/mahjong create riichi mahjong-soul
/mahjong create mcr green-book
/mahjong create sichuan t-tfmj-01-2024
```

The creator becomes the lobby owner but still needs to click a CE chair to take a seat. If creation fails, inspect the message for room bounds, the 7×4×7 envelope, headroom, nearby tables, or land protection.

### 3.2 Join, spectate, and ready up

Clicking a chair is the normal path. Command fallbacks are:

```text
/mahjong join <table-id> [east|south|west|north]
/mahjong spectate <table-id>
/mahjong unspectate
/mahjong leave
```

Before the match starts, the owner can use:

```text
/mahjong mode <riichi|mcr|sichuan> [profile]
/mahjong bot add <east|south|west|north>
/mahjong bot remove <east|south|west|north>
/mahjong owner <east|south|west|north>
```

Each human clicks the Ready table action or runs:

```text
/mahjong ready
```

When all four seats are occupied and ready, the owner clicks Start or runs:

```text
/mahjong start
```

An administrator can quickly exercise a full four-bot match:

```text
/mahjong botmatch MAJSOUL_HANCHAN
/mahjong botmatch GB
/mahjong botmatch SICHUAN
```

### 3.3 Play the match

The normal interaction path uses private CE hand tiles and action furniture:

- click a hand tile to select or discard it;
- use chii, pon, kan, win, and pass actions exposed for the current revision;
- click the river-view furniture to enter the read-only overhead view; use the table panel for rules, trustee/leave controls, and settlement;
- private facts are projected only to the authorized player.

If the resource pack or furniture interaction is temporarily unavailable, command fallbacks include:

```text
/mahjong tsumo
/mahjong ron
/mahjong pon
/mahjong minkan
/mahjong chii <tileA> <tileB>
/mahjong kan <tile>
/mahjong skip
/mahjong riichi <hand-index>
/mahjong kyuushu
```

Commands enter the same revision-bound action pipeline. Stale actions and actions illegal under the active rule pack are rejected.

Disconnect automation and manual automation share the same bounded rule-pack AI path:

```text
/mahjong auto on
/mahjong auto off
```

## 4. Command quick reference

Players have `mahjongpaper.command` by default. Administrative operations require `mahjongpaper.admin`, which defaults to operators. Run `/mahjong help [page]` for the built-in paged help; the table below records the exact forms used by this tutorial.

| Command | Purpose |
|---|---|
| `/mahjong create [rule] [profile]` | Create a waiting lobby and physical table at your position. |
| `/mahjong join <table-id> [seat]` | Join by command instead of clicking a chair. |
| `/mahjong table [table-id]` | Open the table control panel. |
| `/mahjong rule [summary]` | Open rule settings or the summary. |
| `/mahjong ready`, `/mahjong start` | Ready up and start a complete lobby. |
| `/mahjong list`, `/mahjong state [table-id]` | Inspect your table and current state. |
| `/mahjong history [page]` | Show personal match history. |
| `/mahjong rank [riichi|mcr|sichuan] [page]` | Show rankings. |
| `/mahjong settlement [table-id]` | Open settlement details. |
| `/mahjong remove <table-id>` | Remove your waiting table; admins may remove any table. |
| `/mahjong rules ...` | Install, verify, activate, swap, and roll back rule packs. |
| `/mahjong room ...` | Manage game rooms. |
| `/mahjong ops status <table-id>` | Inspect runtime health. |
| `/mahjong ops force-end <table-id>` | Force-end a broken match as an administrator. |
| `/mahjong reload` | Reload the indexed game rooms; core configuration and rule activation still require a full restart. |

Accepted aliases include `richi → riichi`, `gb → mcr`, and `MAJSOUL_HANCHAN/MAJSOUL_TONPUU → riichi`.

## 5. Backups, recovery, and upgrades

- Back up the database and the entire `plugins/MahjongPaper/` directory, not only rule JARs.
- Running matches pin their rule generation. Never delete a version still referenced by snapshots.
- Stop the server cleanly before copying H2. Use the database vendor's consistent backup tools for external SQL.
- Read release notes before upgrading the core and verify that its exact CraftEngine requirement remains 26.8.
- MahjongPaper deliberately gates CraftEngine internal APIs by exact version; never update CE alone and keep an older core running.
- For recovery failures, run `/mahjong rules verify` and `/mahjong ops status <table-id>`, then preserve logs, SQL data, and quarantined artifacts.

## 6. Troubleshooting

### CraftEngine version rejected

Use the exact 26.8 API line and keep `misc.delay-configuration-load: true`. Do not bypass the check by editing a version string.

### Missing classes or download failures on first boot

The thin JAR relies on Paper's runtime library loader. Allow access to a Maven Central mirror and `https://repo.momirealms.net/releases/`, or pre-warm the `libraries/` cache on an equivalent online staging server.

### A rule is installed but tables cannot use it

Check, in order:

1. `/mahjong rules verify <id>` reports valid;
2. `/mahjong rules list` shows `[active]`;
3. the JVM was fully restarted after `activate`;
4. the core release embeds the public key matching the configured registry.

### Models, labels, or sounds are missing

Confirm that CE reload and pack generation completed, the resource pack is distributed, and the client accepted it. Inspect CraftEngine logs. MahjongPaper has no legacy Display Entity fallback renderer.

### Table creation is rejected

By default, the complete table must be inside an administrator-defined game room and pass space, height, neighbor-distance, and land-protection checks. Use `/mahjong room info` and the detailed failure message.

## 7. Authoring your own rule pack

See the full [SPI 1.6.0 rule-pack authoring guide](docs/rule-pack-authoring.zh-CN.md). The guide is currently maintained in Chinese; its code and contract tables map directly to the Java API.

### 7.1 Current stock-runtime limitation

The stock 2.0 runtime accepts only the signed identities `riichi`, `mcr`, and `sichuan`. You can independently develop and run the TCK for another ID, but you cannot install it into a stock server. To deploy one, either:

- contribute to the corresponding official rule repository; or
- fork the core, extend the rule-ID allowlist and command/profile mappings, embed your own Ed25519 public key, and operate your own signed registry.

Do not disguise an unrelated ruleset as an existing identity.

### 7.2 Publish a local SDK repository

From the core checkout:

```bash
./gradlew \
  :mahjong-rule-spi:publishAllPublicationsToTestRepository \
  :mahjong-rule-tck:publishAllPublicationsToTestRepository
```

Use these Java 21 dependencies in the rule project:

```groovy
compileOnly 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'

testImplementation platform('org.junit:junit-bom:5.12.2')
testImplementation 'org.junit.jupiter:junit-jupiter'
testImplementation 'top.ellan.mahjong:mahjong-rule-spi:1.6.0'
testImplementation 'top.ellan.mahjong:mahjong-rule-tck:1.6.0'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

tasks.withType(Test).configureEach { useJUnitPlatform() }
```

The production rule artifact must be a dependency-free Java 21 thin JAR. It must not bundle the SPI.

### 7.3 Implement the complete match contract

A `RulePackProvider` must provide:

- `descriptor` and `createMatch`;
- `legalActions` and `transition`;
- public views and strictly viewer-scoped private views;
- snapshots, restore, and a stable state hash;
- optional deterministic scheduling and automation; a terminal result is mandatory after `MATCH_ENDED`.

The same seed and action sequence must produce identical results. A rejected transition must return the identical state instance with no events or presentation cues. The rule contract forbids platform APIs, files, networks, every wall-clock source, threads, native code, JDBC, and reflection; an as-yet unlisted JDK entry point is not a supported escape from that contract.

### 7.4 Register, test, and package

At minimum, the JAR contains:

```text
META-INF/mahjong-rule-pack.properties
META-INF/services/top.ellan.mahjong.spi.RulePackProvider
```

Run the reusable TCK:

```java
RulePackTckReport report = RulePackTck.verify(
        provider,
        new RulePackTckCase(setup, rejectedActions));
```

Each release pairs the thin rule JAR with a separate, resource-only CraftEngine ZIP. A signed registry binds both HTTPS URLs, byte lengths, versions, and SHA-256 digests. Forking one of the three official rule repositories is the safest way to inherit the Gradle validators, TCK setup, resource bundle, and release workflow.

## 8. Building the core

Use JDK 25:

```bash
./gradlew clean check :mahjong-plugin:jar :mahjong-plugin:verifyThinJar --no-daemon
```

Output:

```text
modules/mahjong-plugin/build/libs/mahjong-plugin-<version>.jar
```

`verifyThinJar` rejects classes outside `top/ellan/mahjong/`, rejects nested JARs, and confirms the Paper loader/runtime-catalog entries; the `check` tasks separately run module and architecture tests. A production release must also embed its rule-registry public key through the build environment.

## 9. Documentation

- [Installation and directory layout](docs/installation.zh-CN.md)
- [Rule-pack authoring](docs/rule-pack-authoring.zh-CN.md)
- [Rule SPI and TCK](docs/rule-sdk.zh-CN.md)
- [Official rule-pack release contract](docs/rule-packs.zh-CN.md)
- [Architecture and module boundaries](docs/architecture.zh-CN.md)
- [Concurrency model](docs/concurrency.zh-CN.md)
- [Interaction contract](docs/interaction.zh-CN.md)
- [Performance baseline](docs/performance-notes.zh-CN.md)
- [Momirealms library adoption audit](docs/momirealms-libraries.zh-CN.md)

## 10. License

Core source code uses the root MIT license. CraftEngine, database drivers, Sparrow, rule packs, and resource assets retain their own licenses; see `THIRD_PARTY_NOTICES.md` and `resourcepack/ATTRIBUTION.md`.
