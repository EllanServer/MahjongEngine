# MahjongPaper 2.0

MahjongPaper 2.0 is a CraftEngine-first Mahjong core for Paper/Folia. The core ships no game-specific rules. Riichi, MCR, and Sichuan are installed as independently versioned and signed rule-pack JARs.

The `2.0` branch is under active development and is not a stable release yet.

Implemented foundations include bounded per-table actors, a fair bounded rule CPU pool, memory-first SQL outboxes, event/snapshot recovery, signed child-first rule-pack loading, latest-only `SceneGraph` projection, and region-budgeted CraftEngine mutations. CraftEngine configuration owns furniture models, poses, hitboxes, interactions, and entity culling. Secret tile faces are client-only projections.

All modes share one physical table compiler. A rule pack supplies only its actual tile instances, wall stacks and draw origin, zone ordering, rotations/stacks, and typed action placement; the core contains no 108/136/144-tile mode branches.

The former session/controllers, mixed Java/Kotlin rule code, `mahjong-utils`, GB JNI/CMake tree, Display Entity renderer, legacy/shadow modes, and runtime fallback have been removed.

Requirements: Java 21, Paper/Folia 1.20.1+, and CraftEngine 26.7+.

See the [Chinese architecture guide](docs/architecture.zh-CN.md), [interaction contract](docs/interaction.zh-CN.md), [installation guide](docs/installation.zh-CN.md), and [rule-pack guide](docs/rule-packs.zh-CN.md).

GitHub Actions builds the plugin with:

```text
./gradlew clean check :mahjong-plugin:shadowJar --no-daemon
```

The final artifact is produced by `modules/mahjong-plugin`.
