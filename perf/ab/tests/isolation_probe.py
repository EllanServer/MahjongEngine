#!/usr/bin/env python3
"""Linux CI probe for the candidate UID evidence boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import stat
import subprocess
import sys


AB_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AB_DIR))

import run_matrix  # noqa: E402


def sha256_file(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(user: str, output_dir: pathlib.Path) -> int:
    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    sentinel = output_dir / "runner-owned.txt"
    result = output_dir / "candidate-result.json"
    report = output_dir / "isolation-probe.json"
    for path in (sentinel, result, report):
        if path.exists():
            path.chmod(0o600)
            path.unlink()

    sentinel.write_text("runner-owned\n", encoding="utf-8")
    sentinel.chmod(0o444)
    expected_sentinel_hash = sha256_file(sentinel)
    run_matrix.prepare_untrusted_result(result, user)
    candidate_program = """
import pathlib, subprocess, sys
result = pathlib.Path(sys.argv[1])
sentinel = pathlib.Path(sys.argv[2])
result.write_text('{"candidate": true}\\n', encoding='utf-8')
try:
    sentinel.write_text('tampered\\n', encoding='utf-8')
except PermissionError:
    pass
else:
    raise SystemExit('candidate unexpectedly rewrote runner evidence')
subprocess.Popen([
    '/usr/bin/python3', '-c',
    'import pathlib,sys,time; time.sleep(60); pathlib.Path(sys.argv[1]).write_text("late\\n")',
    str(result),
])
"""
    try:
        completed = subprocess.run(
            [
                "sudo",
                "-H",
                "-u",
                user,
                "--",
                "/usr/bin/python3",
                "-c",
                candidate_program,
                str(result),
                str(sentinel),
            ],
            check=False,
        )
    finally:
        run_matrix.reclaim_untrusted_result(result, user)
    if completed.returncode != 0:
        raise RuntimeError(f"candidate isolation probe exited {completed.returncode}")
    if sha256_file(sentinel) != expected_sentinel_hash:
        raise RuntimeError("candidate changed runner-owned evidence")
    if json.loads(result.read_text(encoding="utf-8")) != {"candidate": True}:
        raise RuntimeError("candidate result changed after UID cleanup")
    result_mode = stat.S_IMODE(result.stat().st_mode)
    if result_mode != 0o444:
        raise RuntimeError(f"candidate result mode is {result_mode:o}, expected 444")
    report.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "passed": True,
                "candidate_user": user,
                "sentinel_sha256": expected_sentinel_hash,
                "result_sha256": sha256_file(result),
                "result_mode": result_mode,
                "residual_processes_killed": True,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    return parser.parse_args()


if __name__ == "__main__":
    arguments = parse_args()
    raise SystemExit(run(arguments.user, arguments.output_dir))
