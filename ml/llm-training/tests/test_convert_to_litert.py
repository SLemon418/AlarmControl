from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from enum import Enum
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

LLM_TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LLM_TRAINING_DIR))

import convert_to_litert


class FakeQuantizationName(str, Enum):
    NONE = "none"
    DYNAMIC_INT8 = "dynamic_int8"
    DYNAMIC_INT4_BLOCK128 = "dynamic_int4_block128"


def create_model_dir(root: Path) -> Path:
    model_dir = root / "model"
    model_dir.mkdir()
    (model_dir / "config.json").write_text("{}", encoding="utf-8")
    (model_dir / "tokenizer.model").write_bytes(b"tokenizer")
    (model_dir / "model.safetensors").write_bytes(b"weights")
    return model_dir


def fake_converter_modules() -> tuple[dict[str, object], dict[str, Mock]]:
    calls = {
        "build_model_270m": Mock(),
        "build_model_1b": Mock(),
        "convert_to_tflite": Mock(),
    }

    def conversion_entrypoint(
        model: object,
        *,
        prefill_seq_len: int,
        kv_cache_max_len: int,
        quantize: str,
    ) -> None:
        del model, prefill_seq_len, kv_cache_max_len, quantize

    calls["convert_to_tflite"].side_effect = conversion_entrypoint
    modules = {
        convert_to_litert.MODULE_NAMES["gemma3"]: SimpleNamespace(
            build_model_270m=calls["build_model_270m"],
            build_model_1b=calls["build_model_1b"],
        ),
        convert_to_litert.MODULE_NAMES["converter"]: SimpleNamespace(
            QuantizationName=FakeQuantizationName,
            convert_to_tflite=conversion_entrypoint,
        ),
        convert_to_litert.MODULE_NAMES["kv_cache"]: SimpleNamespace(
            KV_LAYOUT_TRANSPOSED=object(),
        ),
        convert_to_litert.MODULE_NAMES["export_config"]: SimpleNamespace(
            ExportConfig=type("ExportConfig", (), {}),
        ),
    }
    return modules, calls


class ConvertToLiteRtTest(unittest.TestCase):
    def test_defaults_recommend_270m_dynamic_int4(self) -> None:
        args = convert_to_litert.parse_args(["--model-dir", "model"])

        self.assertEqual("270m", args.model_size)
        self.assertEqual("dynamic_int4_block128", args.quantize)
        self.assertEqual(
            "alarmcontrol-gemma3-270m",
            convert_to_litert.model_name(args.model_size, args.name),
        )
        self.assertEqual(
            "1b",
            convert_to_litert.parse_args(
                ["--model-dir", "model", "--model-size", "1b"]
            ).model_size,
        )

    def test_validation_requires_complete_local_model(self) -> None:
        cases = (
            ("config.json", "config_missing"),
            ("tokenizer.model", "tokenizer_missing"),
            ("model.safetensors", "weights_missing"),
        )
        for filename, expected_code in cases:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                model_dir = create_model_dir(root)
                (model_dir / filename).unlink()

                with self.assertRaises(convert_to_litert.ConversionInputError) as raised:
                    convert_to_litert.validate_conversion_inputs(
                        model_dir,
                        root / "output",
                        "candidate",
                        128,
                        256,
                    )

                self.assertEqual(expected_code, raised.exception.code)

    def test_validation_rejects_invalid_lengths(self) -> None:
        cases = (
            (0, 256, "prefill_length_invalid"),
            (128, 0, "kv_cache_length_invalid"),
            (256, 256, "length_order_invalid"),
            (128, 4_097, "kv_cache_length_invalid"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_dir = create_model_dir(root)
            for prefill, cache, expected_code in cases:
                with self.subTest(prefill=prefill, cache=cache):
                    with self.assertRaises(
                        convert_to_litert.ConversionInputError
                    ) as raised:
                        convert_to_litert.validate_conversion_inputs(
                            model_dir,
                            root / "output",
                            "candidate",
                            prefill,
                            cache,
                        )
                    self.assertEqual(expected_code, raised.exception.code)

    def test_validation_rejects_path_misuse_and_output_collision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_dir = create_model_dir(root)
            with self.assertRaises(
                convert_to_litert.ConversionInputError
            ) as raised:
                convert_to_litert.validate_conversion_inputs(
                    model_dir,
                    model_dir / "output",
                    "candidate",
                    128,
                    256,
                )
            self.assertEqual("output_overlaps_model", raised.exception.code)

            output_dir = root / "output"
            output_dir.mkdir()
            (output_dir / "candidate_q4.tflite").write_bytes(b"existing")
            with self.assertRaises(
                convert_to_litert.ConversionInputError
            ) as raised:
                convert_to_litert.validate_conversion_inputs(
                    model_dir,
                    output_dir,
                    "candidate",
                    128,
                    256,
                )
            self.assertEqual("output_collision", raised.exception.code)

            with self.assertRaises(
                convert_to_litert.ConversionInputError
            ) as raised:
                convert_to_litert.validate_conversion_inputs(
                    model_dir,
                    root / "other-output",
                    "../candidate",
                    128,
                    256,
                )
            self.assertEqual("name_invalid", raised.exception.code)

    def test_static_inspection_does_not_construct_or_convert(self) -> None:
        modules, calls = fake_converter_modules()

        inspected, report = convert_to_litert.inspect_converter_api(
            model_size="270m",
            quantization="dynamic_int4_block128",
            module_loader=modules.__getitem__,
            version_reader=lambda _: "0.9.1",
        )

        self.assertIsNotNone(inspected)
        self.assertTrue(report["capability_passed"])
        self.assertEqual("STATIC_ONLY", report["status"])
        self.assertEqual("RUNTIME_UNVERIFIED", report["runtime_status"])
        self.assertEqual(
            "UNVERIFIED",
            report["mediapipe_tasks_genai_0_10_35"],
        )
        self.assertFalse(report["model_constructed"])
        self.assertFalse(report["conversion_executed"])
        for mocked_call in calls.values():
            mocked_call.assert_not_called()

    def test_preflight_json_is_aggregate_and_contains_no_paths(self) -> None:
        modules, _ = fake_converter_modules()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_dir = create_model_dir(root)
            output = io.StringIO()
            with (
                patch.object(
                    convert_to_litert,
                    "inspect_converter_api",
                    side_effect=lambda **kwargs: (
                        modules,
                        {
                            **convert_to_litert.base_preflight_report(**kwargs),
                            "input_valid": True,
                            "capability_passed": True,
                        },
                    ),
                ),
                contextlib.redirect_stdout(output),
            ):
                exit_code = convert_to_litert.main(
                    [
                        "--model-dir",
                        str(model_dir),
                        "--output-dir",
                        str(root / "output"),
                        "--preflight-only",
                    ]
                )

            report = json.loads(output.getvalue())
            serialized = json.dumps(report, sort_keys=True)
            self.assertEqual(0, exit_code)
            self.assertEqual("STATIC_ONLY", report["status"])
            self.assertEqual("RUNTIME_UNVERIFIED", report["runtime_status"])
            self.assertNotIn(str(root), serialized)
            self.assertFalse((root / "output").exists())

    def test_preflight_fails_closed_when_api_is_incomplete(self) -> None:
        modules, _ = fake_converter_modules()
        modules[convert_to_litert.MODULE_NAMES["gemma3"]] = SimpleNamespace(
            build_model_1b=Mock(),
        )

        inspected, report = convert_to_litert.inspect_converter_api(
            model_size="270m",
            quantization="dynamic_int4_block128",
            module_loader=modules.__getitem__,
            version_reader=lambda _: "0.9.1",
        )

        self.assertIsNone(inspected)
        self.assertFalse(report["capability_passed"])
        self.assertIn("build_model_270m_unavailable", report["errors"])

    def test_offline_cpu_environment_is_bounded(self) -> None:
        variables = (
            "HF_HUB_OFFLINE",
            "TRANSFORMERS_OFFLINE",
            "CUDA_VISIBLE_DEVICES",
            "HIP_VISIBLE_DEVICES",
            "JAX_PLATFORMS",
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
        )
        with patch.dict(os.environ, {name: "unbounded" for name in variables}):
            convert_to_litert.configure_offline_cpu_environment()

            self.assertEqual("1", os.environ["HF_HUB_OFFLINE"])
            self.assertEqual("1", os.environ["TRANSFORMERS_OFFLINE"])
            self.assertEqual("", os.environ["CUDA_VISIBLE_DEVICES"])
            self.assertEqual("", os.environ["HIP_VISIBLE_DEVICES"])
            self.assertEqual("cpu", os.environ["JAX_PLATFORMS"])
            for variable in variables[5:]:
                self.assertEqual("2", os.environ[variable])

    def test_converter_version_matches_exact_requirement_pin(self) -> None:
        required = [
            line
            for line in (
                LLM_TRAINING_DIR / "requirements-convert.txt"
            ).read_text(encoding="utf-8").splitlines()
            if line.startswith("litert-torch==")
        ]
        self.assertEqual(
            [f"litert-torch=={convert_to_litert.LITERT_TORCH_VERSION}"],
            required,
        )


if __name__ == "__main__":
    unittest.main()
