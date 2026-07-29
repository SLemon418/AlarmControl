from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "package_release_apks.py"
SPEC = importlib.util.spec_from_file_location("package_release_apks", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ReleaseApkPackagingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.metadata_path = self.root / "output-metadata.json"
        self.output_directory = self.root / "packaged"
        self.version = "1.2.3"
        self.payloads: dict[str, bytes] = {}
        self.metadata = self._metadata_fixture()
        self._write_fixture()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _metadata_fixture(self) -> dict[str, object]:
        elements: list[dict[str, object]] = []
        for label in MODULE.OUTPUT_LABELS:
            output_file = f"app-{label}-release.apk"
            payload = f"APK bytes for {label}\n".encode()
            self.payloads[output_file] = payload
            filters: list[dict[str, str]] = []
            if label != "universal":
                filters.append({"filterType": "ABI", "value": label})
            elements.append(
                {
                    "type": "SINGLE",
                    "filters": filters,
                    "versionCode": 7,
                    "versionName": self.version,
                    "outputFile": output_file,
                }
            )
        return {
            "version": 3,
            "applicationId": "com.alarmcontrol",
            "variantName": "release",
            "elements": elements,
            "elementType": "File",
        }

    def _write_fixture(self) -> None:
        for output_file, payload in self.payloads.items():
            (self.root / output_file).write_bytes(payload)
        self.metadata_path.write_text(
            json.dumps(self.metadata),
            encoding="utf-8",
        )

    @property
    def elements(self) -> list[dict[str, object]]:
        elements = self.metadata["elements"]
        assert isinstance(elements, list)
        return elements

    def _rewrite_metadata(self) -> None:
        self.metadata_path.write_text(
            json.dumps(self.metadata),
            encoding="utf-8",
        )

    def test_packages_all_apks_with_matching_sha256_sidecars(self) -> None:
        packaged = MODULE.package_release_apks(
            self.metadata_path,
            self.version,
            self.output_directory,
        )

        expected_names = [
            f"AlarmControl-{self.version}-{label}.apk"
            for label in MODULE.OUTPUT_LABELS
        ]
        self.assertEqual(expected_names, [path.name for path in packaged])
        self.assertEqual(
            sorted(
                expected_names
                + [f"{apk_name}.sha256" for apk_name in expected_names]
            ),
            sorted(path.name for path in self.output_directory.iterdir()),
        )
        for label, packaged_apk in zip(MODULE.OUTPUT_LABELS, packaged, strict=True):
            source_name = f"app-{label}-release.apk"
            payload = self.payloads[source_name]
            self.assertEqual(payload, packaged_apk.read_bytes())
            checksum = packaged_apk.with_name(f"{packaged_apk.name}.sha256")
            self.assertEqual(
                f"{hashlib.sha256(payload).hexdigest()}  {packaged_apk.name}\n",
                checksum.read_text(encoding="utf-8"),
            )

    def test_cli_accepts_metadata_version_and_output_directory(self) -> None:
        result = subprocess.run(
            (
                sys.executable,
                str(SCRIPT),
                "--metadata",
                str(self.metadata_path),
                "--version",
                self.version,
                "--output-dir",
                str(self.output_directory),
            ),
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(10, len(result.stdout.splitlines()))

    def test_rejects_missing_metadata_output(self) -> None:
        self.elements.pop()
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "exactly 5"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_extra_metadata_output(self) -> None:
        self.elements.append(copy.deepcopy(self.elements[-1]))
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "exactly 5"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_missing_or_non_file_apk(self) -> None:
        output_file = str(self.elements[0]["outputFile"])
        apk = self.root / output_file
        apk.unlink()
        apk.mkdir()

        with self.assertRaisesRegex(
            MODULE.PackagingError, "missing or is not a file"
        ):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_extra_filter(self) -> None:
        filters = self.elements[1]["filters"]
        assert isinstance(filters, list)
        filters.append({"filterType": "DENSITY", "value": "xxhdpi"})
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "exactly one ABI filter"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_non_abi_filter(self) -> None:
        self.elements[1]["filters"] = [
            {"filterType": "DENSITY", "value": "xxhdpi"}
        ]
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "only one ABI filter"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_duplicate_abi_output(self) -> None:
        self.elements[3]["filters"] = copy.deepcopy(self.elements[1]["filters"])
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "Duplicate APK output"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_unsupported_abi_filter(self) -> None:
        self.elements[1]["filters"] = [
            {"filterType": "ABI", "value": "riscv64"}
        ]
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "Unsupported ABI"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_output_file_path_traversal(self) -> None:
        for unsafe_path in ("../escape.apk", r"..\escape.apk", "/tmp/escape.apk"):
            with self.subTest(unsafe_path=unsafe_path):
                original = self.elements[0]["outputFile"]
                self.elements[0]["outputFile"] = unsafe_path
                self._rewrite_metadata()
                with self.assertRaisesRegex(MODULE.PackagingError, "basename"):
                    MODULE.package_release_apks(
                        self.metadata_path,
                        self.version,
                        self.output_directory,
                    )
                self.elements[0]["outputFile"] = original

    def test_rejects_version_mismatch(self) -> None:
        self.elements[2]["versionName"] = "1.2.4"
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "versionName"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_wrong_application_id(self) -> None:
        self.metadata["applicationId"] = "example.invalid"
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "applicationId"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_wrong_variant(self) -> None:
        self.metadata["variantName"] = "debug"
        self._rewrite_metadata()

        with self.assertRaisesRegex(MODULE.PackagingError, "variantName"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_nonempty_output_directory(self) -> None:
        self.output_directory.mkdir()
        (self.output_directory / "existing.txt").write_text(
            "do not overwrite\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(MODULE.PackagingError, "must be empty"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )

    def test_rejects_symbolic_link_output_directory(self) -> None:
        real_output_directory = self.root / "real-packaged"
        real_output_directory.mkdir()
        try:
            self.output_directory.symlink_to(
                real_output_directory,
                target_is_directory=True,
            )
        except (NotImplementedError, OSError) as error:
            self.skipTest(f"Symbolic links are unavailable: {error}")

        with self.assertRaisesRegex(MODULE.PackagingError, "symbolic link"):
            MODULE.package_release_apks(
                self.metadata_path,
                self.version,
                self.output_directory,
            )


if __name__ == "__main__":
    unittest.main()
