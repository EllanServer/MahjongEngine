# Changelog

## 2.0.0 - Unreleased

### Performance

- Fixed `idx_rank_summary_ladder`, which omitted `total_score` and so diverged from the leaderboard
  `ORDER BY` at its fourth key. A production-shaped H2 probe then exposed a second problem hidden by
  the original player-id-only test: fetching every display column made the optimizer choose the
  primary key and sort the entire leaderboard partition even after the index itself was corrected.
- Changed leaderboard paging to one bounded two-stage SQL statement. Its covered inner query uses the
  narrow ladder index to select at most fifty-one ids and sort keys; the outer primary-key lookup fetches
  their display rows and can sort only that bounded page. This avoids both a partition-wide sort and a
  hundreds-of-bytes-per-player covering index.
- Added `RankingQueryPlanTest`, which reads index metadata and a production-shaped H2 plan, and
  `ExternalRankingQueryPlanTest`, enabled by GitHub's real MySQL 8.4 and MariaDB 11.4 services. The
  external gate migrates fresh schemas, populates 20,000 analysed rows, checks the actual seven-column
  ascending index, and rejects an inner full scan, filesort, or any plan not using the ladder index.
- Made the leaderboard ordering uniformly descending and declared the ladder index ascending, so the
  inner page can use a portable backward scan instead of depending on descending-index definitions.
  Verified on H2, MySQL 8.4.11 and MariaDB 11.4.12: both external engines select the ladder index
  without an inner filesort and use a primary-key `eq_ref` lookup for the bounded display rows.
- Recorded the measured baseline and hot-path attribution in `docs/performance-notes.zh-CN.md`,
  including the paths deliberately left alone: scene projection is event-driven rather than per tick
  and already carries four bounded caches, and bot decisions run on a dedicated bounded executor at
  roughly 0.08% of a core.
- Added `TableActorThroughputTest`, a 64-table baseline for the actor pipeline itself — mailbox
  handoff, fair-executor dispatch, projection rebuild, token issue and outbox append — measured over a
  trivial rule provider. It asserts only correctness and prints throughput, because a timing threshold
  on shared CI hardware fails for reasons unrelated to this code.
- Gated scene projection against regressions with a `scene-projection-regression` pull-request job
  that measures the candidate and its base commit on the same runner, comparing them through
  `.github/scripts/compare-scene-benchmark.py`. Allocation carries the tight bound because it barely
  moves between runs on one host, while wall time gets a loose one: two runs of identical code drifted
  19.2% in `ns/op` but only 1.0% in `bytes/op`. A vanished measurement also fails the gate.
- Replaced the game-room `YamlConfiguration` field plumbing with Sparrow YAML 1.0.12 typed serializers
  while preserving bounded input, duplicate-key rejection, one-codec confinement and atomic file
  replacement. The dependency was already shipped but previously had no source usage.
- Replaced the manually assembled paged command-help component tree with Sparrow MiniMessage 0.5,
  injection-safe placeholders and a bounded locale/admin/page cache. Two consecutive benchmark runs put
  Sparrow at 8.01–8.09 microseconds and 23,376–23,464 bytes/op versus Kyori MiniMessage at 14.79–15.51
  microseconds and 30,936–31,440 bytes/op. A visually equivalent direct Adventure tree remains roughly
  12 times faster, so every HUD/scene hot path stays direct.
- Updated the Momirealms stack to CraftEngine 26.8, AntiGriefLib 1.0.17 and Sparrow Reflection 0.34.
  Migrated world adaptation to `BukkitAdaptor`, raised the runtime gate to CE 26.8 and removed the
  unreachable legacy table-hitbox seat inference.
- Removed Sparrow Heart. CE furniture now owns persistent public entities, definition-reload
  replacement, private hand back/face switching, selection variants and static action labels. Scene
  identity lives in CE `FurniturePersistentData`; a registered CE behavior rebuilds the O(1) live index
  through `loadCustomData/onLoad/onUnload` and owns furniture interaction/hit protection. This removes
  the anchor-chunk UUID index and Paper entity/furniture event listeners without world, chunk-entity or
  nearby scans. Viewer authorization remains an O(1), fail-closed plugin condition; only dynamic
  semantic text, BossBars and the unsupported camera packet stay on the minimal packet/UI boundary.
- Pinned the CE adapter to the exact 26.8 internal API line. Placement now goes through
  `BukkitFurnitureManager`; changed bundles are reloaded and packed through CE's own reload and
  `PackManager` lifecycle. Folia region/entity scheduling remains Paper-owned because CE's safe
  operation runner is not a cross-region dispatcher.
- Replaced the Shadow fat JAR with a verified thin plugin JAR. It merges only this repository's
  Mahjong modules; Paper's plugin loader resolves database drivers, AntiGriefLib, Sparrow and ASM
  into the server library cache. CI rejects every foreign class and nested JAR in the release artifact.
- Moved CE asset IDs, shared layout dimensions/capacities and opening animation timings out of
  `config.yml` into a validated descriptor inside the CE resource pack. Furniture geometry, conditions,
  variants and text styling remain in CE YAML; only the unsupported overhead-camera settings stay in
  operational plugin configuration.

### 1.5.0 parity pass

- Restored bot and trustee playing strength: all three rule packs now call melds, declare kongs and
  choose discards from a ting/shanten-aware evaluation mirroring the 1.5.0 scoring
  (`1_000_000 + bestFan*10_000 + qualifyingWaits*100 + totalFan`). Riichi keeps the 1.5.0
  eleven-distinct-kind threshold for the nine-terminals abort. Kongs are now declared whenever they
  do not worsen the hand, instead of only when they create a ready hand.
- Added bot decision-quality regression tests that drive complete automated matches per rule pack;
  the previous smoke tests only asserted that *some* legal action came back, which is why the
  regression above was invisible to CI.
- Fixed three Riichi defects the bot regression had been masking: a reaction window kept offering
  actions to players who had already answered, a chii meld whose claimed tile was not its lowest
  made scoring throw `invalid sequence group`, and a declared riichi hand offered every tile as a
  legal discard instead of only the drawn one.
- Aligned non-discard decision timing with 1.5.0: a 5-second base plus a 20-second extra pool
  shared by all of one player's non-discard decisions within a hand, replacing a flat 25 seconds
  per decision. The 60/30/15/10-second discard anti-idle ladder is unchanged.
- Restored the 1.5.0 warning shown before a seat is played automatically. 2.0 auto-played silently;
  a second bounded per-table timer now fires five seconds early through a new
  `HumanDecisionWarningPort`, and the plugin puts `Auto-discard in 5s` on the player's action bar.
- Restored the 1.5.0 centre highlight of the newest discard: an enlarged public copy floats above
  the table so every seat can read the tile a call would be made on. All three packs already
  reported `RuleTablePresentation.lastDiscard`; nothing consumed it.
- Restored the land-protection soft-dependency declarations in both plugin manifests. AntiGriefLib
  binds its provider adapters when `ProtectionService` is constructed, so a protection plugin that
  loaded later went undetected.
- Fixed the game-room exit warning, which passed both the room name and the countdown into a message
  with a single placeholder and so told players to "return within &lt;room name&gt; seconds".
- Added a locale gate asserting every message key carries the same placeholder count in all six
  locales; a locale with a surplus placeholder throws `MissingFormatArgumentException` at runtime.
- Corrected the `/mahjong rank` help text, which advertised "Mahjong Soul-style rank progress" while
  the command only reports rank points, match count and total score.
- Ported the 1.5.0 Mahjong Soul rank ladder as portable, tested domain logic: the authoritative stage
  table, room awards, uma, stage penalties, promotion carry-over, demotion borrowing, the
  no-demotion floor for Novice and Adept 1, open-ended Celestial SP levels and the bounded
  stronger-table bonus.
- Placed rank progression in the core rather than in the rule packs, because it is common to every
  variant: a pack reports only the rule-specific placement and score. All three packs reported
  `rankingPointsMilli` as their score restated in thousandths, which carries no ladder information,
  so it no longer drives progression. `RankProgression` is stateless and operates on immutable
  values, so the terminal-result transaction can apply it on whichever thread already owns it.
- Extended `player_rank_summary` with the stage columns and a ladder-ordered index. A tier is stored
  both by name and by ordinal, since ordering by name would rank Adept above Celestial. Every column
  defaults to the Novice 1 starting profile, so an existing database upgrades without a backfill.
- Wired the ladder end to end. A new `RankProgressionPort` lets the plugin supply the configured room
  and match length while the persistence layer keeps the I/O: the terminal-result transaction reads
  every seat's profile in one indexed query, applies the port, and writes the result back without
  opening a second transaction or thread. The existing "skip seats whose ledger row already exists"
  guard means a retried result can never promote anyone twice.
- Added `ranking.enabled`, `ranking.east-room` and `ranking.south-room`. An unknown room name fails
  the config load rather than silently ranking everyone in the silver room.
- `/mahjong rank` now reports tier, level, points against the next threshold (Celestial as
  `x.x/20.0 SP`), average place and the first, top-two and fourth-place rates. The leaderboard is
  ordered by ladder standing instead of accumulated score, with score only breaking ties inside one
  stage.
- Removed every remaining backward-compatibility path: SPI now accepts only `SpiVersion.CURRENT`,
  the activation store only the current field set, the rule registry only its current entry format,
  and `ActionLabelText` only the current label key format. The registry format-1 convenience
  constructor and the stale 1.5.0 version strings in CI workflows and test fixtures are gone.

### Architecture

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
