#!/usr/bin/env python3
"""Run an isolated standard Paper server.jar and retain live performance evidence."""

from __future__ import annotations

import argparse
from collections.abc import Iterable
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any
import zipfile

from download_locked import artifact_from_lock, hash_file, load_lock, verify_file
from rcon import RconClient


ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MINECRAFT_FORMAT = re.compile(r"§.")
TPS_VALUES = re.compile(r"\*?(\d+(?:\.\d+)?)")
MSPT_TRIPLE = re.compile(r"(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)")
READY_LINE = re.compile(r"Done \([^)]+\)! For help, type")
DEFAULT_CRAFTENGINE_ARTIFACT = "craftengine-paper-26.7.3"
MAHJONG_TRAFFIC_COMMAND = re.compile(
    r"^/mahjong botmatch (MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN)$"
)
SENSITIVE_ENV_EXACT = frozenset(
    {
        "GITHUB_TOKEN",
        "GH_TOKEN",
        "ACTIONS_RUNTIME_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_URL",
        "ACTIONS_CACHE_URL",
        "ACTIONS_RESULTS_URL",
    }
)
SENSITIVE_ENV_PREFIXES = ("AWS_", "AZURE_", "GOOGLE_", "GCP_", "CI_JOB_JWT")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def append_json_line(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(value, sort_keys=True))
        handle.write("\n")
        handle.flush()


def clean_text(value: str) -> str:
    return MINECRAFT_FORMAT.sub("", ANSI_ESCAPE.sub("", value)).strip()


def parse_tps(value: str) -> dict[str, float] | None:
    cleaned = clean_text(value)
    marker = cleaned.lower().find("tps from last")
    tail = cleaned[marker:] if marker >= 0 else cleaned
    numbers = [float(number) for number in TPS_VALUES.findall(tail)]
    # Ignore the 1/5/15 window labels when they are present in the same text.
    if marker >= 0 and len(numbers) >= 6:
        numbers = numbers[-3:]
    if len(numbers) < 3:
        return None
    return {"1m": numbers[0], "5m": numbers[1], "15m": numbers[2]}


def parse_mspt(value: str) -> dict[str, dict[str, float]] | None:
    triples = MSPT_TRIPLE.findall(clean_text(value))
    if len(triples) < 3:
        return None
    windows = ("5s", "10s", "60s")
    return {
        window: {"average": float(values[0]), "minimum": float(values[1]), "maximum": float(values[2])}
        for window, values in zip(windows, triples[:3], strict=True)
    }


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def distribution(values: list[float]) -> dict[str, float | int | None]:
    def rounded(value: float | None) -> float | None:
        return round(value, 6) if value is not None else None

    return {
        "samples": len(values),
        "minimum": rounded(min(values) if values else None),
        "p50": rounded(percentile(values, 0.50)),
        "p95": rounded(percentile(values, 0.95)),
        "maximum": rounded(max(values) if values else None),
    }


def reserve_ports(count: int) -> list[int]:
    reservations: list[socket.socket] = []
    try:
        for _ in range(count):
            reservation = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            reservation.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
            reservation.bind(("127.0.0.1", 0))
            reservations.append(reservation)
        return [int(reservation.getsockname()[1]) for reservation in reservations]
    finally:
        for reservation in reservations:
            reservation.close()


def sanitized_environment() -> tuple[dict[str, str], list[str]]:
    environment = dict(os.environ)
    removed: list[str] = []
    for key in list(environment):
        if key in SENSITIVE_ENV_EXACT or key.startswith(SENSITIVE_ENV_PREFIXES):
            removed.append(key)
            del environment[key]
    return environment, sorted(removed)


def command_version(command: list[str]) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.SubprocessError) as exception:
        return f"unavailable: {exception}"
    return clean_text((result.stdout + result.stderr).strip())


def git_revision(repository: Path) -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repository, capture_output=True, text=True, timeout=10, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else None


def jar_plugin_name(path: Path) -> str | None:
    with zipfile.ZipFile(path) as archive:
        for descriptor in ("paper-plugin.yml", "plugin.yml"):
            try:
                contents = archive.read(descriptor).decode("utf-8")
            except KeyError:
                continue
            match = re.search(r"(?m)^name:\s*[\"']?([^\s\"'#]+)", contents)
            if match:
                return match.group(1)
    return None


def hash_description(path: Path, algorithm: str = "sha256") -> dict[str, Any]:
    return {
        "path": str(path.resolve()),
        "size": path.stat().st_size,
        "algorithm": algorithm,
        "digest": hash_file(path, algorithm),
    }


def tree_hash(path: Path) -> dict[str, Any]:
    digest = hashlib.sha256()
    files = 0
    total_bytes = 0
    for file in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        relative = file.relative_to(path).as_posix()
        file_digest = hash_file(file, "sha256")
        size = file.stat().st_size
        digest.update(relative.encode("utf-8"))
        digest.update(b"\x00")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\x00")
        digest.update(file_digest.encode("ascii"))
        digest.update(b"\n")
        files += 1
        total_bytes += size
    return {"root": str(path.resolve()), "files": files, "bytes": total_bytes, "sha256": digest.hexdigest()}


def copy_world_template(template: Path, instance: Path) -> dict[str, Any]:
    copied: list[str] = []
    for name in ("world", "world_nether", "world_the_end"):
        source = template / name
        if source.is_dir():
            shutil.copytree(source, instance / name)
            copied.append(name)
    if "world" not in copied or not (instance / "world" / "level.dat").is_file():
        raise ValueError("World template must contain world/level.dat")
    return {"tree": tree_hash(template), "copied_worlds": copied}


def server_properties(server_port: int, rcon_port: int, rcon_password: str) -> dict[str, str]:
    return {
        "allow-nether": "false",
        "allow-flight": "true",
        "difficulty": "peaceful",
        "enable-command-block": "false",
        "enable-jmx-monitoring": "false",
        "enable-query": "false",
        "enable-rcon": "true",
        "enforce-secure-profile": "false",
        "force-gamemode": "true",
        "gamemode": "adventure",
        "generate-structures": "false",
        "hardcore": "false",
        "level-name": "world",
        "level-seed": "289134731802341",
        "max-players": "8",
        "motd": "MahjongPaper deterministic live harness",
        "network-compression-threshold": "256",
        "online-mode": "false",
        "player-idle-timeout": "0",
        "prevent-proxy-connections": "false",
        "pvp": "false",
        "rcon.password": rcon_password,
        "rcon.port": str(rcon_port),
        "server-ip": "127.0.0.1",
        "server-port": str(server_port),
        "simulation-distance": "4",
        "spawn-animals": "false",
        "spawn-monsters": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "sync-chunk-writes": "true",
        "view-distance": "4",
        "white-list": "false",
    }


def write_properties(path: Path, properties: dict[str, str]) -> None:
    contents = "\n".join(f"{key}={value}" for key, value in sorted(properties.items())) + "\n"
    path.write_text(contents, encoding="utf-8", newline="\n")


class ServerOutput:
    def __init__(self, process: subprocess.Popen[str], destination: Path) -> None:
        self.process = process
        self.destination = destination
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._copy, name="paper-stdout", daemon=True)

    def start(self) -> None:
        self.thread.start()

    def _copy(self) -> None:
        assert self.process.stdout is not None
        with self.destination.open("w", encoding="utf-8", newline="\n") as output:
            for line in self.process.stdout:
                output.write(line)
                output.flush()
                if READY_LINE.search(clean_text(line)):
                    self.ready.set()

    def join(self, timeout: float = 5.0) -> None:
        self.thread.join(timeout)


def rcon_command(port: int, password: str, command: str, timeout: float = 5.0) -> str:
    with RconClient("127.0.0.1", port, password, timeout=timeout) as client:
        return client.command(command)


def wait_for_server(
    process: subprocess.Popen[str], output: ServerOutput, rcon_port: int, password: str, timeout: float
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        return_code = process.poll()
        if return_code is not None:
            raise RuntimeError(f"Paper exited before readiness with code {return_code}")
        if output.ready.is_set():
            try:
                rcon_command(rcon_port, password, "list", timeout=2.0)
                return
            except (OSError, ValueError, PermissionError, ConnectionError):
                pass
        time.sleep(0.2)
    raise TimeoutError(f"Paper did not become ready within {timeout:.0f}s")


def wait_for_file(path: Path, process: subprocess.Popen[Any], timeout: float, description: str) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file():
            with path.open("r", encoding="utf-8") as handle:
                return json.load(handle)
        return_code = process.poll()
        if return_code is not None:
            raise RuntimeError(f"{description} process exited early with code {return_code}")
        time.sleep(0.1)
    raise TimeoutError(f"Timed out waiting for {description}: {path}")


def validate_plugins(port: int, password: str, plugins: Iterable[str], destination: Path) -> None:
    results = []
    for plugin in plugins:
        raw = rcon_command(port, password, f"version {plugin}")
        cleaned = clean_text(raw)
        available = plugin.lower() in cleaned.lower() and "not running any plugin" not in cleaned.lower()
        results.append({"plugin": plugin, "available": available, "raw": raw, "clean": cleaned})
        if not available:
            write_json(destination, {"schema_version": 1, "results": results})
            raise RuntimeError(f"Required plugin is not enabled: {plugin}")
    write_json(destination, {"schema_version": 1, "results": results})


def collect_samples(
    port: int, password: str, measurement_seconds: float, sample_interval: float, destination: Path
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    started = time.monotonic()
    deadline = started + measurement_seconds
    sample_index = 0
    next_sample = started
    with RconClient("127.0.0.1", port, password, timeout=5.0) as client:
        while next_sample < deadline - 0.01:
            delay = next_sample - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            observed_at = utc_now()
            tps_raw = client.command("tps")
            mspt_raw = client.command("mspt")
            sample = {
                "index": sample_index,
                "observed_at": observed_at,
                "elapsed_seconds": time.monotonic() - started,
                "tps_raw": tps_raw,
                "mspt_raw": mspt_raw,
                "tps": parse_tps(tps_raw),
                "mspt": parse_mspt(mspt_raw),
            }
            samples.append(sample)
            append_json_line(destination, sample)
            sample_index += 1
            next_sample = started + sample_index * sample_interval

    parsed_tps = [sample["tps"] for sample in samples if sample["tps"] is not None]
    parsed_mspt = [sample["mspt"] for sample in samples if sample["mspt"] is not None]
    summary = {
        "schema_version": 1,
        "configured_measurement_seconds": measurement_seconds,
        "configured_sample_interval_seconds": sample_interval,
        "samples": len(samples),
        "parsed_tps_samples": len(parsed_tps),
        "parsed_mspt_samples": len(parsed_mspt),
        "tps": {
            window: distribution([sample[window] for sample in parsed_tps])
            for window in ("1m", "5m", "15m")
        },
        "mspt": {
            window: {
                statistic: distribution([sample[window][statistic] for sample in parsed_mspt])
                for statistic in ("average", "minimum", "maximum")
            }
            for window in ("5s", "10s", "60s")
        },
    }
    if not parsed_tps or not parsed_mspt:
        raise RuntimeError(
            f"Paper command output could not be parsed (TPS={len(parsed_tps)}, MSPT={len(parsed_mspt)})"
        )
    return samples, summary


def stop_server(
    process: subprocess.Popen[str], rcon_port: int, password: str, shutdown_timeout: float
) -> dict[str, Any]:
    result: dict[str, Any] = {"requested_at": utc_now(), "method": None, "graceful": False}
    if process.poll() is not None:
        result.update({"method": "already-exited", "exit_code": process.returncode, "graceful": process.returncode == 0})
        return result
    try:
        rcon_command(rcon_port, password, "stop", timeout=3.0)
        result["method"] = "rcon"
    except (OSError, ValueError, PermissionError, ConnectionError):
        if process.stdin is not None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                result["method"] = "stdin"
            except OSError:
                result["method"] = "terminate"
        else:
            result["method"] = "terminate"
    try:
        process.wait(timeout=shutdown_timeout)
        result.update({"graceful": process.returncode == 0, "exit_code": process.returncode})
    except subprocess.TimeoutExpired:
        process.terminate()
        result["method"] = f"{result['method']}+terminate"
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
            result["method"] = f"{result['method']}+kill"
        result.update({"exit_code": process.returncode, "graceful": False})
    result["completed_at"] = utc_now()
    return result


def retain_instance_evidence(instance: Path, raw_dir: Path, properties: dict[str, str]) -> None:
    redacted = dict(properties)
    redacted["rcon.password"] = "<redacted-random-per-run>"
    write_properties(raw_dir / "server.properties", redacted)
    for relative in (
        Path("logs/latest.log"),
        Path("bukkit.yml"),
        Path("spigot.yml"),
        Path("config/paper-global.yml"),
        Path("config/paper-world-defaults.yml"),
    ):
        source = instance / relative
        if source.is_file():
            destination = raw_dir / "instance" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
    crash_reports = instance / "crash-reports"
    if crash_reports.is_dir():
        shutil.copytree(crash_reports, raw_dir / "instance" / "crash-reports", dirs_exist_ok=True)


def runtime_jar_manifest(instance: Path) -> list[dict[str, Any]]:
    candidates = {instance / "server.jar"}
    for root in (instance / "plugins", instance / "libraries", instance / "cache"):
        if root.exists():
            candidates.update(root.rglob("*.jar"))
    result = []
    for jar in sorted((path for path in candidates if path.is_file()), key=lambda path: path.as_posix()):
        result.append(
            {
                "path": jar.relative_to(instance).as_posix(),
                "size": jar.stat().st_size,
                "sha256": hash_file(jar, "sha256"),
            }
        )
    return result


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--paper-jar", required=True, type=Path)
    parser.add_argument("--paper-lock", type=Path, default=script_dir / "artifacts.lock.json")
    parser.add_argument("--paper-artifact", default="paper-1.20.1-196")
    parser.add_argument("--plugin-jar", type=Path)
    parser.add_argument("--craftengine-jar", type=Path)
    parser.add_argument("--craftengine-artifact", default=DEFAULT_CRAFTENGINE_ARTIFACT)
    parser.add_argument("--required-plugin", action="append", default=[])
    parser.add_argument("--setup-command", action="append", default=[])
    parser.add_argument("--scenario-name")
    parser.add_argument("--scenario-command")
    parser.add_argument("--scenario-ready-pattern")
    parser.add_argument("--scenario-timeout-seconds", type=float, default=90.0)
    parser.add_argument("--require-mahjong-traffic", action="store_true")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--temp-root", type=Path)
    parser.add_argument("--world-template", type=Path)
    parser.add_argument("--evidence-mode", choices=("smoke", "measurement"), default="smoke")
    parser.add_argument("--java", default="java")
    parser.add_argument("--node", default="node")
    parser.add_argument("--client-script", type=Path, default=script_dir / "client" / "probe.js")
    parser.add_argument("--minecraft-version", default="1.20.1")
    parser.add_argument("--warmup-seconds", type=float, default=5.0)
    parser.add_argument("--measurement-seconds", type=float, default=12.0)
    parser.add_argument("--sample-interval-seconds", type=float, default=3.0)
    parser.add_argument("--startup-timeout-seconds", type=float, default=360.0)
    parser.add_argument("--shutdown-timeout-seconds", type=float, default=60.0)
    parser.add_argument("--heap-mib", type=int, default=1024)
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    if not args.paper_jar.is_file():
        raise ValueError(f"Paper jar does not exist: {args.paper_jar}")
    if args.plugin_jar is not None and not args.plugin_jar.is_file():
        raise ValueError(f"Plugin jar does not exist: {args.plugin_jar}")
    if args.plugin_jar is not None and args.craftengine_jar is None:
        raise ValueError("--plugin-jar requires the real locked --craftengine-jar; a stub dependency is not accepted")
    if args.craftengine_jar is not None and not args.craftengine_jar.is_file():
        raise ValueError(f"CraftEngine jar does not exist: {args.craftengine_jar}")
    if args.plugin_jar is None and args.craftengine_jar is not None:
        raise ValueError("--craftengine-jar is only meaningful together with --plugin-jar")
    if not args.client_script.is_file():
        raise ValueError(f"Protocol client script does not exist: {args.client_script}")
    if args.warmup_seconds < 0 or args.measurement_seconds <= 0 or args.sample_interval_seconds <= 0:
        raise ValueError("Warmup/measurement/sample intervals are invalid")
    if args.sample_interval_seconds > args.measurement_seconds:
        raise ValueError("Sample interval cannot exceed the measurement duration")
    if args.heap_mib < 512:
        raise ValueError("Paper live runs require at least 512 MiB heap")
    if args.evidence_mode == "measurement":
        if args.world_template is None:
            raise ValueError("measurement evidence requires --world-template")
        if args.warmup_seconds < 60 or args.measurement_seconds < 60:
            raise ValueError("measurement evidence requires at least 60s warmup and 60s measurement")
        if args.sample_interval_seconds > 10:
            raise ValueError("measurement evidence requires a TPS/MSPT sample interval of 10s or less")
    if args.world_template is not None and not args.world_template.is_dir():
        raise ValueError(f"World template does not exist: {args.world_template}")
    scenario_values = (args.scenario_name, args.scenario_command, args.scenario_ready_pattern)
    if any(scenario_values) and not all(scenario_values):
        raise ValueError("Scenario runs require --scenario-name, --scenario-command and --scenario-ready-pattern")
    if args.scenario_command is not None and args.plugin_jar is None:
        raise ValueError("A live scenario requires --plugin-jar and its real dependency")
    if args.scenario_timeout_seconds <= 0:
        raise ValueError("Scenario timeout must be positive")
    if args.require_mahjong_traffic:
        if args.scenario_command is None or not MAHJONG_TRAFFIC_COMMAND.fullmatch(args.scenario_command):
            raise ValueError(
                "--require-mahjong-traffic requires a fixed '/mahjong botmatch <mode>' client command"
            )


def main() -> int:
    args = parse_args()
    repository = Path(__file__).resolve().parents[2]
    output_dir = args.output_dir.resolve()
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError(f"Output directory must be absent or empty: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = output_dir / "raw"
    raw_dir.mkdir()
    validate_args(args)

    lock = load_lock(args.paper_lock)
    paper_spec = artifact_from_lock(lock, args.paper_artifact)
    paper_verification = verify_file(args.paper_jar, paper_spec)
    input_artifacts: dict[str, Any] = {"paper": paper_verification}
    plugin_name = None
    if args.plugin_jar is not None:
        plugin_name = jar_plugin_name(args.plugin_jar)
        if not plugin_name:
            raise ValueError(f"Plugin jar has no readable Paper/Bukkit descriptor: {args.plugin_jar}")
        input_artifacts["plugin"] = hash_description(args.plugin_jar)
        craftengine_spec = artifact_from_lock(lock, args.craftengine_artifact)
        input_artifacts["craftengine"] = verify_file(args.craftengine_jar, craftengine_spec)

    ports = reserve_ports(2)
    server_port, rcon_port = ports
    rcon_password = secrets.token_urlsafe(24)
    properties = server_properties(server_port, rcon_port, rcon_password)
    environment, removed_environment = sanitized_environment()
    lifecycle: dict[str, Any] = {"started_at": utc_now()}
    failure: str | None = None
    process: subprocess.Popen[str] | None = None
    output: ServerOutput | None = None
    client_process: subprocess.Popen[Any] | None = None
    client_stdout = None
    client_stderr = None
    shutdown: dict[str, Any] | None = None
    sample_summary: dict[str, Any] | None = None
    world_description: dict[str, Any] | None = None
    scenario_ready: dict[str, Any] | None = None

    temp_root = args.temp_root.resolve() if args.temp_root else None
    if temp_root is not None:
        temp_root.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="mahjong-live-", dir=temp_root) as temporary:
        instance = Path(temporary)
        shutil.copy2(args.paper_jar, instance / "server.jar")
        (instance / "eula.txt").write_text("eula=true\n", encoding="utf-8", newline="\n")
        write_properties(instance / "server.properties", properties)
        (instance / "bukkit.yml").write_text(
            "settings:\n  allow-end: false\n  spawn-radius: 2\n",
            encoding="utf-8",
            newline="\n",
        )
        if args.world_template is not None:
            world_description = copy_world_template(args.world_template.resolve(), instance)
        if args.plugin_jar is not None:
            plugins_dir = instance / "plugins"
            plugins_dir.mkdir()
            shutil.copy2(args.craftengine_jar, plugins_dir / str(artifact_from_lock(lock, args.craftengine_artifact)["filename"]))
            shutil.copy2(args.plugin_jar, plugins_dir / args.plugin_jar.name)

        java_command = [
            args.java,
            f"-Xms{args.heap_mib}M",
            f"-Xmx{args.heap_mib}M",
            "-XX:+UseG1GC",
            "-XX:+AlwaysPreTouch",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-jar",
            "server.jar",
            "--nogui",
        ]
        creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        try:
            process = subprocess.Popen(
                java_command,
                cwd=instance,
                env=environment,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=creation_flags,
            )
            lifecycle["pid"] = process.pid
            output = ServerOutput(process, raw_dir / "server-stdout.log")
            output.start()
            wait_for_server(process, output, rcon_port, rcon_password, args.startup_timeout_seconds)
            lifecycle["server_ready_at"] = utc_now()

            required_plugins = list(args.required_plugin)
            if plugin_name is not None:
                required_plugins.extend(["CraftEngine", plugin_name])
            validate_plugins(rcon_port, rcon_password, dict.fromkeys(required_plugins), raw_dir / "plugins.json")

            protocol_dir = raw_dir / "protocol-client"
            protocol_dir.mkdir()
            client_command = [
                args.node,
                str(args.client_script.resolve()),
                "--host",
                "127.0.0.1",
                "--port",
                str(server_port),
                "--version",
                args.minecraft_version,
                "--username",
                "MahjongPerfBot",
                "--warmup-seconds",
                str(args.warmup_seconds),
                "--duration-seconds",
                str(args.measurement_seconds),
                "--connect-timeout-seconds",
                "45",
                "--output-dir",
                str(protocol_dir),
            ]
            if args.scenario_command is not None:
                command_trigger = protocol_dir / "command.trigger"
                client_command.extend(
                    [
                        "--startup-command",
                        args.scenario_command,
                        "--command-trigger-file",
                        str(command_trigger),
                        "--scenario-ready-pattern",
                        args.scenario_ready_pattern,
                        "--scenario-timeout-seconds",
                        str(args.scenario_timeout_seconds),
                    ]
                )
            client_stdout = (raw_dir / "protocol-client-stdout.log").open("wb")
            client_stderr = (raw_dir / "protocol-client-stderr.log").open("wb")
            client_process = subprocess.Popen(
                client_command,
                cwd=repository,
                env=environment,
                stdout=client_stdout,
                stderr=client_stderr,
                creationflags=creation_flags,
            )
            wait_for_file(protocol_dir / "ready.json", client_process, 50, "protocol client PLAY readiness")
            setup_results = []
            for command in args.setup_command:
                raw = rcon_command(rcon_port, rcon_password, command)
                setup_results.append({"command": command, "raw": raw, "clean": clean_text(raw)})
            write_json(raw_dir / "setup-commands.json", {"schema_version": 1, "commands": setup_results})
            if args.scenario_command is not None:
                op_raw = rcon_command(rcon_port, rcon_password, "op MahjongPerfBot")
                op_clean = clean_text(op_raw)
                write_json(
                    raw_dir / "scenario-authorization.json",
                    {"schema_version": 1, "command": "op MahjongPerfBot", "raw": op_raw, "clean": op_clean},
                )
                if "operator" not in op_clean.lower() and "opped" not in op_clean.lower():
                    raise RuntimeError(f"Could not authorize the protocol client for the scenario: {op_clean}")
                command_trigger.write_text("authorized\n", encoding="utf-8", newline="\n")
                scenario_ready = wait_for_file(
                    protocol_dir / "scenario-ready.json",
                    client_process,
                    args.scenario_timeout_seconds + 5,
                    f"scenario {args.scenario_name}",
                )
                if not scenario_ready.get("ready"):
                    raise RuntimeError(f"Scenario did not report ready: {scenario_ready}")

            wait_for_file(
                protocol_dir / "measurement-ready.json",
                client_process,
                args.warmup_seconds + 15,
                "protocol client measurement readiness",
            )
            lifecycle["measurement_started_at"] = utc_now()
            _samples, sample_summary = collect_samples(
                rcon_port,
                rcon_password,
                args.measurement_seconds,
                args.sample_interval_seconds,
                raw_dir / "paper-samples.jsonl",
            )
            write_json(output_dir / "paper-summary.json", sample_summary)
            lifecycle["measurement_completed_at"] = utc_now()

            client_return_code = client_process.wait(timeout=20)
            if client_return_code != 0:
                raise RuntimeError(f"Protocol client failed with exit code {client_return_code}")
            with (protocol_dir / "summary.json").open("r", encoding="utf-8") as handle:
                protocol_summary = json.load(handle)
            if protocol_summary.get("status") != "complete":
                raise RuntimeError(f"Protocol client summary is not complete: {protocol_summary.get('failure')}")
        except Exception as exception:  # Evidence must still be retained after any live failure.
            failure = f"{exception.__class__.__name__}: {exception}"
        finally:
            if client_process is not None and client_process.poll() is None:
                client_process.terminate()
                try:
                    client_process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    client_process.kill()
                    client_process.wait(timeout=10)
            if client_stdout is not None:
                client_stdout.close()
            if client_stderr is not None:
                client_stderr.close()
            if process is not None:
                shutdown = stop_server(process, rcon_port, rcon_password, args.shutdown_timeout_seconds)
                if output is not None:
                    output.join()
                if not shutdown.get("graceful") and failure is None:
                    failure = f"Paper did not stop gracefully: {shutdown}"
            retain_instance_evidence(instance, raw_dir, properties)
            runtime_jars = runtime_jar_manifest(instance)
            write_json(raw_dir / "runtime-jars.json", {"schema_version": 1, "jars": runtime_jars})

    lifecycle["completed_at"] = utc_now()
    manifest = {
        "schema_version": 1,
        "status": "failed" if failure else "complete",
        "failure": failure,
        "evidence_mode": args.evidence_mode,
        "claim_scope": (
            (
                "fixed Mahjong botmatch traffic smoke; validates live collection but is not an optimization decision"
                if args.require_mahjong_traffic
                else "infrastructure-smoke-only; not optimization evidence"
            )
            if args.evidence_mode == "smoke"
            else "single live measurement; requires fixed-scene paired ABBA comparison before an optimization claim"
        ),
        "lifecycle": lifecycle,
        "shutdown": shutdown,
        "repository": {"root": str(repository), "revision": git_revision(repository)},
        "inputs": {
            "artifacts": input_artifacts,
            "artifact_lock": hash_description(args.paper_lock.resolve()),
            "world_template": world_description,
            "plugin_name": plugin_name,
            "setup_commands": args.setup_command,
            "scenario": {
                "name": args.scenario_name,
                "client_command": args.scenario_command,
                "ready_pattern": args.scenario_ready_pattern,
                "timeout_seconds": args.scenario_timeout_seconds,
                "require_mahjong_traffic": args.require_mahjong_traffic,
                "ready_evidence": scenario_ready,
            },
        },
        "runtime": {
            "java": command_version([args.java, "-version"]),
            "node": command_version([args.node, "--version"]),
            "python": sys.version,
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "cpu_count": os.cpu_count(),
            "jvm": {
                "heap_mib": args.heap_mib,
                "flags": ["-XX:+UseG1GC", "-XX:+AlwaysPreTouch", "-Dterminal.jline=false", "-Dterminal.ansi=false"],
            },
            "removed_sensitive_environment_keys": removed_environment,
        },
        "server": {
            "minecraft_version": args.minecraft_version,
            "address": "127.0.0.1",
            "port": server_port,
            "rcon_port": rcon_port,
            "rcon_password_retained": False,
            "properties": {**properties, "rcon.password": "<redacted-random-per-run>"},
        },
        "measurement": {
            "warmup_seconds": args.warmup_seconds,
            "measurement_seconds": args.measurement_seconds,
            "sample_interval_seconds": args.sample_interval_seconds,
            "paper_summary": sample_summary,
        },
    }
    write_json(output_dir / "run-manifest.json", manifest)
    print(json.dumps({"status": manifest["status"], "output": str(output_dir), "failure": failure}, sort_keys=True))
    return 1 if failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
