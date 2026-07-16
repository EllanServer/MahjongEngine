#!/usr/bin/env python3
"""Download one explicitly locked live-test artifact and verify it before use."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any
from urllib.request import Request, urlopen


USER_AGENT = "EllanServer-Mahjong-live-perf/1.0 (https://github.com/EllanServer/Mahjong)"
SUPPORTED_HASHES = frozenset({"sha256", "sha512"})


def load_lock(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        lock = json.load(handle)
    if lock.get("schema_version") != 1 or not isinstance(lock.get("artifacts"), dict):
        raise ValueError(f"Unsupported artifact lock schema in {path}")
    return lock


def artifact_from_lock(lock: dict[str, Any], artifact_name: str) -> dict[str, Any]:
    try:
        artifact = lock["artifacts"][artifact_name]
    except KeyError as exception:
        choices = ", ".join(sorted(lock["artifacts"]))
        raise ValueError(f"Unknown artifact {artifact_name!r}; available: {choices}") from exception
    required = {"filename", "url", "size", "hash"}
    missing = sorted(required.difference(artifact))
    if missing:
        raise ValueError(f"Artifact {artifact_name!r} is missing fields: {', '.join(missing)}")
    hash_spec = artifact["hash"]
    algorithm = str(hash_spec.get("algorithm", "")).lower()
    digest = str(hash_spec.get("digest", "")).lower()
    if algorithm not in SUPPORTED_HASHES:
        raise ValueError(f"Unsupported hash algorithm for {artifact_name!r}: {algorithm!r}")
    if len(digest) != hashlib.new(algorithm).digest_size * 2:
        raise ValueError(f"Invalid {algorithm} digest length for {artifact_name!r}")
    if not str(artifact["url"]).startswith("https://"):
        raise ValueError(f"Artifact {artifact_name!r} must use an HTTPS URL")
    if int(artifact["size"]) <= 0:
        raise ValueError(f"Artifact {artifact_name!r} has an invalid size")
    return artifact


def hash_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path: Path, artifact: dict[str, Any]) -> dict[str, Any]:
    expected_size = int(artifact["size"])
    actual_size = path.stat().st_size
    algorithm = str(artifact["hash"]["algorithm"]).lower()
    expected_digest = str(artifact["hash"]["digest"]).lower()
    actual_digest = hash_file(path, algorithm)
    if actual_size != expected_size:
        raise ValueError(f"Size mismatch for {path}: expected {expected_size}, got {actual_size}")
    if actual_digest != expected_digest:
        raise ValueError(
            f"{algorithm} mismatch for {path}: expected {expected_digest}, got {actual_digest}"
        )
    return {
        "path": str(path.resolve()),
        "size": actual_size,
        "algorithm": algorithm,
        "digest": actual_digest,
    }


def download(artifact: dict[str, Any], destination: Path) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file():
        try:
            result = verify_file(destination, artifact)
            result["cache_hit"] = True
            return result
        except ValueError:
            pass

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{destination.name}.", suffix=".part", dir=destination.parent, delete=False
        ) as handle:
            temporary_path = Path(handle.name)
            request = Request(str(artifact["url"]), headers={"User-Agent": USER_AGENT})
            with urlopen(request, timeout=60) as response:
                final_url = response.geturl()
                if not final_url.startswith("https://"):
                    raise ValueError(f"Artifact download redirected to a non-HTTPS URL: {final_url}")
                while chunk := response.read(1024 * 1024):
                    handle.write(chunk)
            handle.flush()
            os.fsync(handle.fileno())
        result = verify_file(temporary_path, artifact)
        os.replace(temporary_path, destination)
        temporary_path = None
        result["path"] = str(destination.resolve())
        result["cache_hit"] = False
        return result
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("artifacts.lock.json"))
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--destination", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    lock = load_lock(args.lock)
    artifact = artifact_from_lock(lock, args.artifact)
    result = download(artifact, args.destination)
    result["artifact"] = args.artifact
    result["source_url"] = artifact["url"]
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
