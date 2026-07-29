from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"
WORKFLOW = WORKFLOWS / "github-release.yml"
ANDROID_WORKFLOW = WORKFLOWS / "android.yml"
APP_BUILD = Path(__file__).resolve().parents[2] / "app" / "build.gradle.kts"
MANAGED_DEVICE_COMMAND = (
    "run: ./gradlew --dependency-verification strict "
    "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect "
)


class GitHubReleaseWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.android_workflow = ANDROID_WORKFLOW.read_text(encoding="utf-8")
        cls.app_build = APP_BUILD.read_text(encoding="utf-8")

    def test_release_tests_the_checked_out_tag_commit(self) -> None:
        self.assertIn(
            'release_commit="$(git rev-parse "${GITHUB_REF_NAME}^{commit}")"',
            self.workflow,
        )
        self.assertIn('checked_out_commit="$(git rev-parse HEAD)"', self.workflow)
        self.assertIn(
            'if [[ "$checked_out_commit" != "$release_commit" ]]; then',
            self.workflow,
        )

    def test_managed_device_suites_gate_publication(self) -> None:
        publish_index = self.workflow.index("- name: Publish GitHub Release")
        required_steps = (
            "- name: Enable KVM access",
            MANAGED_DEVICE_COMMAND + ":data:pixel2Api34DebugAndroidTest",
            MANAGED_DEVICE_COMMAND + ":ml:pixel2Api34DebugAndroidTest",
            MANAGED_DEVICE_COMMAND + ":app:pixel2Api34DebugAndroidTest",
            "- name: Upload managed-device reports on failure",
        )

        previous_index = self.workflow.index("- name: Validate release tag")
        for required_step in required_steps:
            step_index = self.workflow.index(required_step)
            self.assertGreater(step_index, previous_index)
            self.assertLess(step_index, publish_index)
            previous_index = step_index
            self.assertIn(required_step, self.android_workflow)

    def test_signing_certificate_pin_is_checked_before_managed_devices(self) -> None:
        pin_index = self.workflow.index("- name: Validate public signing certificate pin")
        managed_device_index = self.workflow.index("- name: Enable KVM access")
        self.assertLess(pin_index, managed_device_index)
        self.assertIn("config/release-signing-certificate.sha256", self.workflow)
        self.assertIn("^[0-9a-fA-F]{64}$", self.workflow)

    def test_workflow_runs_release_tooling_regressions(self) -> None:
        regression_index = self.workflow.index("- name: Release tooling regression tests")
        publication_index = self.workflow.index("- name: Publish GitHub Release")
        self.assertLess(regression_index, publication_index)

    def test_packages_and_publishes_all_five_apk_variants(self) -> None:
        self.assertIn("scripts/package_release_apks.py", self.workflow)
        self.assertIn("-Palarmcontrol.releaseAbiApks=true", self.workflow)
        self.assertIn(
            "if (( ${#apks[@]} != 5 || ${#checksums[@]} != 5 )); then",
            self.workflow,
        )
        self.assertIn("sha256sum --check ./*.apk.sha256", self.workflow)
        self.assertIn('assets=("$release_dir"/*.apk "$release_dir"/*.apk.sha256)', self.workflow)
        self.assertIn("if (( ${#assets[@]} != 10 )); then", self.workflow)
        self.assertIn('"${assets[@]}"', self.workflow)
        self.assertNotIn("Expected one release APK", self.workflow)

    def test_release_abi_apks_are_opt_in_so_aab_verification_stays_single_output(
        self,
    ) -> None:
        self.assertIn(
            'gradleProperty("alarmcontrol.releaseAbiApks")',
            self.app_build,
        )
        self.assertIn("isEnable = releaseAbiApksEnabled.get()", self.app_build)
        self.assertIn(".orElse(false)", self.app_build)
        self.assertNotIn(
            "-Palarmcontrol.releaseAbiApks=true",
            self.android_workflow,
        )
        self.assertIn(":app:bundleRelease", self.android_workflow)

    def test_release_explains_abi_choice(self) -> None:
        for label in (
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64 / x86",
            "universal",
        ):
            self.assertIn(label, self.workflow)
        self.assertIn("GitHub does not select an ABI automatically", self.workflow)

    def test_remote_actions_are_immutably_pinned(self) -> None:
        uses_pattern = re.compile(r"^\s*(?:-\s*)?uses:\s+([^\s#]+)", re.MULTILINE)
        immutable_action = re.compile(r"[^@\s]+@[0-9a-fA-F]{40}")
        immutable_container = re.compile(
            r"docker://[^@\s]+@sha256:[0-9a-fA-F]{64}"
        )
        actions = uses_pattern.findall(self.workflow)
        unpinned = [
            action
            for action in actions
            if not (
                action.startswith("./")
                or immutable_action.fullmatch(action)
                or immutable_container.fullmatch(action)
            )
        ]

        self.assertEqual([], unpinned)


if __name__ == "__main__":
    unittest.main()
