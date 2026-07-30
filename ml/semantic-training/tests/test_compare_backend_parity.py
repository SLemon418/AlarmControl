from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import compare_backend_parity as parity  # noqa: E402
from semantic_contract import (  # noqa: E402
    LABELS,
    RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
    model_bundle_hashes,
)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def probability_map(predicted: str, confidence: float) -> dict[str, float]:
    remainder = (1.0 - confidence) / (len(LABELS) - 1)
    return {
        label: confidence if label == predicted else remainder
        for label in LABELS
    }


class BackendParityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "dataset.jsonl"
        self.source_manifest = self.root / "manifest.json"
        self.model_dir = self.root / "trained-model"
        self.model_dir.mkdir()
        (self.model_dir / "model.safetensors").write_bytes(b"trained")
        (self.model_dir / "vocab.txt").write_text(
            "[PAD]\n[UNK]\n[CLS]\n[SEP]\n",
            encoding="utf-8",
        )
        _, self.bundle_sha256 = model_bundle_hashes(self.model_dir)
        self.vocab_sha256 = sha256_file(self.model_dir / "vocab.txt")

        self.tflite_model = self.root / "semantic_classifier.tflite"
        self.tflite_model.write_bytes(b"converted-model")
        self.tflite_sha256 = sha256_file(self.tflite_model)
        self.tflite_vocab = self.root / "semantic_vocab.txt"
        self.tflite_vocab.write_bytes(
            (self.model_dir / "vocab.txt").read_bytes()
        )

        source_rows: list[dict[str, object]] = []
        prediction_rows: list[dict[str, object]] = []
        locale_intents = (
            ("ko", "MARKETING"),
            ("en", "SECURITY"),
            ("mixed", "OTHER"),
        )
        for locale, intent in locale_intents:
            pair_id = f"{locale}_{intent.lower()}_pair"
            for injection in (False, True):
                identifier = (
                    f"{pair_id}_{'injected' if injection else 'clean'}"
                )
                source_rows.append(
                    {
                        "id": identifier,
                        "locale": locale,
                        "intent": intent,
                        "pair_id": pair_id,
                        "injection": injection,
                        "title": f"fictional {locale}",
                        "body": f"synthetic {intent} {injection}",
                        "split": "test",
                    }
                )
                prediction_rows.append(
                    {
                        "id": identifier,
                        "locale": locale,
                        "intent": intent,
                        "pair_id": pair_id,
                        "injection": injection,
                        "predicted_intent": intent,
                        "confidence": 0.99,
                        "probabilities": probability_map(intent, 0.99),
                        "split": "test",
                    }
                )
        self._write_jsonl(self.source, source_rows)
        self.source_manifest.write_text(
            json.dumps(
                {
                    "schema_version": "semantic-dataset-manifest-v3",
                    "row_count": len(source_rows),
                    "files": {
                        "dataset.jsonl": {
                            "sha256": sha256_file(self.source),
                            "rows": len(source_rows),
                        }
                    },
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

        self.pytorch_predictions = self.root / "pytorch.jsonl"
        self.tflite_predictions = self.root / "tflite.jsonl"
        self._write_jsonl(self.pytorch_predictions, prediction_rows)
        tflite_rows = json.loads(json.dumps(prediction_rows))
        changed = tflite_rows[-1]
        changed["predicted_intent"] = "SOCIAL"
        changed["confidence"] = 0.98
        changed["probabilities"] = probability_map("SOCIAL", 0.98)
        self._write_jsonl(self.tflite_predictions, tflite_rows)
        self._write_prediction_manifest(
            self.pytorch_predictions,
            backend=parity.PYTORCH_BACKEND,
        )
        self._write_prediction_manifest(
            self.tflite_predictions,
            backend=parity.TFLITE_BACKEND,
        )

        self.conversion_manifest = self.root / "conversion_manifest.json"
        self._write_conversion_manifest()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
        path.write_text(
            "".join(
                json.dumps(row, sort_keys=True) + "\n"
                for row in rows
            ),
            encoding="utf-8",
        )

    def _write_prediction_manifest(self, path: Path, *, backend: str) -> None:
        manifest: dict[str, object] = {
            "schema_version": "alarmcontrol-semantic-prediction-manifest-v2",
            "prediction_schema_version": "alarmcontrol-semantic-prediction-v1",
            "backend": backend,
            "input": str(self.source),
            "input_sha256": sha256_file(self.source),
            "output": str(path),
            "output_sha256": sha256_file(path),
            "model_artifact_sha256": (
                self.bundle_sha256
                if backend == parity.PYTORCH_BACKEND
                else self.tflite_sha256
            ),
            "selected_split": "test",
            "row_count": 6,
            "vocab_sha256": self.vocab_sha256,
        }
        if backend == parity.PYTORCH_BACKEND:
            manifest.update(
                {
                    "model_bundle": str(self.model_dir),
                    "model_bundle_sha256": self.bundle_sha256,
                }
            )
        else:
            manifest.update(
                {
                    "model": str(self.tflite_model),
                    "model_sha256": self.tflite_sha256,
                    "vocab": str(self.tflite_vocab),
                }
            )
        path.with_suffix(f"{path.suffix}.manifest.json").write_text(
            json.dumps(manifest, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def _write_conversion_manifest(
        self,
        *,
        bundle_sha256: str | None = None,
        vocab_sha256: str | None = None,
    ) -> None:
        self.conversion_manifest.write_text(
            json.dumps(
                {
                    "schema_version": parity.CONVERSION_SCHEMA_VERSION,
                    "labels": list(LABELS),
                    "source": {
                        "model_bundle_sha256": (
                            bundle_sha256 or self.bundle_sha256
                        ),
                        "vocab_sha256": vocab_sha256 or self.vocab_sha256,
                    },
                    "artifact": {"sha256": self.tflite_sha256},
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )

    def _build(self) -> dict[str, object]:
        return parity.build_parity_report(
            pytorch_predictions=self.pytorch_predictions,
            tflite_predictions=self.tflite_predictions,
            source_manifest=self.source_manifest,
            conversion_manifest=self.conversion_manifest,
            thresholds={
                "general": RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
                "marketing": RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
            },
        )

    def test_builds_content_free_hash_bound_report(self) -> None:
        report = self._build()

        self.assertEqual(parity.SCHEMA_VERSION, report["schema_version"])
        self.assertEqual("test", report["split"])
        self.assertEqual(6, report["row_count"])
        self.assertEqual(
            RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
            report["general_threshold"],
        )
        self.assertEqual(
            RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
            report["marketing_threshold"],
        )
        self.assertNotIn("threshold", report)
        self.assertNotIn("thresholds", report)
        self.assertEqual(
            5,
            report["agreement"]["raw_argmax"]["count"],
        )
        self.assertEqual(
            self.bundle_sha256,
            report["provenance"]["pytorch_model_bundle_sha256"],
        )
        self.assertEqual(
            self.tflite_sha256,
            report["provenance"]["tflite_model_sha256"],
        )
        serialized = json.dumps(report, sort_keys=True)
        self.assertNotIn(str(self.root), serialized)
        self.assertNotIn("pair_id", serialized)
        self.assertNotIn("title", serialized)
        self.assertNotIn("body", serialized)

    def test_report_reuses_validated_prediction_snapshot_hashes(self) -> None:
        pytorch_sha256 = sha256_file(self.pytorch_predictions)
        tflite_sha256 = sha256_file(self.tflite_predictions)
        real_loader = parity.load_bound_prediction_set
        completed_loads = 0

        def load_then_replace(paths, source_manifest):
            nonlocal completed_loads
            bound = real_loader(paths, source_manifest)
            completed_loads += 1
            if completed_loads == 2:
                self.pytorch_predictions.write_bytes(b"later-pytorch\n")
                self.tflite_predictions.write_bytes(b"later-tflite\n")
            return bound

        with mock.patch.object(
            parity,
            "load_bound_prediction_set",
            side_effect=load_then_replace,
        ):
            report = self._build()

        self.assertEqual(
            pytorch_sha256,
            report["provenance"]["pytorch_predictions_sha256"],
        )
        self.assertEqual(
            tflite_sha256,
            report["provenance"]["tflite_predictions_sha256"],
        )

    def test_manifest_switch_during_snapshot_load_is_rejected(self) -> None:
        real_loader = parity.load_bound_prediction_set
        switched = False

        def switch_then_load(paths, source_manifest):
            nonlocal switched
            if not switched:
                manifest_path = self.pytorch_predictions.with_suffix(
                    f"{self.pytorch_predictions.suffix}.manifest.json"
                )
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                manifest["model_bundle"] = str(self.root / "switched-bundle")
                manifest_path.write_text(
                    json.dumps(manifest, sort_keys=True) + "\n",
                    encoding="utf-8",
                )
                switched = True
            return real_loader(paths, source_manifest)

        with mock.patch.object(
            parity,
            "load_bound_prediction_set",
            side_effect=switch_then_load,
        ):
            with self.assertRaisesRegex(
                parity.ParityError,
                "changed while its prediction snapshot was loaded",
            ):
                self._build()

    def test_conversion_hashes_use_the_parsed_manifest_snapshot(self) -> None:
        original = self.conversion_manifest.read_bytes()
        real_loads = parity.json.loads
        switched = False

        def parse_then_switch(*args, **kwargs):
            nonlocal switched
            value = real_loads(*args, **kwargs)
            if not switched:
                self._write_conversion_manifest(bundle_sha256="a" * 64)
                switched = True
            return value

        with mock.patch.object(
            parity.json,
            "loads",
            side_effect=parse_then_switch,
        ):
            hashes = parity._conversion_hashes(self.conversion_manifest)

        self.assertEqual(hashlib.sha256(original).hexdigest(), hashes["manifest"])
        self.assertEqual(self.bundle_sha256, hashes["bundle"])

    def test_cli_output_is_byte_deterministic(self) -> None:
        output = self.root / "report" / "parity.json"
        arguments = [
            "--pytorch-predictions",
            str(self.pytorch_predictions),
            "--tflite-predictions",
            str(self.tflite_predictions),
            "--source-manifest",
            str(self.source_manifest),
            "--conversion-manifest",
            str(self.conversion_manifest),
            "--general-threshold",
            str(RELEASE_CONFIDENCE_THRESHOLD_FLOOR),
            "--marketing-threshold",
            str(RELEASE_CONFIDENCE_THRESHOLD_FLOOR),
            "--output",
            str(output),
        ]
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, parity.main(arguments))
        first = output.read_bytes()
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, parity.main(arguments))
        self.assertEqual(first, output.read_bytes())

    def test_rejects_incomplete_or_non_float32_thresholds(self) -> None:
        arguments = {
            "pytorch_predictions": self.pytorch_predictions,
            "tflite_predictions": self.tflite_predictions,
            "source_manifest": self.source_manifest,
            "conversion_manifest": self.conversion_manifest,
        }
        with self.assertRaisesRegex(
            parity.ParityError,
            "exactly general and marketing",
        ):
            parity.build_parity_report(
                **arguments,
                thresholds={"general": RELEASE_CONFIDENCE_THRESHOLD_FLOOR},
            )
        with self.assertRaisesRegex(parity.ParityError, "float32"):
            parity.build_parity_report(
                **arguments,
                thresholds={
                    "general": 0.95,
                    "marketing": RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
                },
            )

    def test_cli_requires_both_threshold_fields(self) -> None:
        parser = parity.build_parser()
        arguments = [
            "--pytorch-predictions",
            "pytorch.jsonl",
            "--tflite-predictions",
            "tflite.jsonl",
            "--source-manifest",
            "manifest.json",
            "--conversion-manifest",
            "conversion.json",
            "--general-threshold",
            str(RELEASE_CONFIDENCE_THRESHOLD_FLOOR),
            "--output",
            "parity.json",
        ]
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                parser.parse_args(arguments)

    def test_rejects_mismatched_bundle_or_vocabulary(self) -> None:
        for field in ("bundle", "vocab"):
            with self.subTest(field=field):
                if field == "bundle":
                    self._write_conversion_manifest(bundle_sha256="a" * 64)
                else:
                    self._write_conversion_manifest(vocab_sha256="b" * 64)
                with self.assertRaises(parity.ParityError):
                    self._build()
                self._write_conversion_manifest()

    def test_keeps_dataset_v2_manifest_compatibility(self) -> None:
        manifest = json.loads(
            self.source_manifest.read_text(encoding="utf-8")
        )
        manifest["schema_version"] = "semantic-dataset-manifest-v2"
        self.source_manifest.write_text(
            json.dumps(manifest, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        report = self._build()

        self.assertEqual(6, report["row_count"])

    def test_rejects_sealed_manifest_before_reading_source_rows(self) -> None:
        self.source.unlink()
        self.source_manifest.write_text(
            json.dumps(
                {
                    "schema_version": "semantic-sealed-holdout-manifest-v2",
                    "files": {},
                }
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(
            parity.ParityError,
            "sealed holdouts must not be read",
        ):
            self._build()


if __name__ == "__main__":
    unittest.main()
