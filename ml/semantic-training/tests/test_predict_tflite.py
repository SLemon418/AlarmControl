from __future__ import annotations

import hashlib
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import predict_tflite as predictor  # noqa: E402
from atomic_generation import publish_generation  # noqa: E402
from semantic_contract import (  # noqa: E402
    CONVERSION_BUNDLE_FILES,
    CONVERSION_GENERATIONS_DIRECTORY,
    CONVERSION_POINTER_FILENAME,
    MAX_SEQUENCE_LENGTH,
)


class TflitePredictionScriptTest(unittest.TestCase):
    def test_source_digest_stays_bound_to_the_loaded_records(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "dataset.jsonl"
            original_record = {
                "id": "one",
                "locale": "en",
                "intent": "OTHER",
                "pair_id": "pair-one",
                "title": "Original",
                "body": "Original body",
                "injection": False,
                "split": "test",
            }
            original = (
                json.dumps(original_record, separators=(",", ":")) + "\n"
            ).encode()
            path.write_bytes(original)
            real_loader = predictor.load_jsonl_snapshot

            def load_then_replace(selected_path):
                snapshot = real_loader(selected_path)
                changed = dict(original_record)
                changed["body"] = "changed after snapshot"
                path.write_text(
                    json.dumps(changed, separators=(",", ":")) + "\n",
                    encoding="utf-8",
                )
                return snapshot

            with mock.patch.object(
                predictor,
                "load_jsonl_snapshot",
                side_effect=load_then_replace,
            ):
                records, digest = predictor._load_source_records(path, "test")

            self.assertEqual(original_record["body"], records[0]["body"])
            self.assertEqual(hashlib.sha256(original).hexdigest(), digest)
            self.assertNotEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_output_leaf_symlink_is_rejected_without_touching_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            outside = directory / "outside.jsonl"
            outside.write_bytes(b"keep")
            output = directory / "predictions.jsonl"
            try:
                output.symlink_to(outside)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaisesRegex(ValueError, "must not be a symlink"):
                predictor._resolve_output_path(output)

            self.assertEqual(b"keep", outside.read_bytes())
            self.assertTrue(output.is_symlink())

    def test_model_evidence_rejects_replacement_and_input_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            model = directory / "model.tflite"
            model.write_bytes(b"same-model")
            evidence = predictor._file_evidence(model, "model")
            replacement = directory / "replacement.tflite"
            replacement.write_bytes(b"same-model")
            replacement.replace(model)

            with self.assertRaisesRegex(ValueError, "changed while predictions"):
                predictor._require_unchanged_file(
                    model,
                    "model",
                    evidence,
                )

            link = directory / "linked-model.tflite"
            try:
                link.symlink_to(model)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")
            with self.assertRaisesRegex(ValueError, "non-symlink regular file"):
                predictor._resolve_regular_input(link, "model")

    def test_prediction_writes_fsync_file_and_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            writers = (
                (
                    predictor._atomic_jsonl,
                    directory / "predictions.jsonl",
                    [{"id": "one"}],
                ),
                (
                    predictor._atomic_json,
                    directory / "predictions.jsonl.manifest.json",
                    {"row_count": 1},
                ),
            )

            for writer, output, value in writers:
                with self.subTest(output=output.name):
                    with (
                        mock.patch.object(predictor.os, "fsync") as file_fsync,
                        mock.patch.object(
                            predictor,
                            "_fsync_directory",
                        ) as directory_fsync,
                    ):
                        writer(output, value)

                    file_fsync.assert_called_once()
                    directory_fsync.assert_called_once_with(directory)

    def test_prediction_writes_preserve_existing_file_when_replace_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            writers = (
                (
                    predictor._atomic_jsonl,
                    directory / "predictions.jsonl",
                    [{"id": "new"}],
                ),
                (
                    predictor._atomic_json,
                    directory / "predictions.jsonl.manifest.json",
                    {"row_count": 2},
                ),
            )

            for writer, output, value in writers:
                with self.subTest(output=output.name):
                    output.write_text("existing\n", encoding="utf-8")
                    with mock.patch.object(
                        predictor.os,
                        "replace",
                        side_effect=OSError("replace failed"),
                    ):
                        with self.assertRaisesRegex(OSError, "replace failed"):
                            writer(output, value)

                    self.assertEqual(
                        "existing\n",
                        output.read_text(encoding="utf-8"),
                    )
                    self.assertEqual(
                        [],
                        list(directory.glob(f".{output.name}.*.tmp")),
                    )

    def test_prediction_writes_preserve_existing_file_when_file_fsync_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            writers = (
                (
                    predictor._atomic_jsonl,
                    directory / "predictions.jsonl",
                    [{"id": "new"}],
                ),
                (
                    predictor._atomic_json,
                    directory / "predictions.jsonl.manifest.json",
                    {"row_count": 2},
                ),
            )

            for writer, output, value in writers:
                with self.subTest(output=output.name):
                    output.write_text("existing\n", encoding="utf-8")
                    with (
                        mock.patch.object(
                            predictor.os,
                            "fsync",
                            side_effect=OSError("file fsync failed"),
                        ),
                        mock.patch.object(predictor.os, "replace") as replace,
                    ):
                        with self.assertRaisesRegex(
                            OSError,
                            "file fsync failed",
                        ):
                            writer(output, value)

                    replace.assert_not_called()
                    self.assertEqual(
                        "existing\n",
                        output.read_text(encoding="utf-8"),
                    )
                    self.assertEqual(
                        [],
                        list(directory.glob(f".{output.name}.*.tmp")),
                    )

    def test_prediction_manifest_uses_common_v2_binding(self) -> None:
        self.assertEqual(
            "alarmcontrol-semantic-prediction-v1",
            predictor.PREDICTION_SCHEMA_VERSION,
        )
        self.assertEqual(
            "alarmcontrol-semantic-prediction-manifest-v2",
            predictor.MANIFEST_SCHEMA_VERSION,
        )
        self.assertEqual("tensorflow-lite", predictor.BACKEND)
        self.assertEqual(128, MAX_SEQUENCE_LENGTH)

    def test_source_validation_is_lightweight_and_strict(self) -> None:
        record = {
            "id": "en-other-001-clean",
            "locale": "en",
            "intent": "OTHER",
            "pair_id": "en-other-001",
            "title": "Reminder",
            "body": "Bring a notebook",
            "injection": False,
        }

        predictor._validate_source_record(record, "source")
        record["injection"] = 1
        with self.assertRaises(ValueError):
            predictor._validate_source_record(record, "source")

    def test_softmax_matches_deployed_float32_arithmetic(self) -> None:
        probabilities = predictor._softmax(
            [1.0, 0.0, -1.0, -2.0, -3.0, -4.0, -5.0]
        )
        bits = [
            struct.unpack(">I", struct.pack(">f", value))[0]
            for value in probabilities
        ]

        self.assertEqual(
            [
                0x3F21F877,
                0x3E6E57B3,
                0x3DAF5CD7,
                0x3D01064E,
                0x3C3DDCAF,
                0x3B8BB154,
                0x3ACD8F6E,
            ],
            bits,
        )
        self.assertTrue(
            all(
                value == predictor._float32(value)
                for value in probabilities
            )
        )

    def test_explicit_conv1d_paths_resolve_once_to_the_same_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)

            def writer(directory: Path) -> None:
                for name in CONVERSION_BUNDLE_FILES:
                    (directory / name).write_bytes(name.encode())

            generation = publish_generation(
                output,
                pointer_name=CONVERSION_POINTER_FILENAME,
                generations_name=CONVERSION_GENERATIONS_DIRECTORY,
                required_files=CONVERSION_BUNDLE_FILES,
                writer=writer,
            )

            model, vocab = predictor._resolve_model_and_vocab(
                output / predictor.CONVERSION_MODEL_FILENAME,
                output / predictor.CONVERSION_VOCAB_FILENAME,
            )

            self.assertEqual(generation.resolve(), model.parent)
            self.assertEqual(generation.resolve(), vocab.parent)


if __name__ == "__main__":
    unittest.main()
