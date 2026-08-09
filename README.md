# MahjongPaper 2.0

MahjongPaper 2.0 is a CraftEngine-first Mahjong core for Paper/Folia. The core ships no game-specific rules. Riichi, MCR, and Sichuan are installed as independently versioned and signed rule-pack JARs.

The `2.0` branch is under active development and is not a stable release yet.

Implemented foundations include bounded per-table actors, a fair bounded rule CPU pool, memory-first SQL outboxes, event/snapshot recovery, signed child-first rule-pack loading, latest-only `SceneGraph` projection, and region-budgeted CraftEngine mutations. CraftEngine configuration owns furniture models, poses, hitboxes, interactions, entity culling, and opening-dice slot/face variants; Java switches stable variants instead of respawning furniture. Secret tile faces are client-only projections.

The pre-match path is modular as well: immutable lobby state lives in `mahjong-domain`; lobby commands, reducer, actor, projection factory, ports, runtime index, and use cases are separated under `mahjong-application`; JDBC stores only implement the persistence port; Paper owns world-anchor conversion; CraftEngine resolves its configured chair hitboxes; and `mahjong-plugin` contains only categorized command and lifecycle integration packages. Lobby-to-match activation consumes the durable lobby and creates the pinned initial rule snapshot in one SQL transaction.

Production packages are classified by responsibility. Domain, application, presentation, CraftEngine, SQL, rule-runtime, and plugin integration roots reject unclassified classes in CI; the plugin root contains only the JavaPlugin entry point and the composition root. Presentation also enforces a 300-line class ceiling: the universal layout is split into cache/compiler/immutable lookup plan, while public, private, and interaction scene projection are separate components.

Rule SPI 1.5 adds rule-aware bots and trustee control without leaking concrete action names into the core. System progression and automation compete for one deterministic, revision-bound `ScheduledRuleAction`; the core owns one bounded timer per table and never scans every table for timeouts.

The signed rule-pack runtime is likewise separated into activation, administration, installation, class loading, registry verification, security, storage, and lifecycle packages. The Java-only rule SPI remains a deliberately flat, versioned external contract for the three rule repositories.

Create a reusable table with `/mahjong create <riichi|mcr|sichuan> [profile]`. Players sit by clicking the four CraftEngine-configured chairs, then use the projected ready/start actions. Owners can use `/mahjong bot add <seat>` for persistent rule-neutral bot seats; active humans use `/mahjong auto on|off`, while disconnect/reconnect toggles the same bounded trustee path automatically.

All modes share one physical table compiler. A rule pack supplies only its actual tile instances, wall stacks and draw origin, zone ordering, rotations/stacks, and typed action placement; the core contains no 108/136/144-tile mode branches.

The former session/controllers, mixed Java/Kotlin rule code, `mahjong-utils`, GB JNI/CMake tree, Display Entity renderer, legacy/shadow modes, and runtime fallback have been removed.

Requirements: Java 21, Paper/Folia 1.20.1+, and CraftEngine 26.7+.

See the [Chinese architecture guide](docs/architecture.zh-CN.md), [interaction contract](docs/interaction.zh-CN.md), [installation guide](docs/installation.zh-CN.md), and [rule-pack guide](docs/rule-packs.zh-CN.md).

GitHub Actions builds the plugin with:

```text
./gradlew clean check :mahjong-plugin:shadowJar --no-daemon
```

The final artifact is produced by `modules/mahjong-plugin`.
