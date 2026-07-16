# Performance Testing

MahjongPaper has two deliberately different performance test tiers:

1. `perfTest` is the existing lightweight JUnit diagnostic suite. It is useful while
   developing, but its same-JVM timings are **not** merge evidence.
2. `jmh` / `jmhJar` compiles the isolated `src/perfTest/java` JMH harness. Pull requests
   labeled `performance-ab` are judged only by the base-owned paired JMH gate.

The first infrastructure phase intentionally benchmarks deterministic CPU/allocation paths.
It does not pretend to simulate Paper ticks, entity tracking or a protocol client. TPS/MSPT
and packet changes still require the live-server stage described at the end of this document.

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
- `performance-ray-proxy` - 1, 4 and 32-viewer coordinator lifecycle and unchanged-geometry
  reuse. This CPU/allocation profile does not measure protocol bytes or client hit coverage.

The workflow is loaded through `pull_request_target`, uses only `contents: read`, persists no
checkout credentials, disables Gradle's shared cache, and clears GitHub/Actions runtime
credentials from every shell step that executes candidate bytecode. Candidate JMH runs use a
dedicated no-login UID: only the current candidate JSON path receives candidate ownership,
while stdout is confined to the current runner-opened log and the jars, parent directories,
previous results and manifests remain runner-owned. After each run, residual processes under
that UID are killed and audited, ownership is reclaimed, and the result/log are locked
read-only before hashing. The final statistics are evaluated on a fresh runner from a fresh
base checkout; candidate code cannot rewrite base/AA evidence or the decision gate.
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

## Run

```powershell
$env:GRADLE_USER_HOME='E:\project\majiang\.gradle-home'
.\gradlew.bat perfTest --console plain
```

## Tune Iterations

`perfTest` reads these Gradle properties:

- `perfWarmups`
- `perfIterations`
- `perfBatchSize`

Example:

```powershell
.\gradlew.bat perfTest -PperfWarmups=8 -PperfIterations=20 -PperfBatchSize=500 --console plain
```

These values are forwarded to the benchmark helper as:

- `mahjong.perf.warmupIterations`
- `mahjong.perf.measurementIterations`
- `mahjong.perf.batchSize`

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

1. Run `perfTest` on the current baseline
2. Save `build/reports/performance/results.md`
3. Apply the optimization
4. Run the same `perfTest` command again
5. Compare benchmark names one by one instead of relying on a single global impression

These benchmarks are intentionally lightweight and developer-friendly. They help catch regressions and compare hot-path changes, but they are not a substitute for full production profiling on a live Paper/Folia server.

## Live Paper and packet validation

The JMH gate proves only the CPU micro/macro path represented by its base-owned workload. The
ray-proxy profile in particular does not prove fewer packets, fewer real client entity IDs, or
successful clicks across protocol versions.
Before merging a TPS/MSPT or packet optimization, keep behavior tests green and additionally
run the same deterministic scene on `server.jar` for base and candidate:

- identical Java flags, world, plugin config and bot/player script;
- warm server before measurement;
- paired base/candidate order rather than one long base run followed by one long candidate
  run;
- server TPS/MSPT percentiles and profiler evidence;
- packet counts/bytes per viewer, entity counts and display churn where relevant;
- real client acceptance for all affected game modes and interactions.

Real Paper/protocol-client automation is a later infrastructure stage. Until that exists,
do not claim that a green JMH result alone proves lower TPS/MSPT or fewer packets.
