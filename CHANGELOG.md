# Changelog

## 2.0.0 - Unreleased

- Replaced the 1.x runtime with a Java 21, CraftEngine-first multi-module core.
- Removed every embedded rules implementation, Kotlin production rule path, GB JNI/native code,
  Display Entity fallback, legacy/shadow mode, global table scan, and direct `mahjong-utils`
  dependency.
- Added the signed rule-pack SPI/runtime, per-table bounded actor, fair bounded rule execution,
  memory-first SQL outbox and replayable snapshots.
- Moved public scene assets, furniture geometry, hitboxes, seats, interactions, and entity culling
  into the CraftEngine bundle. Secret faces remain per-player packet projections.
- Added CE-owned opening-dice slots and face variants; rule packs publish only deterministic dice
  facts, and stable furniture changes variant without entity respawn.
- Added the rule-neutral SPI 1.4 tabletop contract and one cached physical layout compiler; rule
  packs now declare their tile inventory, wall shape, stable slots, rotations and action targets.
- Added Linux and Windows Java 21 verification plus a legacy-free release-artifact contract in
  GitHub Actions.

This history intentionally starts at 2.0. The former implementation is not part of this branch.
