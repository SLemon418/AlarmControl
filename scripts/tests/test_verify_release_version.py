from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "verify_release_version.py"
SPEC = importlib.util.spec_from_file_location("verify_release_version", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ReleaseVersionVerificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary_directory.name)
        self.git("init", "-q")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("config", "user.name", "Release Test")
        self.git("config", "commit.gpgsign", "false")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ("git", "-C", str(self.repo), *arguments),
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    def release(self, name: str, code: int, *, include_version: bool = True) -> None:
        version_file = self.repo / "app" / "version.json"
        version_file.parent.mkdir(exist_ok=True)
        if include_version:
            version_file.write_text(
                json.dumps({"versionCode": code, "versionName": name}) + "\n",
                encoding="utf-8",
            )
        elif version_file.exists():
            version_file.unlink()
        marker = self.repo / "marker.txt"
        marker.write_text(f"{name}-{code}\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", f"release {name}")
        self.git("tag", f"v{name}")

    def test_accepts_first_semver_release(self) -> None:
        self.release("1.0.0", 1)
        message = MODULE.verify(self.repo, "v1.0.0", "HEAD")
        self.assertIn("first reachable SemVer release", message)

    def test_accepts_strictly_increased_version_code(self) -> None:
        self.release("1.0.0", 7)
        self.release("1.1.0", 8)
        message = MODULE.verify(self.repo, "v1.1.0", "HEAD")
        self.assertIn("previous maximum was 7", message)

    def test_rejects_equal_version_code(self) -> None:
        self.release("1.0.0", 7)
        self.release("1.1.0", 7)
        with self.assertRaisesRegex(MODULE.VerificationError, "must be greater"):
            MODULE.verify(self.repo, "v1.1.0", "HEAD")

    def test_compares_against_maximum_prior_version_code(self) -> None:
        self.release("1.0.0", 10)
        self.release("1.1.0", 8)
        self.release("1.2.0", 9)
        with self.assertRaisesRegex(MODULE.VerificationError, "v1.0.0 versionCode 10"):
            MODULE.verify(self.repo, "v1.2.0", "HEAD")

    def test_ignores_non_semver_tags(self) -> None:
        self.release("1.0.0", 1)
        self.git("tag", "personal-v1.0.0")
        message = MODULE.verify(self.repo, "v1.0.0", "HEAD")
        self.assertIn("first reachable SemVer release", message)

    def test_fails_closed_when_prior_release_has_no_version_file(self) -> None:
        self.release("1.0.0", 1, include_version=False)
        self.release("1.1.0", 2)
        with self.assertRaisesRegex(MODULE.VerificationError, "git show"):
            MODULE.verify(self.repo, "v1.1.0", "HEAD")

    def test_rejects_tag_and_version_name_mismatch(self) -> None:
        self.release("1.0.0", 1)
        self.git("tag", "v2.0.0")
        with self.assertRaisesRegex(MODULE.VerificationError, "does not match"):
            MODULE.verify(self.repo, "v2.0.0", "HEAD")

    def test_rejects_late_tag_behind_newer_release_on_history_ref(self) -> None:
        self.release("1.0.0", 1)
        version_file = self.repo / "app" / "version.json"
        version_file.write_text(
            json.dumps({"versionCode": 2, "versionName": "1.5.0"}) + "\n",
            encoding="utf-8",
        )
        self.git("add", ".")
        self.git("commit", "-qm", "prepare 1.5.0")
        late_release_commit = self.git("rev-parse", "HEAD").strip()
        self.release("2.0.0", 3)
        self.git("tag", "v1.5.0", late_release_commit)

        with self.assertRaisesRegex(MODULE.VerificationError, "v2.0.0 versionCode 3"):
            MODULE.verify(self.repo, "v1.5.0", "HEAD")

    def test_rejects_missing_history_ref(self) -> None:
        self.release("1.0.0", 1)
        with self.assertRaisesRegex(MODULE.VerificationError, "git rev-parse"):
            MODULE.verify(self.repo, "v1.0.0", "refs/heads/missing")


if __name__ == "__main__":
    unittest.main()
