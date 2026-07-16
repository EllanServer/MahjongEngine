#!/usr/bin/env python3
"""Run an order-balanced JMH A/A or A/B matrix and retain raw evidence."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
import pathlib
import platform
import re
import stat
import subprocess
import sys
import time
import zipfile
from typing import Any


SCHEMA_VERSION = 1
TIME_PATTERN = re.compile(r"^[1-9][0-9]*(?:ns|us|ms|s)$")
USER_PATTERN = re.compile(r"^[a-z_][a-z0-9_-]{0,31}$")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def build_schedule(phase: str, repetitions_per_order: int) -> list[dict[str, Any]]:
    if repetitions_per_order <= 0:
        raise ValueError("repetitions_per_order must be positive")
    if phase == "ab":
        forward = ("base", "candidate")
        reverse = ("candidate", "base")
        forward_name = "AB"
        reverse_name = "BA"
    elif phase == "aa":
        forward = ("baseline_a", "baseline_b")
        reverse = ("baseline_b", "baseline_a")
        forward_name = "A1A2"
        reverse_name = "A2A1"
    else:
        raise ValueError(f"unsupported phase: {phase}")

    schedule: list[dict[str, Any]] = []
    for pair_index in range(repetitions_per_order * 2):
        # Interleave forward/reverse pairs so temporal runner drift is counterbalanced as
        # AB, BA, AB, BA (flattened execution order: ABBA, ABBA).
        is_forward = pair_index % 2 == 0
        roles = forward if is_forward else reverse
        order = forward_name if is_forward else reverse_name
        for position, role in enumerate(roles):
            schedule.append(
                {
                    "pair_index": pair_index,
                    "order": order,
                    "position": position,
                    "role": role,
                }
            )
    return schedule


def java_version(java: str) -> str:
    completed = subprocess.run(
        [java, "-version"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return completed.stdout.strip()


def validate_jmh_config(config: dict[str, Any]) -> None:
    jmh = config["jmh"]
    for key in ("forks", "warmup_iterations", "measurement_iterations"):
        if not isinstance(jmh[key], int) or jmh[key] <= 0:
            raise ValueError(f"jmh.{key} must be a positive integer")
    for key in ("warmup_time", "measurement_time"):
        if not TIME_PATTERN.fullmatch(jmh[key]):
            raise ValueError(f"jmh.{key} has unsupported duration: {jmh[key]}")
    if not all(isinstance(value, str) and value.startswith("-") for value in jmh.get("jvm_args", [])):
        raise ValueError("jmh.jvm_args must contain JVM option strings")


def command_for(
    java: str,
    jar: pathlib.Path,
    result_file: pathlib.Path,
    config: dict[str, Any],
    command_prefix: list[str] | None = None,
) -> list[str]:
    jmh = config["jmh"]
    fork_jvm_args = jmh.get("jvm_args", [])
    command = [*(command_prefix or []), java, "-jar", str(jar), jmh["include"]]
    command.extend(
        [
            "-bm",
            jmh["mode"],
            "-tu",
            jmh["time_unit"],
            "-f",
            str(jmh["forks"]),
            "-wi",
            str(jmh["warmup_iterations"]),
            "-i",
            str(jmh["measurement_iterations"]),
            "-w",
            jmh["warmup_time"],
            "-r",
            jmh["measurement_time"],
            "-gc",
            str(bool(jmh.get("force_gc", True))).lower(),
            "-foe",
            "true",
            "-rf",
            "json",
            "-rff",
            str(result_file),
            "-v",
            "NORMAL",
        ]
    )
    if fork_jvm_args:
        command.extend(["-jvmArgsAppend", " ".join(fork_jvm_args)])
    for profiler in jmh.get("profilers", []):
        command.extend(["-prof", profiler])
    return command


def candidate_isolation(args: argparse.Namespace) -> tuple[list[str], str | None]:
    prefix_json = args.candidate_command_prefix_json
    file_user = args.candidate_file_user
    if (prefix_json is None) != (file_user is None):
        raise ValueError("candidate command prefix and file user must be configured together")
    if prefix_json is None:
        return [], None
    try:
        prefix = json.loads(prefix_json)
    except json.JSONDecodeError as error:
        raise ValueError("candidate command prefix must be a JSON string array") from error
    if not isinstance(prefix, list) or not prefix or not all(isinstance(value, str) and value for value in prefix):
        raise ValueError("candidate command prefix must be a non-empty JSON string array")
    if not USER_PATTERN.fullmatch(file_user):
        raise ValueError("candidate file user has an unsafe name")
    if os.name != "posix":
        raise ValueError("candidate user isolation is supported only on POSIX runners")
    return prefix, file_user


def prepare_untrusted_result(path: pathlib.Path, file_user: str) -> None:
    if path.exists():
        path.unlink()
    path.touch(mode=0o600)
    path.chmod(0o600)
    subprocess.run(["sudo", "chown", "--", file_user, str(path)], check=True)


def reclaim_untrusted_result(path: pathlib.Path, file_user: str) -> None:
    # A candidate can daemonize while retaining an open writable descriptor. Kill every
    # process under the dedicated UID before making the result immutable and hashing it.
    subprocess.run(
        ["sudo", "pkill", "-KILL", "-u", file_user],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(100):
        remaining = subprocess.run(
            ["sudo", "pgrep", "-u", file_user],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if remaining.returncode == 1:
            break
        if remaining.returncode > 1:
            raise RuntimeError(f"could not audit candidate processes for user {file_user}")
        time.sleep(0.01)
    else:
        raise RuntimeError(f"candidate processes survived SIGKILL for user {file_user}")
    subprocess.run(
        ["sudo", "chown", "--", f"{os.getuid()}:{os.getgid()}", str(path)],
        check=True,
    )
    path.chmod(0o444)


def verify_fork_jvm_args(log_file: pathlib.Path, expected_args: list[str]) -> None:
    if not expected_args:
        return
    lines = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
    option_lines = [line for line in lines if line.startswith("# VM options:")]
    if not option_lines:
        raise ValueError(f"JMH log does not report fork VM options: {log_file}")
    reported = "\n".join(option_lines)
    missing = [argument for argument in expected_args if argument not in reported]
    if missing:
        raise ValueError(f"JMH fork omitted fixed VM options {missing}: {log_file}")


def verify_jars_unchanged(expected_hashes: dict[pathlib.Path, str]) -> None:
    for jar, expected_hash in expected_hashes.items():
        if not jar.is_file() or sha256_file(jar) != expected_hash:
            raise ValueError(f"benchmark jar changed during the matrix: {jar}")


def verify_required_classes(jar: pathlib.Path, profile: dict[str, Any]) -> None:
    required = profile.get("required_classes", [])
    if not required:
        return
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
    missing = [name for name in required if name not in names]
    if missing:
        raise ValueError(
            f"profile requires production classes missing from {jar.name}: {', '.join(missing)}"
        )


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def run(args: argparse.Namespace) -> int:
    config_path = args.config.resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))
    if config.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unsupported config schema: {config.get('schema_version')}")
    if args.profile not in config.get("profiles", {}):
        raise ValueError(f"unknown benchmark profile: {args.profile}")
    profile = config["profiles"][args.profile]
    effective_config = copy.deepcopy(config)
    effective_config["jmh"]["include"] = profile["include"]
    overrides = {
        "forks": args.forks,
        "warmup_iterations": args.warmup_iterations,
        "measurement_iterations": args.measurement_iterations,
        "warmup_time": args.warmup_time,
        "measurement_time": args.measurement_time,
    }
    for key, value in overrides.items():
        if value is not None:
            effective_config["jmh"][key] = value
    validate_jmh_config(effective_config)
    candidate_prefix, candidate_file_user = candidate_isolation(args)

    base_jar = args.base_jar.resolve()
    candidate_jar = args.candidate_jar.resolve()
    for jar in (base_jar, candidate_jar):
        if not jar.is_file():
            raise FileNotFoundError(jar)
        verify_required_classes(jar, profile)
    expected_jar_hashes = {
        base_jar: sha256_file(base_jar),
        candidate_jar: sha256_file(candidate_jar),
    }

    output_dir = args.output_dir.resolve()
    raw_dir = output_dir / "raw"
    log_dir = output_dir / "logs"
    raw_dir.mkdir(parents=True, exist_ok=True)
    log_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "run-manifest.json"
    repetitions = config["matrix"]["repetitions_per_order"]
    schedule = build_schedule(args.phase, repetitions)
    jar_by_role = {
        "base": base_jar,
        "candidate": candidate_jar,
        "baseline_a": base_jar,
        "baseline_b": base_jar,
    }
    revision_by_role = {
        "base": args.base_sha,
        "candidate": args.candidate_sha,
        "baseline_a": args.base_sha,
        "baseline_b": args.base_sha,
    }

    manifest: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "phase": args.phase,
        "profile": args.profile,
        "created_at": utc_now(),
        "completed_at": None,
        "base_sha": args.base_sha,
        "candidate_sha": args.candidate_sha,
        "config_path": str(config_path),
        "config_sha256": sha256_file(config_path),
        "base_jar_sha256": expected_jar_hashes[base_jar],
        "candidate_jar_sha256": expected_jar_hashes[candidate_jar],
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "logical_cpu_count": os.cpu_count(),
            "python": sys.version,
            "java": java_version(args.java),
            "github_runner_name": os.environ.get("RUNNER_NAME"),
            "github_run_id": os.environ.get("GITHUB_RUN_ID"),
            "github_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        },
        "jmh": effective_config["jmh"],
        "ci_overrides": {key: value for key, value in overrides.items() if value is not None},
        "candidate_isolation": {
            "enabled": bool(candidate_prefix),
            "command_prefix": candidate_prefix,
            "file_user": candidate_file_user,
        },
        "schedule": schedule,
        "executions": [],
    }
    write_json(manifest_path, manifest)

    for execution_index, entry in enumerate(schedule):
        verify_jars_unchanged(expected_jar_hashes)
        role = entry["role"]
        jar = jar_by_role[role]
        stem = (
            f"{args.phase}-pair-{entry['pair_index'] + 1:02d}-"
            f"pos-{entry['position'] + 1}-{role}"
        )
        result_file = raw_dir / f"{stem}.json"
        log_file = log_dir / f"{stem}.log"
        isolated_candidate = role == "candidate" and candidate_file_user is not None
        if result_file.exists():
            result_file.chmod(0o600)
            result_file.unlink()
        if log_file.exists():
            log_file.chmod(0o600)
        if isolated_candidate:
            prepare_untrusted_result(result_file, candidate_file_user)
        command = command_for(
            args.java,
            jar,
            result_file,
            effective_config,
            candidate_prefix if isolated_candidate else None,
        )
        started_at = utc_now()
        started_ns = time.monotonic_ns()
        try:
            with log_file.open("w", encoding="utf-8", newline="\n") as log:
                completed = subprocess.run(
                    command,
                    cwd=jar.parent,
                    check=False,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    text=True,
                )
        finally:
            if isolated_candidate:
                reclaim_untrusted_result(result_file, candidate_file_user)
        log_file.chmod(0o444)
        if result_file.is_file() and not isolated_candidate:
            result_file.chmod(0o444)
        elapsed_seconds = (time.monotonic_ns() - started_ns) / 1_000_000_000.0
        run_succeeded = completed.returncode == 0 and result_file.is_file()
        fork_jvm_args_verified = False
        validation_error = None
        try:
            if run_succeeded:
                verify_fork_jvm_args(log_file, effective_config["jmh"].get("jvm_args", []))
                fork_jvm_args_verified = True
            verify_jars_unchanged(expected_jar_hashes)
        except ValueError as error:
            validation_error = str(error)
        record = {
            **entry,
            "execution_index": execution_index,
            "revision_sha": revision_by_role[role],
            "jar_sha256": expected_jar_hashes[jar],
            "fork_jvm_args_verified": fork_jvm_args_verified,
            "validation_error": validation_error,
            "isolated_candidate": isolated_candidate,
            "result_mode_after_lock": (
                stat.S_IMODE(result_file.stat().st_mode) if result_file.is_file() else None
            ),
            "log_mode_after_lock": stat.S_IMODE(log_file.stat().st_mode),
            "started_at": started_at,
            "completed_at": utc_now(),
            "elapsed_seconds": elapsed_seconds,
            "exit_code": completed.returncode,
            "result_file": result_file.relative_to(output_dir).as_posix(),
            "result_sha256": sha256_file(result_file) if result_file.is_file() else None,
            "log_file": log_file.relative_to(output_dir).as_posix(),
            "log_sha256": sha256_file(log_file),
            "command": command,
        }
        manifest["executions"].append(record)
        write_json(manifest_path, manifest)
        if not run_succeeded:
            raise RuntimeError(f"JMH execution failed for {stem}; see {log_file}")
        if validation_error is not None:
            raise RuntimeError(f"JMH execution evidence failed validation: {validation_error}")

    manifest["completed_at"] = utc_now()
    write_json(manifest_path, manifest)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("aa", "ab"), required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--base-jar", type=pathlib.Path, required=True)
    parser.add_argument("--candidate-jar", type=pathlib.Path, required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--forks", type=int)
    parser.add_argument("--warmup-iterations", type=int)
    parser.add_argument("--measurement-iterations", type=int)
    parser.add_argument("--warmup-time")
    parser.add_argument("--measurement-time")
    parser.add_argument("--candidate-command-prefix-json")
    parser.add_argument("--candidate-file-user")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        raise SystemExit(run(parse_args()))
    except Exception as error:  # Keep a concise error beside the retained logs/partial manifest.
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
