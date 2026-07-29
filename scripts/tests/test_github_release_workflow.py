from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"
WORKFLOW = WORKFLOWS / "github-release.yml"
ANDROID_WORKFLOW = WORKFLOWS / "android.yml"
MANAGED_DEVICE_COMMAND = (
    "run: ./gradlew --dependency-verification strict "
    "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect "
)


class GitHubReleaseWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.android_workflow = ANDROID_WORKFLOW.read_text(encoding="utf-8")

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
