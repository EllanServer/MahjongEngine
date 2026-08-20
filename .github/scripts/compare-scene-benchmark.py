#!/usr/bin/env python3
"""Gates scene-projection performance by comparing two runs measured on one host.

The benchmark is a self-built harness, not JMH: no forks, no confidence intervals, no
percentiles. Its absolute numbers are therefore meaningless across machines, and GitHub's
``ubuntu-latest`` pool drifts between runner sizes, so a stored baseline would compare
different hardware and fail for reasons unrelated to the change.

This script instead expects both inputs to come from the same job on the same runner: the
candidate (the pull request) and the base commit it merges into. That pairing is what makes
the comparison a gate rather than noise.

Two signals, two very different tolerances:

``bytes/op``
    Allocation is decided by the code and the JDK, not by CPU speed or scheduling, so it
    barely moves between runs on one host. It is the primary signal here and catches the
    regressions that actually matter, such as a per-node allocation reintroduced into the
    projection loop or a cache quietly stopping to hit.

``ns/op``
    Wall time moves with GC timing, thermal state and neighbouring tenants even on one
    machine, so it gets a deliberately loose bound. It exists to catch order-of-magnitude
    mistakes, not small drifts.
"""

from __future__ import annotations

import re
import sys

# Allocation is near-deterministic for a fixed JDK and code shape, so this can stay tight.
BYTES_REGRESSION_FACTOR = 1.25
# Wall time is noisy on shared runners even within one host; only catch large regressions.
NANOS_REGRESSION_FACTOR = 1.30
# Anything below this is measurement noise rather than a real difference worth printing.
NOTABLE_CHANGE = 0.05

BENCHMARK_LINE = re.compile(
    r"BENCHMARK\s+name=(?P<name>\S+).*?\bns/op=(?P<nanos>[\d.]+).*?\bbytes/op=(?P<bytes>[\d.]+|NaN)"
)


def parse(path: str) -> dict[str, dict[str, float]]:
    """Reads every BENCHMARK line from a captured Gradle log."""
    results: dict[str, dict[str, float]] = {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = BENCHMARK_LINE.search(line)
            if not match:
                continue
            raw_bytes = match.group("bytes")
            results[match.group("name")] = {
                "nanos": float(match.group("nanos")),
                # The harness prints NaN when the JVM denies per-thread allocation counters.
                "bytes": float("nan") if raw_bytes == "NaN" else float(raw_bytes),
            }
    return results


def ratio(candidate: float, baseline: float) -> float | None:
    """Returns candidate/baseline, or None when the pair cannot be compared."""
    if baseline <= 0 or candidate != candidate or baseline != baseline:
        return None
    return candidate / baseline


def describe(name: str, metric: str, value: float | None, limit: float) -> tuple[bool, str]:
    """Formats one metric comparison and reports whether it breaches the gate."""
    if value is None:
        return False, f"  {name} {metric}: not comparable (missing or unsupported), skipped"
    percent = (value - 1.0) * 100.0
    if value > limit:
        return True, (
            f"  {name} {metric}: REGRESSED {percent:+.1f}% "
            f"(x{value:.2f}, limit x{limit:.2f})"
        )
    if abs(percent) < NOTABLE_CHANGE * 100.0:
        return False, f"  {name} {metric}: unchanged ({percent:+.1f}%)"
    direction = "slower/heavier" if percent > 0 else "faster/lighter"
    return False, f"  {name} {metric}: {percent:+.1f}% {direction} (limit x{limit:.2f})"


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(
            "usage: compare-scene-benchmark.py <candidate-log> <baseline-log>",
            file=sys.stderr,
        )
        return 2

    candidate = parse(argv[1])
    baseline = parse(argv[2])

    if not candidate:
        print(f"no BENCHMARK lines found in candidate log {argv[1]}", file=sys.stderr)
        return 1
    if not baseline:
        print(f"no BENCHMARK lines found in baseline log {argv[2]}", file=sys.stderr)
        return 1

    missing = sorted(set(baseline) - set(candidate))
    if missing:
        # A vanished measurement hides a regression, so treat it as one.
        print(
            f"candidate no longer reports these measurements: {', '.join(missing)}",
            file=sys.stderr,
        )
        return 1

    print("Scene projection A/B comparison (same runner, candidate vs base commit)")
    print(
        f"  gate: bytes/op <= x{BYTES_REGRESSION_FACTOR:.2f}, "
        f"ns/op <= x{NANOS_REGRESSION_FACTOR:.2f}"
    )
    print()

    failed = False
    for name in sorted(candidate):
        if name not in baseline:
            print(f"  {name}: new measurement, nothing to compare against")
            continue
        print(
            f"  {name}: candidate {candidate[name]['nanos']:.1f} ns/op, "
            f"{candidate[name]['bytes']:.0f} B/op | base "
            f"{baseline[name]['nanos']:.1f} ns/op, {baseline[name]['bytes']:.0f} B/op"
        )
        for metric, key, limit in (
            ("bytes/op", "bytes", BYTES_REGRESSION_FACTOR),
            ("ns/op", "nanos", NANOS_REGRESSION_FACTOR),
        ):
            breached, message = describe(
                name, metric, ratio(candidate[name][key], baseline[name][key]), limit
            )
            failed = failed or breached
            print(message)
        print()

    if failed:
        print(
            "Scene projection regressed beyond the gate. Allocation growth is the signal to\n"
            "trust: it is near-deterministic on one host. If the change is intentional, say so\n"
            "in the pull request and adjust the factors in this script deliberately.",
            file=sys.stderr,
        )
        return 1

    print("Scene projection is within the gate.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
