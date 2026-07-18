from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest


LIVE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LIVE_DIR))

from download_locked import artifact_from_lock, load_lock, verify_file  # noqa: E402


class LockedArtifactTest(unittest.TestCase):
    def test_committed_paper_lock_has_expected_immutable_object(self) -> None:
        lock = load_lock(LIVE_DIR / "artifacts.lock.json")
        paper = artifact_from_lock(lock, "paper-1.20.1-196")
        self.assertEqual(196, paper["build"])
        self.assertEqual("STABLE", paper["channel"])
        self.assertEqual(
            "234a9b32098100c6fc116664d64e36ccdb58b5b649af0f80bcccb08b0255eaea",
            paper["hash"]["digest"],
        )
        self.assertIn(paper["hash"]["digest"], paper["url"])

    def test_committed_craftengine_lock_matches_latest_paper_release(self) -> None:
        lock = load_lock(LIVE_DIR / "artifacts.lock.json")
        craftengine = artifact_from_lock(lock, "craftengine-paper-26.7.4")
        self.assertEqual("craftengine", craftengine["project"])
        self.assertEqual("paper", craftengine["distribution"])
        self.assertEqual("26.7.4", craftengine["version"])
        self.assertEqual("aINSQrXC", craftengine["modrinth_version_id"])
        self.assertIn("paper", craftengine["loaders"])
        self.assertIn("1.20.1", craftengine["minecraft_versions"])
        self.assertIn("26.1.2", craftengine["minecraft_versions"])
        self.assertEqual("craft-engine-paper-plugin-26.7.4.jar", craftengine["filename"])
        self.assertEqual(
            "https://cdn.modrinth.com/data/tRX6FMfQ/versions/aINSQrXC/"
            "craft-engine-paper-plugin-26.7.4.jar",
            craftengine["url"],
        )
        self.assertEqual(8_970_694, craftengine["size"])
        self.assertEqual("sha512", craftengine["hash"]["algorithm"])
        self.assertEqual(
            "1d6982e325552fd51a9a481252e0c055c32a5b6e3c2d816cf69ca4b2f355a5f7"
            "af1abed00ff9d29c08fb32ff293d9d11837ad4372c34675b73682a98b1ec86b0",
            craftengine["hash"]["digest"],
        )
        self.assertEqual(craftengine["hash"]["digest"], craftengine["published_hashes"]["sha512"])
        self.assertEqual(
            "179e7b8b191093e41beca56e3d52ae608f46e161",
            craftengine["published_hashes"]["sha1"],
        )
        self.assertEqual(
            "https://api.modrinth.com/v2/version/aINSQrXC",
            craftengine["source_metadata"],
        )

    def test_verify_file_checks_both_size_and_digest(self) -> None:
        payload = b"fixed-test-artifact"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "artifact.bin"
            path.write_bytes(payload)
            artifact = {
                "size": len(payload),
                "hash": {"algorithm": "sha256", "digest": hashlib.sha256(payload).hexdigest()},
            }
            result = verify_file(path, artifact)
            self.assertEqual(len(payload), result["size"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), result["digest"])

            artifact["size"] += 1
            with self.assertRaisesRegex(ValueError, "Size mismatch"):
                verify_file(path, artifact)

    def test_rejects_unknown_artifact(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unknown artifact"):
            artifact_from_lock({"artifacts": {}}, "missing")


if __name__ == "__main__":
    unittest.main()
