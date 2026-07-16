# GB Mahjong Status And Roadmap

This document tracks the current implementation state of GB Mahjong and the most likely follow-up areas.

Normative rules sources:

- [EMA/WMO Mahjong Competition Rules (Green Book)](https://mahjong-europe.org/portal/images/docs/mcr_EN.pdf)
- [EMA MCR Regulations](https://mahjong-europe.org/portal/images/docs/mcr_regulations.pdf)
- See also: [the player-facing GB profile](./gb-mahjong-rules.md) and [the verification matrix](./rule-verification-matrix.zh-CN.md)

Implementation references:

- [zheng-fan/GB-Mahjong](https://github.com/zheng-fan/GB-Mahjong), vendored under its own license
- [JNI implementation notes](./gb-mahjong-jni.md)

The Green Book and tournament regulations define the target behavior. The vendored project is the evaluator implementation and may be patched locally when a covered result conflicts with that authority.

## Current Status

GB Mahjong is no longer a placeholder branch experiment. It is integrated into the live table/session runtime and can be selected as a real ruleset through table rule presets.

What is already landed:

- table runtime can switch between `RIICHI` and `GB`
- `MahjongTableSession` delegates round behavior through a round-controller abstraction
- GB tables use `GbTableRoundController`
- GB tables support the Green Book choice to expose a flower and draw a replacement, or retain the flower for a later discard
- GB reaction windows support chi, pon, open kan, concealed kan, added kan, ron, and tsumo
- robbing-kong handling is wired into GB reaction flow
- settlement UI has GB-aware output
- bot scheduling is variant-aware
- command handling hides or blocks ruleset-specific actions where appropriate
- the native GB evaluator is wired through a vendored copy of `GB-Mahjong`

## Important Boundaries

GB support is live, but it should not be described as “done forever”.

The current implementation deliberately splits responsibility like this:

- the EMA/WMO Green Book and EMA regulations own the normative rule definition
- the vendored `GB-Mahjong` backend performs fan counting, ting analysis, and win evaluation at runtime, subject to local corrections and regression tests when it conflicts with the rule definition
- MahjongPaper owns table flow, spectator state, rendering, persistence, UI, and region-thread-safe scheduling

That means future work is more about hardening, coverage, and compatibility than about bootstrapping the ruleset from scratch.

## Stable Hotspots In The Codebase

When changing GB behavior, these files are usually the highest-signal places to inspect first:

- `src/main/java/top/ellan/mahjong/table/core/MahjongTableSession.java`
- `src/main/java/top/ellan/mahjong/table/core/round/GbTableRoundController.java`
- `src/main/java/top/ellan/mahjong/table/core/round/GbBotDecisionService.java`
- `src/main/java/top/ellan/mahjong/table/core/round/GbNativeRequestFactory.java`
- `src/main/java/top/ellan/mahjong/table/core/round/TableRoundController.java`
- `src/main/java/top/ellan/mahjong/command/MahjongCommandContext.java`
- `src/main/java/top/ellan/mahjong/command/subcommand/`
- `src/main/java/top/ellan/mahjong/ui/SettlementUi.java`
- `src/main/java/top/ellan/mahjong/gb/runtime/GbNativeRulesGateway.java`
- `src/main/kotlin/top/ellan/mahjong/gb/jni/GbMahjongNativeModels.kt`

## Remaining Roadmap

### 1. Native Build And Release Hardening

The JNI source and Gradle tasks already exist, but release discipline still matters:

- confirm native builds for each supported target platform
- validate bundled extraction paths on Windows, Linux, and macOS
- verify that packaged jars include the expected native sidecar files

### 2. Rule Calibration Against The Green Book

The native architecture remains based on `GB-Mahjong`, but rule parity is measured against the Green Book rather than against unmodified upstream output. Complex edge cases still benefit from more coverage:

- unusual flower-heavy hands
- multiple simultaneous reactions
- robbing-kong edge cases
- low-level flag mapping around last tile / after kong / robbed kong situations
- fan inclusion and exclusion combinations, including the six-point open-kong plus concealed-kong case
- formal waits whose alternative tile is physically exhausted

### 3. Broader Acceptance Coverage

The codebase already includes GB controller tests, but practical coverage should continue expanding:

- multi-hand live validation
- table persistence and reload behavior while GB tables exist
- UI and localization checks for GB-specific settlement output
- more regression coverage for bot behavior and suggestion stability

### 4. Player-Facing Documentation And Translation

GB support now deserves first-class docs and copy quality, not just developer notes:

- keep command help and README coverage aligned with actual GB behavior
- expand translated player-facing fan names and descriptions where needed
- maintain a practical live checklist for real server validation

## What This Is Not

This project is not aiming at a vague “Chinese-style Mahjong” mode with loosely interpreted rules.

The intended contract is:

- MahjongPaper follows the gameplay/session model documented for this plugin
- GB legality, fan behavior, flower choice, payment, dealer rotation, and match structure are anchored to the EMA/WMO Green Book and EMA regulations
- the vendored `GB-Mahjong` engine supplies the runtime evaluator but is not a second rule authority

If behavior diverges, inspect request mapping, the vendored calculation, settlement translation, and plugin-side state flow. Fix the responsible implementation layer and protect the Green Book result with a regression test instead of inventing a new local interpretation or preserving an upstream mismatch.
