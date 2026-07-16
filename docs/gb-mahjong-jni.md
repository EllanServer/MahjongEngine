# GB Mahjong JNI Bridge

MahjongPaper ships a real JNI bridge for the GB Mahjong ruleset. The Java side keeps the plugin's table/session model, while the native side delegates legality, fan counting, ting analysis, and win evaluation to a vendored copy of the upstream `GB-Mahjong` project.

Normative rule target:

- [EMA/WMO Mahjong Competition Rules (Green Book)](https://mahjong-europe.org/portal/images/docs/mcr_EN.pdf)
- [EMA MCR Regulations](https://mahjong-europe.org/portal/images/docs/mcr_regulations.pdf)
- See also: [gb-mahjong-rules.md](./gb-mahjong-rules.md) and [rule-verification-matrix.zh-CN.md](./rule-verification-matrix.zh-CN.md)

Implementation source:

- [zheng-fan/GB-Mahjong](https://github.com/zheng-fan/GB-Mahjong)

The vendored source is an implementation dependency, not the rule authority. Covered disagreements are fixed in the vendored copy or bridge and locked down with tests against the Green Book behavior.

## Architecture

The bridge is split into three layers:

1. Java/Kotlin request and response models
2. A JNI bridge that marshals JSON payloads into native calls
3. A C++ adapter that translates MahjongPaper state into the upstream `GB-Mahjong` APIs

The plugin does not try to embed C++ logic directly into gameplay flow. Instead, the Java side builds structured requests, the native side evaluates them, and the plugin converts the native answer back into MahjongPaper settlement and UI models.

## JVM Side

Main entry points:

- `src/main/java/top/ellan/mahjong/gb/jni/GbMahjongNativeLibrary.java`
- `src/main/java/top/ellan/mahjong/gb/jni/GbMahjongNativeBridge.java`
- `src/main/java/top/ellan/mahjong/gb/jni/GbNativeWarmupService.java`
- `src/main/java/top/ellan/mahjong/gb/runtime/GbNativeRulesGateway.java`
- `src/main/kotlin/top/ellan/mahjong/gb/jni/GbMahjongNativeModels.kt`

Responsibilities:

- resolve the correct native library name for the current platform
- load the native library from one of several locations
- expose availability checks and diagnostic detail
- encode requests into JSON and decode JSON responses back into typed models
- shield the rest of the plugin from JNI failures by converting them into invalid `fan`, `ting`, or `win` responses

### Startup Warmup And First-Call Benchmark

At plugin startup, MahjongPaper now runs a one-time async JNI warmup (`GbNativeWarmupService.warmupOnce(...)`):

- checks bridge availability
- probes `libraryVersion()` and `ping()`
- benchmarks first and warm calls for `evaluateFan`, `evaluateTing`, and `evaluateWin`

This produces startup log lines with nanosecond timings for first call vs warm call, so JNI call-pool ideas can be evaluated using data instead of assumptions.

### Library Loading Order

`GbMahjongNativeLibrary` currently tries these locations in order:

1. A path supplied with `-Dmahjongpaper.gb.native.path=/absolute/path/to/library`
2. A bundled native resource under `native/<platform>/`
3. Development build outputs such as `build/native/gbmahjong/...`
4. The normal `java.library.path`

The runtime also loads known sidecar dependencies when needed, such as `libwinpthread-1.dll` on `windows-x86_64`.

### Bridge API

`GbMahjongNativeBridge` exposes:

- `isAvailable()`
- `availabilityDetail()`
- `libraryVersion()`
- `ping()`
- `evaluateFan(GbFanRequest)`
- `evaluateTing(GbTingRequest)`
- `evaluateWin(GbWinRequest)`

`GbNativeRulesGateway` wraps those calls and converts missing bridge state, null responses, and runtime exceptions into safe fallback results instead of letting JNI failures tear down table flow.

## Native Side

Native project files:

- `native/gbmahjong/CMakeLists.txt`
- `native/gbmahjong/src/gbmahjong_jni.cpp`
- `native/gbmahjong/vendor/GB-Mahjong/mahjong/`

The vendored upstream license is kept at:

- `native/gbmahjong/vendor/GB-Mahjong/LICENSE`

Responsibilities of the C++ layer:

- parse JSON payloads from the JVM side
- translate MahjongPaper tile names, melds, and situational flags into upstream-compatible inputs
- call the vendored `GB-Mahjong` parser/fan logic
- return normalized JSON payloads for fan, ting, and win evaluation

## Request Mapping

MahjongPaper keeps its own structured request model and then converts it to the notation the upstream rule engine expects.

Current mapping includes:

- suit tiles mapped to the upstream `m`, `p`, and `s` grammar
- wind and dragon tiles mapped to the upstream honor notation
- flower tiles mapped to the upstream `a` through `h` ids
- melds rendered into upstream bracket notation
- round-state flags such as last tile, robbing kong, or after kong folded into the native status bits expected by the upstream evaluator

This design keeps the rest of the plugin readable and testable without forcing gameplay code to manipulate raw upstream hand strings directly.

## Native Runtime Responsibility And Rule Authority

The JNI/native layer produces the runtime result for:

- fan counting
- ting wait discovery
- win legality
- per-fan breakdown returned to the plugin

Those results remain governed by the Green Book. In particular, the repository regression contract includes the six-point combination of one melded kong plus one concealed kong and formal wait classification even when an alternative wait tile is already exhausted. An unmodified upstream result is not preserved merely for parity when it conflicts with the normative source.

The plugin still owns:

- region-thread-safe scheduling on Paper/Folia
- round lifecycle and reaction windows
- table UI, settlement UI, and messages
- score settlement objects used by MahjongPaper itself
- persistence and spectator/viewer state

## Build And Packaging

Relevant Gradle tasks:

- `configureGbMahjongNative`
- `buildGbMahjongNative`
- `packageGbMahjongNative`

Typical full project build:

```powershell
.\gradlew.bat build --console plain
```

Native-only build:

```powershell
.\gradlew.bat buildGbMahjongNative --console plain
```

During packaging, native outputs are copied into generated resources under:

```text
build/generated/resources/native/native/<platform>/
```

Those resources are then bundled into the plugin jar so the runtime loader can extract them to a temporary directory when needed.

## Verification

The bridge should be validated at three levels:

1. Model serialization and deserialization
2. Java-side controller tests that depend on `GbNativeRulesGateway`
3. Real native build verification on a machine or CI runner with CMake and a C++ toolchain

Useful checks:

```powershell
.\gradlew.bat test --console plain
.\gradlew.bat buildGbMahjongNative --console plain
```

If the native bridge is unavailable at runtime, the plugin does not silently switch to an unrelated rules implementation. Instead, `GbNativeRulesGateway` reports invalid native responses, which makes failures easier to diagnose.
