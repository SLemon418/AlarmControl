from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"
WORKFLOW = WORKFLOWS / "github-release.yml"
ANDROID_WORKFLOW = WORKFLOWS / "android.yml"
APP_BUILD = Path(__file__).resolve().parents[2] / "app" / "build.gradle.kts"
ROOT_BUILD = Path(__file__).resolve().parents[2] / "build.gradle.kts"
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
        cls.root_build = ROOT_BUILD.read_text(encoding="utf-8")

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

    def test_release_notes_report_release_commit_gates(self) -> None:
        for result in (
            "API 34 managed-device suites passed",
            "`releaseCandidate` passed JVM, quality, offline",
            "for this tag commit",
        ):
            self.assertIn(result, self.workflow)

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

    def test_pin_scanner_includes_composite_actions_outside_dot_github(self) -> None:
        self.assertIn("Files.walkFileTree(", self.root_build)
        self.assertIn('file.name == "action.yml"', self.root_build)
        self.assertIn('file.name == "action.yaml"', self.root_build)
        for excluded in (".git", "build", "generated", "vendor"):
            self.assertIn(f'"{excluded}"', self.root_build)

    def test_pin_scanner_parses_yaml_and_rejects_parser_differentials(self) -> None:
        for required_guard in (
            "Load(loadSettings)",
            "setAllowDuplicateKeys(false)",
            "setAllowRecursiveKeys(false)",
            "setMaxAliasesForCollections(50)",
            "must normalize escaped YAML keys",
            "must recognize explicit keys and block scalars",
            "must resolve aliases used as keys",
            "must safely resolve YAML merge keys",
            "must honor explicit values over YAML merge defaults",
            "must reject duplicate YAML keys",
            "must reject unrecognized YAML tags",
        ):
            self.assertIn(required_guard, self.root_build)

    def test_pin_scanner_does_not_follow_symbolic_links(self) -> None:
        self.assertIn("Files.walkFileTree(", self.root_build)
        self.assertIn("Files.isSymbolicLink(file)", self.root_build)
        self.assertIn(
            "must reject symbolic-link path components",
            self.root_build,
        )
        self.assertIn(
            "scan paths must not contain symbolic links",
            self.root_build,
        )

    def test_local_docker_actions_require_all_external_images_to_be_pinned(self) -> None:
        self.assertIn("pinnedDockerfileFailures.isEmpty()", self.root_build)
        for external_source in (
            "syntax frontend is not pinned",
            "allowed docker/dockerfile repository",
            "FROM image is not pinned",
            "COPY has a malformed --from option",
            "RUN mount has a malformed from entry",
            "ADD is unsupported",
            "ONBUILD external sources are unsupported",
            "Dockerfile heredocs are unsupported",
            "tokens split across continuations",
            "parser-directive whitespace rules",
            "normalize quoted and escaped builder flags and mount CSV",
            "not treat an escaped trailing escape as a continuation",
            "match BuildKit's current three-escape behavior",
            "preserve whitespace on continuation lines",
            "only trim Docker-supported continuation whitespace",
            "not report non-continuation terminal escapes",
            "alternate escape directives are unsupported",
        ):
            self.assertIn(external_source, self.root_build)

        self.assertIn(
            "must ignore inputs.image and with.image outside image schemas",
            self.root_build,
        )

    def test_release_tag_uses_strict_semver_without_leading_zeroes(self) -> None:
        self.assertIn(
            r'^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$',
            self.workflow,
        )
        self.assertIn(
            "strict vMAJOR.MINOR.PATCH without leading zeroes",
            self.workflow,
        )


if __name__ == "__main__":
    unittest.main()
