import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

from asset_publisher import (
    CURRENT_POINTER,
    GENERATIONS_DIRECTORY,
    PENDING_GENERATION_PREFIX,
    PENDING_MARKER,
    publish_asset_set,
    resolve_committed_asset_set,
)


class AssetPublisherTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary_directory.name)
        self.original = {
            "notification_classifier.tflite": b"old-model",
            "vocab.txt": b"old-vocab\n",
            "labels.txt": b"old-labels\n",
        }
        self.replacement = {
            "notification_classifier.tflite": b"new-model",
            "vocab.txt": b"new-vocab\n",
            "labels.txt": b"new-labels\n",
        }
        for name, payload in self.original.items():
            (self.directory / name).write_bytes(payload)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_validation_reads_an_unpublished_generation_before_commit(self):
        validation_calls = []

        def validate(staged):
            validation_calls.append(staged)
            self.assertEqual(
                self.directory / GENERATIONS_DIRECTORY,
                next(iter(staged.values())).parent.parent,
            )
            self.assertEqual(self.original, self._read_live())
            self.assertEqual(
                self.replacement,
                {name: path.read_bytes() for name, path in staged.items()},
            )

        publish_asset_set(self.directory, self.replacement, validate)

        self.assertEqual(1, len(validation_calls))
        self.assertEqual(self.replacement, self._read_live())
        self.assertEqual([], self._transaction_artifacts())

    def test_validation_failure_preserves_last_known_good_set(self):
        def reject(_staged):
            raise MemoryError("validation failed")

        with self.assertRaises(MemoryError):
            publish_asset_set(self.directory, self.replacement, reject)

        self.assertEqual(self.original, self._read_live())
        self.assertEqual([], self._transaction_artifacts())

    def test_pointer_publish_failure_preserves_the_last_committed_set(self):
        real_replace = os.replace

        def fail_pointer_publish(source, destination):
            if Path(destination).name == CURRENT_POINTER:
                raise OSError("simulated publish failure")
            return real_replace(source, destination)

        with mock.patch("asset_publisher.os.replace", side_effect=fail_pointer_publish):
            with self.assertRaises(OSError):
                publish_asset_set(self.directory, self.replacement, lambda _staged: None)

        self.assertEqual(self.original, self._read_live())
        self.assertEqual([], self._transaction_artifacts())

    def test_post_replace_interrupt_keeps_the_selected_generation(self):
        real_replace = os.replace

        def replace_then_interrupt(source, destination):
            real_replace(source, destination)
            if Path(destination).name == CURRENT_POINTER:
                raise KeyboardInterrupt("after pointer replacement")

        with mock.patch(
            "asset_publisher.os.replace",
            side_effect=replace_then_interrupt,
        ):
            with self.assertRaisesRegex(
                KeyboardInterrupt,
                "after pointer replacement",
            ):
                publish_asset_set(
                    self.directory,
                    self.replacement,
                    lambda _staged: None,
                )

        self.assertEqual(self.replacement, self._read_live())
        generation_id = (self.directory / CURRENT_POINTER).read_text().strip()
        self.assertTrue(
            (
                self.directory
                / GENERATIONS_DIRECTORY
                / generation_id
                / "notification_classifier.tflite"
            ).is_file()
        )
        selected = (
            self.directory / GENERATIONS_DIRECTORY / generation_id
        )
        self.assertTrue((selected / PENDING_MARKER).is_file())

        publish_asset_set(
            self.directory,
            self.replacement,
            lambda _staged: None,
        )

        self.assertTrue(selected.is_dir())
        self.assertFalse((selected / PENDING_MARKER).exists())

    def test_next_publish_removes_only_explicit_pending_generations(self):
        generations = self.directory / GENERATIONS_DIRECTORY
        generations.mkdir()
        pending_id = "a" * 32
        renamed_id = "b" * 32
        historical_id = "c" * 32
        pending = generations / f"{PENDING_GENERATION_PREFIX}{pending_id}"
        pending.mkdir()
        (pending / "partial").write_bytes(b"partial")
        renamed = generations / renamed_id
        renamed.mkdir()
        (renamed / PENDING_MARKER).write_text(
            f"{renamed_id}\n",
            encoding="ascii",
        )
        historical = generations / historical_id
        historical.mkdir()
        (historical / "keep").write_bytes(b"keep")
        pending_staging = (
            self.directory / f".{CURRENT_POINTER}.{pending_id}.staging"
        )
        renamed_staging = (
            self.directory / f".{CURRENT_POINTER}.{renamed_id}.staging"
        )
        pending_staging.write_text(f"{pending_id}\n", encoding="ascii")
        renamed_staging.write_text(f"{renamed_id}\n", encoding="ascii")

        publish_asset_set(
            self.directory,
            self.replacement,
            lambda _staged: None,
        )

        self.assertFalse(pending.exists())
        self.assertFalse(renamed.exists())
        self.assertFalse(pending_staging.exists())
        self.assertFalse(renamed_staging.exists())
        self.assertEqual(b"keep", (historical / "keep").read_bytes())

    def test_complete_generation_without_pointer_is_never_selected(self):
        abandoned = self.directory / GENERATIONS_DIRECTORY / ("a" * 32)
        abandoned.mkdir(parents=True)
        for name, payload in self.replacement.items():
            (abandoned / name).write_bytes(payload)

        self.assertEqual(self.original, self._read_live())

    def test_symlink_destination_and_generations_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / "outside"
            outside.mkdir()
            destination = root / "assets"
            try:
                destination.symlink_to(outside, target_is_directory=True)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")
            with self.assertRaises(ValueError):
                publish_asset_set(destination, self.replacement, lambda _staged: None)
            self.assertEqual([], list(outside.iterdir()))

        generations = self.directory / GENERATIONS_DIRECTORY
        outside = self.directory / "outside-generations"
        outside.mkdir()
        try:
            generations.symlink_to(outside, target_is_directory=True)
        except (NotImplementedError, OSError) as error:
            self.skipTest(f"symlink unavailable: {error}")
        with self.assertRaises(ValueError):
            publish_asset_set(self.directory, self.replacement, lambda _staged: None)
        self.assertEqual([], list(outside.iterdir()))

    def test_validator_cannot_replace_staged_asset_with_symlink(self):
        outside = self.directory / "outside-model"
        outside.write_bytes(b"outside")

        def replace_with_symlink(staged):
            model = staged["notification_classifier.tflite"]
            model.unlink()
            try:
                model.symlink_to(outside)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

        with self.assertRaises(ValueError):
            publish_asset_set(
                self.directory,
                self.replacement,
                replace_with_symlink,
            )

        self.assertEqual(self.original, self._read_live())
        self.assertEqual(b"outside", outside.read_bytes())

    def test_concurrent_publishers_never_expose_a_mixed_generation(self):
        alternate = {
            "notification_classifier.tflite": b"alternate-model",
            "vocab.txt": b"alternate-vocab\n",
            "labels.txt": b"alternate-labels\n",
        }
        started = threading.Barrier(2)
        stop_reader = threading.Event()
        observed = []

        def publish(payloads):
            started.wait()
            publish_asset_set(self.directory, payloads, lambda _staged: None)

        def read_until_stopped():
            while not stop_reader.is_set():
                observed.append(self._read_live())

        reader = threading.Thread(target=read_until_stopped)
        reader.start()
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(publish, self.replacement),
                executor.submit(publish, alternate),
            ]
            for future in futures:
                future.result()
        stop_reader.set()
        reader.join()

        self.assertTrue(observed)
        self.assertTrue(
            all(value in (self.original, self.replacement, alternate) for value in observed)
        )
        self.assertIn(self._read_live(), (self.replacement, alternate))
        self.assertEqual([], self._transaction_artifacts())

    def _read_live(self):
        committed = resolve_committed_asset_set(self.directory, self.original)
        return {
            name: committed[name].read_bytes()
            for name in self.original
        }

    def _transaction_artifacts(self):
        return sorted(
            path.name
            for path in self.directory.rglob("*")
            if path.name.endswith((".staging", ".backup"))
        )


if __name__ == "__main__":
    unittest.main()
