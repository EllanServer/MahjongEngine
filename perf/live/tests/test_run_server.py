from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


LIVE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LIVE_DIR))

from run_server import (  # noqa: E402
    DEFAULT_CRAFTENGINE_ARTIFACT,
    clean_text,
    jar_plugin_name,
    parse_mspt,
    parse_tps,
    percentile,
    reserve_ports,
    tree_hash,
)


class PaperOutputParsingTest(unittest.TestCase):
    def test_parses_colored_tps_windows_without_treating_labels_as_values(self) -> None:
        parsed = parse_tps("§6TPS from last 1m, 5m, 15m: §a*20.0, §a19.85, §e18.5")
        self.assertEqual({"1m": 20.0, "5m": 19.85, "15m": 18.5}, parsed)

    def test_parses_mspt_avg_min_max_windows(self) -> None:
        parsed = parse_mspt(
            "Server tick times (avg/min/max) from last 5s, 10s, 1m:\n"
            "§a0.42/0.10/1.90, §a0.51/0.10/2.30, §e0.70/0.10/4.50"
        )
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(0.42, parsed["5s"]["average"])
        self.assertEqual(2.30, parsed["10s"]["maximum"])
        self.assertEqual(0.10, parsed["60s"]["minimum"])

    def test_strips_ansi_and_minecraft_format_codes(self) -> None:
        self.assertEqual("TPS: 20", clean_text("\x1b[32m§aTPS: 20\x1b[0m"))

    def test_percentile_uses_linear_interpolation(self) -> None:
        self.assertEqual(2.5, percentile([1.0, 2.0, 3.0, 4.0], 0.5))


class LiveInputTest(unittest.TestCase):
    def test_uses_latest_paper_compatible_craftengine_by_default(self) -> None:
        self.assertEqual("craftengine-paper-26.7.3", DEFAULT_CRAFTENGINE_ARTIFACT)

    def test_reserved_ports_are_distinct(self) -> None:
        ports = reserve_ports(3)
        self.assertEqual(3, len(set(ports)))
        self.assertTrue(all(1 <= port <= 65535 for port in ports))

    def test_reads_plugin_name_from_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "plugin.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("paper-plugin.yml", 'name: "MahjongPaper"\n')
            self.assertEqual("MahjongPaper", jar_plugin_name(jar))

    def test_tree_hash_changes_with_content_and_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "world").mkdir()
            file = root / "world" / "level.dat"
            file.write_bytes(b"first")
            first = tree_hash(root)
            file.write_bytes(b"second")
            second = tree_hash(root)
            self.assertNotEqual(first["sha256"], second["sha256"])
            self.assertEqual(1, second["files"])


if __name__ == "__main__":
    unittest.main()
