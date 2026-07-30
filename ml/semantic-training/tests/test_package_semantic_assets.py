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

import package_semantic_assets as packager  # noqa: E402
from semantic_contract import LABELS, model_bundle_hashes  # noqa: E402


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


class PackageFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.conversion_dir = root / "conversion"
        self.conversion_dir.mkdir()
        self.model_path = (
            self.conversion_dir / packager.CONVERSION_MODEL_FILENAME
        )
        self.model_path.write_bytes(b"TFL3-tiny-semantic-model")
        self.conversion_manifest_path = (
            self.conversion_dir / packager.CONVERSION_MANIFEST_FILENAME
        )
        self.conversion_manifest = {
            "schema_version": packager.CONVERSION_MANIFEST_SCHEMA,
            "labels": list(LABELS),
            "source": {},
            "converter": {},
            "quantization": {
                "requested": "auto",
                "applied": "dynamic-int8",
                "calibration_used": False,
                "experimental_backend": True,
                "fallback_reason": None,
            },
            "quantization_audit": {
                "schema_version": packager.QUANTIZATION_AUDIT_SCHEMA,
                "method": (
                    "litert-interpreter-tensor-and-operator-inspection"
                ),
                "tensor_count": 100,
                "int8_tensor_count": 20,
                "operator_count": 50,
                "quantize_operator_count": 10,
                "passed": True,
            },
            "resources": {},
            "tensor_contract": {
                "inputs": [
                    {
                        "name": "input_ids",
                        "dtype": "int32",
                        "shape": [1, 128],
                    },
                    {
                        "name": "attention_mask",
                        "dtype": "int32",
                        "shape": [1, 128],
                    },
                ],
                "output": {
                    "name": "logits",
                    "dtype": "float32",
                    "shape": [1, 7],
                },
            },
            "artifact": {
                "file": packager.CONVERSION_MODEL_FILENAME,
                "size_bytes": self.model_path.stat().st_size,
                "max_size_bytes": packager.MAX_MODEL_BYTES,
                "sha256": sha256_file(self.model_path),
            },
        }
        self.write_conversion_manifest()

        self.model_dir = root / "trained-model"
        self.model_dir.mkdir()
        self.vocab_path = self.model_dir / packager.SOURCE_VOCAB_FILENAME
        self.vocab_path.write_text(
            "\n".join(
                (
                    "[PAD]",
                    "[UNK]",
                    "[CLS]",
                    "[SEP]",
                    "[MASK]",
                    "알림",
                    "sale",
                    "##s",
                )
            )
            + "\n",
            encoding="utf-8",
        )
        self.weights_path = self.model_dir / "model.safetensors"
        self.weights_path.write_bytes(b"trained-weights")
        self.model_sha256 = sha256_file(self.model_path)
        self.vocab_sha256 = sha256_file(self.vocab_path)
        _, self.model_bundle_sha256 = model_bundle_hashes(self.model_dir)
        self.conversion_manifest["source"] = {
            "model_bundle_sha256": self.model_bundle_sha256,
            "vocab_sha256": self.vocab_sha256,
        }
        self.write_conversion_manifest()
        self.general_threshold = packager.MINIMUM_CONFIDENCE_THRESHOLD
        self.marketing_threshold = struct.unpack(
            ">f",
            struct.pack(">f", 0.99),
        )[0]
        self.threshold_provenance = {
            "schema_version": packager.EVALUATION_PROVENANCE_SCHEMA,
            "source_manifest_sha256": "a" * 64,
            "backend": "tensorflow-lite",
            "model_artifact_sha256": self.model_sha256,
            "vocab_sha256": self.vocab_sha256,
        }
        self.holdout_provenance = {
            **self.threshold_provenance,
            "source_manifest_sha256": "b" * 64,
        }
        self.selection_rows_by_locale = dict(
            packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS_BY_LOCALE
        )
        self.selection_trusted_by_locale = {
            locale: row_count * 5 // 6
            for locale, row_count in self.selection_rows_by_locale.items()
        }
        self.selection_trusted_count = sum(
            self.selection_trusted_by_locale.values()
        )
        self.holdout_rows_by_locale = dict(
            packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS_BY_LOCALE
        )

        self.threshold_path = root / "threshold-selection.json"
        passing_gate = {
            "passed": True,
            "checks": {
                "raw_macro_f1": {
                    "actual": 0.90,
                    "minimum": 0.85,
                    "passed": True,
                },
                "raw_marketing_precision": {
                    "actual": 0.95,
                    "minimum": 0.90,
                    "passed": True,
                },
                "raw_locale_macro_f1": {
                    locale: {
                        "actual": 0.88,
                        "minimum": 0.80,
                        "passed": True,
                    }
                    for locale in packager.LOCALES
                },
                "trusted_marketing_false_positives": {
                    "actual": 0,
                    "maximum": 0,
                    "passed": True,
                },
                "minimum_trusted_coverage": {
                    "actual": (
                        self.selection_trusted_count
                        / packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS
                    ),
                    "minimum": 0.60,
                    "passed": True,
                },
                "minimum_locale_trusted_coverage": {
                    locale: {
                        "actual": (
                            self.selection_trusted_by_locale[locale]
                            / self.selection_rows_by_locale[locale]
                        ),
                        "minimum": 0.40,
                        "passed": True,
                    }
                    for locale in packager.LOCALES
                },
            },
        }
        self.threshold_selection = {
            "schema_version": packager.THRESHOLD_SELECTION_SCHEMA,
            "status": "selected",
            "validation_only": True,
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "trusted_coverage": {
                "eligibility": packager.TRUSTED_COVERAGE_ELIGIBILITY,
                "overall": {
                    "trusted_count": self.selection_trusted_count,
                    "row_count": (
                        packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS
                    ),
                    "rate": (
                        self.selection_trusted_count
                        / packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS
                    ),
                },
                "by_locale": {
                    locale: {
                        "trusted_count": (
                            self.selection_trusted_by_locale[locale]
                        ),
                        "row_count": self.selection_rows_by_locale[locale],
                        "rate": (
                            self.selection_trusted_by_locale[locale]
                            / self.selection_rows_by_locale[locale]
                        ),
                    }
                    for locale in packager.LOCALES
                },
            },
            "gate": passing_gate,
            "provenance": self.threshold_provenance,
        }
        self.write_threshold_selection()

        self.holdout_gate_path = root / "sealed-holdout-gate.json"
        holdout_gate = json.loads(json.dumps(passing_gate))
        holdout_gate["checks"]["minimum_trusted_coverage"]["actual"] = (
            300 / packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS
        )
        for locale in packager.LOCALES:
            holdout_gate["checks"][
                "minimum_locale_trusted_coverage"
            ][locale]["actual"] = (
                100
                / packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS_BY_LOCALE[locale]
            )
        self.holdout_gate = {
            "schema_version": packager.GATED_EVALUATION_SCHEMA,
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "evaluation": {
                "schema_version": packager.EVALUATION_SCHEMA,
                "labels": list(LABELS),
                "general_threshold": self.general_threshold,
                "marketing_threshold": self.marketing_threshold,
                "row_count": 420,
                "raw": {
                    "overall": {
                        "accuracy": 0.90,
                        "macro_f1": 0.90,
                        "marketing_precision": 0.95,
                    },
                    "by_locale": {
                        locale: {"macro_f1": 0.88}
                        for locale in packager.LOCALES
                    },
                },
                "runtime": {
                    "overall": {
                        "accuracy": 0.88,
                        "macro_f1": 0.88,
                        "marketing_precision": 0.95,
                    }
                },
                "pair_consistency": {},
                "trusted_coverage": {
                    "eligibility": packager.TRUSTED_COVERAGE_ELIGIBILITY,
                    "overall": {
                        "trusted_count": 300,
                        "row_count": (
                            packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS
                        ),
                        "rate": (
                            300
                            / packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS
                        ),
                    },
                    "by_locale": {
                        locale: {
                            "trusted_count": 100,
                            "row_count": self.holdout_rows_by_locale[locale],
                            "rate": (
                                100
                                / self.holdout_rows_by_locale[locale]
                            ),
                        }
                        for locale in packager.LOCALES
                    },
                },
                "safety": {
                    "trusted_non_marketing_predicted_marketing_count": 0,
                },
            },
            "gate": holdout_gate,
            "provenance": self.holdout_provenance,
        }
        self.write_holdout_gate()

        self.development_test_gate_path = (
            root / "development-test-gate.json"
        )
        self.development_test_gate = json.loads(
            json.dumps(self.holdout_gate)
        )
        self.development_test_gate["provenance"] = (
            self.threshold_provenance
        )
        self.development_test_gate["gate"] = passing_gate
        development_evaluation = self.development_test_gate["evaluation"]
        development_evaluation["row_count"] = (
            packager.EXPECTED_DEVELOPMENT_TEST_ROWS
        )
        development_evaluation["trusted_coverage"] = (
            self.threshold_selection["trusted_coverage"]
        )
        self.write_development_test_gate()

        self.test_parity_report_path = root / "test-parity.json"
        tflite_quality = {
            "raw": {
                "accuracy": development_evaluation["raw"]["overall"][
                    "accuracy"
                ],
                "macro_f1": development_evaluation["raw"]["overall"][
                    "macro_f1"
                ],
                "marketing_precision": development_evaluation["raw"][
                    "overall"
                ]["marketing_precision"],
                "locale_macro_f1": {
                    locale: development_evaluation["raw"]["by_locale"][
                        locale
                    ]["macro_f1"]
                    for locale in packager.LOCALES
                },
            },
            "runtime": dict(
                development_evaluation["runtime"]["overall"]
            ),
            "trusted_coverage": development_evaluation[
                "trusted_coverage"
            ]["overall"]["rate"],
            "trusted_marketing_false_positives": 0,
        }
        self.test_parity_report = {
            "schema_version": packager.PARITY_REPORT_SCHEMA,
            "labels": list(LABELS),
            "split": "test",
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "row_count": packager.EXPECTED_DEVELOPMENT_TEST_ROWS,
            "provenance": {
                "source_manifest_sha256": (
                    self.threshold_provenance["source_manifest_sha256"]
                ),
                "conversion_manifest_sha256": sha256_file(
                    self.conversion_manifest_path
                ),
                "pytorch_model_bundle_sha256": self.model_bundle_sha256,
                "tflite_model_sha256": self.model_sha256,
                "vocab_sha256": self.vocab_sha256,
                "pytorch_predictions_sha256": "c" * 64,
                "tflite_predictions_sha256": "d" * 64,
            },
            "agreement": {
                name: {
                    "count": packager.EXPECTED_DEVELOPMENT_TEST_ROWS,
                    "row_count": packager.EXPECTED_DEVELOPMENT_TEST_ROWS,
                    "rate": 1.0,
                }
                for name in ("raw_argmax", "runtime_output")
            },
            "probability_error": {
                "percentile_method": "nearest-rank",
                "absolute_per_label": {
                    "mean": 0.0,
                    "p95": 0.0,
                    "maximum": 0.0,
                },
                "confidence": {
                    "mean": 0.0,
                    "p95": 0.0,
                    "maximum": 0.0,
                },
                "row_total_variation": {
                    "mean": 0.0,
                    "p95": 0.0,
                    "maximum": 0.0,
                },
            },
            "quality": {
                "pytorch": tflite_quality,
                "tflite": tflite_quality,
                "tflite_minus_pytorch": {
                    "raw": {
                        "accuracy": 0.0,
                        "macro_f1": 0.0,
                        "marketing_precision": 0.0,
                        "locale_macro_f1": {
                            locale: 0.0 for locale in packager.LOCALES
                        },
                    },
                    "runtime": {
                        "accuracy": 0.0,
                        "macro_f1": 0.0,
                        "marketing_precision": 0.0,
                    },
                    "trusted_coverage": 0.0,
                    "trusted_marketing_false_positives": 0,
                },
            },
        }
        self.test_parity_report["agreement"]["trusted_marketing"] = {
            "pytorch_count": 100,
            "tflite_count": 100,
            "disagreement_count": 0,
            "introduced_by_tflite_count": 0,
            "introduced_unsafe_by_tflite_count": 0,
        }
        self.write_test_parity_report()
        self.output_dir = root / "android-assets"

    def write_conversion_manifest(self) -> None:
        write_json(
            self.conversion_manifest_path,
            self.conversion_manifest,
        )

    def write_threshold_selection(self) -> None:
        write_json(self.threshold_path, self.threshold_selection)

    def write_holdout_gate(self) -> None:
        write_json(self.holdout_gate_path, self.holdout_gate)

    def write_development_test_gate(self) -> None:
        write_json(
            self.development_test_gate_path,
            self.development_test_gate,
        )

    def write_test_parity_report(self) -> None:
        write_json(
            self.test_parity_report_path,
            self.test_parity_report,
        )

    def options(self) -> packager.PackageOptions:
        return packager.PackageOptions(
            conversion_dir=self.conversion_dir,
            model_dir=self.model_dir,
            threshold_selection=self.threshold_path,
            development_test_gate=self.development_test_gate_path,
            test_parity_report=self.test_parity_report_path,
            sealed_holdout_gate=self.holdout_gate_path,
            output_dir=self.output_dir,
        )


class PackageSemanticAssetsTest(unittest.TestCase):
    def test_packages_exact_assets_and_binds_all_payloads_and_evidence(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            expected_evidence = {
                "conversion_manifest_sha256": sha256_file(
                    fixture.conversion_manifest_path
                ),
                "threshold_selection_sha256": sha256_file(
                    fixture.threshold_path
                ),
                "development_test_gate_sha256": sha256_file(
                    fixture.development_test_gate_path
                ),
                "test_parity_report_sha256": sha256_file(
                    fixture.test_parity_report_path
                ),
                "sealed_holdout_gate_sha256": sha256_file(
                    fixture.holdout_gate_path
                ),
            }

            with mock.patch.object(
                packager,
                "_fsync_directory",
                wraps=packager._fsync_directory,
            ) as sync_directory:
                manifest = packager.package_assets(fixture.options())

            self.assertEqual(
                packager.OUTPUT_FILENAMES,
                {path.name for path in fixture.output_dir.iterdir()},
            )
            self.assertEqual(2, sync_directory.call_count)
            self.assertEqual(
                fixture.output_dir.parent.resolve(),
                sync_directory.call_args_list[-1].args[0].resolve(),
            )
            self.assertEqual(
                fixture.model_path.read_bytes(),
                (
                    fixture.output_dir / packager.MODEL_ASSET_FILENAME
                ).read_bytes(),
            )
            self.assertEqual(
                fixture.vocab_path.read_bytes(),
                (
                    fixture.output_dir / packager.VOCAB_ASSET_FILENAME
                ).read_bytes(),
            )
            self.assertEqual(
                ("\n".join(LABELS) + "\n").encode("utf-8"),
                (
                    fixture.output_dir / packager.LABELS_ASSET_FILENAME
                ).read_bytes(),
            )
            self.assertEqual(
                packager.OUTPUT_MANIFEST_SCHEMA,
                manifest["schema_version"],
            )
            self.assertEqual(list(LABELS), manifest["labels"])
            self.assertEqual(128, manifest["max_sequence_length"])
            self.assertEqual(
                fixture.general_threshold,
                manifest["general_threshold"],
            )
            self.assertEqual(
                fixture.marketing_threshold,
                manifest["marketing_threshold"],
            )
            self.assertEqual(
                packager.TOKENIZER_CONTRACT,
                manifest["tokenizer"],
            )
            self.assertEqual(
                fixture.conversion_manifest["quantization"],
                manifest["conversion"]["quantization"],
            )
            self.assertEqual(
                fixture.conversion_manifest["quantization_audit"],
                manifest["conversion"]["quantization_audit"],
            )
            self.assertEqual(
                fixture.conversion_manifest["tensor_contract"],
                manifest["conversion"]["tensor_contract"],
            )
            self.assertEqual(expected_evidence, manifest["evidence"])
            self.assertEqual(
                {
                    "threshold_selection": fixture.threshold_provenance,
                    "development_test": fixture.threshold_provenance,
                    "sealed_holdout": fixture.holdout_provenance,
                },
                manifest["evaluation_provenance"],
            )

            written_manifest = json.loads(
                (
                    fixture.output_dir / packager.MODEL_MANIFEST_FILENAME
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(manifest, written_manifest)
            for filename in (
                packager.MODEL_ASSET_FILENAME,
                packager.VOCAB_ASSET_FILENAME,
                packager.LABELS_ASSET_FILENAME,
            ):
                payload = fixture.output_dir / filename
                self.assertEqual(
                    sha256_file(payload),
                    manifest["files"][filename]["sha256"],
                )
                self.assertEqual(
                    payload.stat().st_size,
                    manifest["files"][filename]["size_bytes"],
                )

    def test_rejects_conversion_label_tensor_artifact_and_quantization_drift(
        self,
    ) -> None:
        mutations = (
            "labels",
            "artifact_hash",
            "artifact_size",
            "input_shape",
            "output_dtype",
            "quantization",
            "float32_quantization",
            "missing_quantization_audit",
            "empty_int8_audit",
            "failed_quantization_audit",
            "vocab_provenance",
            "model_bundle_provenance",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    fixture = PackageFixture(Path(directory))
                    if mutation == "labels":
                        fixture.conversion_manifest["labels"] = list(
                            reversed(LABELS)
                        )
                    elif mutation == "artifact_hash":
                        fixture.conversion_manifest["artifact"]["sha256"] = (
                            "0" * 64
                        )
                    elif mutation == "artifact_size":
                        fixture.conversion_manifest["artifact"][
                            "size_bytes"
                        ] += 1
                    elif mutation == "input_shape":
                        fixture.conversion_manifest["tensor_contract"][
                            "inputs"
                        ][0]["shape"] = [1, 64]
                    elif mutation == "output_dtype":
                        fixture.conversion_manifest["tensor_contract"][
                            "output"
                        ]["dtype"] = "int8"
                    elif mutation == "quantization":
                        fixture.conversion_manifest["quantization"][
                            "experimental_backend"
                        ] = False
                    elif mutation == "float32_quantization":
                        quantization = fixture.conversion_manifest[
                            "quantization"
                        ]
                        quantization["requested"] = "float32"
                        quantization["applied"] = "float32"
                        quantization["experimental_backend"] = False
                    elif mutation == "missing_quantization_audit":
                        del fixture.conversion_manifest[
                            "quantization_audit"
                        ]
                    elif mutation == "empty_int8_audit":
                        fixture.conversion_manifest[
                            "quantization_audit"
                        ]["int8_tensor_count"] = 0
                    elif mutation == "failed_quantization_audit":
                        fixture.conversion_manifest[
                            "quantization_audit"
                        ]["passed"] = False
                    elif mutation == "vocab_provenance":
                        fixture.conversion_manifest["source"][
                            "vocab_sha256"
                        ] = "0" * 64
                    else:
                        fixture.conversion_manifest["source"][
                            "model_bundle_sha256"
                        ] = "0" * 64
                    fixture.write_conversion_manifest()

                    with self.assertRaises(packager.PackagingError):
                        packager.package_assets(fixture.options())
                    self.assertFalse(fixture.output_dir.exists())

    def test_rejects_unselected_or_failed_evidence_and_threshold_mismatch(
        self,
    ) -> None:
        mutations = (
            "not_selected",
            "not_validation_only",
            "selection_gate",
            "weak_selection_gate",
            "weak_selection_locale_gate",
            "selection_coverage_gate_mismatch",
            "low_selection_coverage",
            "low_selection_locale_coverage",
            "selection_actionable_row_count",
            "old_selection_schema",
            "legacy_single_threshold",
            "missing_general_threshold",
            "missing_marketing_threshold",
            "low_general_threshold",
            "low_marketing_threshold",
            "non_finite",
            "non_float32",
            "inverted_thresholds",
            "holdout_gate",
            "holdout_root_legacy_single_threshold",
            "holdout_root_missing_marketing_threshold",
            "holdout_root_low_general_threshold",
            "holdout_root_non_float32",
            "holdout_root_inverted_thresholds",
            "holdout_root_threshold_mismatch",
            "holdout_general_threshold_mismatch",
            "holdout_marketing_threshold_mismatch",
            "holdout_missing_marketing_threshold",
            "holdout_labels",
            "holdout_row_count",
            "holdout_macro_f1",
            "holdout_marketing_precision",
            "holdout_locale",
            "holdout_safety",
            "holdout_coverage",
            "holdout_actionable_row_count",
            "old_gated_schema",
            "old_evaluation_schema",
            "threshold_backend",
            "threshold_model",
            "threshold_vocab",
            "holdout_backend",
            "holdout_model",
            "holdout_vocab",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    fixture = PackageFixture(Path(directory))
                    if mutation == "not_selected":
                        fixture.threshold_selection["status"] = (
                            "no-feasible-threshold"
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "not_validation_only":
                        fixture.threshold_selection["validation_only"] = False
                        fixture.write_threshold_selection()
                    elif mutation == "selection_gate":
                        fixture.threshold_selection["gate"]["passed"] = False
                        fixture.write_threshold_selection()
                    elif mutation == "weak_selection_gate":
                        check = fixture.threshold_selection["gate"]["checks"][
                            "raw_macro_f1"
                        ]
                        check["actual"] = 0.84
                        check["minimum"] = 0.10
                        fixture.write_threshold_selection()
                    elif mutation == "weak_selection_locale_gate":
                        check = fixture.threshold_selection["gate"]["checks"][
                            "minimum_locale_trusted_coverage"
                        ]["ko"]
                        check["actual"] = 0.39
                        check["minimum"] = 0.10
                        fixture.write_threshold_selection()
                    elif mutation == "selection_coverage_gate_mismatch":
                        fixture.threshold_selection["gate"]["checks"][
                            "minimum_trusted_coverage"
                        ]["actual"] = 0.90
                        fixture.write_threshold_selection()
                    elif mutation == "low_selection_coverage":
                        coverage = fixture.threshold_selection[
                            "trusted_coverage"
                        ]["overall"]
                        coverage["trusted_count"] = 1
                        coverage["rate"] = (
                            1
                            / packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "low_selection_locale_coverage":
                        coverage = fixture.threshold_selection[
                            "trusted_coverage"
                        ]
                        coverage["by_locale"]["ko"]["trusted_count"] = 100
                        coverage["by_locale"]["ko"]["rate"] = (
                            100
                            / fixture.selection_rows_by_locale["ko"]
                        )
                        trusted_count = (
                            fixture.selection_trusted_count
                            - fixture.selection_trusted_by_locale["ko"]
                            + 100
                        )
                        coverage["overall"]["trusted_count"] = trusted_count
                        coverage["overall"]["rate"] = (
                            trusted_count
                            / packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "selection_actionable_row_count":
                        coverage = fixture.threshold_selection[
                            "trusted_coverage"
                        ]["overall"]
                        row_count = (
                            packager.EXPECTED_VALIDATION_ACTIONABLE_ROWS - 1
                        )
                        coverage["row_count"] = row_count
                        coverage["rate"] = (
                            fixture.selection_trusted_count / row_count
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "old_selection_schema":
                        fixture.threshold_selection["schema_version"] = (
                            "semantic-threshold-selection-v3"
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "legacy_single_threshold":
                        del fixture.threshold_selection["general_threshold"]
                        del fixture.threshold_selection["marketing_threshold"]
                        fixture.threshold_selection["selected_threshold"] = (
                            fixture.general_threshold
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "missing_general_threshold":
                        del fixture.threshold_selection["general_threshold"]
                        fixture.write_threshold_selection()
                    elif mutation == "missing_marketing_threshold":
                        del fixture.threshold_selection["marketing_threshold"]
                        fixture.write_threshold_selection()
                    elif mutation == "low_general_threshold":
                        fixture.threshold_selection["general_threshold"] = 0.59
                        fixture.write_threshold_selection()
                    elif mutation == "low_marketing_threshold":
                        fixture.threshold_selection["marketing_threshold"] = (
                            0.59
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "non_finite":
                        fixture.threshold_selection["marketing_threshold"] = (
                            float("nan")
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "non_float32":
                        fixture.threshold_selection["general_threshold"] = 0.95
                        fixture.write_threshold_selection()
                    elif mutation == "inverted_thresholds":
                        fixture.threshold_selection["general_threshold"] = (
                            fixture.marketing_threshold
                        )
                        fixture.threshold_selection["marketing_threshold"] = (
                            fixture.general_threshold
                        )
                        fixture.write_threshold_selection()
                    elif mutation == "holdout_gate":
                        fixture.holdout_gate["gate"]["passed"] = False
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_legacy_single_threshold":
                        del fixture.holdout_gate["general_threshold"]
                        del fixture.holdout_gate["marketing_threshold"]
                        fixture.holdout_gate["threshold"] = (
                            fixture.general_threshold
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_missing_marketing_threshold":
                        del fixture.holdout_gate["marketing_threshold"]
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_low_general_threshold":
                        fixture.holdout_gate["general_threshold"] = 0.5
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_non_float32":
                        fixture.holdout_gate["general_threshold"] = 0.95
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_inverted_thresholds":
                        fixture.holdout_gate["general_threshold"] = (
                            fixture.marketing_threshold
                        )
                        fixture.holdout_gate["marketing_threshold"] = (
                            fixture.general_threshold
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_root_threshold_mismatch":
                        fixture.holdout_gate["general_threshold"] = (
                            fixture.marketing_threshold
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_general_threshold_mismatch":
                        fixture.holdout_gate["evaluation"][
                            "general_threshold"
                        ] = fixture.marketing_threshold
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_marketing_threshold_mismatch":
                        fixture.holdout_gate["evaluation"][
                            "marketing_threshold"
                        ] = 1.0
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_missing_marketing_threshold":
                        del fixture.holdout_gate["evaluation"][
                            "marketing_threshold"
                        ]
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_labels":
                        fixture.holdout_gate["evaluation"]["labels"] = list(
                            reversed(LABELS)
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_row_count":
                        fixture.holdout_gate["evaluation"]["row_count"] = 419
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_macro_f1":
                        fixture.holdout_gate["evaluation"]["raw"]["overall"][
                            "macro_f1"
                        ] = 0.84
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_marketing_precision":
                        fixture.holdout_gate["evaluation"]["raw"]["overall"][
                            "marketing_precision"
                        ] = 0.89
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_locale":
                        fixture.holdout_gate["evaluation"]["raw"]["by_locale"][
                            "mixed"
                        ]["macro_f1"] = 0.79
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_safety":
                        fixture.holdout_gate["evaluation"]["safety"][
                            "trusted_non_marketing_predicted_marketing_count"
                        ] = 1
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_coverage":
                        coverage = fixture.holdout_gate["evaluation"][
                            "trusted_coverage"
                        ]["overall"]
                        coverage["trusted_count"] = 1
                        coverage["rate"] = (
                            1 / packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "holdout_actionable_row_count":
                        coverage = fixture.holdout_gate["evaluation"][
                            "trusted_coverage"
                        ]["overall"]
                        row_count = (
                            packager.EXPECTED_HOLDOUT_ACTIONABLE_ROWS - 1
                        )
                        coverage["row_count"] = row_count
                        coverage["rate"] = 300 / row_count
                        fixture.write_holdout_gate()
                    elif mutation == "old_gated_schema":
                        fixture.holdout_gate["schema_version"] = (
                            "semantic-gated-evaluation-v2"
                        )
                        fixture.write_holdout_gate()
                    elif mutation == "old_evaluation_schema":
                        fixture.holdout_gate["evaluation"][
                            "schema_version"
                        ] = "semantic-evaluation-v2"
                        fixture.write_holdout_gate()
                    elif mutation.startswith("threshold_"):
                        field = mutation.removeprefix("threshold_")
                        if field == "backend":
                            fixture.threshold_selection["provenance"][
                                "backend"
                            ] = "pytorch-koelectra"
                        elif field == "model":
                            fixture.threshold_selection["provenance"][
                                "model_artifact_sha256"
                            ] = "0" * 64
                        else:
                            fixture.threshold_selection["provenance"][
                                "vocab_sha256"
                            ] = "0" * 64
                        fixture.write_threshold_selection()
                    else:
                        field = mutation.removeprefix("holdout_")
                        if field == "backend":
                            fixture.holdout_gate["provenance"]["backend"] = (
                                "pytorch-koelectra"
                            )
                        elif field == "model":
                            fixture.holdout_gate["provenance"][
                                "model_artifact_sha256"
                            ] = "0" * 64
                        else:
                            fixture.holdout_gate["provenance"][
                                "vocab_sha256"
                            ] = "0" * 64
                        fixture.write_holdout_gate()

                    with self.assertRaises(packager.PackagingError):
                        packager.package_assets(fixture.options())
                    self.assertFalse(fixture.output_dir.exists())

    def test_accepts_exact_float32_floor_and_rejects_previous_float32(
        self,
    ) -> None:
        floor = packager.MINIMUM_CONFIDENCE_THRESHOLD
        bits = struct.unpack(">I", struct.pack(">f", floor))[0]
        previous_float32 = struct.unpack(
            ">f",
            struct.pack(">I", bits - 1),
        )[0]
        self.assertEqual(
            floor,
            struct.unpack(">f", struct.pack(">f", floor))[0],
        )
        self.assertLess(previous_float32, floor)

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            manifest = packager.package_assets(fixture.options())
            self.assertEqual(floor, manifest["general_threshold"])
            self.assertEqual(
                fixture.marketing_threshold,
                manifest["marketing_threshold"],
            )

        for field in ("general_threshold", "marketing_threshold"):
            with self.subTest(field=field):
                with tempfile.TemporaryDirectory() as directory:
                    fixture = PackageFixture(Path(directory))
                    fixture.threshold_selection[field] = previous_float32
                    fixture.write_threshold_selection()
                    with self.assertRaisesRegex(
                        packager.PackagingError,
                        "below the runtime safety floor",
                    ):
                        packager.package_assets(fixture.options())

    def test_rejects_development_gate_and_test_parity_tampering(
        self,
    ) -> None:
        mutations = (
            "development_gate",
            "development_rows",
            "development_source",
            "development_backend",
            "development_model",
            "development_vocab",
            "development_safety",
            "development_root_missing_general_threshold",
            "development_root_marketing_threshold_mismatch",
            "development_general_threshold_mismatch",
            "development_marketing_threshold_mismatch",
            "development_missing_general_threshold",
            "old_parity_schema",
            "legacy_parity_threshold",
            "parity_missing_general_threshold",
            "parity_general_threshold_mismatch",
            "parity_marketing_threshold_mismatch",
            "parity_split",
            "parity_conversion",
            "parity_source",
            "parity_model_bundle",
            "parity_model",
            "parity_vocab",
            "parity_agreement",
            "parity_count_contradiction",
            "parity_unsafe",
            "parity_quality",
            "parity_delta",
            "parity_probability_error",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    fixture = PackageFixture(Path(directory))
                    if mutation == "development_gate":
                        fixture.development_test_gate["gate"][
                            "passed"
                        ] = False
                        fixture.write_development_test_gate()
                    elif mutation == "development_rows":
                        fixture.development_test_gate["evaluation"][
                            "row_count"
                        ] -= 1
                        fixture.write_development_test_gate()
                    elif mutation == "development_source":
                        fixture.development_test_gate["provenance"][
                            "source_manifest_sha256"
                        ] = "e" * 64
                        fixture.write_development_test_gate()
                    elif mutation == "development_root_missing_general_threshold":
                        del fixture.development_test_gate[
                            "general_threshold"
                        ]
                        fixture.write_development_test_gate()
                    elif (
                        mutation
                        == "development_root_marketing_threshold_mismatch"
                    ):
                        fixture.development_test_gate[
                            "marketing_threshold"
                        ] = 1.0
                        fixture.write_development_test_gate()
                    elif mutation == "development_general_threshold_mismatch":
                        fixture.development_test_gate["evaluation"][
                            "general_threshold"
                        ] = fixture.marketing_threshold
                        fixture.write_development_test_gate()
                    elif mutation == "development_marketing_threshold_mismatch":
                        fixture.development_test_gate["evaluation"][
                            "marketing_threshold"
                        ] = 1.0
                        fixture.write_development_test_gate()
                    elif mutation == "development_missing_general_threshold":
                        del fixture.development_test_gate["evaluation"][
                            "general_threshold"
                        ]
                        fixture.write_development_test_gate()
                    elif mutation.startswith("development_"):
                        field = mutation.removeprefix("development_")
                        if field == "backend":
                            fixture.development_test_gate["provenance"][
                                "backend"
                            ] = "pytorch-koelectra"
                        elif field == "model":
                            fixture.development_test_gate["provenance"][
                                "model_artifact_sha256"
                            ] = "e" * 64
                        elif field == "vocab":
                            fixture.development_test_gate["provenance"][
                                "vocab_sha256"
                            ] = "e" * 64
                        else:
                            fixture.development_test_gate["evaluation"][
                                "safety"
                            ][
                                "trusted_non_marketing_predicted_marketing_count"
                            ] = 1
                        fixture.write_development_test_gate()
                    elif mutation == "old_parity_schema":
                        fixture.test_parity_report["schema_version"] = (
                            "semantic-backend-parity-v1"
                        )
                        fixture.write_test_parity_report()
                    elif mutation == "legacy_parity_threshold":
                        del fixture.test_parity_report["general_threshold"]
                        del fixture.test_parity_report["marketing_threshold"]
                        fixture.test_parity_report["threshold"] = (
                            fixture.general_threshold
                        )
                        fixture.write_test_parity_report()
                    elif mutation == "parity_missing_general_threshold":
                        del fixture.test_parity_report["general_threshold"]
                        fixture.write_test_parity_report()
                    elif mutation == "parity_general_threshold_mismatch":
                        fixture.test_parity_report["general_threshold"] = (
                            fixture.marketing_threshold
                        )
                        fixture.write_test_parity_report()
                    elif mutation == "parity_marketing_threshold_mismatch":
                        fixture.test_parity_report["marketing_threshold"] = 1.0
                        fixture.write_test_parity_report()
                    elif mutation == "parity_split":
                        fixture.test_parity_report["split"] = "validation"
                        fixture.write_test_parity_report()
                    elif mutation in {
                        "parity_conversion",
                        "parity_source",
                        "parity_model_bundle",
                        "parity_model",
                        "parity_vocab",
                    }:
                        fields = {
                            "parity_conversion": (
                                "conversion_manifest_sha256"
                            ),
                            "parity_source": "source_manifest_sha256",
                            "parity_model_bundle": (
                                "pytorch_model_bundle_sha256"
                            ),
                            "parity_model": "tflite_model_sha256",
                            "parity_vocab": "vocab_sha256",
                        }
                        fixture.test_parity_report["provenance"][
                            fields[mutation]
                        ] = "e" * 64
                        fixture.write_test_parity_report()
                    elif mutation == "parity_agreement":
                        fixture.test_parity_report["agreement"][
                            "raw_argmax"
                        ]["rate"] = 0.5
                        fixture.write_test_parity_report()
                    elif mutation == "parity_unsafe":
                        fixture.test_parity_report["agreement"][
                            "trusted_marketing"
                        ]["introduced_unsafe_by_tflite_count"] = 1
                        fixture.write_test_parity_report()
                    elif mutation == "parity_count_contradiction":
                        fixture.test_parity_report["agreement"][
                            "trusted_marketing"
                        ]["introduced_by_tflite_count"] = 1
                        fixture.write_test_parity_report()
                    elif mutation == "parity_quality":
                        fixture.test_parity_report["quality"]["tflite"][
                            "raw"
                        ]["macro_f1"] -= 0.01
                        fixture.write_test_parity_report()
                    elif mutation == "parity_delta":
                        fixture.test_parity_report["quality"][
                            "tflite_minus_pytorch"
                        ]["raw"]["macro_f1"] = 0.01
                        fixture.write_test_parity_report()
                    else:
                        fixture.test_parity_report["probability_error"][
                            "confidence"
                        ]["p95"] = 0.5
                        fixture.write_test_parity_report()

                    with self.assertRaises(packager.PackagingError):
                        packager.package_assets(fixture.options())
                    self.assertFalse(fixture.output_dir.exists())

    def test_rejects_duplicate_json_keys_and_invalid_wordpiece_vocab(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            fixture.threshold_path.write_text(
                (
                    '{"schema_version":"'
                    f'{packager.THRESHOLD_SELECTION_SCHEMA}",'
                    '"status":"selected","status":"selected",'
                    '"general_threshold":0.949999988079071,'
                    '"marketing_threshold":0.949999988079071,'
                    '"gate":{"passed":true}}\n'
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                packager.PackagingError,
                "duplicate JSON key",
            ):
                packager.package_assets(fixture.options())

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            fixture.vocab_path.write_text(
                "[PAD]\n[UNK]\n[CLS]\n[CLS]\n",
                encoding="utf-8",
            )
            with self.assertRaises(packager.PackagingError):
                packager.package_assets(fixture.options())

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            fixture.vocab_path.write_text(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\n\nword\n",
                encoding="utf-8",
            )
            with self.assertRaises(packager.PackagingError):
                packager.package_assets(fixture.options())

    def test_rejects_symlink_inputs_and_existing_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            real_vocab = fixture.model_dir / "real-vocab.txt"
            real_vocab.write_bytes(fixture.vocab_path.read_bytes())
            fixture.vocab_path.unlink()
            try:
                fixture.vocab_path.symlink_to(real_vocab)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink unavailable: {error}")
            with self.assertRaises(packager.PackagingError):
                packager.package_assets(fixture.options())

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            fixture.output_dir.mkdir()
            with self.assertRaises(packager.PackagingError):
                packager.package_assets(fixture.options())

    def test_model_ceiling_can_be_exercised_with_a_tiny_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))

            with self.assertRaises(packager.PackagingError):
                packager._validate_conversion(
                    fixture.conversion_dir,
                    max_model_bytes=fixture.model_path.stat().st_size - 1,
                )

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            with mock.patch.object(packager, "MAX_VOCAB_BYTES", 1):
                with self.assertRaises(packager.PackagingError):
                    packager.package_assets(fixture.options())

        with tempfile.TemporaryDirectory() as directory:
            fixture = PackageFixture(Path(directory))
            with mock.patch.object(packager, "MAX_MANIFEST_BYTES", 1):
                with self.assertRaises(packager.PackagingError):
                    packager.package_assets(fixture.options())
            self.assertFalse(fixture.output_dir.exists())

    def test_atomic_directory_removes_partial_output_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "assets"

            def fail(temporary: Path) -> None:
                (temporary / "partial").write_text(
                    "partial",
                    encoding="utf-8",
                )
                raise RuntimeError("stop")

            with self.assertRaises(RuntimeError):
                packager.atomic_output_directory(output, fail)

            self.assertFalse(output.exists())
            self.assertEqual([], list(output.parent.iterdir()))


if __name__ == "__main__":
    unittest.main()
