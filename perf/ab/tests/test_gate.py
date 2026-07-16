from __future__ import annotations

import json
import math
import pathlib
import sys
import tempfile
import unittest
import zipfile


AB_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AB_DIR))

import gate  # noqa: E402
import merge_manifests  # noqa: E402
import run_matrix  # noqa: E402
import verify_protected  # noqa: E402


class GateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = json.loads((AB_DIR / "gate-config.json").read_text(encoding="utf-8"))
        self.config["bootstrap"]["samples"] = 2_000
        self.profile = "infra"
        self.specs = [self.config["benchmarks"][0]]
        self.benchmark_id = self.specs[0]["id"]
        self.name = self.specs[0]["benchmark"]
        self.aa = {self.benchmark_id: [(100.0, 100.0)] * 8}
        self.units = {self.benchmark_id: "ns/op"}

    def evaluate(self, candidate_scores: list[float], aa=None):
        pairs = {self.benchmark_id: list(zip([100.0] * 8, candidate_scores, strict=True))}
        return gate.evaluate(self.config, self.profile, self.specs, aa or self.aa, pairs, self.units)

    def test_clear_improvement_passes(self) -> None:
        decision = self.evaluate([88.0, 90.0, 89.0, 91.0, 87.0, 90.0, 89.0, 88.0])
        self.assertEqual(gate.PASS_OPTIMIZED, decision["state"])
        self.assertTrue(decision["aa_drift"]["passed"])

    def test_clear_regression_fails(self) -> None:
        decision = self.evaluate([112.0, 110.0, 111.0, 109.0, 113.0, 110.0, 112.0, 111.0])
        self.assertEqual(gate.FAIL, decision["state"])

    def test_flat_or_noisy_result_is_inconclusive(self) -> None:
        decision = self.evaluate([96.0, 104.0, 98.0, 102.0, 97.0, 103.0, 99.0, 101.0])
        self.assertEqual(gate.INCONCLUSIVE, decision["state"])

    def test_effect_must_exceed_twice_the_paired_mad(self) -> None:
        log_improvements = [0.02, 0.08] * 4
        candidate_scores = [100.0 / math.exp(value) for value in log_improvements]
        decision = self.evaluate(candidate_scores)
        self.assertEqual(gate.INCONCLUSIVE, decision["state"])
        self.assertTrue(
            any("multiple of paired MAD" in reason for reason in decision["benchmarks"][0]["reasons"])
        )

    def test_aa_drift_vetoes_otherwise_positive_candidate(self) -> None:
        drifting_aa = {self.benchmark_id: [(100.0, 80.0)] * 8}
        decision = self.evaluate([88.0] * 8, aa=drifting_aa)
        self.assertEqual(gate.INCONCLUSIVE, decision["state"])
        self.assertFalse(decision["aa_drift"]["passed"])

    def test_bootstrap_is_deterministic(self) -> None:
        pairs = [(100.0, score) for score in (91.0, 89.0, 92.0, 88.0, 90.0, 87.0, 93.0, 89.0)]
        first = gate.paired_statistics(pairs, "lower", self.config["bootstrap"], "determinism")
        second = gate.paired_statistics(pairs, "lower", self.config["bootstrap"], "determinism")
        self.assertEqual(first, second)

    def test_lower_and_higher_direction_use_positive_for_improvement(self) -> None:
        lower = gate.paired_statistics([(100.0, 80.0)] * 8, "lower", self.config["bootstrap"], "lower")
        higher = gate.paired_statistics([(100.0, 120.0)] * 8, "higher", self.config["bootstrap"], "higher")
        self.assertGreater(lower["median_log_improvement"], 0.0)
        self.assertGreater(higher["median_log_improvement"], 0.0)

    def test_ties_do_not_count_toward_six_required_wins(self) -> None:
        metrics = gate.paired_statistics(
            [(100.0, score) for score in (90.0, 91.0, 92.0, 93.0, 94.0, 100.0, 100.0, 110.0)],
            "lower",
            self.config["bootstrap"],
            "strict-wins",
        )
        self.assertEqual(5, metrics["win_count"])
        self.assertEqual(2, metrics["tie_count"])

    def test_manifest_requires_exact_interleaved_abba_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            executions = []
            schedule = run_matrix.build_schedule("ab", 4)
            for index, scheduled in enumerate(schedule):
                runtime_temp_dir = f"/tmp/jmh-runtime-{index}"
                runtime_temp_arg = f"-Djava.io.tmpdir={runtime_temp_dir}"
                result = root / f"result-{index}.json"
                result.write_text(
                    json.dumps(
                        [
                            {
                                "benchmark": self.name,
                                "primaryMetric": {"score": 100.0, "scoreUnit": "ns/op"},
                            }
                        ]
                    ),
                    encoding="utf-8",
                )
                log = root / f"result-{index}.log"
                log.write_text(
                    f"# VM options: -Xms1g -Xmx1g {runtime_temp_arg}\n",
                    encoding="utf-8",
                )
                executions.append(
                    {
                        **scheduled,
                        "exit_code": 0,
                        "result_file": result.name,
                        "result_sha256": gate.sha256_file(result),
                        "log_file": log.name,
                        "log_sha256": gate.sha256_file(log),
                        "revision_sha": "candidate" if scheduled["role"] == "candidate" else "base",
                        "jar_sha256": "candidate-jar" if scheduled["role"] == "candidate" else "base-jar",
                        "fork_jvm_args_verified": True,
                        "validation_error": None,
                        "isolated_candidate": scheduled["role"] == "candidate",
                        "result_mode_after_lock": 0o444,
                        "log_mode_after_lock": 0o444,
                        "runtime_temp_dir": runtime_temp_dir,
                        "command": (
                            ["sudo", "-u", "candidate", "--", "java", runtime_temp_arg]
                            if scheduled["role"] == "candidate"
                            else ["java", runtime_temp_arg]
                        ),
                    }
                )
            manifest = root / "run-manifest.json"
            manifest_value = {
                "schema_version": 1,
                "phase": "ab",
                "profile": self.profile,
                "completed_at": "2026-01-01T00:00:00Z",
                "config_sha256": "base-config",
                "base_sha": "base",
                "candidate_sha": "candidate",
                "base_jar_sha256": "base-jar",
                "candidate_jar_sha256": "candidate-jar",
                "candidate_isolation": {
                    "enabled": True,
                    "command_prefix": ["sudo", "-u", "candidate", "--"],
                    "file_user": "candidate",
                },
                "executions": executions,
            }
            manifest.write_text(json.dumps(manifest_value), encoding="utf-8")
            pairs, units, _ = gate.load_pairs(
                manifest,
                "ab",
                self.specs,
                "base-config",
                4,
                self.profile,
                ["-Xms1g", "-Xmx1g"],
            )
            self.assertEqual(8, len(pairs[self.benchmark_id]))
            self.assertEqual("ns/op", units[self.benchmark_id])

            original_runtime_temp = executions[1]["runtime_temp_dir"]
            executions[1]["runtime_temp_dir"] = executions[0]["runtime_temp_dir"]
            manifest.write_text(json.dumps(manifest_value), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "reused JMH runtime temp directory"):
                gate.load_pairs(
                    manifest,
                    "ab",
                    self.specs,
                    "base-config",
                    4,
                    self.profile,
                    ["-Xms1g", "-Xmx1g"],
                )
            executions[1]["runtime_temp_dir"] = original_runtime_temp

            executions[0]["position"] = 1
            manifest.write_text(json.dumps(manifest_value), encoding="utf-8")
            with self.assertRaises(ValueError):
                gate.load_pairs(
                    manifest,
                    "ab",
                    self.specs,
                    "base-config",
                    4,
                    self.profile,
                    ["-Xms1g", "-Xmx1g"],
                )

    def test_secondary_allocation_metric_is_loaded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = pathlib.Path(temporary) / "jmh.json"
            result.write_text(
                json.dumps(
                    [
                        {
                            "benchmark": self.name,
                            "primaryMetric": {"score": 42.0, "scoreUnit": "ns/op"},
                            "secondaryMetrics": {
                                "gc.alloc.rate.norm": {"score": 512.0, "scoreUnit": "B/op"}
                            },
                        }
                    ]
                ),
                encoding="utf-8",
            )
            metrics = gate.read_jmh_results(result, gate.sha256_file(result))[self.name]
            self.assertEqual(42.0, metrics["primary"]["score"])
            self.assertEqual(512.0, metrics["gc.alloc.rate.norm"]["score"])
            self.assertEqual("B/op", metrics["gc.alloc.rate.norm"]["unit"])

    def test_profile_requires_must_pass_metric_even_when_minimum_count_passes(self) -> None:
        profile = "snapshot"
        index = {entry["id"]: entry for entry in self.config["benchmarks"]}
        specs = [index[benchmark_id] for benchmark_id in self.config["profiles"][profile]["benchmark_ids"]]
        aa = {entry["id"]: [(100.0, 100.0)] * 8 for entry in specs}
        ab = {entry["id"]: [(100.0, 88.0)] * 8 for entry in specs}
        ab["snapshot.viewers128.time"] = [(100.0, 100.0)] * 8
        units = {entry["id"]: "ns/op" for entry in specs}
        decision = gate.evaluate(self.config, profile, specs, aa, ab, units)
        self.assertEqual(gate.INCONCLUSIVE, decision["state"])

    def test_any_profile_metric_with_strong_regression_fails(self) -> None:
        profile = "snapshot"
        index = {entry["id"]: entry for entry in self.config["benchmarks"]}
        specs = [index[benchmark_id] for benchmark_id in self.config["profiles"][profile]["benchmark_ids"]]
        aa = {entry["id"]: [(100.0, 100.0)] * 8 for entry in specs}
        ab = {entry["id"]: [(100.0, 88.0)] * 8 for entry in specs}
        ab["snapshot.viewers4.alloc"] = [(100.0, 112.0)] * 8
        units = {entry["id"]: "ns/op" for entry in specs}
        decision = gate.evaluate(self.config, profile, specs, aa, ab, units)
        self.assertEqual(gate.FAIL, decision["state"])

    def test_secondary_metrics_do_not_count_as_optimization_passes(self) -> None:
        profile = "snapshot"
        index = {entry["id"]: entry for entry in self.config["benchmarks"]}
        specs = [index[benchmark_id] for benchmark_id in self.config["profiles"][profile]["benchmark_ids"]]
        aa = {entry["id"]: [(100.0, 100.0)] * 8 for entry in specs}
        ab = {
            entry["id"]: [(100.0, 100.0 if entry.get("metric", "primary") == "primary" else 80.0)] * 8
            for entry in specs
        }
        units = {entry["id"]: "ns/op" for entry in specs}
        decision = gate.evaluate(self.config, profile, specs, aa, ab, units)
        self.assertEqual(gate.INCONCLUSIVE, decision["state"])

    def test_secondary_guardrails_allow_at_most_five_percent_regression(self) -> None:
        profile = "snapshot"
        index = {entry["id"]: entry for entry in self.config["benchmarks"]}
        specs = [index[benchmark_id] for benchmark_id in self.config["profiles"][profile]["benchmark_ids"]]
        aa = {entry["id"]: [(100.0, 100.0)] * 8 for entry in specs}
        units = {entry["id"]: "ns/op" for entry in specs}

        within_budget = {
            entry["id"]: [(100.0, 88.0 if entry.get("metric", "primary") == "primary" else 104.0)] * 8
            for entry in specs
        }
        accepted = gate.evaluate(self.config, profile, specs, aa, within_budget, units)
        self.assertEqual(gate.PASS_OPTIMIZED, accepted["state"])

        beyond_budget = {
            entry["id"]: [(100.0, 88.0 if entry.get("metric", "primary") == "primary" else 106.0)] * 8
            for entry in specs
        }
        rejected = gate.evaluate(self.config, profile, specs, aa, beyond_budget, units)
        self.assertEqual(gate.FAIL, rejected["state"])

    def test_global_states_use_explicit_three_state_semantics(self) -> None:
        self.assertEqual("PASS_OPTIMIZED", gate.PASS_OPTIMIZED)
        self.assertEqual("INCONCLUSIVE_NOISE_OR_NO_GAIN", gate.INCONCLUSIVE)
        self.assertEqual("FAIL_REGRESSION_OR_BEHAVIOR", gate.FAIL)

    def test_every_profile_has_valid_primary_thresholds(self) -> None:
        benchmark_index = {entry["id"]: entry for entry in self.config["benchmarks"]}
        for profile_name, profile in self.config["profiles"].items():
            with self.subTest(profile=profile_name):
                benchmark_specs = [benchmark_index[entry] for entry in profile["benchmark_ids"]]
                gate.validate_profile_config(self.config, profile_name, benchmark_specs)

    def test_profile_validation_rejects_minimum_above_primary_count(self) -> None:
        config = json.loads(json.dumps(self.config))
        profile_name = "display-spawn"
        config["profiles"][profile_name]["minimum_passes"] = 2
        benchmark_index = {entry["id"]: entry for entry in config["benchmarks"]}
        benchmark_specs = [
            benchmark_index[entry]
            for entry in config["profiles"][profile_name]["benchmark_ids"]
        ]
        with self.assertRaisesRegex(ValueError, "invalid primary minimum_passes"):
            gate.validate_profile_config(config, profile_name, benchmark_specs)


class MatrixScheduleTest(unittest.TestCase):
    def test_ab_schedule_is_exactly_four_interleaved_ab_and_ba_pairs(self) -> None:
        schedule = run_matrix.build_schedule("ab", 4)
        orders = [schedule[index]["order"] for index in range(0, len(schedule), 2)]
        self.assertEqual(["AB", "BA"] * 4, orders)
        for pair_index in range(8):
            pair = [entry for entry in schedule if entry["pair_index"] == pair_index]
            self.assertEqual({"base", "candidate"}, {entry["role"] for entry in pair})

    def test_aa_schedule_is_order_balanced(self) -> None:
        schedule = run_matrix.build_schedule("aa", 4)
        orders = [schedule[index]["order"] for index in range(0, len(schedule), 2)]
        self.assertEqual(["A1A2", "A2A1"] * 4, orders)

    def test_consecutive_pair_shards_preserve_complete_global_pairs(self) -> None:
        full = run_matrix.build_schedule("ab", 4)
        shards = [run_matrix.select_pair_range(full, start, 1) for start in range(8)]
        self.assertEqual(full, [entry for shard in shards for entry in shard])
        for shard_index, shard in enumerate(shards):
            self.assertEqual(
                [shard_index],
                sorted({entry["pair_index"] for entry in shard}),
            )
            self.assertEqual("AB" if shard_index % 2 == 0 else "BA", shard[0]["order"])

    def test_pair_range_defaults_to_every_remaining_complete_pair(self) -> None:
        full = run_matrix.build_schedule("ab", 4)
        self.assertEqual(full, run_matrix.select_pair_range(full))
        tail = run_matrix.select_pair_range(full, 6)
        self.assertEqual([6, 7], sorted({entry["pair_index"] for entry in tail}))

    def test_pair_range_rejects_empty_negative_or_overflowing_slices(self) -> None:
        full = run_matrix.build_schedule("ab", 4)
        for start, count in ((-1, 2), (0, 0), (7, 2), (8, 1)):
            with self.subTest(start=start, count=count):
                with self.assertRaises(ValueError):
                    run_matrix.select_pair_range(full, start, count)

    def test_partial_shard_requires_a_safe_runner_session_id(self) -> None:
        with self.assertRaisesRegex(ValueError, "required for a partial pair shard"):
            run_matrix.validate_runner_session_id(None, True)
        self.assertEqual(
            "run-42.shard-1",
            run_matrix.validate_runner_session_id("run-42.shard-1", True),
        )
        self.assertIsNone(run_matrix.validate_runner_session_id(None, False))
        with self.assertRaisesRegex(ValueError, "unsupported characters"):
            run_matrix.validate_runner_session_id("bad session", True)

    def test_pending_ray_profile_is_rejected_without_production_classes(self) -> None:
        config = json.loads((AB_DIR / "gate-config.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary:
            jar = pathlib.Path(temporary) / "benchmark.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("placeholder.txt", "not the production coordinator")
            with self.assertRaises(ValueError):
                run_matrix.verify_required_classes(jar, config["profiles"]["ray-proxy"])

    def test_fixed_jvm_options_are_appended_to_the_measurement_fork(self) -> None:
        config = {
            "jmh": {
                "include": "Benchmark",
                "mode": "avgt",
                "time_unit": "ns",
                "forks": 1,
                "warmup_iterations": 5,
                "measurement_iterations": 8,
                "warmup_time": "1s",
                "measurement_time": "1s",
                "force_gc": True,
                "profilers": ["gc"],
                "jvm_args": ["-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"],
            }
        }
        command = run_matrix.command_for(
            "java",
            pathlib.Path("bench.jar"),
            pathlib.Path("result.json"),
            config,
            pathlib.Path("/isolated/jmh-run"),
        )
        self.assertEqual(
            [
                "java",
                "-Djava.io.tmpdir=/isolated/jmh-run",
                "-jar",
                "bench.jar",
                "Benchmark",
            ],
            command[:5],
        )
        append_index = command.index("-jvmArgsAppend")
        self.assertEqual(
            "-Xms1g -Xmx1g -XX:+AlwaysPreTouch -Djava.io.tmpdir=/isolated/jmh-run",
            command[append_index + 1],
        )

    def test_candidate_command_prefix_wraps_only_the_requested_command(self) -> None:
        config = {
            "jmh": {
                "include": "Benchmark",
                "mode": "avgt",
                "time_unit": "ns",
                "forks": 1,
                "warmup_iterations": 5,
                "measurement_iterations": 8,
                "warmup_time": "1s",
                "measurement_time": "1s",
                "force_gc": True,
                "profilers": [],
                "jvm_args": ["-Xms1g"],
            }
        }
        command = run_matrix.command_for(
            "/jdk/bin/java",
            pathlib.Path("bench.jar"),
            pathlib.Path("result.json"),
            config,
            pathlib.Path("/isolated/candidate-run"),
            ["sudo", "-H", "-u", "mahjong-benchmark", "--"],
        )
        self.assertEqual(
            [
                "sudo",
                "-H",
                "-u",
                "mahjong-benchmark",
                "--",
                "/jdk/bin/java",
                "-Djava.io.tmpdir=/isolated/candidate-run",
                "-jar",
            ],
            command[:8],
        )

    def test_trusted_runtime_temp_must_be_new_and_private(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runtime_temp = pathlib.Path(temporary) / "execution"
            run_matrix.prepare_runtime_temp(runtime_temp, None)
            self.assertEqual(0o700, runtime_temp.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                run_matrix.prepare_runtime_temp(runtime_temp, None)

    def test_fork_log_must_confirm_every_fixed_jvm_option(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            log = pathlib.Path(temporary) / "jmh.log"
            log.write_text("# VM options: -Xms1g -Xmx1g -XX:+AlwaysPreTouch\n", encoding="utf-8")
            run_matrix.verify_fork_jvm_args(log, ["-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"])
            with self.assertRaises(ValueError):
                run_matrix.verify_fork_jvm_args(log, ["-Xms1g", "-Dfile.encoding=UTF-8"])


class ManifestMergeTest(unittest.TestCase):
    @staticmethod
    def write_shard(
        root: pathlib.Path,
        phase: str,
        pair_start: int,
        pair_count: int,
        runner_session_id: str,
        *,
        total_pairs: int = 8,
        candidate_sha: str = "candidate",
        directory_name: str | None = None,
    ) -> pathlib.Path:
        directory = root / (directory_name or f"{phase}-{pair_start}")
        raw = directory / "raw"
        logs = directory / "logs"
        raw.mkdir(parents=True)
        logs.mkdir()
        full_schedule = run_matrix.build_schedule(phase, total_pairs // 2)
        schedule = run_matrix.select_pair_range(full_schedule, pair_start, pair_count)
        executions = []
        for local_index, scheduled in enumerate(schedule):
            role = scheduled["role"]
            stem = (
                f"{phase}-pair-{scheduled['pair_index'] + 1:02d}-"
                f"pos-{scheduled['position'] + 1}-{role}"
            )
            result = raw / f"{stem}.json"
            result.write_text("[]\n", encoding="utf-8")
            log = logs / f"{stem}.log"
            log.write_text("# VM options: -Xms1g -Xmx1g\n", encoding="utf-8")
            is_candidate = role == "candidate"
            executions.append(
                {
                    **scheduled,
                    "execution_index": local_index,
                    "revision_sha": candidate_sha if is_candidate else "base",
                    "jar_sha256": "candidate-jar" if is_candidate else "base-jar",
                    "fork_jvm_args_verified": True,
                    "validation_error": None,
                    "isolated_candidate": is_candidate,
                    "result_mode_after_lock": 0o444,
                    "log_mode_after_lock": 0o444,
                    "started_at": "2026-01-01T00:00:00Z",
                    "completed_at": "2026-01-01T00:00:01Z",
                    "elapsed_seconds": 1.0,
                    "exit_code": 0,
                    "result_file": result.relative_to(directory).as_posix(),
                    "result_sha256": run_matrix.sha256_file(result),
                    "log_file": log.relative_to(directory).as_posix(),
                    "log_sha256": run_matrix.sha256_file(log),
                    "runtime_temp_dir": f"/tmp/{runner_session_id}/{stem}",
                    "command": ["java", f"-Djava.io.tmpdir=/tmp/{runner_session_id}/{stem}"],
                }
            )
        manifest = {
            "schema_version": 1,
            "phase": phase,
            "profile": "infra",
            "created_at": "2026-01-01T00:00:00Z",
            "completed_at": "2026-01-01T00:01:00Z",
            "base_sha": "base",
            "candidate_sha": candidate_sha,
            "config_path": "/base/perf/ab/gate-config.json",
            "config_sha256": "base-config",
            "base_jar_sha256": "base-jar",
            "candidate_jar_sha256": "base-jar" if phase == "aa" else "candidate-jar",
            "environment": {"runner": runner_session_id},
            "jmh": {"mode": "avgt", "jvm_args": ["-Xms1g", "-Xmx1g"]},
            "ci_overrides": {},
            "candidate_isolation": {
                "enabled": True,
                "command_prefix": ["sudo", "-u", "candidate", "--"],
                "file_user": "candidate",
            },
            "runner_session_id": runner_session_id,
            "pair_range": {
                "start": pair_start,
                "count": pair_count,
                "end_exclusive": pair_start + pair_count,
                "total_pairs": total_pairs,
            },
            "schedule": schedule,
            "executions": executions,
        }
        manifest_path = directory / "run-manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return manifest_path

    @classmethod
    def write_matrix_shards(
        cls,
        root: pathlib.Path,
        phase: str,
        *,
        prefix: str = "",
        sessions: list[str] | None = None,
        omit: set[int] | None = None,
        candidate_sha_overrides: dict[int, str] | None = None,
    ) -> list[pathlib.Path]:
        runner_sessions = sessions or [f"runner-{index}" for index in range(8)]
        if len(runner_sessions) != 8:
            raise ValueError("tests require one runner session for each of eight pairs")
        omitted = omit or set()
        overrides = candidate_sha_overrides or {}
        return [
            cls.write_shard(
                root,
                phase,
                pair_index,
                1,
                runner_sessions[pair_index],
                candidate_sha=overrides.get(pair_index, "candidate"),
                directory_name=f"{prefix}{phase}-{pair_index}",
            )
            for pair_index in range(8)
            if pair_index not in omitted
        ]

    def test_merges_consecutive_shards_into_one_gate_compatible_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            manifests = self.write_matrix_shards(root, "ab")
            output = root / "merged-ab"
            merged_path = merge_manifests.merge(manifests, output)
            merged = json.loads(merged_path.read_text(encoding="utf-8"))

            self.assertEqual(
                {"start": 0, "count": 8, "end_exclusive": 8, "total_pairs": 8},
                merged["pair_range"],
            )
            self.assertEqual(
                [f"runner-{index}" for index in range(8)],
                merged["runner_session_ids"],
            )
            self.assertEqual(list(range(16)), [entry["execution_index"] for entry in merged["executions"]])
            self.assertEqual(
                [pair_index for pair_index in range(8) for _ in range(2)],
                [entry["pair_index"] for entry in merged["executions"]],
            )
            self.assertEqual(16, len(list((output / "raw").glob("*.json"))))
            self.assertEqual(16, len(list((output / "logs").glob("*.log"))))

    def test_rejects_duplicate_or_missing_pair_ranges(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            duplicate = [
                self.write_shard(root, "ab", 0, 1, "runner-0", directory_name="first"),
                self.write_shard(root, "ab", 0, 1, "runner-1", directory_name="duplicate"),
            ]
            with self.assertRaisesRegex(ValueError, "overlaps"):
                merge_manifests.merge(duplicate, root / "duplicate-output")

            missing = self.write_matrix_shards(root, "ab", prefix="missing-", omit={2})
            with self.assertRaisesRegex(ValueError, "missing pair range starting at 2"):
                merge_manifests.merge(missing, root / "missing-output")

    def test_rejects_cross_shard_identity_changes_and_reused_runner_sessions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            changed_identity = self.write_matrix_shards(
                root,
                "ab",
                prefix="identity-",
                candidate_sha_overrides={7: "different-candidate"},
            )
            with self.assertRaisesRegex(ValueError, "candidate_sha"):
                merge_manifests.merge(changed_identity, root / "identity-output")

            reused_session = self.write_matrix_shards(
                root,
                "ab",
                prefix="session-",
                sessions=["same-runner", "same-runner", *[f"runner-{index}" for index in range(2, 8)]],
            )
            with self.assertRaisesRegex(ValueError, "reused across shards"):
                merge_manifests.merge(reused_session, root / "session-output")

    def test_cross_phase_validation_requires_the_same_session_for_each_pair_range(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            aa = merge_manifests.merge(
                self.write_matrix_shards(root, "aa", prefix="good-"),
                root / "merged-aa",
            )
            ab = merge_manifests.merge(
                self.write_matrix_shards(root, "ab", prefix="good-"),
                root / "merged-ab",
                aa,
            )
            merge_manifests.validate_cross_phase_sessions(
                json.loads(aa.read_text(encoding="utf-8")),
                json.loads(ab.read_text(encoding="utf-8")),
            )

            mismatched = self.write_matrix_shards(
                root,
                "ab",
                prefix="bad-",
                sessions=[*[f"runner-{index}" for index in range(7)], "other-runner"],
            )
            with self.assertRaisesRegex(ValueError, "runner sessions differ"):
                merge_manifests.merge(mismatched, root / "bad-merged-ab", aa)

    def test_rejects_tampered_raw_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            manifests = self.write_matrix_shards(root, "ab")
            raw_file = next((manifests[0].parent / "raw").glob("*.json"))
            raw_file.write_text("tampered\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                merge_manifests.merge(manifests, root / "tampered-output")


class ProtectedPathTest(unittest.TestCase):
    def test_candidate_change_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            base = root / "base"
            candidate = root / "candidate"
            for checkout, value in ((base, "base\n"), (candidate, "changed\n")):
                path = checkout / "perf" / "ab" / "gate-config.json"
                path.parent.mkdir(parents=True)
                path.write_text(value, encoding="utf-8")
            protected = ["perf/ab"]
            self.assertNotEqual(
                verify_protected.entries(base, protected),
                verify_protected.entries(candidate, protected),
            )

    def test_identical_candidate_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            base = root / "base"
            candidate = root / "candidate"
            for checkout in (base, candidate):
                path = checkout / "src" / "perfTest" / "Harness.java"
                path.parent.mkdir(parents=True)
                path.write_text("final class Harness {}\n", encoding="utf-8")
            protected = ["src/perfTest"]
            self.assertEqual(
                verify_protected.entries(base, protected),
                verify_protected.entries(candidate, protected),
            )

    def test_candidate_cannot_add_unprotected_groovy_build_variants(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            base = root / "base"
            candidate = root / "candidate"
            base.mkdir()
            candidate.mkdir()
            (candidate / "build.gradle").write_text("throw new Error('candidate code')\n", encoding="utf-8")
            protected = ["build.gradle", "settings.gradle", "gradle.lockfile"]
            self.assertNotEqual(
                verify_protected.entries(base, protected),
                verify_protected.entries(candidate, protected),
            )


if __name__ == "__main__":
    unittest.main()
