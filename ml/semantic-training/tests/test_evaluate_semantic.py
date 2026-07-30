from __future__ import annotations

import hashlib
import io
import json
import math
import struct
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import evaluate_semantic as evaluator  # noqa: E402
from semantic_contract import LABELS  # noqa: E402


def thresholds(
    *,
    general: float = evaluator.DEFAULT_TRUST_THRESHOLD,
    marketing: float = evaluator.DEFAULT_TRUST_THRESHOLD,
) -> dict[str, float]:
    return {
        "general": general,
        "marketing": marketing,
    }


def probability_map(predicted: str, confidence: float) -> dict[str, float]:
    remainder = (1.0 - confidence) / (len(LABELS) - 1)
    return {
        label: confidence if label == predicted else remainder
        for label in LABELS
    }


def prediction_rows(
    *,
    pairs_per_group: int = 1,
    split: str | None = None,
) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for locale in evaluator.LOCALES:
        for intent in LABELS:
            for pair_number in range(pairs_per_group):
                pair_id = f"{locale}_{intent}_{pair_number:02d}"
                for injection in (False, True):
                    identifier = (
                        f"{pair_id}_{'injected' if injection else 'clean'}"
                    )
                    row: dict[str, object] = {
                        "id": identifier,
                        "locale": locale,
                        "intent": intent,
                        "injection": injection,
                        "pair_id": pair_id,
                        "predicted_intent": intent,
                        "confidence": 0.95,
                        "probabilities": probability_map(intent, 0.95),
                    }
                    if split is not None:
                        row["split"] = split
                    rows.append(row)
    return rows


def set_prediction(
    row: dict[str, object],
    predicted: str,
    confidence: float,
) -> None:
    row["predicted_intent"] = predicted
    row["confidence"] = confidence
    row["probabilities"] = probability_map(predicted, confidence)


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(
            json.dumps(row, separators=(",", ":")) + "\n"
            for row in rows
        ),
        encoding="utf-8",
    )


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_rows(
    rows: list[dict[str, object]],
    *,
    keep_split: bool,
) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for row in rows:
        source = {
            field: row[field]
            for field in evaluator.SOURCE_BINDING_FIELDS
        }
        source["title"] = f"title {row['id']}"
        source["body"] = f"body {row['id']}"
        if keep_split:
            source["split"] = row["split"]
        result.append(source)
    return result


def write_prediction_manifest(
    prediction_path: Path,
    input_path: Path,
    row_count: int,
    selected_split: str | None,
    **overrides: object,
) -> Path:
    manifest: dict[str, object] = {
        "schema_version": evaluator.PREDICTION_MANIFEST_SCHEMA_VERSION,
        "prediction_schema_version": "alarmcontrol-semantic-prediction-v1",
        "backend": "unit-test",
        "input": str(input_path),
        "input_sha256": sha256_file(input_path),
        "output": str(prediction_path),
        "output_sha256": sha256_file(prediction_path),
        "model_artifact_sha256": "a" * 64,
        "selected_split": selected_split,
        "row_count": row_count,
        "backend_detail": "retained",
    }
    manifest.update(overrides)
    manifest_path = prediction_path.with_suffix(
        f"{prediction_path.suffix}.manifest.json"
    )
    manifest_path.write_text(
        json.dumps(manifest, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    return manifest_path


class SemanticEvaluationTest(unittest.TestCase):
    def test_accepts_probability_object_and_label_ordered_array(self) -> None:
        rows = prediction_rows()
        first = rows[0]
        first["probabilities"] = [
            first["probabilities"][label]
            for label in LABELS
        ]

        normalized = evaluator.validate_prediction_records(rows)

        self.assertEqual(set(LABELS), set(normalized[0]["probabilities"]))
        self.assertEqual(42, len(normalized))

    def test_rejects_invalid_schema_labels_probabilities_and_pairs(self) -> None:
        mutations = (
            "extra_field",
            "invalid_intent",
            "invalid_prediction",
            "non_boolean_injection",
            "duplicate_id",
            "missing_probability",
            "short_probability_array",
            "non_finite_probability",
            "not_normalized",
            "confidence_mismatch",
            "not_argmax",
            "broken_pair",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                rows = prediction_rows()
                if mutation == "extra_field":
                    rows[0]["package_name"] = "forbidden"
                elif mutation == "invalid_intent":
                    rows[0]["intent"] = "UNKNOWN"
                elif mutation == "invalid_prediction":
                    rows[0]["predicted_intent"] = "UNKNOWN"
                elif mutation == "non_boolean_injection":
                    rows[0]["injection"] = 1
                elif mutation == "duplicate_id":
                    rows[1]["id"] = rows[0]["id"]
                elif mutation == "missing_probability":
                    del rows[0]["probabilities"]["AMBIGUOUS"]
                elif mutation == "short_probability_array":
                    rows[0]["probabilities"] = [0.2] * 6
                elif mutation == "non_finite_probability":
                    rows[0]["probabilities"]["MARKETING"] = math.nan
                elif mutation == "not_normalized":
                    rows[0]["probabilities"] = {
                        label: 0.2
                        for label in LABELS
                    }
                elif mutation == "confidence_mismatch":
                    rows[0]["confidence"] = 0.8
                elif mutation == "not_argmax":
                    rows[0]["predicted_intent"] = "OTHER"
                    rows[0]["confidence"] = rows[0]["probabilities"]["OTHER"]
                else:
                    rows[1]["pair_id"] = "different"

                with self.assertRaises(evaluator.EvaluationError):
                    evaluator.validate_prediction_records(rows)

    def test_reports_confusion_locale_injection_and_pair_metrics(self) -> None:
        rows = prediction_rows()

        report = evaluator.evaluate_predictions(rows, thresholds())

        self.assertEqual(
            evaluator.EVALUATION_SCHEMA_VERSION,
            report["schema_version"],
        )
        self.assertEqual(
            evaluator.DEFAULT_TRUST_THRESHOLD,
            report["general_threshold"],
        )
        self.assertEqual(
            evaluator.DEFAULT_TRUST_THRESHOLD,
            report["marketing_threshold"],
        )
        self.assertNotIn("threshold", report)
        self.assertNotIn("thresholds", report)
        self.assertEqual(1.0, report["raw"]["overall"]["macro_f1"])
        self.assertEqual(
            1.0,
            report["raw"]["overall"]["marketing_precision"],
        )
        self.assertEqual(
            {locale: 1.0 for locale in evaluator.LOCALES},
            {
                locale: report["raw"]["by_locale"][locale]["macro_f1"]
                for locale in evaluator.LOCALES
            },
        )
        self.assertEqual(
            21,
            report["raw"]["by_injection"]["clean"]["row_count"],
        )
        self.assertEqual(
            21,
            report["raw"]["by_injection"]["injected"]["row_count"],
        )
        self.assertEqual(
            6,
            report["raw"]["overall"]["confusion"]["SECURITY"]["SECURITY"],
        )
        self.assertEqual(
            1.0,
            report["pair_consistency"]["raw_same_prediction_rate"],
        )
        self.assertEqual(
            {
                "trusted_count": 36,
                "row_count": 36,
                "rate": 1.0,
            },
            report["trusted_coverage"]["overall"],
        )
        self.assertEqual(
            {
                locale: {
                    "trusted_count": 12,
                    "row_count": 12,
                    "rate": 1.0,
                }
                for locale in evaluator.LOCALES
            },
            report["trusted_coverage"]["by_locale"],
        )

    def test_runtime_abstains_only_low_confidence_non_ambiguous(self) -> None:
        rows = prediction_rows()
        low_other = next(
            row
            for row in rows
            if row["intent"] == "OTHER" and not row["injection"]
        )
        low_ambiguous = next(
            row
            for row in rows
            if row["intent"] == "AMBIGUOUS" and not row["injection"]
        )
        set_prediction(low_other, "OTHER", 0.40)
        set_prediction(low_ambiguous, "AMBIGUOUS", 0.40)

        report = evaluator.evaluate_predictions(rows, thresholds())

        self.assertEqual(
            1,
            report["runtime"]["abstained_to_ambiguous_count"],
        )
        self.assertEqual(
            6,
            report["runtime"]["natural_ambiguous_argmax_count"],
        )
        self.assertEqual(
            7,
            report["runtime"]["ambiguous_output_count"],
        )
        self.assertEqual(
            35 / 36,
            report["trusted_coverage"]["overall"]["rate"],
        )
        self.assertEqual(1.0, report["raw"]["overall"]["macro_f1"])
        self.assertLess(report["runtime"]["overall"]["macro_f1"], 1.0)

    def test_runtime_applies_marketing_threshold_only_to_marketing(self) -> None:
        rows = prediction_rows()
        confidence = evaluator._float32(0.97)
        marketing = next(
            row
            for row in rows
            if row["intent"] == "MARKETING" and not row["injection"]
        )
        other = next(
            row
            for row in rows
            if row["intent"] == "OTHER" and not row["injection"]
        )
        set_prediction(marketing, "MARKETING", confidence)
        set_prediction(other, "OTHER", confidence)
        selected = thresholds(
            marketing=evaluator._next_float32_up(confidence),
        )

        self.assertEqual(
            "AMBIGUOUS",
            evaluator.runtime_output(marketing, selected),
        )
        self.assertEqual("OTHER", evaluator.runtime_output(other, selected))
        ambiguous = next(
            row
            for row in rows
            if row["intent"] == "AMBIGUOUS" and not row["injection"]
        )
        self.assertEqual(
            "AMBIGUOUS",
            evaluator.runtime_output(ambiguous, selected),
        )

    def test_default_gate_enforces_trusted_injected_safety(self) -> None:
        rows = prediction_rows(pairs_per_group=10)
        unsafe = next(
            row
            for row in rows
            if row["locale"] == "ko"
            and row["intent"] == "SECURITY"
            and row["injection"]
        )
        unsafe_confidence = evaluator._float32(0.97)
        set_prediction(unsafe, "MARKETING", unsafe_confidence)

        failed = evaluator.evaluate_and_gate(rows, thresholds())
        passed = evaluator.evaluate_and_gate(
            rows,
            thresholds(
                marketing=evaluator._next_float32_up(unsafe_confidence),
            ),
        )

        self.assertGreaterEqual(
            failed["evaluation"]["raw"]["overall"]["macro_f1"],
            0.85,
        )
        self.assertGreaterEqual(
            failed["evaluation"]["raw"]["overall"]["marketing_precision"],
            0.90,
        )
        self.assertFalse(failed["gate"]["passed"])
        self.assertEqual(
            1,
            failed["gate"]["checks"][
                "trusted_marketing_false_positives"
            ]["actual"],
        )
        self.assertTrue(passed["gate"]["passed"])

    def test_safety_counts_every_trusted_non_marketing_false_positive(self) -> None:
        rows = prediction_rows(pairs_per_group=10)
        clean_other = next(
            row
            for row in rows
            if row["locale"] == "en"
            and row["intent"] == "OTHER"
            and not row["injection"]
        )
        injected_social = next(
            row
            for row in rows
            if row["locale"] == "mixed"
            and row["intent"] == "SOCIAL"
            and row["injection"]
        )
        set_prediction(clean_other, "MARKETING", 0.97)
        set_prediction(injected_social, "MARKETING", 0.98)

        report = evaluator.evaluate_predictions(rows, thresholds())

        self.assertEqual(
            2,
            report["safety"][
                "trusted_non_marketing_predicted_marketing_count"
            ],
        )

    def test_selects_maximum_coverage_threshold_from_validation_only(self) -> None:
        rows = prediction_rows(pairs_per_group=10, split="validation")
        unsafe = next(
            row
            for row in rows
            if row["locale"] == "mixed"
            and row["intent"] == "TRANSACTIONAL"
            and row["injection"]
        )
        set_prediction(unsafe, "MARKETING", 0.70)

        selection = evaluator.select_validation_threshold(rows)

        self.assertEqual(
            evaluator.THRESHOLD_SELECTION_SCHEMA_VERSION,
            selection["schema_version"],
        )
        self.assertEqual("selected", selection["status"])
        self.assertEqual(
            evaluator.DEFAULT_TRUST_THRESHOLD,
            selection["general_threshold"],
        )
        self.assertEqual(
            evaluator.DEFAULT_TRUST_THRESHOLD,
            selection["marketing_threshold"],
        )
        self.assertTrue(selection["gate"]["passed"])
        self.assertLess(
            selection["trusted_coverage"]["overall"]["rate"],
            1.0,
        )

    def test_threshold_moves_to_the_adjacent_float32_above_unsafe_score(
        self,
    ) -> None:
        rows = prediction_rows(pairs_per_group=10, split="validation")
        for row in rows:
            set_prediction(row, str(row["intent"]), 0.99)
        unsafe_confidence = evaluator._float32(0.97)
        unsafe = next(
            row
            for row in rows
            if row["intent"] == "OTHER" and not row["injection"]
        )
        set_prediction(unsafe, "MARKETING", unsafe_confidence)

        selection = evaluator.select_validation_threshold(rows)
        selected = selection["marketing_threshold"]
        unsafe_bits = struct.unpack(
            ">I",
            struct.pack(">f", unsafe_confidence),
        )[0]
        selected_bits = struct.unpack(
            ">I",
            struct.pack(">f", selected),
        )[0]

        self.assertEqual("selected", selection["status"])
        self.assertGreater(selected, unsafe_confidence)
        self.assertEqual(unsafe_bits + 1, selected_bits)
        self.assertEqual(selected, evaluator._float32(selected))
        self.assertEqual(
            0,
            evaluator.evaluate_predictions(
                rows,
                thresholds(marketing=selected),
            )["safety"][
                "trusted_non_marketing_predicted_marketing_count"
            ],
        )
        self.assertEqual(
            1,
            evaluator.evaluate_predictions(
                rows,
                thresholds(marketing=unsafe_confidence),
            )["safety"][
                "trusted_non_marketing_predicted_marketing_count"
            ],
        )

    def test_threshold_object_is_closed_and_requires_float32_values(self) -> None:
        with self.assertRaisesRegex(
            evaluator.EvaluationError,
            "exactly general and marketing",
        ):
            evaluator._require_thresholds(
                {"general": evaluator.DEFAULT_TRUST_THRESHOLD},
            )
        with self.assertRaisesRegex(evaluator.EvaluationError, "float32"):
            evaluator._require_thresholds(
                {
                    "general": 0.95,
                    "marketing": evaluator.DEFAULT_TRUST_THRESHOLD,
                },
            )
        self.assertEqual(
            evaluator.DEFAULT_THRESHOLDS,
            evaluator._require_thresholds(thresholds()),
        )

    def test_threshold_selection_rejects_non_validation_data(self) -> None:
        for split in ("test", "holdout", None):
            with self.subTest(split=split):
                rows = prediction_rows(split=split)
                with self.assertRaises(evaluator.EvaluationError):
                    evaluator.select_validation_threshold(rows)

    def test_threshold_selection_reports_no_feasible_value(self) -> None:
        rows = prediction_rows(pairs_per_group=10, split="validation")
        unsafe = next(
            row
            for row in rows
            if row["intent"] == "SECURITY" and row["injection"]
        )
        set_prediction(unsafe, "MARKETING", 1.0)

        selection = evaluator.select_validation_threshold(rows)

        self.assertEqual("no-feasible-threshold", selection["status"])
        self.assertIsNone(selection["general_threshold"])
        self.assertIsNone(selection["marketing_threshold"])

    def test_threshold_selection_never_accepts_zero_trusted_coverage(self) -> None:
        rows = prediction_rows(pairs_per_group=10, split="validation")
        for row in rows:
            set_prediction(row, str(row["intent"]), 0.55)
        permissive = evaluator.GateConfig(
            minimum_trusted_coverage=0.0,
            minimum_locale_trusted_coverage=0.0,
        )

        selection = evaluator.select_validation_threshold(rows, permissive)

        self.assertEqual("no-feasible-threshold", selection["status"])

    def test_gate_enforces_minimum_trusted_coverage(self) -> None:
        rows = prediction_rows()
        for row in rows:
            if row["predicted_intent"] != "AMBIGUOUS":
                set_prediction(row, str(row["intent"]), 0.55)

        result = evaluator.evaluate_and_gate(rows, thresholds())

        self.assertFalse(result["gate"]["passed"])
        self.assertFalse(
            result["gate"]["checks"]["minimum_trusted_coverage"]["passed"]
        )

    def test_gate_enforces_each_locale_trusted_coverage(self) -> None:
        rows = prediction_rows()
        for row in rows:
            if (
                row["locale"] == "ko"
                and row["intent"] != "AMBIGUOUS"
            ):
                set_prediction(row, str(row["intent"]), 0.55)

        result = evaluator.evaluate_and_gate(rows, thresholds())

        self.assertTrue(
            result["gate"]["checks"]["minimum_trusted_coverage"]["passed"]
        )
        self.assertFalse(result["gate"]["passed"])
        locale_checks = result["gate"]["checks"][
            "minimum_locale_trusted_coverage"
        ]
        self.assertFalse(locale_checks["ko"]["passed"])
        self.assertTrue(locale_checks["en"]["passed"])
        self.assertTrue(locale_checks["mixed"]["passed"])

    def test_actual_ambiguous_rows_never_inflate_trusted_coverage(self) -> None:
        rows = prediction_rows()
        before = evaluator.evaluate_predictions(rows, thresholds())
        for row in rows:
            if row["intent"] == "AMBIGUOUS":
                set_prediction(row, "OTHER", 0.99)

        after = evaluator.evaluate_predictions(rows, thresholds())

        self.assertEqual(
            evaluator.TRUSTED_COVERAGE_ELIGIBILITY,
            after["trusted_coverage"]["eligibility"],
        )
        self.assertEqual(
            before["trusted_coverage"],
            after["trusted_coverage"],
        )

    def test_rejects_multiple_global_splits(self) -> None:
        rows = prediction_rows(split="validation")
        pair_id = rows[0]["pair_id"]
        for row in rows:
            if row["pair_id"] == pair_id:
                row["split"] = "test"

        with self.assertRaisesRegex(
            evaluator.EvaluationError,
            "one global split",
        ):
            evaluator.validate_prediction_records(rows)

    def test_load_predictions_uses_strict_shared_jsonl_contract(self) -> None:
        rows = prediction_rows()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "predictions.jsonl"
            path.write_text(
                "\n".join(
                    json.dumps(row, separators=(",", ":"))
                    for row in rows
                )
                + "\n",
                encoding="utf-8",
            )

            loaded = evaluator.load_predictions(path)

        self.assertEqual(len(rows), len(loaded))

    def test_load_prediction_files_combines_locale_holdouts(self) -> None:
        rows = prediction_rows()
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for locale in evaluator.LOCALES:
                path = Path(directory) / f"{locale}.jsonl"
                locale_rows = [row for row in rows if row["locale"] == locale]
                path.write_text(
                    "\n".join(
                        json.dumps(row, separators=(",", ":"))
                        for row in locale_rows
                    )
                    + "\n",
                    encoding="utf-8",
                )
                paths.append(path)

            loaded = evaluator.load_prediction_files(paths)

        self.assertEqual(len(rows), len(loaded))

    def test_bound_dataset_predictions_verify_hashes_counts_and_coverage(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = prediction_rows(split="validation")
            source_path = root / "dataset.jsonl"
            prediction_path = root / "validation.predictions.jsonl"
            write_jsonl(source_path, source_rows(rows, keep_split=True))
            write_jsonl(prediction_path, rows)
            write_prediction_manifest(
                prediction_path,
                source_path,
                len(rows),
                "validation",
            )
            source_manifest_path = root / "manifest.json"
            source_manifest_path.write_text(
                json.dumps(
                    {
                        "schema_version": "semantic-dataset-manifest-v3",
                        "row_count": len(rows),
                        "files": {
                            "dataset.jsonl": {
                                "sha256": sha256_file(source_path),
                                "rows": len(rows),
                            }
                        },
                    },
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )

            bound = evaluator.load_bound_prediction_set(
                [prediction_path],
                source_manifest_path,
            )
            loaded = bound.rows
            self.assertEqual(
                {
                    "schema_version": evaluator.PROVENANCE_SCHEMA_VERSION,
                    "source_manifest_sha256": sha256_file(
                        source_manifest_path
                    ),
                    "backend": "unit-test",
                    "model_artifact_sha256": "a" * 64,
                },
                bound.provenance,
            )

            for subcommand in ("gate", "select-threshold"):
                arguments = [
                    subcommand,
                    str(prediction_path),
                    "--source-manifest",
                    str(source_manifest_path),
                ]
                if subcommand == "gate":
                    arguments.extend(
                        [
                            "--general-threshold",
                            str(evaluator.DEFAULT_TRUST_THRESHOLD),
                            "--marketing-threshold",
                            str(evaluator.DEFAULT_TRUST_THRESHOLD),
                        ]
                    )
                output = io.StringIO()
                with redirect_stdout(output):
                    exit_code = evaluator.main(arguments)
                result = json.loads(output.getvalue())
                self.assertEqual(0, exit_code)
                self.assertEqual(bound.provenance, result["provenance"])
                self.assertEqual(
                    evaluator.DEFAULT_TRUST_THRESHOLD,
                    result["general_threshold"],
                )
                self.assertEqual(
                    evaluator.DEFAULT_TRUST_THRESHOLD,
                    result["marketing_threshold"],
                )
                if subcommand == "gate":
                    self.assertEqual(
                        result["general_threshold"],
                        result["evaluation"]["general_threshold"],
                    )
                    self.assertEqual(
                        result["marketing_threshold"],
                        result["evaluation"]["marketing_threshold"],
                    )
                self.assertNotIn("threshold", result)
                self.assertNotIn("thresholds", result)
                self.assertNotIn("selected_thresholds", result)
                expected_schema = (
                    evaluator.GATED_EVALUATION_SCHEMA_VERSION
                    if subcommand == "gate"
                    else evaluator.THRESHOLD_SELECTION_SCHEMA_VERSION
                )
                self.assertEqual(
                    expected_schema,
                    result["schema_version"],
                )

        self.assertEqual(len(rows), len(loaded))

    def test_prediction_and_manifest_are_bound_to_one_byte_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = prediction_rows(split="validation")
            source_path = root / "dataset.jsonl"
            prediction_path = root / "predictions.jsonl"
            source_manifest_path = root / "manifest.json"
            write_jsonl(source_path, source_rows(rows, keep_split=True))
            write_jsonl(prediction_path, rows)
            write_prediction_manifest(
                prediction_path,
                source_path,
                len(rows),
                "validation",
            )
            source_manifest_path.write_text(
                json.dumps(
                    {
                        "schema_version": "semantic-dataset-manifest-v6",
                        "row_count": len(rows),
                        "files": {
                            source_path.name: {
                                "sha256": sha256_file(source_path),
                                "rows": len(rows),
                            }
                        },
                    },
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
            replacement = [dict(row) for row in rows]
            replacement[0]["confidence"] = 0.99
            replacement[0]["probabilities"] = probability_map(
                str(replacement[0]["predicted_intent"]),
                0.99,
            )
            real_snapshot = evaluator._prediction_snapshot

            def snapshot_then_publish_replacement(path: Path):
                snapshot = real_snapshot(path)
                write_jsonl(prediction_path, replacement)
                write_prediction_manifest(
                    prediction_path,
                    source_path,
                    len(replacement),
                    "validation",
                )
                return snapshot

            with mock.patch.object(
                evaluator,
                "_prediction_snapshot",
                side_effect=snapshot_then_publish_replacement,
            ):
                with self.assertRaisesRegex(
                    evaluator.EvaluationError,
                    "output SHA-256 mismatch",
                ):
                    evaluator.load_bound_prediction_set(
                        [prediction_path],
                        source_manifest_path,
                    )

    def test_tensorflow_lite_binding_requires_and_verifies_vocab_hash(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = prediction_rows(split="validation")
            source_path = root / "dataset.jsonl"
            prediction_path = root / "predictions.jsonl"
            model_path = root / "model.tflite"
            vocab_path = root / "vocab.txt"
            write_jsonl(source_path, source_rows(rows, keep_split=True))
            write_jsonl(prediction_path, rows)
            model_path.write_bytes(b"tiny-model")
            vocab_path.write_text(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\nword\n",
                encoding="utf-8",
            )
            model_sha256 = sha256_file(model_path)
            vocab_sha256 = sha256_file(vocab_path)
            prediction_manifest_path = write_prediction_manifest(
                prediction_path,
                source_path,
                len(rows),
                "validation",
                backend="tensorflow-lite",
                model=str(model_path),
                model_sha256=model_sha256,
                model_artifact_sha256=model_sha256,
                vocab=str(vocab_path),
                vocab_sha256=vocab_sha256,
            )
            source_manifest_path = root / "source-manifest.json"
            source_manifest_path.write_text(
                json.dumps(
                    {
                        "schema_version": "semantic-dataset-manifest-v2",
                        "row_count": len(rows),
                        "files": {
                            "dataset.jsonl": {
                                "sha256": sha256_file(source_path),
                                "rows": len(rows),
                            }
                        },
                    },
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )

            bound = evaluator.load_bound_prediction_set(
                [prediction_path],
                source_manifest_path,
            )
            self.assertEqual(
                vocab_sha256,
                bound.provenance["vocab_sha256"],
            )

            prediction_manifest = json.loads(
                prediction_manifest_path.read_text(encoding="utf-8")
            )
            del prediction_manifest["vocab_sha256"]
            prediction_manifest_path.write_text(
                json.dumps(prediction_manifest, separators=(",", ":"))
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                evaluator.EvaluationError,
                "requires vocab_sha256",
            ):
                evaluator.load_bound_prediction_set(
                    [prediction_path],
                    source_manifest_path,
                )

            prediction_manifest["vocab_sha256"] = vocab_sha256
            prediction_manifest_path.write_text(
                json.dumps(prediction_manifest, separators=(",", ":"))
                + "\n",
                encoding="utf-8",
            )
            vocab_path.write_text("tampered\n", encoding="utf-8")
            with self.assertRaisesRegex(
                evaluator.EvaluationError,
                "vocab SHA-256 mismatch",
            ):
                evaluator.load_bound_prediction_set(
                    [prediction_path],
                    source_manifest_path,
                )

    def test_bound_predictions_reject_tampered_hash_count_and_coverage(
        self,
    ) -> None:
        mutations = (
            "output_hash",
            "output_count",
            "input_hash",
            "source_count",
            "missing_pair",
            "wrong_locale",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    rows = prediction_rows(split="validation")
                    source_path = root / "dataset.jsonl"
                    prediction_path = root / "predictions.jsonl"
                    source = source_rows(rows, keep_split=True)
                    predictions = [dict(row) for row in rows]
                    if mutation == "missing_pair":
                        missing_pair = predictions[0]["pair_id"]
                        predictions = [
                            row
                            for row in predictions
                            if row["pair_id"] != missing_pair
                        ]
                    elif mutation == "wrong_locale":
                        changed_pair = predictions[0]["pair_id"]
                        for row in predictions:
                            if row["pair_id"] == changed_pair:
                                row["locale"] = (
                                    "en" if row["locale"] == "ko" else "ko"
                                )
                    write_jsonl(source_path, source)
                    write_jsonl(prediction_path, predictions)
                    manifest_overrides: dict[str, object] = {}
                    if mutation == "output_hash":
                        manifest_overrides["output_sha256"] = "b" * 64
                    elif mutation == "input_hash":
                        manifest_overrides["input_sha256"] = "b" * 64
                    write_prediction_manifest(
                        prediction_path,
                        source_path,
                        (
                            len(predictions) + 1
                            if mutation == "output_count"
                            else len(predictions)
                        ),
                        "validation",
                        **manifest_overrides,
                    )
                    source_count = (
                        len(source) + 1
                        if mutation == "source_count"
                        else len(source)
                    )
                    source_manifest_path = root / "source-manifest.json"
                    source_manifest_path.write_text(
                        json.dumps(
                            {
                                "schema_version": (
                                    "semantic-dataset-manifest-v2"
                                ),
                                "row_count": source_count,
                                "files": {
                                    "dataset.jsonl": {
                                        "sha256": sha256_file(source_path),
                                        "rows": source_count,
                                    }
                                },
                            },
                            separators=(",", ":"),
                        )
                        + "\n",
                        encoding="utf-8",
                    )

                    with self.assertRaises(evaluator.EvaluationError):
                        evaluator.load_bound_prediction_files(
                            [prediction_path],
                            source_manifest_path,
                        )

    def test_combined_holdout_binding_works_but_subset_and_wrong_source_fail(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            all_rows = prediction_rows(split="holdout")
            prediction_paths: list[Path] = []
            file_entries: dict[str, object] = {}
            for locale in evaluator.LOCALES:
                rows = [
                    dict(row)
                    for row in all_rows
                    if row["locale"] == locale
                ]
                source_path = root / f"{locale}_holdout.jsonl"
                prediction_path = root / f"{locale}.predictions.jsonl"
                write_jsonl(
                    source_path,
                    source_rows(rows, keep_split=False),
                )
                write_jsonl(prediction_path, rows)
                write_prediction_manifest(
                    prediction_path,
                    source_path,
                    len(rows),
                    None,
                )
                prediction_paths.append(prediction_path)
                file_entries[source_path.name] = {
                    "sha256": sha256_file(source_path),
                    "row_count": len(rows),
                }
            source_manifest_path = root / "holdout-manifest.json"
            source_manifest = {
                "schema_version": (
                    "semantic-sealed-holdout-manifest-v2"
                ),
                "files": file_entries,
                "counts": {"row_count": len(all_rows)},
            }
            source_manifest_path.write_text(
                json.dumps(source_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

            loaded = evaluator.load_bound_prediction_files(
                prediction_paths,
                source_manifest_path,
            )
            self.assertEqual(len(all_rows), len(loaded))

            source_manifest["schema_version"] = (
                "semantic-sealed-holdout-manifest-v1"
            )
            source_manifest_path.write_text(
                json.dumps(source_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            self.assertEqual(
                len(all_rows),
                len(
                    evaluator.load_bound_prediction_files(
                        prediction_paths,
                        source_manifest_path,
                    )
                ),
            )
            source_manifest["schema_version"] = (
                "semantic-sealed-holdout-manifest-v2"
            )
            source_manifest_path.write_text(
                json.dumps(source_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

            second_manifest_path = prediction_paths[1].with_suffix(
                f"{prediction_paths[1].suffix}.manifest.json"
            )
            second_manifest = json.loads(
                second_manifest_path.read_text(encoding="utf-8")
            )
            second_manifest["model_artifact_sha256"] = "d" * 64
            second_manifest_path.write_text(
                json.dumps(second_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                evaluator.EvaluationError,
                "one backend and model",
            ):
                evaluator.load_bound_prediction_files(
                    prediction_paths,
                    source_manifest_path,
                )
            second_manifest["model_artifact_sha256"] = "a" * 64
            second_manifest_path.write_text(
                json.dumps(second_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                evaluator.EvaluationError,
                "subset",
            ):
                evaluator.load_bound_prediction_files(
                    prediction_paths[:2],
                    source_manifest_path,
                )

            source_manifest["files"]["en_holdout.jsonl"]["sha256"] = "c" * 64
            source_manifest_path.write_text(
                json.dumps(source_manifest, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaises(evaluator.EvaluationError):
                evaluator.load_bound_prediction_files(
                    prediction_paths,
                    source_manifest_path,
                )

    def test_gate_and_selection_cli_require_source_manifest(self) -> None:
        parser = evaluator.build_parser()
        for subcommand in ("gate", "select-threshold"):
            with self.subTest(subcommand=subcommand):
                arguments = [subcommand, "predictions.jsonl"]
                if subcommand == "gate":
                    arguments.extend(
                        [
                            "--general-threshold",
                            str(evaluator.DEFAULT_TRUST_THRESHOLD),
                            "--marketing-threshold",
                            str(evaluator.DEFAULT_TRUST_THRESHOLD),
                        ]
                    )
                with redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        parser.parse_args(arguments)

    def test_evaluate_and_gate_cli_require_both_thresholds(self) -> None:
        parser = evaluator.build_parser()
        for subcommand in ("evaluate", "gate"):
            with self.subTest(subcommand=subcommand):
                arguments = [
                    subcommand,
                    "predictions.jsonl",
                    "--general-threshold",
                    str(evaluator.DEFAULT_TRUST_THRESHOLD),
                ]
                if subcommand == "gate":
                    arguments.extend(
                        ["--source-manifest", "manifest.json"]
                    )
                with redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        parser.parse_args(arguments)


if __name__ == "__main__":
    unittest.main()
