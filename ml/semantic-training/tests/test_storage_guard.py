from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import storage_guard


class StorageGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        storage_guard.initialize_root(self.root)

    def _disposable(
        self,
        relative_path: str,
        payload: bytes = b"payload",
    ) -> Path:
        directory = self.root / relative_path
        directory.mkdir(parents=True)
        (directory / storage_guard.DISPOSABLE_MARKER).write_text("", encoding="utf-8")
        (directory / "artifact.bin").write_bytes(payload)
        return directory

    def _policy_for_one_deletion(
        self,
        first: Path,
    ) -> storage_guard.StoragePolicy:
        total = storage_guard.tree_bytes(self.root)
        first_size = storage_guard.tree_bytes(first)
        return storage_guard.StoragePolicy(
            soft_bytes=total,
            cleanup_target_bytes=total - first_size + 1,
            hard_bytes=total + 100,
            min_free_bytes=0,
        )

    def test_root_marker_is_required_and_init_is_idempotent(self) -> None:
        other_root = self.root / "other"
        other_root.mkdir()
        with self.assertRaises(storage_guard.MarkerError):
            storage_guard.check_root_marker(other_root)

        first = storage_guard.initialize_root(other_root)
        second = storage_guard.initialize_root(other_root)

        self.assertEqual(first, second)
        self.assertEqual(
            storage_guard.ROOT_MARKER_CONTENT,
            __import__("json").loads(first.read_text(encoding="utf-8")),
        )

    def test_dry_run_plans_oldest_first_without_deleting(self) -> None:
        old = self._disposable("cache/old", b"old-data")
        new = self._disposable("tmp/new", b"new-data")
        os.utime(old, ns=(1_000, 1_000))
        os.utime(new, ns=(2_000, 2_000))
        policy = self._policy_for_one_deletion(old)

        report = storage_guard.enforce_storage(
            self.root,
            policy,
            apply=False,
            free_bytes=10_000,
        )

        self.assertEqual("cleanup-required", report["status"])
        self.assertEqual(["cache/old"], [item["path"] for item in report["cleanup"]["planned"]])
        self.assertTrue(old.exists())
        self.assertTrue(new.exists())

    def test_apply_deletes_oldest_until_strictly_below_target(self) -> None:
        old = self._disposable("cache/old", b"old-data")
        new = self._disposable("tmp/new", b"new-data")
        os.utime(old, ns=(1_000, 1_000))
        os.utime(new, ns=(2_000, 2_000))
        policy = self._policy_for_one_deletion(old)

        report = storage_guard.enforce_storage(
            self.root,
            policy,
            apply=True,
            free_bytes=10_000,
        )

        self.assertEqual("ready", report["status"])
        self.assertFalse(old.exists())
        self.assertTrue(new.exists())
        self.assertLess(report["run_bytes"], policy.cleanup_target_bytes)
        self.assertEqual(["cache/old"], [item["path"] for item in report["cleanup"]["deleted"]])

    def test_only_marked_unprotected_disposable_directories_are_candidates(self) -> None:
        eligible = self._disposable("cache/eligible")
        unmarked = self.root / "tmp/unmarked"
        unmarked.mkdir(parents=True)
        (unmarked / "artifact.bin").write_bytes(b"keep")

        best = self._disposable("checkpoints/best")
        last = self._disposable("checkpoints/last")
        selected = self._disposable("checkpoints/selected")
        marked_protected = self._disposable("checkpoints/ordinary")
        (marked_protected / ".protected").write_text("", encoding="utf-8")
        config = self._disposable("seed-weights/configured")
        (config / "config.json").write_text("{}", encoding="utf-8")
        tokenizer = self._disposable("seed-weights/tokenizer-copy")
        (tokenizer / "tokenizer.json").write_text("{}", encoding="utf-8")
        final_model = self._disposable("failed-conversions/model")
        (final_model / "final_model.tflite").write_bytes(b"model")
        model_card = self._disposable("failed-conversions/model-card-copy")
        (model_card / "MODEL_CARD.md").write_text("model", encoding="utf-8")
        dataset = self._disposable("cache/training-copy")
        (dataset / "datasets").mkdir()
        (dataset / "datasets/examples.jsonl").write_text("{}\n", encoding="utf-8")
        manifest = self._disposable("cache/manifest-copy")
        (manifest / "manifest.json").write_text("{}", encoding="utf-8")
        license_file = self._disposable("cache/license-copy")
        (license_file / "LICENSE.txt").write_text("terms", encoding="utf-8")
        evaluation = self._disposable("tmp/evaluation-copy")
        (evaluation / "evaluations").mkdir()
        (evaluation / "evaluations/results.json").write_text("{}", encoding="utf-8")
        outside_allowlist = self._disposable("exports/marked")

        candidates = storage_guard.cleanup_candidates(self.root)

        self.assertEqual(["cache/eligible"], [candidate.relative_path for candidate in candidates])
        self.assertTrue(eligible.exists())
        self.assertTrue(unmarked.exists())
        self.assertTrue(best.exists())
        self.assertTrue(last.exists())
        self.assertTrue(selected.exists())
        self.assertTrue(marked_protected.exists())
        self.assertTrue(config.exists())
        self.assertTrue(tokenizer.exists())
        self.assertTrue(final_model.exists())
        self.assertTrue(model_card.exists())
        self.assertTrue(dataset.exists())
        self.assertTrue(manifest.exists())
        self.assertTrue(license_file.exists())
        self.assertTrue(evaluation.exists())
        self.assertTrue(outside_allowlist.exists())

    def test_symlink_anywhere_in_run_root_fails_closed(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        container = self.root / "cache"
        container.mkdir()
        link = container / "escape"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except (NotImplementedError, OSError) as error:
            self.skipTest(f"symlink unavailable: {error}")

        with self.assertRaises(storage_guard.SafetyError):
            storage_guard.inspect_storage(self.root, free_bytes=10_000)

    def test_out_of_root_candidate_is_rejected(self) -> None:
        outside_parent = self.root.parent / f"{self.root.name}-outside"
        outside_parent.mkdir()
        self.addCleanup(lambda: outside_parent.rmdir() if outside_parent.exists() else None)
        (outside_parent / storage_guard.DISPOSABLE_MARKER).write_text("", encoding="utf-8")

        with self.assertRaises(storage_guard.SafetyError):
            storage_guard.delete_candidate(self.root, outside_parent)

        self.assertTrue(outside_parent.exists())
        (outside_parent / storage_guard.DISPOSABLE_MARKER).unlink()

    def test_parent_symlink_swap_cannot_redirect_candidate_deletion(self) -> None:
        candidate = self._disposable("cache/victim", b"inside")
        outside = self.root.parent / f"{self.root.name}-outside-swap"
        outside_candidate = outside / "victim"
        outside_candidate.mkdir(parents=True)
        outside_payload = outside_candidate / "artifact.bin"
        outside_payload.write_bytes(b"outside")
        detached_container = self.root / "cache-detached"
        original_snapshot = storage_guard._snapshot_open_directory
        resolved_candidate = candidate.resolve()
        swapped = False

        def swap_after_snapshot(*args, **kwargs):
            nonlocal swapped
            tree = original_snapshot(*args, **kwargs)
            if tree.path == resolved_candidate and not swapped:
                (self.root / "cache").rename(detached_container)
                try:
                    (self.root / "cache").symlink_to(
                        outside,
                        target_is_directory=True,
                    )
                except (NotImplementedError, OSError) as error:
                    self.skipTest(f"symlink unavailable: {error}")
                swapped = True
            return tree

        try:
            with mock.patch.object(
                storage_guard,
                "_snapshot_open_directory",
                side_effect=swap_after_snapshot,
            ):
                deleted = storage_guard.delete_candidate(self.root, candidate)

            self.assertGreater(deleted, 0)
            self.assertTrue(swapped)
            self.assertTrue(outside_payload.is_file())
            self.assertFalse((detached_container / "victim").exists())
        finally:
            swapped_link = self.root / "cache"
            if swapped_link.is_symlink():
                swapped_link.unlink()
            if detached_container.exists():
                detached_container.rename(self.root / "cache")
            if outside_payload.exists():
                outside_payload.unlink()
            if outside_candidate.exists():
                outside_candidate.rmdir()
            if outside.exists():
                outside.rmdir()

    def test_low_filesystem_free_space_is_a_hard_stop(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 20,
            min_free_bytes=100,
        )

        report = storage_guard.inspect_storage(self.root, policy, free_bytes=99)

        self.assertEqual("hard-stop", report["status"])
        self.assertEqual(
            ["filesystem-free-below-hard-minimum"],
            report["hard_reasons"],
        )

    def test_run_bytes_at_hard_limit_is_a_hard_stop(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=max(1, current - 1),
            cleanup_target_bytes=0,
            hard_bytes=current,
            min_free_bytes=0,
        )

        report = storage_guard.inspect_storage(self.root, policy, free_bytes=1_000)

        self.assertEqual("hard-stop", report["status"])
        self.assertIn("run-bytes-at-or-above-hard-limit", report["hard_reasons"])

    def test_launch_sets_cpu_only_environment_and_runs_one_child(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 20,
            min_free_bytes=0,
        )
        calls = []

        def fake_runner(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0)

        code, report = storage_guard.launch_guarded(
            self.root,
            [sys.executable, "-c", "pass"],
            policy,
            free_bytes=1_000,
            runner=fake_runner,
        )

        self.assertEqual(0, code)
        self.assertEqual("ready", report["status"])
        self.assertEqual(1, len(calls))
        environment = calls[0][1]["env"]
        for name in (
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
        ):
            self.assertEqual("2", environment[name])
        self.assertEqual("0", environment["PYTORCH_ENABLE_MPS_FALLBACK"])
        self.assertEqual("", environment["CUDA_VISIBLE_DEVICES"])
        self.assertEqual("1", environment["MAX_JOBS"])
        self.assertEqual("1", environment["STORAGE_GUARD_MAX_SUBPROCESSES"])

    def test_launch_dry_run_never_starts_child(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 20,
            min_free_bytes=0,
        )

        def forbidden_runner(*args, **kwargs):
            self.fail(f"runner called during dry-run: {args}, {kwargs}")

        code, report = storage_guard.launch_guarded(
            self.root,
            ["ignored-command"],
            policy,
            dry_run=True,
            free_bytes=1_000,
            runner=forbidden_runner,
        )

        self.assertEqual(0, code)
        self.assertEqual("ready", report["status"])

    def test_launch_post_run_bytes_plan_safe_cleanup_without_deleting(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 100,
            min_free_bytes=0,
        )
        created: list[Path] = []

        def fake_runner(command, **kwargs):
            created.append(self._disposable("cache/post-run", b"x" * 32))
            return subprocess.CompletedProcess(command, 0)

        code, report = storage_guard.launch_guarded(
            self.root,
            ["write-disposable-output"],
            policy,
            apply=False,
            free_bytes=1_000,
            runner=fake_runner,
        )

        self.assertEqual(storage_guard.POST_RUN_STORAGE_FAILURE_EXIT_CODE, code)
        self.assertEqual("cleanup-required", report["status"])
        self.assertEqual(
            ["cache/post-run"],
            [item["path"] for item in report["cleanup"]["planned"]],
        )
        self.assertFalse(report["cleanup"]["applied"])
        self.assertEqual(1, len(created))
        self.assertTrue(created[0].exists())

    def test_launch_post_run_apply_cleans_new_safe_candidate(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 100,
            min_free_bytes=0,
        )
        created: list[Path] = []

        def fake_runner(command, **kwargs):
            created.append(self._disposable("tmp/post-run", b"x" * 32))
            return subprocess.CompletedProcess(command, 0)

        code, report = storage_guard.launch_guarded(
            self.root,
            ["write-disposable-output"],
            policy,
            apply=True,
            free_bytes=1_000,
            runner=fake_runner,
        )

        self.assertEqual(0, code)
        self.assertEqual("ready", report["status"])
        self.assertTrue(report["cleanup"]["applied"])
        self.assertEqual(
            ["tmp/post-run"],
            [item["path"] for item in report["cleanup"]["deleted"]],
        )
        self.assertEqual(1, len(created))
        self.assertFalse(created[0].exists())

    def test_launch_post_run_hard_stop_preserves_failed_child_code(self) -> None:
        current = storage_guard.tree_bytes(self.root)
        policy = storage_guard.StoragePolicy(
            soft_bytes=current + 10,
            cleanup_target_bytes=current + 5,
            hard_bytes=current + 20,
            min_free_bytes=0,
        )

        def successful_runner(command, **kwargs):
            (self.root / "unmanaged-success.bin").write_bytes(b"x" * 32)
            return subprocess.CompletedProcess(command, 0)

        success_code, success_report = storage_guard.launch_guarded(
            self.root,
            ["write-unmanaged-output"],
            policy,
            free_bytes=1_000,
            runner=successful_runner,
        )

        self.assertEqual(storage_guard.POST_RUN_STORAGE_FAILURE_EXIT_CODE, success_code)
        self.assertEqual("hard-stop", success_report["status"])
        self.assertIn(
            "run-bytes-at-or-above-hard-limit",
            success_report["hard_reasons"],
        )

        (self.root / "unmanaged-success.bin").unlink()

        def failed_runner(command, **kwargs):
            (self.root / "unmanaged-failure.bin").write_bytes(b"x" * 32)
            return subprocess.CompletedProcess(command, 17)

        child_code, failed_report = storage_guard.launch_guarded(
            self.root,
            ["fail-after-writing-output"],
            policy,
            free_bytes=1_000,
            runner=failed_runner,
        )

        self.assertEqual(17, child_code)
        self.assertEqual("hard-stop", failed_report["status"])

if __name__ == "__main__":
    unittest.main()
