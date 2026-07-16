#!/usr/bin/env python3
"""Make a deterministic three-state decision from paired JMH A/A and A/B runs."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
import statistics
import sys
from collections import defaultdict
from typing import Any, Iterable


PASS_OPTIMIZED = "PASS_OPTIMIZED"
INCONCLUSIVE = "INCONCLUSIVE_NOISE_OR_NO_GAIN"
FAIL = "FAIL_REGRESSION_OR_BEHAVIOR"
GUARDRAIL_PASS = "PASS_GUARDRAIL"
SCHEMA_VERSION = 1


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def percentile(sorted_values: list[float], probability: float) -> float:
    if not sorted_values:
        raise ValueError("cannot take a percentile of no values")
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = probability * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def stable_seed(seed: int, namespace: str) -> int:
    digest = hashlib.sha256(namespace.encode("utf-8")).digest()
    return seed ^ int.from_bytes(digest[:8], "big")


class SplitMix64:
    """Tiny version-independent PRNG used only for deterministic resampling."""

    MASK = (1 << 64) - 1

    def __init__(self, seed: int) -> None:
        self.state = seed & self.MASK

    def next_u64(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & self.MASK
        value = self.state
        value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & self.MASK
        value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & self.MASK
        return (value ^ (value >> 31)) & self.MASK

    def randbelow(self, bound: int) -> int:
        if bound <= 0:
            raise ValueError("bound must be positive")
        limit = (1 << 64) - ((1 << 64) % bound)
        while True:
            value = self.next_u64()
            if value < limit:
                return value % bound


def bootstrap_median_ci(
    values: list[float],
    samples: int,
    confidence: float,
    seed: int,
) -> tuple[float, float]:
    if not values:
        raise ValueError("cannot bootstrap no values")
    if samples <= 0:
        raise ValueError("bootstrap samples must be positive")
    if not 0.0 < confidence < 1.0:
        raise ValueError("bootstrap confidence must be between zero and one")
    generator = SplitMix64(seed)
    length = len(values)
    medians = [
        statistics.median(values[generator.randbelow(length)] for _ in range(length))
        for _ in range(samples)
    ]
    medians.sort()
    tail = (1.0 - confidence) / 2.0
    return percentile(medians, tail), percentile(medians, 1.0 - tail)


def strict_win_summary(values: Iterable[float]) -> dict[str, Any]:
    values = list(values)
    if not values:
        raise ValueError("cannot calculate win rate without values")
    wins = sum(value > 0.0 for value in values)
    ties = sum(value == 0.0 for value in values)
    return {
        "win_count": wins,
        "tie_count": ties,
        "loss_count": len(values) - wins - ties,
        "win_rate": wins / len(values),
    }


def paired_statistics(
    pairs: list[tuple[float, float]],
    direction: str,
    bootstrap: dict[str, Any],
    namespace: str,
) -> dict[str, Any]:
    if direction not in ("lower", "higher", "symmetric"):
        raise ValueError(f"unsupported direction: {direction}")
    log_ratios: list[float] = []
    raw_pairs: list[dict[str, float]] = []
    for baseline, comparison in pairs:
        if not math.isfinite(baseline) or not math.isfinite(comparison):
            raise ValueError("all scores must be finite")
        if baseline <= 0.0 or comparison <= 0.0:
            raise ValueError("log-ratio analysis requires positive scores")
        if direction in ("lower", "symmetric"):
            log_ratio = math.log(baseline / comparison)
        else:
            log_ratio = math.log(comparison / baseline)
        log_ratios.append(log_ratio)
        raw_pairs.append(
            {
                "baseline_score": baseline,
                "comparison_score": comparison,
                "log_improvement": log_ratio,
            }
        )
    median_log = statistics.median(log_ratios)
    mad = statistics.median(abs(value - median_log) for value in log_ratios)
    lower, upper = bootstrap_median_ci(
        log_ratios,
        int(bootstrap["samples"]),
        float(bootstrap["confidence"]),
        stable_seed(int(bootstrap["seed"]), namespace),
    )
    win_summary = strict_win_summary(log_ratios)
    return {
        "pair_count": len(pairs),
        "pairs": raw_pairs,
        "median_log_improvement": median_log,
        "median_improvement_fraction": math.expm1(median_log),
        "log_mad": mad,
        **win_summary,
        "bootstrap_ci_log": {"lower": lower, "upper": upper},
        "bootstrap_ci_improvement_fraction": {
            "lower": math.expm1(lower),
            "upper": math.expm1(upper),
        },
    }


def decide_optimization(metrics: dict[str, Any], thresholds: dict[str, Any]) -> tuple[str, list[str]]:
    reasons: list[str] = []
    median_log = metrics["median_log_improvement"]
    ci = metrics["bootstrap_ci_log"]
    pass_median = math.log1p(float(thresholds["minimum_median_improvement_fraction"]))
    pass_ci = math.log1p(float(thresholds["minimum_ci_lower_improvement_fraction"]))
    fail_median = -math.log1p(float(thresholds["failure_median_regression_fraction"]))
    enough_pairs = metrics["pair_count"] >= int(thresholds["minimum_pairs"])

    passed = (
        enough_pairs
        and median_log >= pass_median
        and ci["lower"] > pass_ci
        and metrics["win_count"] >= int(thresholds["minimum_wins"])
        and median_log
        > float(thresholds["minimum_effect_to_mad_ratio"]) * metrics["log_mad"]
        and metrics["log_mad"] <= float(thresholds["maximum_log_mad"])
    )
    if passed:
        reasons.append(
            "median, confidence bound, win rate, effect-to-MAD ratio and dispersion all clear the optimization thresholds"
        )
        return PASS_OPTIMIZED, reasons

    failed = (
        enough_pairs
        and median_log <= fail_median
        and ci["upper"] < 0.0
        and metrics["win_rate"] <= float(thresholds["failure_maximum_win_rate"])
    )
    if failed:
        reasons.append("paired samples show a statistically supported regression")
        return FAIL, reasons

    if not enough_pairs:
        reasons.append("too few complete pairs")
    if median_log < pass_median:
        reasons.append("median improvement is below the practical threshold")
    if ci["lower"] <= pass_ci:
        reasons.append("95% confidence interval does not clear the improvement floor")
    if metrics["win_count"] < int(thresholds["minimum_wins"]):
        reasons.append("fewer than the required paired runs improved")
    if median_log <= float(thresholds["minimum_effect_to_mad_ratio"]) * metrics["log_mad"]:
        reasons.append("median effect does not exceed the required multiple of paired MAD")
    if metrics["log_mad"] > float(thresholds["maximum_log_mad"]):
        reasons.append("paired log ratios are too dispersed")
    return INCONCLUSIVE, reasons


def decide_guardrail(metrics: dict[str, Any], thresholds: dict[str, Any]) -> tuple[str, list[str]]:
    """Require a secondary metric's 95% worst case to stay within its regression budget."""
    reasons: list[str] = []
    maximum_regression = float(thresholds["maximum_secondary_regression_fraction"])
    regression_floor = -math.log1p(maximum_regression)
    ci = metrics["bootstrap_ci_log"]
    if ci["lower"] >= regression_floor:
        return GUARDRAIL_PASS, [
            f"95% confidence bound stays within the {maximum_regression:.1%} regression budget"
        ]
    if metrics["median_log_improvement"] < regression_floor and ci["upper"] < regression_floor:
        return FAIL, [
            f"secondary metric has a statistically supported regression beyond {maximum_regression:.1%}"
        ]
    reasons.append(
        f"secondary metric cannot yet exclude a regression beyond {maximum_regression:.1%}"
    )
    return INCONCLUSIVE, reasons


def decide_aa(metrics: dict[str, Any], thresholds: dict[str, Any]) -> tuple[bool, list[str]]:
    ci = metrics["bootstrap_ci_log"]
    checks = {
        "median drift": abs(metrics["median_log_improvement"])
        <= float(thresholds["maximum_abs_median_log_ratio"]),
        "dispersion": metrics["log_mad"] <= float(thresholds["maximum_log_mad"]),
        "confidence width": (ci["upper"] - ci["lower"]) <= float(thresholds["maximum_ci_width"]),
    }
    if thresholds.get("require_ci_contains_zero", True):
        checks["confidence interval contains zero"] = ci["lower"] <= 0.0 <= ci["upper"]
    failures = [name for name, passed in checks.items() if not passed]
    if failures:
        return False, [f"A/A drift check failed: {name}" for name in failures]
    return True, ["A/A drift is within the base-owned limits"]


def read_jmh_results(path: pathlib.Path, expected_sha256: str | None) -> dict[str, dict[str, Any]]:
    if expected_sha256 and sha256_file(path) != expected_sha256:
        raise ValueError(f"raw result digest mismatch: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError(f"JMH result is not an array: {path}")
    results: dict[str, dict[str, Any]] = {}
    for entry in payload:
        name = entry.get("benchmark")
        primary = entry.get("primaryMetric", {})
        score = primary.get("score")
        if not isinstance(name, str) or not isinstance(score, (int, float)):
            raise ValueError(f"malformed JMH primary metric in {path}")
        metrics = {"primary": {"score": float(score), "unit": primary.get("scoreUnit")}}
        for metric_name, metric in entry.get("secondaryMetrics", {}).items():
            metric_score = metric.get("score")
            if isinstance(metric_name, str) and isinstance(metric_score, (int, float)):
                metrics[metric_name] = {
                    "score": float(metric_score),
                    "unit": metric.get("scoreUnit"),
                }
        results[name] = metrics
    return results


def verify_fork_log(path: pathlib.Path, expected_sha256: str | None, expected_args: list[str]) -> None:
    if not path.is_file():
        raise ValueError(f"missing JMH log: {path}")
    if expected_sha256 and sha256_file(path) != expected_sha256:
        raise ValueError(f"JMH log digest mismatch: {path}")
    reported = "\n".join(
        line
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
        if line.startswith("# VM options:")
    )
    if not reported:
        raise ValueError(f"JMH log does not report fork VM options: {path}")
    missing = [argument for argument in expected_args if argument not in reported]
    if missing:
        raise ValueError(f"JMH fork omitted fixed VM options {missing}: {path}")


def load_pairs(
    manifest_path: pathlib.Path,
    expected_phase: str,
    benchmark_specs: list[dict[str, Any]],
    config_sha256: str,
    repetitions_per_order: int,
    profile_name: str,
    expected_jvm_args: list[str],
) -> tuple[dict[str, list[tuple[float, float]]], dict[str, str | None], dict[str, Any]]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unsupported run manifest schema: {manifest_path}")
    if manifest.get("phase") != expected_phase:
        raise ValueError(f"expected {expected_phase} manifest, got {manifest.get('phase')}")
    if manifest.get("profile") != profile_name:
        raise ValueError(f"expected {profile_name} profile, got {manifest.get('profile')}")
    if manifest.get("config_sha256") != config_sha256:
        raise ValueError(f"run did not use the base-owned gate config: {manifest_path}")
    if not manifest.get("completed_at"):
        raise ValueError(f"run manifest is incomplete: {manifest_path}")
    isolation = manifest.get("candidate_isolation", {})
    isolation_prefix = isolation.get("command_prefix", [])
    if expected_phase == "ab" and (
        isolation.get("enabled") is not True
        or not isinstance(isolation_prefix, list)
        or not isolation_prefix
        or not isolation.get("file_user")
    ):
        raise ValueError("A/B candidate did not run under the required user isolation")

    by_pair: dict[int, dict[str, dict[str, dict[str, Any]]]] = defaultdict(dict)
    ordered_roles: dict[int, dict[int, str]] = defaultdict(dict)
    runtime_temp_dirs: set[str] = set()
    for execution in manifest.get("executions", []):
        if execution.get("exit_code") != 0:
            raise ValueError(f"run contains failed execution: {manifest_path}")
        result_path = manifest_path.parent / execution["result_file"]
        pair_index = int(execution["pair_index"])
        position = int(execution["position"])
        role = execution["role"]
        expected_revision = manifest.get("candidate_sha") if role == "candidate" else manifest.get("base_sha")
        expected_jar_hash = (
            manifest.get("candidate_jar_sha256") if role == "candidate" else manifest.get("base_jar_sha256")
        )
        if execution.get("revision_sha") != expected_revision:
            raise ValueError(f"execution revision mismatch in pair {pair_index}: {manifest_path}")
        if execution.get("jar_sha256") != expected_jar_hash:
            raise ValueError(f"execution jar digest mismatch in pair {pair_index}: {manifest_path}")
        if execution.get("fork_jvm_args_verified") is not True or execution.get("validation_error") is not None:
            raise ValueError(f"execution evidence was not validated in pair {pair_index}: {manifest_path}")
        runtime_temp_dir = execution.get("runtime_temp_dir")
        if not isinstance(runtime_temp_dir, str) or not runtime_temp_dir:
            raise ValueError(f"execution omitted its JMH runtime temp directory in pair {pair_index}")
        if runtime_temp_dir in runtime_temp_dirs:
            raise ValueError(f"execution reused JMH runtime temp directory in pair {pair_index}")
        runtime_temp_dirs.add(runtime_temp_dir)
        runtime_temp_arg = f"-Djava.io.tmpdir={runtime_temp_dir}"
        command = execution.get("command", [])
        command_offset = len(isolation_prefix) if role == "candidate" else 0
        if command[command_offset + 1 : command_offset + 2] != [runtime_temp_arg]:
            raise ValueError(f"execution launcher omitted its JMH runtime temp directory in pair {pair_index}")
        if role == "candidate":
            if execution.get("isolated_candidate") is not True or command[: len(isolation_prefix)] != isolation_prefix:
                raise ValueError(f"candidate execution escaped its command prefix in pair {pair_index}")
            if execution.get("result_mode_after_lock") != 0o444:
                raise ValueError(f"candidate result was not locked after pair {pair_index}")
        elif execution.get("isolated_candidate") is not False:
            raise ValueError(f"trusted execution was unexpectedly marked isolated in pair {pair_index}")
        if execution.get("log_mode_after_lock") != 0o444:
            raise ValueError(f"JMH log was not locked after pair {pair_index}")
        log_path = manifest_path.parent / execution["log_file"]
        verify_fork_log(
            log_path,
            execution.get("log_sha256"),
            [*expected_jvm_args, runtime_temp_arg],
        )
        if role in by_pair[pair_index] or position in ordered_roles[pair_index]:
            raise ValueError(f"duplicate role or position in pair {pair_index}: {manifest_path}")
        by_pair[pair_index][role] = read_jmh_results(
            result_path,
            execution.get("result_sha256"),
        )
        ordered_roles[pair_index][position] = role

    role_pair = ("base", "candidate") if expected_phase == "ab" else ("baseline_a", "baseline_b")
    expected_pairs = repetitions_per_order * 2
    if sorted(by_pair) != list(range(expected_pairs)):
        raise ValueError(f"expected exactly {expected_pairs} sequential pairs in {manifest_path}")
    pairs: dict[str, list[tuple[float, float]]] = {entry["id"]: [] for entry in benchmark_specs}
    units: dict[str, str | None] = {entry["id"]: None for entry in benchmark_specs}
    for pair_index in sorted(by_pair):
        roles = by_pair[pair_index]
        if set(roles) != set(role_pair):
            raise ValueError(f"pair {pair_index} is incomplete in {manifest_path}")
        forward = pair_index % 2 == 0
        expected_order = list(role_pair if forward else reversed(role_pair))
        actual_order = [ordered_roles[pair_index].get(position) for position in (0, 1)]
        if actual_order != expected_order:
            raise ValueError(
                f"pair {pair_index} order is {actual_order}, expected {expected_order} in {manifest_path}"
            )
        first_results = roles[role_pair[0]]
        second_results = roles[role_pair[1]]
        for entry in benchmark_specs:
            benchmark_id = entry["id"]
            benchmark = entry["benchmark"]
            metric = entry.get("metric", "primary")
            if benchmark not in first_results or benchmark not in second_results:
                raise ValueError(f"missing benchmark {benchmark} in pair {pair_index}")
            if metric not in first_results[benchmark] or metric not in second_results[benchmark]:
                raise ValueError(f"missing metric {metric} for {benchmark} in pair {pair_index}")
            first = first_results[benchmark][metric]
            second = second_results[benchmark][metric]
            if first["unit"] != second["unit"]:
                raise ValueError(f"score unit mismatch for {benchmark_id} in pair {pair_index}")
            if units[benchmark_id] not in (None, first["unit"]):
                raise ValueError(f"score unit changed between pairs for {benchmark_id}")
            units[benchmark_id] = first["unit"]
            pairs[benchmark_id].append((first["score"], second["score"]))
    return pairs, units, manifest


def render_markdown(decision: dict[str, Any]) -> str:
    lines = [
        "# Performance A/B decision",
        "",
        f"**{decision['state']}**",
        "",
        f"Profile: **{decision['profile']}**",
        "",
        f"A/A drift gate: **{'PASS' if decision['aa_drift']['passed'] else 'FAIL'}**",
        "",
        "| Benchmark | State | Median | 95% CI | Wins | log MAD |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for result in decision["benchmarks"]:
        metrics = result["metrics"]
        ci = metrics["bootstrap_ci_improvement_fraction"]
        lines.append(
            "| {name} | {state} | {median:+.2%} | [{lower:+.2%}, {upper:+.2%}] | "
            "{wins}/{pairs} | {mad:.4f} |".format(
                name=result["id"],
                state=result["state"],
                median=metrics["median_improvement_fraction"],
                lower=ci["lower"],
                upper=ci["upper"],
                wins=metrics["win_count"],
                pairs=metrics["pair_count"],
                mad=metrics["log_mad"],
            )
        )
    lines.extend(["", "## Reasons", ""])
    for reason in decision["reasons"]:
        lines.append(f"- {reason}")
    lines.append("")
    return "\n".join(lines)


def validate_profile_config(
    config: dict[str, Any],
    profile_name: str,
    benchmark_specs: list[dict[str, Any]],
) -> tuple[set[str], int]:
    profile = config["profiles"][profile_name]
    configured_ids = [entry["id"] for entry in benchmark_specs]
    if len(configured_ids) != len(set(configured_ids)):
        raise ValueError(f"profile {profile_name} contains duplicate benchmark ids")
    primary_ids = {
        entry["id"] for entry in benchmark_specs if entry.get("metric", "primary") == "primary"
    }
    must_pass = set(profile.get("must_pass", []))
    if not must_pass or not must_pass.issubset(primary_ids):
        raise ValueError(f"profile {profile_name} must_pass must contain only primary metrics")
    minimum_passes = profile.get("minimum_passes")
    if isinstance(minimum_passes, bool) or not isinstance(minimum_passes, int):
        raise ValueError(f"profile {profile_name} has a non-integer primary minimum_passes")
    if not 1 <= minimum_passes <= len(primary_ids):
        raise ValueError(f"profile {profile_name} has an invalid primary minimum_passes")
    return must_pass, minimum_passes


def evaluate(
    config: dict[str, Any],
    profile_name: str,
    benchmark_specs: list[dict[str, Any]],
    aa_pairs: dict[str, list[tuple[float, float]]],
    ab_pairs: dict[str, list[tuple[float, float]]],
    units: dict[str, str | None],
) -> dict[str, Any]:
    bootstrap = config["bootstrap"]
    aa_results: list[dict[str, Any]] = []
    benchmark_results: list[dict[str, Any]] = []
    aa_passed = True
    reasons: list[str] = []

    must_pass, minimum_passes = validate_profile_config(config, profile_name, benchmark_specs)
    for entry in benchmark_specs:
        benchmark_id = entry["id"]
        aa_metrics = paired_statistics(
            aa_pairs[benchmark_id],
            "symmetric",
            bootstrap,
            f"aa:{profile_name}:{benchmark_id}",
        )
        passed, aa_reasons = decide_aa(aa_metrics, config["aa_drift"])
        aa_passed = aa_passed and passed
        aa_results.append({"id": benchmark_id, "passed": passed, "metrics": aa_metrics, "reasons": aa_reasons})
        if not passed:
            reasons.extend(f"{benchmark_id}: {reason}" for reason in aa_reasons)

        thresholds = dict(config["decision"])
        thresholds.update(entry.get("decision", {}))
        metrics = paired_statistics(
            ab_pairs[benchmark_id],
            entry["direction"],
            bootstrap,
            f"ab:{profile_name}:{benchmark_id}",
        )
        metric_name = entry.get("metric", "primary")
        role = "optimization" if metric_name == "primary" else "guardrail"
        if role == "optimization":
            state, result_reasons = decide_optimization(metrics, thresholds)
        else:
            state, result_reasons = decide_guardrail(metrics, thresholds)
        benchmark_results.append(
            {
                "id": benchmark_id,
                "benchmark": entry["benchmark"],
                "metric": metric_name,
                "role": role,
                "direction": entry["direction"],
                "must_pass": benchmark_id in must_pass,
                "score_unit": units.get(benchmark_id),
                "state": state,
                "metrics": metrics,
                "reasons": result_reasons,
            }
        )

    optimization_results = [result for result in benchmark_results if result["role"] == "optimization"]
    guardrail_results = [result for result in benchmark_results if result["role"] == "guardrail"]
    pass_count = sum(result["state"] == PASS_OPTIMIZED for result in optimization_results)
    must_pass_satisfied = all(
        result["state"] == PASS_OPTIMIZED
        for result in benchmark_results
        if result["id"] in must_pass
    ) and must_pass.issubset({result["id"] for result in benchmark_results})
    if not aa_passed:
        state = INCONCLUSIVE
        reasons.append("A/A drift gate failed, so the candidate cannot be credited or blamed")
    elif any(result["state"] == FAIL for result in benchmark_results):
        state = FAIL
        reasons.append("at least one optimization or guardrail metric has strong regression evidence")
    elif (
        pass_count >= minimum_passes
        and must_pass_satisfied
        and all(result["state"] == GUARDRAIL_PASS for result in guardrail_results)
    ):
        state = PASS_OPTIMIZED
        reasons.append(
            f"{pass_count} primary metrics passed (minimum {minimum_passes}), all must-pass metrics cleared, and all secondary guardrails held"
        )
    else:
        state = INCONCLUSIVE
        reasons.append(
            f"only {pass_count} primary metrics passed (minimum {minimum_passes}), a must-pass metric is inconclusive, or a secondary guardrail is unresolved"
        )

    for result in benchmark_results:
        if result["state"] not in (PASS_OPTIMIZED, GUARDRAIL_PASS):
            reasons.extend(f"{result['id']}: {reason}" for reason in result["reasons"])
    return {
        "schema_version": SCHEMA_VERSION,
        "created_at": utc_now(),
        "profile": profile_name,
        "state": state,
        "aa_drift": {"passed": aa_passed, "benchmarks": aa_results},
        "benchmarks": benchmark_results,
        "reasons": reasons,
    }


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    config_path = args.config.resolve()
    aa_manifest = args.aa_manifest.resolve()
    ab_manifest = args.ab_manifest.resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))
    if config.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unsupported config schema: {config.get('schema_version')}")
    if args.profile not in config.get("profiles", {}):
        raise ValueError(f"unknown benchmark profile: {args.profile}")
    profile = config["profiles"][args.profile]
    benchmark_index = {entry["id"]: entry for entry in config["benchmarks"]}
    benchmark_specs = [benchmark_index[benchmark_id] for benchmark_id in profile["benchmark_ids"]]
    if not benchmark_specs:
        raise ValueError("at least one benchmark metric must be configured")
    validate_profile_config(config, args.profile, benchmark_specs)
    config_sha = sha256_file(config_path)
    repetitions = int(config["matrix"]["repetitions_per_order"])
    aa_pairs, aa_units, aa_run = load_pairs(
        aa_manifest,
        "aa",
        benchmark_specs,
        config_sha,
        repetitions,
        args.profile,
        config["jmh"].get("jvm_args", []),
    )
    ab_pairs, ab_units, ab_run = load_pairs(
        ab_manifest,
        "ab",
        benchmark_specs,
        config_sha,
        repetitions,
        args.profile,
        config["jmh"].get("jvm_args", []),
    )
    if aa_run.get("jmh") != ab_run.get("jmh"):
        raise ValueError("A/A and A/B did not use identical JMH settings")
    if aa_run.get("base_jar_sha256") != aa_run.get("candidate_jar_sha256"):
        raise ValueError("A/A control did not use the same base jar on both sides")
    if aa_run.get("base_jar_sha256") != ab_run.get("base_jar_sha256"):
        raise ValueError("A/A and A/B used different base jars")
    evidence_root = aa_manifest.parent.parent
    retained_jars = {
        "base": (evidence_root / "jars" / "base-jmh.jar", ab_run.get("base_jar_sha256")),
        "candidate": (
            evidence_root / "jars" / "candidate-jmh.jar",
            ab_run.get("candidate_jar_sha256"),
        ),
    }
    for role, (jar_path, expected_hash) in retained_jars.items():
        if not jar_path.is_file() or sha256_file(jar_path) != expected_hash:
            raise ValueError(f"retained {role} JMH jar does not match the run manifest")
    fixed_jmh_keys = ("mode", "time_unit", "force_gc", "profilers", "jvm_args")
    for key in fixed_jmh_keys:
        if aa_run.get("jmh", {}).get(key) != config["jmh"].get(key):
            raise ValueError(f"run changed base-owned JMH setting: {key}")
    if aa_run.get("jmh", {}).get("include") != profile["include"]:
        raise ValueError("run changed the base-owned benchmark include pattern")
    if aa_run.get("base_sha") != ab_run.get("base_sha") or aa_run.get("candidate_sha") != ab_run.get("candidate_sha"):
        raise ValueError("A/A and A/B revision identities differ")
    for entry in benchmark_specs:
        benchmark_id = entry["id"]
        if aa_units[benchmark_id] != ab_units[benchmark_id]:
            raise ValueError(f"A/A and A/B score units differ for {benchmark_id}")

    decision = evaluate(config, args.profile, benchmark_specs, aa_pairs, ab_pairs, ab_units)
    decision["evidence"] = {
        "config": {"path": str(config_path), "sha256": config_sha},
        "aa_manifest": {"path": str(aa_manifest), "sha256": sha256_file(aa_manifest)},
        "ab_manifest": {"path": str(ab_manifest), "sha256": sha256_file(ab_manifest)},
    }
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    decision_json = output_dir / "decision.json"
    decision_markdown = output_dir / "decision.md"
    write_json(decision_json, decision)
    decision_markdown.write_text(render_markdown(decision), encoding="utf-8")
    write_json(
        output_dir / "evidence-manifest.json",
        {
            "schema_version": SCHEMA_VERSION,
            "created_at": utc_now(),
            "decision_state": decision["state"],
            "files": {
                "decision.json": sha256_file(decision_json),
                "decision.md": sha256_file(decision_markdown),
                "aa/run-manifest.json": sha256_file(aa_manifest),
                "ab/run-manifest.json": sha256_file(ab_manifest),
                "gate-config.json": config_sha,
                "jars/base-jmh.jar": sha256_file(retained_jars["base"][0]),
                "jars/candidate-jmh.jar": sha256_file(retained_jars["candidate"][0]),
            },
        },
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--aa-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--ab-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    try:
        raise SystemExit(run(parse_args()))
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
