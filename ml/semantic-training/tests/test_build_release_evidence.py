from __future__ import annotations

import copy
import hashlib
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import build_release_evidence as evidence_builder  # noqa: E402
from package_semantic_assets import (  # noqa: E402
    LABELS_ASSET_FILENAME,
    MINIMUM_CONFIDENCE_THRESHOLD,
    MODEL_ASSET_FILENAME,
    MODEL_MANIFEST_FILENAME,
    OUTPUT_MANIFEST_SCHEMA,
    QUANTIZATION_AUDIT_SCHEMA,
    VOCAB_ASSET_FILENAME,
)
from semantic_contract import LABELS, model_bundle_hashes  # noqa: E402


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


class ReleaseEvidenceTest(unittest.TestCase):
    general_threshold = MINIMUM_CONFIDENCE_THRESHOLD
    marketing_threshold = struct.unpack(
        ">f",
        struct.pack(">f", 0.99),
    )[0]

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.base_model = self.root / "base-model"
        self.base_model.mkdir()
        upstream_contents = {
            "config.json": b'{"model_type":"electra"}\n',
            "pytorch_model.bin": b"fictional-upstream-weights",
            "tokenizer_config.json": b'{"do_lower_case":false}\n',
            "vocab.txt": b"[PAD]\n[UNK]\n[CLS]\n[SEP]\n",
        }
        for filename, content in upstream_contents.items():
            (self.base_model / filename).write_bytes(content)

        self.upstream = self.root / "upstream.json"
        self.upstream_value = {
            "schema_version": evidence_builder.UPSTREAM_SCHEMA_VERSION,
            "repository_id": "example/fictional-koelectra",
            "revision": "1" * 40,
            "license": "Apache-2.0",
            "evidence_urls": {
                "model_revision": (
                    "https://example.invalid/model/tree/" + "1" * 40
                ),
                "license": "https://example.invalid/model/LICENSE",
            },
            "verification_method": "pinned-revision-and-local-sha256",
            "files": {
                filename: {
                    "sha256": sha256_file(self.base_model / filename),
                    "size_bytes": (self.base_model / filename).stat().st_size,
                }
                for filename in sorted(upstream_contents)
            },
        }
        write_json(self.upstream, self.upstream_value)
        self.vocab_sha256 = sha256_file(self.base_model / "vocab.txt")

        self.training = self.root / "training_manifest.json"
        self.training_value = {
            "schema_version": evidence_builder.TRAINING_SCHEMA_VERSION,
            "status": "completed",
            "labels": list(LABELS),
            "text_format": {
                "version": "runtime-title-body-space-nfc-v2",
                "template": "{title} {body}",
                "normalization": "nfc",
            },
            "inputs": {
                "base_model": str(self.base_model),
                "base_vocab_sha256": self.vocab_sha256,
                "dataset": str(self.root / "dataset.jsonl"),
                "dataset_sha256": "2" * 64,
                "rows_by_split": {
                    "train": 10080,
                    "validation": 1260,
                },
            },
            "epochs": [
                {
                    "epoch": 3,
                    "train_loss": 0.05,
                    "validation_accuracy": 0.97,
                    "validation_loss": 0.1,
                }
            ],
            "best": {
                "epoch": 3,
                "path": "best",
                "validation_accuracy": 0.97,
                "validation_loss": 0.1,
            },
        }
        write_json(self.training, self.training_value)

        self.selected_model = self.root / "selected-model"
        self.selected_model.mkdir()
        (self.selected_model / "config.json").write_text(
            '{"model_type":"electra"}\n',
            encoding="utf-8",
        )
        (self.selected_model / "model.safetensors").write_bytes(
            b"fictional-trained-weights"
        )
        (self.selected_model / "vocab.txt").write_bytes(
            (self.base_model / "vocab.txt").read_bytes()
        )
        write_json(
            self.selected_model / "checkpoint.json",
            {
                "epoch": 3,
                "reason": "best-validation-loss",
                "metrics": self.training_value["epochs"][0],
            },
        )
        _, self.model_bundle_sha256 = model_bundle_hashes(
            self.selected_model
        )
        self.model_file = self.root / "semantic_classifier.tflite"
        self.model_file.write_bytes(b"fictional-tflite-model")
        self.model_sha256 = sha256_file(self.model_file)
        self.conversion = self.root / "conversion_manifest.json"
        self.conversion_value = {
            "schema_version": evidence_builder.CONVERSION_SCHEMA_VERSION,
            "labels": list(LABELS),
            "source": {
                "training_manifest_sha256": sha256_file(self.training),
                "model_bundle_sha256": self.model_bundle_sha256,
                "vocab_sha256": self.vocab_sha256,
            },
            "artifact": {
                "file": "semantic_classifier.tflite",
                "sha256": self.model_sha256,
                "size_bytes": self.model_file.stat().st_size,
                "max_size_bytes": 45 * 1024 * 1024,
            },
            "quantization": {
                "requested": "dynamic-int8",
                "applied": "dynamic-int8",
                "calibration_used": False,
                "experimental_backend": True,
                "fallback_reason": None,
            },
            "quantization_audit": {
                "schema_version": QUANTIZATION_AUDIT_SCHEMA,
                "method": (
                    "litert-interpreter-tensor-and-operator-inspection"
                ),
                "tensor_count": 100,
                "int8_tensor_count": 20,
                "operator_count": 50,
                "quantize_operator_count": 10,
                "passed": True,
            },
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
                    "name": "output_0_output",
                    "dtype": "float32",
                    "shape": [1, 7],
                },
            },
        }
        write_json(self.conversion, self.conversion_value)

        self.dataset_source_sha256 = "4" * 64
        self.threshold_selection = self.root / "threshold_selection.json"
        self.threshold_value = {
            "schema_version": evidence_builder.THRESHOLD_SCHEMA_VERSION,
            "status": "selected",
            "validation_only": True,
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "trusted_coverage": {
                "overall": {
                    "rate": 0.97,
                    "row_count": 1080,
                    "trusted_count": 1048,
                }
            },
            "gate": {"passed": True, "checks": {}},
            "provenance": self._provenance(self.dataset_source_sha256),
        }
        write_json(self.threshold_selection, self.threshold_value)

        self.test_gate = self.root / "test_gate.json"
        self.test_value = self._gate_value(
            row_count=1260,
            source_manifest_sha256=self.dataset_source_sha256,
            macro_f1=0.96,
            marketing_precision=1.0,
            trusted_coverage=0.973,
        )
        write_json(self.test_gate, self.test_value)

        self.holdout_manifest = self.root / "holdout_manifest.json"
        self.holdout_manifest_value = {
            "schema_version": "semantic-sealed-holdout-manifest-v2",
            "files": {
                "ko_holdout.jsonl": {
                    "sha256": "5" * 64,
                    "row_count": 140,
                },
                "en_holdout.jsonl": {
                    "sha256": "6" * 64,
                    "row_count": 140,
                },
                "mixed_holdout.jsonl": {
                    "sha256": "7" * 64,
                    "row_count": 140,
                },
            },
            "counts": {
                "row_count": 420,
                "pair_count": 210,
            },
        }
        write_json(self.holdout_manifest, self.holdout_manifest_value)
        self.holdout_source_sha256 = sha256_file(self.holdout_manifest)
        self.holdout_gate = self.root / "holdout_gate.json"
        self.holdout_value = self._gate_value(
            row_count=420,
            source_manifest_sha256=self.holdout_source_sha256,
            macro_f1=0.91,
            marketing_precision=0.95,
            trusted_coverage=0.88,
        )
        write_json(self.holdout_gate, self.holdout_value)

        self.parity = self.root / "parity.json"
        self.parity_value = {
            "schema_version": evidence_builder.PARITY_SCHEMA_VERSION,
            "labels": list(LABELS),
            "split": "test",
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "row_count": 1260,
            "provenance": {
                "source_manifest_sha256": self.dataset_source_sha256,
                "conversion_manifest_sha256": sha256_file(self.conversion),
                "pytorch_model_bundle_sha256": self.model_bundle_sha256,
                "tflite_model_sha256": self.model_sha256,
                "vocab_sha256": self.vocab_sha256,
                "pytorch_predictions_sha256": "8" * 64,
                "tflite_predictions_sha256": "9" * 64,
            },
            "agreement": {
                "raw_argmax": {
                    "count": 1255,
                    "row_count": 1260,
                    "rate": 1255 / 1260,
                    "by_locale": {},
                },
                "runtime_output": {
                    "count": 1255,
                    "row_count": 1260,
                    "rate": 1255 / 1260,
                    "by_locale": {},
                },
                "trusted_marketing": {
                    "pytorch_count": 177,
                    "tflite_count": 177,
                    "disagreement_count": 0,
                    "introduced_by_tflite_count": 0,
                    "introduced_unsafe_by_tflite_count": 0,
                },
            },
            "probability_error": {
                "percentile_method": "nearest-rank",
                "absolute_per_label": {
                    "mean": 0.001,
                    "p95": 0.01,
                    "maximum": 0.1,
                },
                "confidence": {
                    "mean": 0.002,
                    "p95": 0.02,
                    "maximum": 0.1,
                },
                "row_total_variation": {
                    "mean": 0.003,
                    "p95": 0.03,
                    "maximum": 0.15,
                },
            },
            "quality": {
                "pytorch": {
                    "raw": {
                        "accuracy": 0.963,
                        "macro_f1": 0.963,
                        "marketing_precision": 1.0,
                        "locale_macro_f1": {
                            "ko": 0.92,
                            "en": 0.97,
                            "mixed": 1.0,
                        },
                    },
                    "runtime": {
                        "accuracy": 0.955,
                        "macro_f1": 0.955,
                        "marketing_precision": 1.0,
                    },
                    "trusted_coverage": 0.972,
                    "trusted_marketing_false_positives": 0,
                },
                "tflite": {
                    "raw": {
                        "accuracy": 0.96,
                        "macro_f1": 0.96,
                        "marketing_precision": 1.0,
                        "locale_macro_f1": {
                            "ko": 0.91,
                            "en": 0.97,
                            "mixed": 1.0,
                        },
                    },
                    "runtime": {
                        "accuracy": 0.95,
                        "macro_f1": 0.95,
                        "marketing_precision": 1.0,
                    },
                    "trusted_coverage": 0.973,
                    "trusted_marketing_false_positives": 0,
                },
                "tflite_minus_pytorch": {
                    "raw": {
                        "accuracy": -0.003,
                        "macro_f1": -0.003,
                        "marketing_precision": 0.0,
                        "locale_macro_f1": {
                            "ko": -0.01,
                            "en": 0.0,
                            "mixed": 0.0,
                        },
                    },
                    "runtime": {
                        "accuracy": -0.005,
                        "macro_f1": -0.005,
                        "marketing_precision": 0.0,
                    },
                    "trusted_coverage": 0.001,
                    "trusted_marketing_false_positives": 0,
                },
            },
        }
        write_json(self.parity, self.parity_value)

        self.assets = self.root / "assets"
        self.assets.mkdir()
        (self.assets / MODEL_ASSET_FILENAME).write_bytes(
            self.model_file.read_bytes()
        )
        (self.assets / VOCAB_ASSET_FILENAME).write_bytes(
            (self.base_model / "vocab.txt").read_bytes()
        )
        (self.assets / LABELS_ASSET_FILENAME).write_text(
            "\n".join(LABELS) + "\n",
            encoding="utf-8",
        )
        asset_files = {
            filename: {
                "sha256": sha256_file(self.assets / filename),
                "size_bytes": (self.assets / filename).stat().st_size,
            }
            for filename in (
                MODEL_ASSET_FILENAME,
                VOCAB_ASSET_FILENAME,
                LABELS_ASSET_FILENAME,
            )
        }
        self.asset_manifest_value = {
            "schema_version": OUTPUT_MANIFEST_SCHEMA,
            "labels": list(LABELS),
            "max_sequence_length": 128,
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "files": asset_files,
            "tokenizer": {
                "type": "bert-wordpiece",
                "normalization": "nfc",
                "lowercase": False,
            },
            "conversion": {
                "quantization": self.conversion_value["quantization"],
                "quantization_audit": self.conversion_value[
                    "quantization_audit"
                ],
                "tensor_contract": self.conversion_value["tensor_contract"],
            },
            "evidence": {
                "conversion_manifest_sha256": sha256_file(self.conversion),
                "threshold_selection_sha256": sha256_file(
                    self.threshold_selection
                ),
                "development_test_gate_sha256": sha256_file(
                    self.test_gate
                ),
                "test_parity_report_sha256": sha256_file(self.parity),
                "sealed_holdout_gate_sha256": sha256_file(
                    self.holdout_gate
                ),
            },
            "evaluation_provenance": {
                "threshold_selection": self.threshold_value["provenance"],
                "development_test": self.test_value["provenance"],
                "sealed_holdout": self.holdout_value["provenance"],
            },
        }
        write_json(
            self.assets / MODEL_MANIFEST_FILENAME,
            self.asset_manifest_value,
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _provenance(self, source_manifest_sha256: str) -> dict[str, object]:
        return {
            "schema_version": "semantic-evaluation-provenance-v1",
            "source_manifest_sha256": source_manifest_sha256,
            "backend": evidence_builder.TFLITE_BACKEND,
            "model_artifact_sha256": self.model_sha256,
            "vocab_sha256": self.vocab_sha256,
        }

    def _gate_value(
        self,
        *,
        row_count: int,
        source_manifest_sha256: str,
        macro_f1: float,
        marketing_precision: float,
        trusted_coverage: float,
    ) -> dict[str, object]:
        locale_values = {
            "ko": {"macro_f1": macro_f1 - 0.02},
            "en": {"macro_f1": macro_f1},
            "mixed": {"macro_f1": min(1.0, macro_f1 + 0.02)},
        }
        return {
            "schema_version": evidence_builder.GATE_SCHEMA_VERSION,
            "general_threshold": self.general_threshold,
            "marketing_threshold": self.marketing_threshold,
            "gate": {"passed": True, "checks": {}},
            "provenance": self._provenance(source_manifest_sha256),
            "evaluation": {
                "schema_version": evidence_builder.EVALUATION_SCHEMA_VERSION,
                "labels": list(LABELS),
                "general_threshold": self.general_threshold,
                "marketing_threshold": self.marketing_threshold,
                "row_count": row_count,
                "raw": {
                    "overall": {
                        "accuracy": macro_f1,
                        "macro_f1": macro_f1,
                        "marketing_precision": marketing_precision,
                    },
                    "by_locale": locale_values,
                },
                "runtime": {
                    "overall": {
                        "accuracy": macro_f1 - 0.01,
                        "macro_f1": macro_f1 - 0.01,
                        "marketing_precision": marketing_precision,
                    }
                },
                "pair_consistency": {},
                "trusted_coverage": {
                    "overall": {"rate": trusted_coverage}
                },
                "safety": {
                    "trusted_non_marketing_predicted_marketing_count": 0
                },
            },
        }

    def _arguments(self) -> dict[str, object]:
        return {
            "release_id": "koelectra-primary-v2",
            "upstream_provenance": self.upstream,
            "training_manifest": self.training,
            "model_dir": self.selected_model,
            "conversion_manifest": self.conversion,
            "threshold_selection": self.threshold_selection,
            "test_gate": self.test_gate,
            "sealed_holdout_manifest": self.holdout_manifest,
            "sealed_holdout_gate": self.holdout_gate,
            "parity_report": self.parity,
            "assets_dir": self.assets,
        }

    def _synchronize_selected_model_provenance(self) -> None:
        _, self.model_bundle_sha256 = model_bundle_hashes(
            self.selected_model
        )
        self.conversion_value["source"]["training_manifest_sha256"] = (
            sha256_file(self.training)
        )
        self.conversion_value["source"]["model_bundle_sha256"] = (
            self.model_bundle_sha256
        )
        write_json(self.conversion, self.conversion_value)
        self.parity_value["provenance"]["conversion_manifest_sha256"] = (
            sha256_file(self.conversion)
        )
        self.parity_value["provenance"]["pytorch_model_bundle_sha256"] = (
            self.model_bundle_sha256
        )
        write_json(self.parity, self.parity_value)
        self.asset_manifest_value["evidence"][
            "conversion_manifest_sha256"
        ] = sha256_file(self.conversion)
        self.asset_manifest_value["evidence"][
            "test_parity_report_sha256"
        ] = sha256_file(self.parity)
        write_json(
            self.assets / MODEL_MANIFEST_FILENAME,
            self.asset_manifest_value,
        )

    def test_builds_deterministic_content_free_outputs(self) -> None:
        evidence, card = evidence_builder.build_release_evidence(
            **self._arguments()
        )
        output = self.root / "release-evidence"
        evidence_builder.write_release_evidence(output, evidence, card)
        first_json = (output / "evidence.json").read_bytes()
        first_card = (output / "MODEL_CARD.md").read_bytes()
        evidence_builder.write_release_evidence(output, evidence, card)

        self.assertEqual(first_json, (output / "evidence.json").read_bytes())
        self.assertEqual(first_card, (output / "MODEL_CARD.md").read_bytes())
        self.assertEqual(
            sha256_file(self.upstream),
            evidence["upstream"]["provenance_sha256"],
        )
        self.assertEqual(
            self.model_sha256,
            evidence["provenance"]["tflite_model_sha256"],
        )
        self.assertEqual(
            evidence_builder.SCHEMA_VERSION,
            evidence["schema_version"],
        )
        self.assertEqual(
            self.general_threshold,
            evidence["contract"]["general_threshold"],
        )
        self.assertEqual(
            self.marketing_threshold,
            evidence["contract"]["marketing_threshold"],
        )
        self.assertEqual(
            self.general_threshold,
            evidence["threshold_selection"]["general_threshold"],
        )
        self.assertEqual(
            self.marketing_threshold,
            evidence["threshold_selection"]["marketing_threshold"],
        )
        self.assertIn(
            f"General threshold: {self.general_threshold}",
            card,
        )
        self.assertIn(
            f"MARKETING threshold: {self.marketing_threshold}",
            card,
        )
        self.assertEqual(3, evidence["training"]["selected_epoch"])
        self.assertTrue(
            evidence["training"]["selected_is_best_epoch"]
        )
        serialized = json.dumps(evidence, sort_keys=True)
        self.assertNotIn(str(self.root), serialized)
        self.assertNotIn(str(self.root), card)
        self.assertNotIn('"title"', serialized)
        self.assertNotIn('"body"', serialized)
        self.assertNotIn("confidence_threshold", serialized)
        self.assertNotIn("selected_threshold", serialized)
        self.assertEqual(
            {"evidence.json", "MODEL_CARD.md"},
            {path.name for path in output.iterdir()},
        )

    def test_rejects_old_partial_or_mismatched_threshold_chain(self) -> None:
        def legacy_selection(value: dict[str, object]) -> None:
            value.pop("general_threshold")
            value.pop("marketing_threshold")
            value["selected_threshold"] = self.general_threshold

        def legacy_gate(value: dict[str, object]) -> None:
            value.pop("general_threshold")
            value.pop("marketing_threshold")
            value["threshold"] = self.general_threshold

        cases = (
            (
                "old selection schema",
                self.threshold_value,
                self.threshold_selection,
                lambda value: value.__setitem__(
                    "schema_version",
                    "semantic-threshold-selection-v3",
                ),
            ),
            (
                "legacy single selection threshold",
                self.threshold_value,
                self.threshold_selection,
                legacy_selection,
            ),
            (
                "partial selection pair",
                self.threshold_value,
                self.threshold_selection,
                lambda value: value.pop("marketing_threshold"),
            ),
            (
                "inverted selection pair",
                self.threshold_value,
                self.threshold_selection,
                lambda value: value.update(
                    {
                        "general_threshold": self.marketing_threshold,
                        "marketing_threshold": self.general_threshold,
                    }
                ),
            ),
            (
                "old gated schema",
                self.test_value,
                self.test_gate,
                lambda value: value.__setitem__(
                    "schema_version",
                    "semantic-gated-evaluation-v2",
                ),
            ),
            (
                "legacy single gate threshold",
                self.test_value,
                self.test_gate,
                legacy_gate,
            ),
            (
                "partial development gate pair",
                self.test_value,
                self.test_gate,
                lambda value: value.pop("marketing_threshold"),
            ),
            (
                "development gate threshold below floor",
                self.test_value,
                self.test_gate,
                lambda value: value.__setitem__(
                    "general_threshold",
                    0.5,
                ),
            ),
            (
                "development gate threshold is not float32",
                self.test_value,
                self.test_gate,
                lambda value: value.__setitem__(
                    "general_threshold",
                    0.95,
                ),
            ),
            (
                "inverted development gate pair",
                self.test_value,
                self.test_gate,
                lambda value: value.update(
                    {
                        "general_threshold": self.marketing_threshold,
                        "marketing_threshold": self.general_threshold,
                    }
                ),
            ),
            (
                "development gate pair differs from selection",
                self.test_value,
                self.test_gate,
                lambda value: value.__setitem__(
                    "general_threshold",
                    self.marketing_threshold,
                ),
            ),
            (
                "old evaluation schema",
                self.test_value,
                self.test_gate,
                lambda value: value["evaluation"].__setitem__(
                    "schema_version",
                    "semantic-evaluation-v2",
                ),
            ),
            (
                "partial development pair",
                self.test_value,
                self.test_gate,
                lambda value: value["evaluation"].pop(
                    "general_threshold"
                ),
            ),
            (
                "development pair mismatch",
                self.test_value,
                self.test_gate,
                lambda value: value["evaluation"].__setitem__(
                    "marketing_threshold",
                    1.0,
                ),
            ),
            (
                "partial holdout gate pair",
                self.holdout_value,
                self.holdout_gate,
                lambda value: value.pop("general_threshold"),
            ),
            (
                "holdout gate pair differs from selection",
                self.holdout_value,
                self.holdout_gate,
                lambda value: value.__setitem__(
                    "marketing_threshold",
                    1.0,
                ),
            ),
            (
                "holdout pair mismatch",
                self.holdout_value,
                self.holdout_gate,
                lambda value: value["evaluation"].__setitem__(
                    "general_threshold",
                    self.marketing_threshold,
                ),
            ),
            (
                "old parity schema",
                self.parity_value,
                self.parity,
                lambda value: value.__setitem__(
                    "schema_version",
                    "semantic-backend-parity-v1",
                ),
            ),
            (
                "partial parity pair",
                self.parity_value,
                self.parity,
                lambda value: value.pop("general_threshold"),
            ),
            (
                "parity pair mismatch",
                self.parity_value,
                self.parity,
                lambda value: value.__setitem__(
                    "marketing_threshold",
                    1.0,
                ),
            ),
            (
                "old asset schema",
                self.asset_manifest_value,
                self.assets / MODEL_MANIFEST_FILENAME,
                lambda value: value.__setitem__(
                    "schema_version",
                    "alarmcontrol-semantic-model-manifest-v1",
                ),
            ),
            (
                "partial asset pair",
                self.asset_manifest_value,
                self.assets / MODEL_MANIFEST_FILENAME,
                lambda value: value.pop("marketing_threshold"),
            ),
            (
                "asset pair mismatch",
                self.asset_manifest_value,
                self.assets / MODEL_MANIFEST_FILENAME,
                lambda value: value.__setitem__(
                    "general_threshold",
                    self.marketing_threshold,
                ),
            ),
        )
        for name, original, path, mutate in cases:
            with self.subTest(name=name):
                value = copy.deepcopy(original)
                mutate(value)
                write_json(path, value)
                with self.assertRaises(evidence_builder.EvidenceError):
                    evidence_builder.build_release_evidence(
                        **self._arguments()
                    )
                write_json(path, original)

    def test_reports_selected_non_best_checkpoint_metrics(self) -> None:
        selected_metrics = {
            "epoch": 4,
            "train_loss": 0.03,
            "validation_accuracy": 0.96,
            "validation_loss": 0.12,
        }
        self.training_value["epochs"].append(selected_metrics)
        write_json(self.training, self.training_value)
        write_json(
            self.selected_model / "checkpoint.json",
            {
                "epoch": 4,
                "reason": "epoch-complete",
                "metrics": selected_metrics,
            },
        )
        self._synchronize_selected_model_provenance()

        evidence, card = evidence_builder.build_release_evidence(
            **self._arguments()
        )

        self.assertEqual(3, evidence["training"]["best_epoch"])
        self.assertEqual(4, evidence["training"]["selected_epoch"])
        self.assertFalse(
            evidence["training"]["selected_is_best_epoch"]
        )
        self.assertEqual(
            0.96,
            evidence["training"]["selected_validation_accuracy"],
        )
        self.assertEqual(
            0.12,
            evidence["training"]["selected_validation_loss"],
        )
        self.assertIn("Selected checkpoint epoch: 4", card)
        self.assertIn("Training best epoch: 3", card)

    def test_rejects_checkpoint_metrics_not_in_training_manifest(self) -> None:
        write_json(
            self.selected_model / "checkpoint.json",
            {
                "epoch": 3,
                "reason": "best-validation-loss",
                "metrics": {
                    **self.training_value["epochs"][0],
                    "validation_accuracy": 0.5,
                },
            },
        )
        self._synchronize_selected_model_provenance()

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "checkpoint metrics differ",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_best_metrics_not_in_training_epoch(self) -> None:
        self.training_value["best"]["validation_accuracy"] = 0.5
        write_json(self.training, self.training_value)
        self._synchronize_selected_model_provenance()

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "best metrics differ",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_selected_bundle_not_used_for_conversion(self) -> None:
        (self.selected_model / "model.safetensors").write_bytes(
            b"different-trained-weights"
        )

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "bundle does not match conversion provenance",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_malformed_or_mismatched_upstream_hashes(self) -> None:
        for mutation in ("malformed", "mismatch"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(self.upstream_value)
                value["files"]["config.json"]["sha256"] = (
                    "NOT-A-HASH" if mutation == "malformed" else "a" * 64
                )
                write_json(self.upstream, value)
                with self.assertRaises(evidence_builder.EvidenceError):
                    evidence_builder.build_release_evidence(
                        **self._arguments()
                    )
                write_json(self.upstream, self.upstream_value)

    def test_rejects_parity_provenance_mismatch(self) -> None:
        value = copy.deepcopy(self.parity_value)
        value["provenance"]["vocab_sha256"] = "a" * 64
        write_json(self.parity, value)

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "parity provenance mismatch",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_quantization_audit_and_asset_evidence_tampering(
        self,
    ) -> None:
        conversion = copy.deepcopy(self.conversion_value)
        conversion["quantization_audit"]["passed"] = False
        write_json(self.conversion, conversion)
        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "quantization audit",
        ):
            evidence_builder.build_release_evidence(**self._arguments())
        write_json(self.conversion, self.conversion_value)

        manifest_path = self.assets / MODEL_MANIFEST_FILENAME
        asset_manifest = copy.deepcopy(self.asset_manifest_value)
        asset_manifest["evidence"]["test_parity_report_sha256"] = "a" * 64
        write_json(manifest_path, asset_manifest)
        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "asset evidence hashes mismatch",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_stale_training_text_format(self) -> None:
        value = copy.deepcopy(self.training_value)
        value["text_format"]["version"] = "runtime-title-body-space-v1"
        write_json(self.training, value)
        conversion = copy.deepcopy(self.conversion_value)
        conversion["source"]["training_manifest_sha256"] = sha256_file(
            self.training
        )
        write_json(self.conversion, conversion)

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "training text-format contract mismatch",
        ):
            evidence_builder.build_release_evidence(**self._arguments())

    def test_rejects_absolute_path_in_checked_evidence_field(self) -> None:
        value = copy.deepcopy(self.upstream_value)
        value["verification_method"] = "/tmp/local-verification"
        write_json(self.upstream, value)

        with self.assertRaisesRegex(
            evidence_builder.EvidenceError,
            "must not contain a path",
        ):
            evidence_builder.build_release_evidence(**self._arguments())


if __name__ == "__main__":
    unittest.main()
