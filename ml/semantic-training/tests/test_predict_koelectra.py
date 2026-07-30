from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import predict_koelectra as predictor  # noqa: E402
from atomic_generation import PENDING_MARKER  # noqa: E402
from semantic_contract import LABELS, MAX_SEQUENCE_LENGTH  # noqa: E402


class KoElectraPredictionScriptTest(unittest.TestCase):
    def test_source_digest_stays_bound_to_the_loaded_records(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "dataset.jsonl"
            original_record = self._source_record("validation-clean")
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

            with patch.object(
                predictor,
                "load_jsonl_snapshot",
                side_effect=load_then_replace,
            ):
                records, digest = predictor.select_source_records_snapshot(
                    path,
                    None,
                )

            self.assertEqual(original_record["body"], records[0]["body"])
            self.assertEqual(hashlib.sha256(original).hexdigest(), digest)
            self.assertNotEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_output_leaf_symlink_is_rejected_without_touching_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            outside = directory / "outside.jsonl"
            outside.write_bytes(b"keep")
            output = directory / "predictions.jsonl"
            try:
                output.symlink_to(outside)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")

            with self.assertRaisesRegex(
                predictor.PredictionInputError,
                "must not be a symlink",
            ):
                predictor._resolve_output_path(output)

            self.assertEqual(b"keep", outside.read_bytes())
            self.assertTrue(output.is_symlink())

    def test_prediction_schema_and_runtime_contract_are_exact(self) -> None:
        self.assertEqual(
            "alarmcontrol-semantic-prediction-v1",
            predictor.PREDICTION_SCHEMA_VERSION,
        )
        self.assertEqual(
            "alarmcontrol-semantic-prediction-manifest-v2",
            predictor.MANIFEST_SCHEMA_VERSION,
        )
        self.assertEqual(
            (
                "id",
                "locale",
                "intent",
                "injection",
                "pair_id",
                "predicted_intent",
                "confidence",
                "probabilities",
                "split",
            ),
            predictor.PREDICTION_FIELDS,
        )
        self.assertEqual(128, MAX_SEQUENCE_LENGTH)
        self.assertEqual(16, predictor.MAX_BATCH_SIZE)
        self.assertEqual(2, predictor.MAX_THREADS)
        self.assertEqual(4 * predictor.GIB, predictor.DEFAULT_MAX_RSS_BYTES)

    def test_select_source_records_validates_and_filters_split(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "dataset.jsonl"
            rows = [
                self._source_record("train-clean", split="train"),
                self._source_record("validation-clean", split="validation"),
            ]
            path.write_text(
                "\n".join(
                    json.dumps(row, separators=(",", ":"))
                    for row in rows
                )
                + "\n",
                encoding="utf-8",
            )

            selected = predictor.select_source_records(
                path,
                "validation",
            )

            self.assertEqual(["validation-clean"], [row["id"] for row in selected])

    def test_source_validation_rejects_duplicates_and_empty_text(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "holdout.jsonl"
            duplicate = self._source_record("same")
            path.write_text(
                f"{json.dumps(duplicate)}\n{json.dumps(duplicate)}\n",
                encoding="utf-8",
            )
            with self.assertRaises(predictor.PredictionInputError):
                predictor.select_source_records(path, None)

            empty = self._source_record("empty")
            empty["title"] = ""
            empty["body"] = ""
            path.write_text(f"{json.dumps(empty)}\n", encoding="utf-8")
            with self.assertRaises(predictor.PredictionInputError):
                predictor.select_source_records(path, None)

    def test_local_bundle_hashes_are_stable_and_reject_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            model_dir = Path(temporary_directory) / "model"
            model_dir.mkdir()
            (model_dir / "config.json").write_text("{}\n", encoding="utf-8")
            (model_dir / "model.safetensors").write_bytes(b"weights")
            (model_dir / "vocab.txt").write_text(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\n",
                encoding="utf-8",
            )

            first_files, first_digest = predictor.model_bundle_hashes(model_dir)
            second_files, second_digest = predictor.model_bundle_hashes(model_dir)

            self.assertEqual(first_files, second_files)
            self.assertEqual(first_digest, second_digest)
            self.assertEqual(
                {"config.json", "model.safetensors", "vocab.txt"},
                set(first_files),
            )

            marker = model_dir / PENDING_MARKER
            marker.write_text("a" * 32 + "\n", encoding="ascii")
            marked_files, marked_digest = predictor.model_bundle_hashes(
                model_dir
            )
            self.assertEqual(first_files, marked_files)
            self.assertEqual(first_digest, marked_digest)
            marker.unlink()

            evidence = predictor.model_bundle_evidence(model_dir)
            replacement = model_dir / "replacement-config.json"
            replacement.write_text("{}\n", encoding="utf-8")
            replacement.replace(model_dir / "config.json")
            with self.assertRaisesRegex(
                predictor.PredictionInputError,
                "changed while predictions",
            ):
                predictor._require_unchanged_model_bundle(
                    model_dir,
                    evidence,
                )

            link = model_dir / "linked"
            try:
                link.symlink_to(model_dir / "config.json")
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")
            with self.assertRaises(predictor.PredictionInputError):
                predictor.model_bundle_hashes(model_dir)

    def test_argument_limits_require_local_bundle_and_small_batch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            model_dir = root / "model"
            model_dir.mkdir()
            (model_dir / "config.json").write_text("{}\n", encoding="utf-8")
            (model_dir / "model.safetensors").write_bytes(b"weights")
            input_path = root / "input.jsonl"
            input_path.write_text("{}\n", encoding="utf-8")

            valid = argparse.Namespace(
                model_dir=model_dir,
                input=input_path,
                output=root / "predictions.jsonl",
                split=None,
                batch_size=16,
                max_rss_bytes=predictor.DEFAULT_MAX_RSS_BYTES,
            )
            result = predictor._validate_arguments(valid)
            self.assertEqual(model_dir.resolve(), result.model_dir)

            invalid = argparse.Namespace(
                model_dir=model_dir,
                input=input_path,
                output=root / "predictions.jsonl",
                split=None,
                batch_size=17,
                max_rss_bytes=predictor.DEFAULT_MAX_RSS_BYTES,
            )
            with self.assertRaises(predictor.PredictionInputError):
                predictor._validate_arguments(invalid)

    def test_atomic_jsonl_and_memory_ceiling_are_lightweight(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "predictions.jsonl"
            record = {
                field: field for field in predictor.PREDICTION_FIELDS
            }
            predictor._atomic_jsonl(output, [record])
            self.assertEqual(record, json.loads(output.read_text(encoding="utf-8")))

        with patch.object(predictor, "_maximum_rss_bytes", return_value=100):
            self.assertEqual(100, predictor.enforce_memory_ceiling(101, "test"))
            with self.assertRaises(predictor.ResourceLimitExceeded):
                predictor.enforce_memory_ceiling(100, "test")

    def test_offline_environment_disables_accelerators_and_parallelism(self) -> None:
        predictor.configure_offline_cpu_environment()

        environment = __import__("os").environ
        self.assertEqual("", environment["CUDA_VISIBLE_DEVICES"])
        self.assertEqual("1", environment["TRANSFORMERS_OFFLINE"])
        self.assertEqual("false", environment["TOKENIZERS_PARALLELISM"])
        for name in (
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
            "RAYON_NUM_THREADS",
        ):
            self.assertEqual("2", environment[name])

    @staticmethod
    def _source_record(
        identifier: str,
        *,
        split: str | None = None,
    ) -> dict[str, object]:
        record: dict[str, object] = {
            "id": identifier,
            "locale": "en",
            "intent": LABELS[0],
            "pair_id": f"pair-{identifier}",
            "injection": False,
            "title": "Fictional title",
            "body": "Fictional body",
        }
        if split is not None:
            record["split"] = split
        return record


if __name__ == "__main__":
    unittest.main()
