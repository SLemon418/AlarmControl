from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import atomic_generation as generation  # noqa: E402


class AtomicGenerationSafetyTest(unittest.TestCase):
    def test_dangling_pointer_symlink_is_not_treated_as_no_pointer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            container = Path(temporary)
            pointer = container / "current"
            try:
                pointer.symlink_to(container / "missing-generation")
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaisesRegex(
                generation.GenerationError,
                "pointer must be a regular file",
            ):
                generation.resolve_generation(
                    container,
                    pointer_name="current",
                    generations_name="generations",
                    required_files=("artifact.bin",),
                    legacy=container / "legacy",
                )

    def test_post_replace_interrupt_keeps_the_selected_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            container = Path(temporary)
            real_replace = os.replace

            def replace_then_interrupt(source, destination):
                real_replace(source, destination)
                if Path(destination).name == "current":
                    raise KeyboardInterrupt("after pointer replacement")

            with mock.patch.object(
                generation.os,
                "replace",
                side_effect=replace_then_interrupt,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt,
                    "after pointer replacement",
                ):
                    generation.publish_generation(
                        container,
                        pointer_name="current",
                        generations_name="generations",
                        required_files=("artifact.bin",),
                        writer=lambda directory: (
                            directory / "artifact.bin"
                        ).write_bytes(b"payload"),
                    )

            generation_id = (container / "current").read_text().strip()
            selected = container / "generations" / generation_id
            self.assertEqual(b"payload", (selected / "artifact.bin").read_bytes())
            self.assertTrue(
                (selected / generation.PENDING_MARKER).is_file()
            )
            self.assertEqual(
                selected.resolve(),
                generation.resolve_generation(
                    container,
                    pointer_name="current",
                    generations_name="generations",
                    required_files=("artifact.bin",),
                    legacy=container / "legacy",
                ),
            )

            generation.publish_generation(
                container,
                pointer_name="current",
                generations_name="generations",
                required_files=("artifact.bin",),
                writer=lambda directory: (
                    directory / "artifact.bin"
                ).write_bytes(b"next"),
            )

            self.assertTrue(selected.is_dir())
            self.assertFalse(
                (selected / generation.PENDING_MARKER).exists()
            )

    def test_next_publish_removes_only_explicit_pending_generations(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            container = Path(temporary)
            generations = container / "generations"
            generations.mkdir()
            pending_id = "a" * 32
            renamed_id = "b" * 32
            historical_id = "c" * 32
            pending = (
                generations
                / f"{generation.PENDING_GENERATION_PREFIX}{pending_id}"
            )
            pending.mkdir()
            (pending / "partial").write_bytes(b"partial")
            renamed = generations / renamed_id
            renamed.mkdir()
            (renamed / generation.PENDING_MARKER).write_text(
                f"{renamed_id}\n",
                encoding="ascii",
            )
            historical = generations / historical_id
            historical.mkdir()
            (historical / "artifact.bin").write_bytes(b"historical")
            pending_staging = (
                container / f".current.{pending_id}.one.staging"
            )
            renamed_staging = (
                container / f".current.{renamed_id}.two.staging"
            )
            pending_staging.write_text(f"{pending_id}\n", encoding="ascii")
            renamed_staging.write_text(f"{renamed_id}\n", encoding="ascii")

            generation.publish_generation(
                container,
                pointer_name="current",
                generations_name="generations",
                required_files=("artifact.bin",),
                writer=lambda directory: (
                    directory / "artifact.bin"
                ).write_bytes(b"new"),
            )

            self.assertFalse(pending.exists())
            self.assertFalse(renamed.exists())
            self.assertFalse(pending_staging.exists())
            self.assertFalse(renamed_staging.exists())
            self.assertEqual(
                b"historical",
                (historical / "artifact.bin").read_bytes(),
            )

    def test_publish_rejects_symlink_container(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / "outside"
            outside.mkdir()
            container = root / "container"
            try:
                container.symlink_to(outside, target_is_directory=True)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaises(generation.GenerationError):
                generation.publish_generation(
                    container,
                    pointer_name="current",
                    generations_name="generations",
                    required_files=("artifact.bin",),
                    writer=lambda directory: (
                        directory / "artifact.bin"
                    ).write_bytes(b"payload"),
                )

            self.assertEqual([], list(outside.iterdir()))

    def test_publish_rejects_symlink_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            container = Path(temporary)
            outside = container / "outside"
            outside.write_bytes(b"outside")
            lock = container / "current.lock"
            try:
                lock.symlink_to(outside)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaises(generation.GenerationError):
                generation.publish_generation(
                    container,
                    pointer_name="current",
                    generations_name="generations",
                    required_files=("artifact.bin",),
                    writer=lambda directory: (
                        directory / "artifact.bin"
                    ).write_bytes(b"payload"),
                )

            self.assertEqual(b"outside", outside.read_bytes())
            self.assertFalse((container / "current").exists())


if __name__ == "__main__":
    unittest.main()
