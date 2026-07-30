from __future__ import annotations

import unittest
from pathlib import Path


APP_BUILD = Path(__file__).resolve().parents[2] / "app" / "build.gradle.kts"


class OfflineGuardBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app_build = APP_BUILD.read_text(encoding="utf-8")

    def test_all_runtime_module_test_apk_manifests_are_required_inputs(self) -> None:
        self.assertIn('"processDebugAndroidTestManifest"', self.app_build)
        self.assertIn('":data:processDebugAndroidTestManifest"', self.app_build)
        self.assertIn('":ml:processDebugAndroidTestManifest"', self.app_build)
        self.assertIn(
            '"intermediates/packaged_manifests/debugAndroidTest/"',
            self.app_build,
        )
        self.assertIn("debug instrumented-test APK", self.app_build)
        self.assertIn(
            'listOf(project, project(":data"), project(":ml"))',
            self.app_build,
        )

    def test_all_runtime_module_test_runtime_graphs_are_scanned(self) -> None:
        self.assertIn('"debugAndroidTestRuntimeClasspath"', self.app_build)
        self.assertIn(
            'setOf(":app", ":data", ":ml")',
            self.app_build,
        )
        self.assertIn(
            '"Offline runtime scanner fixture must include every instrumented-test APK"',
            self.app_build,
        )

    def test_internet_scanner_has_a_positive_regression_fixture(self) -> None:
        self.assertIn(
            '"""<uses-permission android:name="android.permission.INTERNET" />"""',
            self.app_build,
        )
        self.assertIn(
            '"Offline manifest scanner fixture must detect INTERNET"',
            self.app_build,
        )

    def test_test_apk_assembly_cannot_bypass_offline_guard(self) -> None:
        self.assertIn('it.name == "assembleDebugAndroidTest"', self.app_build)
        self.assertIn('listOf(project(":data"), project(":ml"))', self.app_build)
        self.assertIn('it.name == "check" || it.name == "assembleDebugAndroidTest"', self.app_build)
        self.assertIn("dependsOn(offlineGuard)", self.app_build)


if __name__ == "__main__":
    unittest.main()
