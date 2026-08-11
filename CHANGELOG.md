# Changelog

## 2.0.0 - Unreleased

- Replaced the 1.x runtime with a Java 21 portable core and Java 25,
  CraftEngine-first Paper/Folia adapters.
- Removed every embedded rules implementation, Kotlin production rule path, GB JNI/native code,
  Display Entity fallback, legacy/shadow mode, global table scan, and direct `mahjong-utils`
  dependency.
- Added the signed rule-pack SPI/runtime, per-table bounded actor, fair bounded rule execution,
  memory-first SQL outbox and replayable snapshots.
- Split every official rule version into a pure rule JAR and a separately signed resource ZIP;
  the plugin retains common table/chair/tile visuals while rule-owned sounds no longer live in
  either the plugin bundle or the rule classloader.
- Moved public scene assets, furniture geometry, hitboxes, seats, interactions, and entity culling
  into the CraftEngine bundle. Secret faces remain per-player packet projections.
- Added CE-owned opening-dice slots and face variants; rule packs publish only deterministic dice
  facts, and stable furniture changes variant without entity respawn.
- Added the rule-neutral SPI 1.4 tabletop contract and one cached physical layout compiler; rule
  packs now declare their tile inventory, wall shape, stable slots, rotations and action targets.
- Added Linux Java 25 verification plus a legacy-free release-artifact contract in GitHub Actions;
  the external rule SDK and rule packs remain Java 21.

This history intentionally starts at 2.0. The former implementation is not part of this branch.
