from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import coupled_artifact_publisher as publisher  # noqa: E402


class CoupledArtifactPublisherTest(unittest.TestCase):
    def test_new_publication_directory_is_durably_linked_from_parent(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "nested" / "output" / "artifact.jsonl"

            with mock.patch.object(
                publisher,
                "_fsync_directory",
            ) as fsync_directory:
                normalized = publisher.normalize_publication_path(output)

            self.assertEqual(output.resolve(), normalized)
            synced = [call.args[0] for call in fsync_directory.call_args_list]
            self.assertIn(root, synced)
            self.assertIn(root / "nested", synced)
            self.assertIn(root / "nested" / "output", synced)

    def test_next_publication_removes_only_owned_orphan_temporaries(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            transaction_id = "a" * 32
            owned = (
                directory
                / f".{primary.name}.{transaction_id}.one.staging",
                directory
                / f".{manifest.name}.{transaction_id}.two.backup",
                directory
                / f".{primary.name}.{transaction_id}.three.recovery.staging",
                directory
                / (
                    "..dataset.publish.lock.transaction."
                    f"{transaction_id}.four.staging"
                ),
            )
            for path in owned:
                path.write_bytes(b"orphan")
            malformed = (
                directory / f".{primary.name}.not-a-transaction.keep.backup"
            )
            unrelated = directory / f".keep.{transaction_id}.keep.backup"
            malformed.write_bytes(b"keep")
            unrelated.write_bytes(b"keep")

            publisher.publish_coupled_files(
                {primary: b"data\n", manifest: b"manifest\n"},
                lock_name=".dataset.publish.lock",
            )

            self.assertTrue(all(not path.exists() for path in owned))
            self.assertEqual(b"keep", malformed.read_bytes())
            self.assertEqual(b"keep", unrelated.read_bytes())

    def test_concurrent_writers_cannot_mix_artifact_pair(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            primary = directory / "predictions.jsonl"
            manifest = directory / "predictions.jsonl.manifest.json"
            barrier = threading.Barrier(2)
            failures: list[BaseException] = []

            def write_pair(label: str) -> None:
                try:
                    barrier.wait()
                    for _ in range(10):
                        publisher.publish_coupled_files(
                            {
                                primary: f"{label}\n".encode(),
                                manifest: f"{label}-manifest\n".encode(),
                            },
                            lock_name=".predictions.publish.lock",
                        )
                except BaseException as error:
                    failures.append(error)

            threads = [
                threading.Thread(target=write_pair, args=(label,))
                for label in ("first", "second")
            ]
            for worker in threads:
                worker.start()
            for worker in threads:
                worker.join(5)

            self.assertEqual([], failures)
            self.assertTrue(all(not worker.is_alive() for worker in threads))
            self.assertIn(
                (primary.read_text(), manifest.read_text()),
                {
                    ("first\n", "first-manifest\n"),
                    ("second\n", "second-manifest\n"),
                },
            )

    def test_failed_second_replace_rolls_back_both_existing_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            publisher.publish_coupled_files(
                {primary: b"old-data\n", manifest: b"old-manifest\n"},
                lock_name=".dataset.publish.lock",
            )
            real_replace = publisher.os.replace
            failed = False

            def fail_manifest_once(source, destination):
                nonlocal failed
                if Path(destination).name == manifest.name and not failed:
                    failed = True
                    raise OSError("injected manifest replace failure")
                return real_replace(source, destination)

            with mock.patch.object(
                publisher.os,
                "replace",
                side_effect=fail_manifest_once,
            ):
                with self.assertRaisesRegex(OSError, "injected"):
                    publisher.publish_coupled_files(
                        {
                            primary: b"new-data\n",
                            manifest: b"new-manifest\n",
                        },
                        lock_name=".dataset.publish.lock",
                    )

            self.assertTrue(failed)
            self.assertEqual(b"old-data\n", primary.read_bytes())
            self.assertEqual(b"old-manifest\n", manifest.read_bytes())
            self.assertFalse(
                (directory / ".dataset.publish.lock.transaction").exists()
            )
            self.assertEqual([], list(directory.glob("*.backup")))
            self.assertEqual([], list(directory.glob("*.staging")))

    def test_symlink_lock_is_rejected_without_touching_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            outside = directory / "outside"
            outside.write_bytes(b"outside")
            lock = directory / ".dataset.publish.lock"
            try:
                lock.symlink_to(outside)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaises(publisher.CoupledArtifactError):
                publisher.publish_coupled_files(
                    {primary: b"data\n", manifest: b"manifest\n"},
                    lock_name=lock.name,
                )

            self.assertFalse(primary.exists())
            self.assertFalse(manifest.exists())
            self.assertEqual(b"outside", outside.read_bytes())

    def test_pending_journal_restores_last_complete_pair(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            primary.write_bytes(b"new-data\n")
            manifest.write_bytes(b"old-manifest\n")
            transaction_id = "a" * 32
            primary_backup = (
                directory / f".dataset.jsonl.{transaction_id}.old.backup"
            )
            manifest_backup = (
                directory / f".manifest.json.{transaction_id}.old.backup"
            )
            primary_backup.write_bytes(b"old-data\n")
            manifest_backup.write_bytes(b"old-manifest\n")
            journal = directory / ".dataset.publish.lock.transaction"
            journal.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "transaction_id": transaction_id,
                        "entries": [
                            {
                                "target": primary.name,
                                "backup": primary_backup.name,
                            },
                            {
                                "target": manifest.name,
                                "backup": manifest_backup.name,
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            publisher._recover_journal(
                directory,
                journal,
                {primary.name, manifest.name},
            )

            self.assertEqual(b"old-data\n", primary.read_bytes())
            self.assertEqual(b"old-manifest\n", manifest.read_bytes())
            self.assertFalse(journal.exists())
            self.assertFalse(primary_backup.exists())
            self.assertFalse(manifest_backup.exists())

    def test_journal_cannot_delete_an_unrelated_same_directory_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            unrelated = directory / "keep.txt"
            unrelated.write_bytes(b"keep")
            journal = directory / ".dataset.publish.lock.transaction"
            journal.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "transaction_id": "c" * 32,
                        "entries": [
                            {"target": primary.name, "backup": None},
                            {"target": unrelated.name, "backup": None},
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                publisher.CoupledArtifactError,
                "journal target",
            ):
                publisher._recover_journal(
                    directory,
                    journal,
                    {primary.name, manifest.name},
                )

            self.assertEqual(b"keep", unrelated.read_bytes())
            self.assertTrue(journal.is_file())

    def test_journal_without_transaction_id_cannot_delete_new_targets(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            primary.write_bytes(b"keep-data")
            manifest.write_bytes(b"keep-manifest")
            journal = directory / ".dataset.publish.lock.transaction"
            journal.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "entries": [
                            {"target": primary.name, "backup": None},
                            {"target": manifest.name, "backup": None},
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                publisher.CoupledArtifactError,
                "transaction_id",
            ):
                publisher._recover_journal(
                    directory,
                    journal,
                    {primary.name, manifest.name},
                )

            self.assertEqual(b"keep-data", primary.read_bytes())
            self.assertEqual(b"keep-manifest", manifest.read_bytes())
            self.assertTrue(journal.is_file())

    def test_journal_cannot_consume_an_unrelated_backup_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            primary = directory / "dataset.jsonl"
            manifest = directory / "manifest.json"
            unrelated = directory / "keep.txt"
            unrelated.write_bytes(b"keep")
            journal = directory / ".dataset.publish.lock.transaction"
            journal.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "transaction_id": "b" * 32,
                        "entries": [
                            {
                                "target": primary.name,
                                "backup": unrelated.name,
                            },
                            {"target": manifest.name, "backup": None},
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                publisher.CoupledArtifactError,
                "invalid publication backup",
            ):
                publisher._recover_journal(
                    directory,
                    journal,
                    {primary.name, manifest.name},
                )

            self.assertEqual(b"keep", unrelated.read_bytes())
            self.assertTrue(journal.is_file())


if __name__ == "__main__":
    unittest.main()
