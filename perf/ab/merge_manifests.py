#!/usr/bin/env python3
"""Safely merge consecutive run-matrix shards into one gate-compatible manifest."""

from __future__ import annotations

import argparse
import copy
import json
import pathlib
import shutil
import tempfile
from dataclasses import dataclass
from typing import Any

import run_matrix


INVARIANT_FIELDS = (
    "schema_version",
    "phase",
    "profile",
    "base_sha",
    "candidate_sha",
    "config_sha256",
    "base_jar_sha256",
    "candidate_jar_sha256",
    "jmh",
    "ci_overrides",
    "candidate_isolation",
)


@dataclass(frozen=True)
class Shard:
    path: pathlib.Path
    digest: str
    payload: dict[str, Any]
    pair_start: int
    pair_count: int
    total_pairs: int

    @property
    def pair_end(self) -> int:
        return self.pair_start + self.pair_count


def require_integer(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def evidence_path(
    manifest_path: pathlib.Path,
    relative_value: Any,
    expected_directory: str,
    expected_suffix: str,
) -> pathlib.Path:
    if not isinstance(relative_value, str) or not relative_value:
        raise ValueError(f"manifest has an invalid {expected_directory} evidence path")
    relative = pathlib.Path(relative_value)
    if (
        relative.is_absolute()
        or len(relative.parts) != 2
        or relative.parts[0] != expected_directory
        or relative.suffix != expected_suffix
        or any(part in ("", ".", "..") for part in relative.parts)
    ):
        raise ValueError(f"unsafe {expected_directory} evidence path: {relative_value}")
    root = manifest_path.parent.resolve()
    requested = root / relative
    if requested.is_symlink() or requested.parent.is_symlink():
        raise ValueError(f"unsafe {expected_directory} evidence file: {relative_value}")
    source = requested.resolve(strict=True)
    if root != source.parent.parent or not source.is_file():
        raise ValueError(f"unsafe {expected_directory} evidence file: {relative_value}")
    return source


def verify_digest(path: pathlib.Path, expected: Any, label: str) -> str:
    if not isinstance(expected, str) or len(expected) != 64:
        raise ValueError(f"{label} omitted a valid SHA-256 digest")
    actual = run_matrix.sha256_file(path)
    if actual != expected:
        raise ValueError(f"{label} digest mismatch: {path}")
    return actual


def load_shard(path: pathlib.Path) -> Shard:
    requested = path.absolute()
    if requested.is_symlink() or not requested.is_file():
        raise FileNotFoundError(f"shard manifest is not a regular file: {requested}")
    manifest_path = requested.resolve()
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"shard manifest is not an object: {manifest_path}")
    if payload.get("schema_version") != run_matrix.SCHEMA_VERSION:
        raise ValueError(f"unsupported shard manifest schema: {manifest_path}")
    if not payload.get("created_at") or not payload.get("completed_at"):
        raise ValueError(f"shard manifest is incomplete: {manifest_path}")
    pair_range = payload.get("pair_range")
    if not isinstance(pair_range, dict):
        raise ValueError(f"shard manifest omitted pair_range: {manifest_path}")
    pair_start = require_integer(pair_range.get("start"), "pair_range.start")
    pair_count = require_integer(pair_range.get("count"), "pair_range.count")
    pair_end = require_integer(pair_range.get("end_exclusive"), "pair_range.end_exclusive")
    total_pairs = require_integer(pair_range.get("total_pairs"), "pair_range.total_pairs")
    if pair_start < 0 or pair_count <= 0 or total_pairs <= 0:
        raise ValueError(f"invalid pair_range in {manifest_path}")
    if pair_end != pair_start + pair_count or pair_end > total_pairs:
        raise ValueError(f"inconsistent pair_range in {manifest_path}")
    return Shard(
        path=manifest_path,
        digest=run_matrix.sha256_file(manifest_path),
        payload=payload,
        pair_start=pair_start,
        pair_count=pair_count,
        total_pairs=total_pairs,
    )


def validate_shard_schedule(shard: Shard, full_schedule: list[dict[str, Any]]) -> None:
    expected = run_matrix.select_pair_range(
        full_schedule,
        shard.pair_start,
        shard.pair_count,
    )
    if shard.payload.get("schedule") != expected:
        raise ValueError(f"shard schedule does not match its declared pair range: {shard.path}")
    executions = shard.payload.get("executions")
    if not isinstance(executions, list) or len(executions) != len(expected):
        raise ValueError(f"shard has incomplete executions: {shard.path}")
    for local_index, (execution, scheduled) in enumerate(zip(executions, expected, strict=True)):
        if not isinstance(execution, dict):
            raise ValueError(f"shard execution {local_index} is not an object: {shard.path}")
        for field in ("pair_index", "order", "position", "role"):
            if execution.get(field) != scheduled[field]:
                raise ValueError(
                    f"shard execution {local_index} changed scheduled {field}: {shard.path}"
                )
        if execution.get("execution_index") != local_index:
            raise ValueError(f"shard execution indices are not sequential: {shard.path}")
        if (
            execution.get("exit_code") != 0
            or execution.get("fork_jvm_args_verified") is not True
            or execution.get("validation_error") is not None
        ):
            raise ValueError(f"shard contains unvalidated execution {local_index}: {shard.path}")
        if execution.get("result_mode_after_lock") != 0o444:
            raise ValueError(f"shard result was not locked read-only: {shard.path}")
        if execution.get("log_mode_after_lock") != 0o444:
            raise ValueError(f"shard log was not locked read-only: {shard.path}")


def validate_shards(shards: list[Shard]) -> tuple[list[Shard], list[dict[str, Any]]]:
    if not shards:
        raise ValueError("at least one shard manifest is required")
    if len({shard.path for shard in shards}) != len(shards):
        raise ValueError("the same shard manifest was supplied more than once")
    ordered = sorted(shards, key=lambda shard: shard.pair_start)
    first = ordered[0]
    if first.total_pairs % 2 != 0:
        raise ValueError("total_pairs must contain equal forward and reverse orders")
    full_schedule = run_matrix.build_schedule(
        str(first.payload.get("phase")),
        first.total_pairs // 2,
    )
    cursor = 0
    runner_session_ids: set[str] = set()
    for shard in ordered:
        for field in INVARIANT_FIELDS:
            if shard.payload.get(field) != first.payload.get(field):
                raise ValueError(f"shard invariant {field} differs: {shard.path}")
        if shard.total_pairs != first.total_pairs:
            raise ValueError(f"shard invariant total_pairs differs: {shard.path}")
        if shard.pair_start < cursor:
            raise ValueError(f"shard pair range overlaps pair {shard.pair_start}: {shard.path}")
        if shard.pair_start > cursor:
            raise ValueError(f"missing pair range starting at {cursor}")
        sharded = shard.pair_start != 0 or shard.pair_count != shard.total_pairs
        runner_session_id = run_matrix.validate_runner_session_id(
            shard.payload.get("runner_session_id"),
            sharded,
        )
        if runner_session_id is not None:
            if runner_session_id in runner_session_ids:
                raise ValueError(f"runner_session_id is reused across shards: {runner_session_id}")
            runner_session_ids.add(runner_session_id)
        validate_shard_schedule(shard, full_schedule)
        cursor = shard.pair_end
    if cursor != first.total_pairs:
        raise ValueError(f"missing pair range starting at {cursor}")
    return ordered, full_schedule


def copy_verified(source: pathlib.Path, target: pathlib.Path, expected_sha256: str) -> None:
    verify_digest(source, expected_sha256, "source evidence")
    target.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as input_stream, target.open("xb") as output_stream:
        shutil.copyfileobj(input_stream, output_stream, length=1024 * 1024)
    target.chmod(0o444)
    verify_digest(target, expected_sha256, "copied evidence")


def build_merged_manifest(
    ordered: list[Shard],
    full_schedule: list[dict[str, Any]],
    destination: pathlib.Path,
) -> dict[str, Any]:
    first = ordered[0]
    merged_executions: list[dict[str, Any]] = []
    runtime_temp_dirs: set[str] = set()
    evidence_names: set[tuple[str, str]] = set()
    shard_provenance: list[dict[str, Any]] = []
    for shard_index, shard in enumerate(ordered):
        shard_provenance.append(
            {
                "index": shard_index,
                "manifest_sha256": shard.digest,
                "pair_range": copy.deepcopy(shard.payload["pair_range"]),
                "created_at": shard.payload.get("created_at"),
                "completed_at": shard.payload.get("completed_at"),
                "runner_session_id": shard.payload.get("runner_session_id"),
                "environment": copy.deepcopy(shard.payload.get("environment", {})),
            }
        )
        for execution in shard.payload["executions"]:
            runtime_temp_dir = execution.get("runtime_temp_dir")
            if not isinstance(runtime_temp_dir, str) or not runtime_temp_dir:
                raise ValueError(f"execution omitted runtime_temp_dir: {shard.path}")
            if runtime_temp_dir in runtime_temp_dirs:
                raise ValueError(f"runtime_temp_dir is reused across shards: {runtime_temp_dir}")
            runtime_temp_dirs.add(runtime_temp_dir)

            result_source = evidence_path(shard.path, execution.get("result_file"), "raw", ".json")
            log_source = evidence_path(shard.path, execution.get("log_file"), "logs", ".log")
            result_name = ("raw", result_source.name)
            log_name = ("logs", log_source.name)
            for evidence_name in (result_name, log_name):
                if evidence_name in evidence_names:
                    raise ValueError(f"duplicate evidence destination: {'/'.join(evidence_name)}")
                evidence_names.add(evidence_name)

            result_digest = verify_digest(
                result_source,
                execution.get("result_sha256"),
                "result evidence",
            )
            log_digest = verify_digest(
                log_source,
                execution.get("log_sha256"),
                "log evidence",
            )
            copy_verified(result_source, destination / "raw" / result_source.name, result_digest)
            copy_verified(log_source, destination / "logs" / log_source.name, log_digest)

            merged_execution = copy.deepcopy(execution)
            merged_execution["source_manifest_sha256"] = shard.digest
            merged_execution["source_execution_index"] = execution["execution_index"]
            merged_execution["execution_index"] = len(merged_executions)
            merged_execution["result_file"] = f"raw/{result_source.name}"
            merged_execution["log_file"] = f"logs/{log_source.name}"
            merged_executions.append(merged_execution)

    return {
        "schema_version": first.payload["schema_version"],
        "phase": first.payload["phase"],
        "profile": first.payload["profile"],
        "created_at": min(str(shard.payload.get("created_at")) for shard in ordered),
        "completed_at": max(str(shard.payload.get("completed_at")) for shard in ordered),
        "base_sha": first.payload["base_sha"],
        "candidate_sha": first.payload["candidate_sha"],
        "config_path": first.payload.get("config_path"),
        "config_sha256": first.payload["config_sha256"],
        "base_jar_sha256": first.payload["base_jar_sha256"],
        "candidate_jar_sha256": first.payload["candidate_jar_sha256"],
        "environment": {
            "sharded": len(ordered) > 1,
            "shards": [copy.deepcopy(entry["environment"]) for entry in shard_provenance],
        },
        "jmh": copy.deepcopy(first.payload["jmh"]),
        "ci_overrides": copy.deepcopy(first.payload.get("ci_overrides", {})),
        "candidate_isolation": copy.deepcopy(first.payload["candidate_isolation"]),
        "runner_session_ids": [
            entry["runner_session_id"]
            for entry in shard_provenance
            if entry["runner_session_id"] is not None
        ],
        "pair_range": {
            "start": 0,
            "count": first.total_pairs,
            "end_exclusive": first.total_pairs,
            "total_pairs": first.total_pairs,
        },
        "schedule": copy.deepcopy(full_schedule),
        "executions": merged_executions,
        "source_shards": shard_provenance,
    }


def shard_session_index(payload: dict[str, Any], label: str) -> dict[tuple[int, int, int], str]:
    source_shards = payload.get("source_shards")
    if not isinstance(source_shards, list) or not source_shards:
        raise ValueError(f"{label} manifest has no source_shards metadata")
    sessions: dict[tuple[int, int, int], str] = {}
    seen_session_ids: set[str] = set()
    for entry in source_shards:
        if not isinstance(entry, dict) or not isinstance(entry.get("pair_range"), dict):
            raise ValueError(f"{label} manifest has malformed source_shards metadata")
        pair_range = entry["pair_range"]
        start = require_integer(pair_range.get("start"), f"{label} pair_range.start")
        count = require_integer(pair_range.get("count"), f"{label} pair_range.count")
        total = require_integer(pair_range.get("total_pairs"), f"{label} pair_range.total_pairs")
        session_id = run_matrix.validate_runner_session_id(
            entry.get("runner_session_id"),
            True,
        )
        key = (start, count, total)
        if key in sessions:
            raise ValueError(f"{label} manifest repeats pair range {key}")
        if session_id in seen_session_ids:
            raise ValueError(f"{label} manifest reuses runner session {session_id}")
        sessions[key] = session_id
        seen_session_ids.add(session_id)
    return sessions


def validate_cross_phase_sessions(
    first_payload: dict[str, Any],
    second_payload: dict[str, Any],
) -> None:
    by_phase = {
        first_payload.get("phase"): first_payload,
        second_payload.get("phase"): second_payload,
    }
    if set(by_phase) != {"aa", "ab"}:
        raise ValueError("cross-phase validation requires one A/A and one A/B manifest")
    aa_payload = by_phase["aa"]
    ab_payload = by_phase["ab"]
    for field in (
        "schema_version",
        "profile",
        "base_sha",
        "candidate_sha",
        "config_sha256",
        "base_jar_sha256",
        "jmh",
        "ci_overrides",
    ):
        if aa_payload.get(field) != ab_payload.get(field):
            raise ValueError(f"cross-phase invariant {field} differs")
    aa_sessions = shard_session_index(aa_payload, "A/A")
    ab_sessions = shard_session_index(ab_payload, "A/B")
    if aa_sessions.keys() != ab_sessions.keys():
        raise ValueError("A/A and A/B shard pair ranges differ")
    for pair_range, aa_session in aa_sessions.items():
        if ab_sessions[pair_range] != aa_session:
            raise ValueError(
                f"A/A and A/B runner sessions differ for pair range {pair_range}"
            )


def read_merged_manifest(path: pathlib.Path) -> dict[str, Any]:
    requested = path.absolute()
    if requested.is_symlink() or not requested.is_file():
        raise FileNotFoundError(f"peer manifest is not a regular file: {requested}")
    payload = json.loads(requested.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"peer manifest is not an object: {requested}")
    return payload


def merge(
    manifest_paths: list[pathlib.Path],
    output_dir: pathlib.Path,
    peer_manifest: pathlib.Path | None = None,
) -> pathlib.Path:
    output = output_dir.resolve()
    if output.exists():
        raise FileExistsError(f"merge output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    ordered, full_schedule = validate_shards([load_shard(path) for path in manifest_paths])
    temporary = pathlib.Path(
        tempfile.mkdtemp(prefix=f".{output.name}.merge-", dir=output.parent)
    )
    try:
        manifest = build_merged_manifest(ordered, full_schedule, temporary)
        if peer_manifest is not None:
            validate_cross_phase_sessions(read_merged_manifest(peer_manifest), manifest)
        run_matrix.write_json(temporary / "run-manifest.json", manifest)
        temporary.replace(output)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return output / "run-manifest.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        dest="manifests",
        action="append",
        type=pathlib.Path,
        required=True,
        help="shard run-manifest.json (repeat for every consecutive shard)",
    )
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument(
        "--peer-manifest",
        type=pathlib.Path,
        help="already-merged opposite phase; validates identical per-shard runner sessions",
    )
    return parser.parse_args()


if __name__ == "__main__":
    try:
        arguments = parse_args()
        raise SystemExit(
            0
            if merge(arguments.manifests, arguments.output_dir, arguments.peer_manifest)
            else 1
        )
    except Exception as error:
        print(f"error: {error}")
        raise SystemExit(1) from error
