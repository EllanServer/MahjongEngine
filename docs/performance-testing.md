# Performance Testing

MahjongPaper has three deliberately different performance test tiers:

1. `perfTest` is the existing lightweight JUnit diagnostic suite. It is useful while
   developing, but its same-JVM timings are **not** merge evidence.
2. `jmh` / `jmhJar` compiles the isolated `src/perfTest/java` JMH harness. Pull requests
   labeled `performance-ab` are judged only by the base-owned paired JMH gate.
3. `perf/live` starts a real, standard Paper `server.jar`, samples Paper's own TPS/MSPT
   commands through RCON, and connects an external Minecraft protocol client that counts
   framed packets and socket payload bytes.

JMH intentionally benchmarks deterministic CPU/allocation paths and does not pretend to
simulate Paper ticks or network traffic. The live harness covers that boundary, but its short
CI smoke profile proves only that evidence collection works. A performance claim still needs
the fixed-world measurement profile and paired base/candidate runs described below.

## Isolated JMH harness

JMH sources live under:

- `src/perfTest/java`

Local defaults are one fork, five one-second warmup iterations and eight one-second
measurement iterations. The convention lives in `buildSrc`; developers may override it
explicitly:

```powershell
.\gradlew.bat jmh `
  -PjmhForks=1 `
  -PjmhWarmups=5 `
  -PjmhIterations=8 `
  -PjmhWarmupTime=1s `
  -PjmhMeasurementTime=1s
```

CI does not trust candidate Gradle properties. It runs the generated jars on a pinned JDK and
OS image using the values from the base revision's `perf/ab/gate-config.json`. Fixed heap,
G1 GC and pre-touch options are appended to every JMH measurement fork and verified from its
retained log. Maintainer-triggered
`workflow_dispatch` runs may override fork/warmup/measurement counts, and the effective
values are recorded in both run manifests.

The base harness also enables JMH's GC profiler. Time and normalized allocation (`B/op`) are
separate paired metrics; allocation is not inferred from heap size or a single process-wide
rate.

## Paired A/B merge gate

The authoritative workflow is `.github/workflows/performance-ab.yml`. Add `performance-ab`
and exactly one profile label to a pure performance PR:

- `performance-snapshot` - real `TableRenderSnapshotFactory` fan-out at 4, 32 and 128 viewers;
- `performance-gb-bot` - real `GbBotDecisionService` with duplicate, mixed and unique hands;
- `performance-region-fingerprint` - a real started-table snapshot and layout, measuring the
  complete region map plus a batch of private/public hands, discards, melds and all 136 wall
  slots;
- `performance-region-keys` - all 452 dynamic keys requested while planning one complete
  table refresh, with exact text, order, uniqueness and checksum sentinels outside timing;
- `performance-layout` - complete RIICHI, GB and SICHUAN started-table
  `TableRenderLayout.precompute` calls covering four occupied seats, hands, discards, melds,
  sticks, each variant's full wall capacity and RIICHI dora;
- `performance-scheduler-reflection` - representative global, region and entity scheduling
  bursts through both Folia-style reflective capabilities and standard Paper fallbacks;
- `performance-ray-proxy` - 1, 4 and 32-viewer coordinator lifecycle and unchanged-geometry
  reuse. This CPU/allocation profile does not measure protocol bytes or client hit coverage.

The region-fingerprint harness checks the complete region map and representative per-tile
results against an independent copy of the current `Objects.toString(value, "")`, colon-delimited,
per-character FNV contract before every JMH iteration. That behavior sentinel is outside the
timed section, so a candidate cannot gain by changing fingerprint encoding while the measured
time and normalized allocation remain focused on production code. The infrastructure
fingerprint profile applies the same rule to its exact delimited string and now treats
normalized allocation as a secondary guardrail.

The workflow is loaded through `pull_request_target`, uses only `contents: read`, persists no
checkout credentials, disables Gradle's shared cache, and clears GitHub/Actions runtime
credentials from every shell step that executes candidate bytecode. Candidate JMH runs use a
dedicated no-login UID: only the current candidate JSON path receives candidate ownership,
while stdout is confined to the current runner-opened log and the jars, parent directories,
previous results and manifests remain runner-owned. After each run, residual processes under
that UID are killed and audited, ownership is reclaimed, and the result/log are locked
read-only before hashing. Every A/A and A/B execution also receives a distinct `java.io.tmpdir`
under a runner-owned parent. The JMH launcher retains its lock without sharing `/tmp/jmh.lock`
across the runner and isolated UIDs, and the same directory is passed to its measurement fork.
The final statistics are evaluated on a fresh runner from a fresh base checkout; candidate code
cannot rewrite base/AA evidence or the decision gate.
The infrastructure workflow exercises this boundary on Linux with a probe that attempts to
rewrite runner-owned evidence and leaves a delayed background writer; both must be contained.

Each profile defines a minimum number of primary time metrics that must decisively improve and
one or more must-pass primary metrics. Allocation metrics are guardrails only: they can never
turn a flat time result into a pass, and their 95% worst-case bound must stay within a 5%
regression budget.

The current `origin/dev` predates the Sparrow ray-proxy coordinator. Its protected benchmark
contract is already present, but the A/B runner checks both jars for the real coordinator and
ray-interaction classes before running that profile. Until the bugfix baseline contains those
classes, `performance-ray-proxy` fails preflight instead of benchmarking the fallback model.
Once present, the same protected benchmark automatically invokes the real package-private
coordinator and verifies that an unchanged second replace emits no additional logical spawns.

The workflow uses two fresh GitHub-hosted runners:

1. Check out the PR base and candidate commits side by side.
2. Byte-compare all `protected_paths` from the base config. The candidate cannot change the
   benchmark source, Gradle harness, wrapper, decision scripts, workflow or thresholds.
3. Build one JMH jar from each revision with identical Java/Paper settings.
4. Run an order-balanced A/A control: four `A1,A2` pairs and four `A2,A1` pairs,
   interleaved in ABBA execution order.
5. Run exactly four `base,candidate` and four `candidate,base` pairs in the same interleaved
   ABBA order, with candidate bytecode confined to the unprivileged UID.
6. Upload the raw evidence and end the runner that executed candidate code.
7. On a fresh runner, check out the base and candidate again and repeat the protected-path
   comparison.
8. Analyze with the fresh base-owned gate, then upload every raw result, log, run manifest,
   digest and decision for review.

If a new benchmark or threshold is needed, merge that harness change first. The subsequent
optimization PR must not contain benchmark-infrastructure changes. This split prevents a
candidate from choosing a friendlier workload or lowering its own bar.

### Statistics

For a lower-is-better benchmark, each complete pair becomes:

```text
log_improvement = log(base_score / candidate_score)
```

For a higher-is-better benchmark, the ratio is reversed. Therefore positive values always
mean that the candidate improved. Decisions use:

- median paired log improvement;
- median absolute deviation (MAD) of paired log improvements;
- strict paired win count (ties do not count as wins);
- deterministic bootstrap 95% confidence interval of the paired median.

The fixed bootstrap seed is combined with the metric ID, making repeated statistical
analysis of the same evidence numerically reproducible. Absolute nanoseconds and bytes per operation are retained in raw JMH
JSON, but the decision is based on paired ratios so runner speed is not confused with code
speed.

### Three outcomes

- `PASS_OPTIMIZED`: A/A is stable, enough primary metrics improve, every must-pass primary
  metric clears the gate, and all secondary guardrails hold.
- `FAIL_REGRESSION_OR_BEHAVIOR`: A/A is stable and a primary metric has strong regression
  evidence or a secondary metric is statistically beyond its 5% budget.
- `INCONCLUSIVE_NOISE_OR_NO_GAIN`: evidence is flat, noisy, mixed, incomplete, a guardrail is
  unresolved, or the A/A control drifts.

Only `PASS_OPTIMIZED` makes the required check green. `INCONCLUSIVE_NOISE_OR_NO_GAIN` is not
silently treated as success; rerun on a stable runner or improve the signal. A failed A/A
control never blames the candidate; it forces `INCONCLUSIVE_NOISE_OR_NO_GAIN`.

Thresholds are reviewed in `perf/ab/gate-config.json`. The default gate requires eight
complete pairs, at least 3% median improvement, a strictly positive lower 95% bound (therefore
the upper 95% bound of candidate/base is below 1), at least six paired wins, and a median
effect strictly greater than twice the paired log-ratio MAD. Strong, consistent regression
evidence produces `FAIL_REGRESSION_OR_BEHAVIOR`.

### Evidence artifacts

The uploaded `performance-ab-*` artifact contains:

```text
artifact-manifest.json       hashes every retained file
gate-config.json             exact base-owned policy snapshot
protected-paths.json         byte-comparison result
jars/base-jmh.jar            exact retained base benchmark bytecode
jars/candidate-jmh.jar       exact retained candidate benchmark bytecode
aa/run-manifest.json         A/A schedule, environment, commands and hashes
aa/raw/*.json                raw JMH output for every control execution
aa/logs/*.log                complete JMH logs
ab/run-manifest.json         4AB+4BA schedule and provenance
ab/raw/*.json                raw candidate/base JMH output
ab/logs/*.log                complete JMH logs
decision.json                machine-readable three-state decision
decision.md                  reviewer summary
evidence-manifest.json       hashes of decision inputs and outputs
```

Run manifests include commit IDs, jar/config hashes, Java/Python/OS/CPU information,
effective JMH settings, execution order, elapsed time and the hash of each raw result/log.

## Legacy diagnostic benchmarks

MahjongPaper includes benchmark-style tests under the `perf` JUnit tag. They are excluded from normal `test` runs and are executed through the dedicated Gradle `perfTest` task.

## How It Works

Benchmark helpers live in:

- `src/test/kotlin/top/ellan/mahjong/perf/PerformanceBenchmarkSupport.kt`

Current benchmark entry points live in:

- `src/test/kotlin/top/ellan/mahjong/perf/CorePerformanceBenchmarksTest.kt`
- `src/test/kotlin/top/ellan/mahjong/perf/GbBotSuggestionBenchmarkTest.kt`

The helper writes aggregated reports after each benchmark run to:

- `build/reports/performance/results.md`
- `build/reports/performance/results.json`

The CI regression gate additionally writes:

- `build/reports/performance/regression.md`
- `build/reports/performance/regression.json`

## Run

```powershell
$env:GRADLE_USER_HOME='E:\project\majiang\.gradle-home'
.\gradlew.bat perfTest --console plain
```

## Tune Iterations

`perfTest` reads these Gradle properties:

- `perfWarmups`
- `perfIterations`
- `perfBatchSize` (the default for benchmarks that do not declare a tuned batch size)

Example:

```powershell
.\gradlew.bat perfTest -PperfWarmups=8 -PperfIterations=20 --console plain
```

These values are forwarded to the benchmark helper as:

- `mahjong.perf.warmupIterations`
- `mahjong.perf.measurementIterations`
- `mahjong.perf.batchSize`

Current benchmark entry points use per-benchmark batch sizes, which are included in every report row. `perfBatchSize` therefore only affects new benchmarks that rely on the helper default.

## Regression Gate

The GitHub performance job compares the candidate commit with the pull request base commit (or the previous commit for a branch push) on the **same runner**. It runs three paired samples and alternates their order (`baseline/candidate`, then `candidate/baseline`) to reduce runner drift and order bias. The gate uses the median of the three paired ratios, so one noisy run cannot fail the build.

The default gate covers the CPU-side render hot paths that directly affect table refresh MSPT:

| Benchmark | Maximum paired-median regression |
| --- | ---: |
| `render.snapshot.create.started_session` | 25% |
| `render.region_fingerprints.precompute.started_snapshot` | 25% |
| `render.layout.precompute.started_snapshot` | 30% |

These are relative limits against code built and measured on the same GitHub runner. They are deliberately not absolute ns/op ceilings tied to one machine.

The comparison task reads one JSON report per paired run from the baseline and candidate directories:

```powershell
.\gradlew.bat perfRegressionCheck `
  -PperfBaselineDir=build/perf-comparison/baseline `
  -PperfCandidateDir=build/perf-comparison/candidate `
  -PperfRegressionMinRuns=3 `
  --console plain
```

Override or add benchmark-specific percentage limits with a semicolon-separated property. Unspecified default limits remain enabled:

```powershell
.\gradlew.bat perfRegressionCheck `
  '-PperfRegressionThresholds=render.snapshot.create.started_session=20;custom.hot_path=35' `
  --console plain
```

Keep at least three runs. A threshold change should reflect an intentional policy decision, not be used to hide a single noisy result; rerun the job first when the paired results disagree.

## Current Benchmarks

As of the current `dev` branch, the benchmark suite covers:

- `render.snapshot.create.started_session`
- `render.layout.precompute.started_snapshot`
- `render.region_fingerprints.precompute.started_snapshot`
- `riichi.round_engine.start_round`
- `gb.round_controller.start_round`
- `gb.bot.suggest_discard.duplicate_hand`
- `gb.native_gateway.ting_cache.hit`

In addition to the `perf` suite, JNI startup now logs a one-time first-call benchmark
(`GbNativeWarmupService`) for `fan/ting/win` first-call vs warm-call latency.
Use those startup numbers as the baseline before considering JNI "call pool" designs.

## Reading Results

The generated markdown report includes:

- warmup, measurement, and batch counts per benchmark
- average ns/op
- median ns/op
- p90 ns/op
- min ns/op
- max ns/op
- total measured milliseconds

Use the same warmup, measurement, and batch parameters before and after an optimization so the results remain comparable.

## Entity-Heavy Render Changes

`perfTest` measures CPU-side hot paths such as snapshot creation, layout precompute, and region fingerprinting. It does **not** model Paper entity tracking cost or client-side rendering cost.

For changes that increase table entities, per-viewer overlays, or display churn, also validate on a live server scene:

- server `mspt`
- client `fps`
- `table.render.region.managed_entities`
- `table.render.region.viewer_overlay_regions`
- `table.render.region.viewer_overlay_entities`

Treat those entity gauges as leading indicators. Even if benchmark numbers stay flat, a higher managed entity count can still hurt server tick time and client frame rate once real viewers are present.

## Recommended local diagnostic workflow

For a performance-sensitive change:

1. Run `perfTest` at least three times on the current baseline and save each JSON report
2. Apply the optimization
3. Run the same `perfTest` command at least three times for the candidate
4. Run `perfRegressionCheck` against the paired report directories
5. Inspect both the paired-ratio gate and the raw reports instead of relying on a single run

These benchmarks are intentionally lightweight and developer-friendly. They help catch regressions and compare hot-path changes, but they are not a substitute for full production profiling on a live Paper/Folia server.

## Live standard server.jar and packet validation

The JMH gate proves only the CPU micro/macro path represented by its base-owned workload. The
ray-proxy profile in particular does not prove fewer packets, fewer real client entity IDs, or
successful clicks across protocol versions.
The live harness is under `perf/live`. It launches a copied `server.jar` in a new temporary
directory on loopback-only, dynamically reserved server/RCON ports. It writes deterministic
server properties, removes CI credential variables from the child environment, uses a random
per-run RCON password that is never retained, and asks Paper to stop through RCON. A timeout
fallback is recorded as a failed, non-graceful run rather than silently killing the process.

The dependency surface is deliberately fixed:

| Component | Pinned value |
| --- | --- |
| JMH runtime | Temurin `25.0.2+10.0.LTS` |
| Live Paper runtime | Temurin `21.0.11+10.0.LTS` |
| External client runtime | Node `22.23.1` |
| Protocol implementation | `minecraft-protocol` `1.66.2`, locked by npm integrity |
| Paper | `1.20.1` build `196`, SHA-256 `234a9b32098100c6fc116664d64e36ccdb58b5b649af0f80bcccb08b0255eaea` |
| CraftEngine for plugin runs | Paper `26.7.3` (`Len451or`), SHA-512 from the committed artifact lock |

`perf/live/artifacts.lock.json` contains immutable URLs, byte sizes and hashes. Downloads are
written to a temporary file, size/hash verified, fsynced, then atomically moved into place.
The Paper URL is the official Paper downloads-service object. The optional CraftEngine entry
is artifact `craftengine-paper-26.7.3`, the latest Paper/Folia/Purpur file published for that
version. Do not substitute the same-version Bukkit/Spigot file: Modrinth marks that separate
artifact for Minecraft 26.x only, while the locked Paper file explicitly includes Paper 1.20.1
through 26.2. Its committed metadata source is
`https://api.modrinth.com/v2/version/Len451or`.

### What is actually counted

`perf/live/client/probe.js` is a separate operating-system process. It does not install a
handler in Paper, CraftEngine, Bukkit or Netty. On its own client socket it records:

- TCP socket payload bytes from Node's `bytesRead` / `bytesWritten`;
- every complete Minecraft length-prefixed protocol frame and its exact on-wire payload size,
  including the VarInt frame prefix and compression as transmitted;
- decoded packet state/name as a classification of that already-observed frame.

The independent socket byte totals must exactly equal the sum parsed by the frame counters or
the probe fails. These numbers are Minecraft protocol frames and TCP payload bytes; they are
not IP packet/segment counts and do not include Ethernet/IP/TCP headers. The client runs in
offline mode because the isolated Paper instance is loopback-only. It confirms teleports and
answers keep-alives through the pinned protocol implementation, but it is not a replacement
for the manual interaction acceptance matrix.

Paper TPS and MSPT are sampled with one persistent external RCON connection. Every raw `tps`
and `mspt` response is retained before the parser derives distributions. Paper reports TPS for
1/5/15 minutes and average/minimum/maximum MSPT for 5/10/60 seconds; the harness does not infer
either value from process CPU time.

### Run the infrastructure smoke

Install the locked client once, fetch Paper, then start the smoke:

```powershell
npm ci --prefix perf/live/client --ignore-scripts --no-audit --no-fund
npm test --prefix perf/live/client
python perf/live/download_locked.py `
  --artifact paper-1.20.1-196 `
  --destination build/live-cache/server.jar
python perf/live/run_server.py `
  --paper-jar build/live-cache/server.jar `
  --output-dir build/reports/live-paper-smoke `
  --warmup-seconds 5 `
  --measurement-seconds 20 `
  --sample-interval-seconds 5
```

The smoke generates a fresh world and is explicitly marked
`infrastructure-smoke-only; not optimization evidence` in `run-manifest.json`.

### Run the real plugin without a fake dependency

MahjongPaper declares CraftEngine as required. The harness therefore refuses a plugin jar
unless the real checksum-locked CraftEngine jar is supplied; it never rewrites plugin metadata
or substitutes a stub named `CraftEngine`.

```powershell
python perf/live/download_locked.py `
  --artifact craftengine-paper-26.7.3 `
  --destination build/live-cache/craft-engine-paper-plugin-26.7.3.jar
python perf/live/run_server.py `
  --paper-jar build/live-cache/server.jar `
  --plugin-jar build/libs/mahjong-paper-1.5.0.jar `
  --craftengine-jar build/live-cache/craft-engine-paper-plugin-26.7.3.jar `
  --required-plugin MahjongPaper `
  --scenario-name majsoul-hanchan-four-bot-spectator `
  --scenario-command "/mahjong botmatch MAJSOUL_HANCHAN" `
  --scenario-ready-pattern 'Round .+ \| Turn .+ \| Wall [1-9][0-9]* \| Spectators 1' `
  --scenario-timeout-seconds 90 `
  --require-mahjong-traffic `
  --output-dir build/reports/live-paper-plugin-smoke `
  --warmup-seconds 5 `
  --measurement-seconds 20 `
  --sample-interval-seconds 5
```

The run fails if either required plugin is not enabled. Plugin/Paper/CraftEngine hashes and all
runtime jars downloaded by Paper or the plugin loader are listed in the raw evidence. For the
fixed scenario above, the external client sends the botmatch command, polls `/mahjong state`,
and must observe the committed ready pattern before warmup and measurement begin. Missing game
traffic is a hard failure, not a fallback to an empty-server keepalive run.

The standalone measurement-infrastructure PR deliberately does not execute this plugin profile
as its required pull-request check. The current foundation baseline does not expose Caffeine to
the plugin classloader on a fresh Paper instance, so the real plugin profile correctly stops at
the required-plugin enablement check. That dependency blocker must be fixed in the foundation
change and then exercised by a stacked run; this harness does not patch the plugin descriptor,
inject a fake dependency, or turn the known failure green.

### Measurement rules

`--evidence-mode measurement` refuses to run without a pre-generated world template containing
`world/level.dat`, at least 60 seconds of warmup, at least 60 seconds of measurement, and a
TPS/MSPT interval no greater than 10 seconds. That protects reviewers from accidentally treating
fresh chunk generation or a 12-second smoke as optimization evidence.

Before merging a TPS/MSPT or packet optimization, keep behavior tests green and run the same
deterministic scene on `server.jar` for base and candidate:

- identical Java flags, world, plugin config and bot/player script;
- warm server before measurement;
- paired base/candidate order rather than one long base run followed by one long candidate
  run;
- server TPS/MSPT percentiles and profiler evidence;
- packet counts/bytes per viewer, entity counts and display churn where relevant;
- real client acceptance for all affected game modes and interactions.

Each live output directory retains:

```text
run-manifest.json                 hashes, versions, ports, timings, shutdown result and claim scope
paper-summary.json                derived TPS/MSPT sample distributions
raw/paper-samples.jsonl           exact timestamped Paper command responses plus parsed values
raw/server-stdout.log             complete server console output
raw/instance/logs/latest.log      Paper's own latest.log
raw/runtime-jars.json             SHA-256 of server/plugin/runtime jars actually loaded
raw/protocol-client/packets.jsonl one record per externally observed Minecraft frame
raw/protocol-client/summary.json  socket/frame cross-check and per-state/name totals
```

The live harness currently produces one run at a time. It does not turn one run into a merge
decision. Use order-balanced base/candidate runs (including an A/A drift control) before making
an optimization claim; a green JMH result or green live smoke alone proves neither lower
TPS/MSPT nor fewer packets.

The `Performance infrastructure` pull-request workflow runs the isolated bare-Paper smoke,
so the harness itself gets real PR evidence without loading the candidate plugin. The fixed
Mahjong scenario remains available through the explicit command above and fails closed until
all real runtime dependencies are present. The
`Performance A/B gate` remains base-owned through `pull_request_target`; on the bootstrap PR
that first adds that workflow, it cannot honestly be reported as a base-owned green gate. It
becomes authoritative only after the workflow is merged to `dev` and a subsequent optimization
PR is labeled. Do not replace that missing base-owned run with a manually fabricated success.
