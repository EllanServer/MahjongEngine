#!/usr/bin/env python3
"""Verify that a candidate has not changed the base-owned benchmark decision surface."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entries(root: pathlib.Path, protected_paths: list[str]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for protected in protected_paths:
        path = root / pathlib.PurePosixPath(protected)
        if not path.exists() and not path.is_symlink():
            result[protected] = {"type": "missing"}
            continue
        if path.is_symlink():
            result[protected] = {"type": "symlink", "target": str(path.readlink())}
            continue
        candidates = [path]
        if path.is_dir():
            candidates = sorted(
                candidate
                for candidate in path.rglob("*")
                if (candidate.is_file() or candidate.is_symlink())
                and "__pycache__" not in candidate.parts
                and candidate.suffix not in {".pyc", ".pyo"}
            )
            if not candidates:
                result[protected] = {"type": "directory", "empty": True}
                continue
        for candidate in candidates:
            relative = candidate.relative_to(root).as_posix()
            if candidate.is_symlink():
                result[relative] = {"type": "symlink", "target": str(candidate.readlink())}
            else:
                result[relative] = {
                    "type": "file",
                    "size": candidate.stat().st_size,
                    "sha256": sha256_file(candidate),
                }
    return result


def run(args: argparse.Namespace) -> int:
    base_dir = args.base_dir.resolve()
    candidate_dir = args.candidate_dir.resolve()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    protected_paths = config["protected_paths"]
    base_entries = entries(base_dir, protected_paths)
    candidate_entries = entries(candidate_dir, protected_paths)
    paths = sorted(set(base_entries) | set(candidate_entries))
    changed = [
        {
            "path": path,
            "base": base_entries.get(path, {"type": "absent"}),
            "candidate": candidate_entries.get(path, {"type": "absent"}),
        }
        for path in paths
        if base_entries.get(path) != candidate_entries.get(path)
    ]
    report = {
        "schema_version": 1,
        "passed": not changed,
        "protected_paths": protected_paths,
        "base_entry_count": len(base_entries),
        "candidate_entry_count": len(candidate_entries),
        "changes": changed,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if changed:
        for entry in changed:
            print(f"protected benchmark path changed: {entry['path']}", file=sys.stderr)
        return 2
    print(f"verified {len(base_entries)} base-owned benchmark files")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-dir", type=pathlib.Path, required=True)
    parser.add_argument("--candidate-dir", type=pathlib.Path, required=True)
    parser.add_argument("--config", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
