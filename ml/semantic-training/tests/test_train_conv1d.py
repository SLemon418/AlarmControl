from __future__ import annotations

import hashlib
import json
import multiprocessing
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import train_conv1d as trainer  # noqa: E402


def _staged_bundle(output: Path, marker: bytes) -> dict[str, Path]:
    staged = trainer._staged_bundle_paths(output)
    for name, path in staged.items():
        path.write_bytes(marker + b"-" + name.encode())
    return staged


def _publish_marker_process(
    output_value: str,
    marker: bytes,
    start: multiprocessing.synchronize.Event,
) -> None:
    output = Path(output_value)
    staged = _staged_bundle(output, marker)
    start.wait()
    trainer._publish_staged_bundle(output, staged, lambda _paths: None)


def _publish_then_pause_process(
    output_value: str,
    marker: bytes,
    ready: multiprocessing.synchronize.Event,
) -> None:
    output = Path(output_value)
    staged = _staged_bundle(output, marker)

    def pause_after_validation(_paths: dict[str, Path]) -> None:
        ready.set()
        while True:
            time.sleep(1)

    trainer._publish_staged_bundle(output, staged, pause_after_validation)


class Conv1dTrainingScriptTest(unittest.TestCase):
    def test_training_manifest_reuses_the_loaded_dataset_snapshot_hash(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "dataset.jsonl"
            record = {
                "id": "one",
                "intent": "OTHER",
                "split": "train",
                "title": "Original",
                "body": "Original body",
            }
            original = (
                json.dumps(record, separators=(",", ":")) + "\n"
            ).encode()
            path.write_bytes(original)
            real_loader = trainer.validated_training_rows_snapshot

            def load_then_replace(selected_path):
                snapshot = real_loader(selected_path)
                path.write_bytes(original + b"\n")
                return snapshot

            with mock.patch.object(
                trainer,
                "validated_training_rows_snapshot",
                side_effect=load_then_replace,
            ):
                records, dataset_sha256 = trainer._load_dataset_snapshot(path)
            manifest = trainer._dataset_manifest(records, dataset_sha256)

            self.assertEqual(
                hashlib.sha256(original).hexdigest(),
                manifest["sha256"],
            )
            self.assertNotEqual(
                manifest["sha256"],
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )

    def test_manifest_records_runtime_nfc_text_contract(self) -> None:
        self.assertEqual(
            {
                "version": "runtime-title-body-space-nfc-v2",
                "template": "{title} {body}",
                "normalization": (
                    "join nonempty title/body with one literal ASCII space, "
                    "strip outer whitespace, then normalize to NFC"
                ),
            },
            trainer._text_format_manifest(),
        )

    def test_validation_failure_preserves_the_last_known_good_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            original = self._write_live_bundle(output, b"old")
            staged = self._write_staged_bundle(output, b"new")

            with self.assertRaisesRegex(ValueError, "invalid tensor contract"):
                trainer._publish_staged_bundle(
                    output,
                    staged,
                    lambda _paths: (_ for _ in ()).throw(
                        ValueError("invalid tensor contract")
                    ),
                )

            self.assertEqual(original, self._read_live_bundle(output))
            self.assertEqual([], self._transaction_artifacts(output))

    def test_pointer_publish_failure_preserves_committed_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            self._publish_marker(output, b"old")
            original = self._read_live_bundle(output)
            staged = self._write_staged_bundle(output, b"new")
            real_replace = os.replace

            def fail_pointer(source, destination):
                if Path(destination).name == trainer.CONVERSION_POINTER_FILENAME:
                    raise OSError("simulated publish failure")
                return real_replace(source, destination)

            with mock.patch(
                "atomic_generation.os.replace",
                side_effect=fail_pointer,
            ):
                with self.assertRaisesRegex(OSError, "simulated publish failure"):
                    trainer._publish_staged_bundle(
                        output,
                        staged,
                        lambda _paths: None,
                    )

            self.assertEqual(original, self._read_live_bundle(output))
            self.assertEqual([], self._transaction_artifacts(output))

    def test_concurrent_publishers_commit_one_complete_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            context = multiprocessing.get_context("fork")
            start = context.Event()
            processes = [
                context.Process(
                    target=_publish_marker_process,
                    args=(str(output), marker, start),
                )
                for marker in (b"first", b"second")
            ]
            for process in processes:
                process.start()
            start.set()
            for process in processes:
                process.join(timeout=10)
                self.assertEqual(0, process.exitcode)

            values = self._read_live_bundle(output)
            markers = {
                value.split(b"-", maxsplit=1)[0]
                for value in values.values()
            }
            self.assertIn(markers, ({b"first"}, {b"second"}))

    def test_sigkill_before_pointer_commit_leaves_old_generation_visible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            self._publish_marker(output, b"old")
            original = self._read_live_bundle(output)
            context = multiprocessing.get_context("fork")
            ready = context.Event()
            process = context.Process(
                target=_publish_then_pause_process,
                args=(str(output), b"new", ready),
            )
            process.start()
            self.assertTrue(ready.wait(timeout=10))
            process.kill()
            process.join(timeout=10)

            self.assertIsNotNone(process.exitcode)
            self.assertNotEqual(0, process.exitcode)
            self.assertEqual(original, self._read_live_bundle(output))
            generations = (
                output / trainer.CONVERSION_GENERATIONS_DIRECTORY
            )
            self.assertTrue(list(generations.glob(".pending-*")))

            self._publish_marker(output, b"recovered")

            self.assertEqual([], list(generations.glob(".pending-*")))
            self.assertEqual(
                {b"recovered"},
                {
                    value.split(b"-", maxsplit=1)[0]
                    for value in self._read_live_bundle(output).values()
                },
            )

    def test_manifest_hashes_bind_all_three_staged_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            staged = self._write_staged_bundle(output, b"new")
            contracts = trainer._bundle_file_contracts(staged)
            staged[trainer.MANIFEST_FILENAME].write_text(
                json.dumps({"artifact_bundle": contracts}),
                encoding="utf-8",
            )

            trainer._validate_conversion_bundle(staged, contracts)
            staged[trainer.VOCAB_FILENAME].write_bytes(b"changed")

            with self.assertRaisesRegex(ValueError, "changed before publication"):
                trainer._validate_conversion_bundle(staged, contracts)

    @staticmethod
    def _write_live_bundle(output: Path, marker: bytes) -> dict[str, bytes]:
        values = {
            name: marker + b"-" + name.encode()
            for name in trainer.CONVERSION_BUNDLE_FILES
        }
        for name, value in values.items():
            (output / name).write_bytes(value)
        return values

    @staticmethod
    def _write_staged_bundle(output: Path, marker: bytes) -> dict[str, Path]:
        return _staged_bundle(output, marker)

    @staticmethod
    def _publish_marker(output: Path, marker: bytes) -> None:
        trainer._publish_staged_bundle(
            output,
            _staged_bundle(output, marker),
            lambda _paths: None,
        )

    @staticmethod
    def _read_live_bundle(output: Path) -> dict[str, bytes]:
        directory = trainer.resolve_conversion_bundle(output)
        return {
            name: (directory / name).read_bytes()
            for name in trainer.CONVERSION_BUNDLE_FILES
        }

    @staticmethod
    def _transaction_artifacts(output: Path) -> list[str]:
        return sorted(
            path.name
            for path in output.iterdir()
            if path.name.endswith((".staging", ".backup"))
        )


if __name__ == "__main__":
    unittest.main()
