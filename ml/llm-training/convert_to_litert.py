#!/usr/bin/env python3
"""Preflight or convert a local Gemma checkpoint with pinned LiteRT Torch."""

from __future__ import annotations

import argparse
import importlib
import importlib.metadata
import inspect
import json
import os
import re
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "artifacts" / "litert"
DEFAULT_MODEL_SIZE = "270m"
DEFAULT_QUANTIZATION = "dynamic_int4_block128"
LITERT_TORCH_VERSION = "0.9.1"
MAX_CONTEXT_LENGTH = 4_096
MAX_NAME_LENGTH = 80
QUANTIZATION_CHOICES = (
    "none",
    "fp16",
    "dynamic_int8",
    "weight_only_int8",
    "dynamic_int4_block128",
)
MODULE_NAMES = {
    "gemma3": "litert_torch.generative.examples.gemma3.gemma3",
    "kv_cache": "litert_torch.generative.layers.kv_cache",
    "converter": "litert_torch.generative.utilities.converter",
    "export_config": "litert_torch.generative.utilities.export_config",
}


class ConversionInputError(ValueError):
    """A content-free, stable validation failure."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--name",
        help="Output prefix; defaults to alarmcontrol-gemma3-<model-size>",
    )
    parser.add_argument(
        "--model-size",
        choices=("270m", "1b"),
        default=DEFAULT_MODEL_SIZE,
    )
    parser.add_argument("--prefill-seq-len", type=int, default=2_048)
    parser.add_argument("--kv-cache-max-len", type=int, default=4_096)
    parser.add_argument(
        "--quantize",
        choices=QUANTIZATION_CHOICES,
        default=DEFAULT_QUANTIZATION,
    )
    parser.add_argument(
        "--preflight-only",
        action="store_true",
        help="Check the pinned static converter API without building a model",
    )
    return parser.parse_args(argv)


def model_name(model_size: str, requested_name: str | None) -> str:
    return requested_name or f"alarmcontrol-gemma3-{model_size}"


def configure_offline_cpu_environment() -> None:
    """Keep the build-time converter offline and limited to two CPU threads."""
    for variable in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE"):
        os.environ[variable] = "1"
    for variable in ("CUDA_VISIBLE_DEVICES", "HIP_VISIBLE_DEVICES"):
        os.environ[variable] = ""
    os.environ["JAX_PLATFORMS"] = "cpu"
    for variable in (
        "OMP_NUM_THREADS",
        "MKL_NUM_THREADS",
        "OPENBLAS_NUM_THREADS",
        "VECLIB_MAXIMUM_THREADS",
        "NUMEXPR_NUM_THREADS",
    ):
        os.environ[variable] = "2"


def validate_conversion_inputs(
    model_dir: Path,
    output_dir: Path,
    name: str,
    prefill_seq_len: int,
    kv_cache_max_len: int,
) -> None:
    if not model_dir.is_dir():
        raise ConversionInputError(
            "model_dir_missing",
            "model-dir must be an existing local directory",
        )

    config = model_dir / "config.json"
    if not config.is_file() or config.stat().st_size == 0:
        raise ConversionInputError(
            "config_missing",
            "model-dir must contain a non-empty config.json",
        )
    try:
        parsed_config = json.loads(config.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ConversionInputError(
            "config_invalid",
            "model-dir config.json must be a readable JSON object",
        ) from error
    if not isinstance(parsed_config, dict):
        raise ConversionInputError(
            "config_invalid",
            "model-dir config.json must be a readable JSON object",
        )

    tokenizer = model_dir / "tokenizer.model"
    if not tokenizer.is_file() or tokenizer.stat().st_size == 0:
        raise ConversionInputError(
            "tokenizer_missing",
            "model-dir must contain a non-empty tokenizer.model",
        )
    weight_files = sorted(model_dir.glob("model*.safetensors"))
    if not weight_files or any(path.stat().st_size == 0 for path in weight_files):
        raise ConversionInputError(
            "weights_missing",
            "model-dir must contain non-empty model*.safetensors weights",
        )

    if (
        not name
        or len(name) > MAX_NAME_LENGTH
        or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) is None
        or ".." in name
    ):
        raise ConversionInputError(
            "name_invalid",
            "name must be a safe 1-80 character file prefix",
        )
    if (
        output_dir == model_dir
        or model_dir in output_dir.parents
        or output_dir in model_dir.parents
    ):
        raise ConversionInputError(
            "output_overlaps_model",
            "output-dir and model-dir must not overlap",
        )
    if output_dir.exists() and not output_dir.is_dir():
        raise ConversionInputError(
            "output_not_directory",
            "output-dir exists and is not a directory",
        )
    if output_dir.is_dir() and any(output_dir.glob(f"{name}*.tflite")):
        raise ConversionInputError(
            "output_collision",
            "output-dir already contains a matching LiteRT output",
        )

    if not 1 <= prefill_seq_len <= MAX_CONTEXT_LENGTH:
        raise ConversionInputError(
            "prefill_length_invalid",
            f"prefill-seq-len must be between 1 and {MAX_CONTEXT_LENGTH}",
        )
    if not 2 <= kv_cache_max_len <= MAX_CONTEXT_LENGTH:
        raise ConversionInputError(
            "kv_cache_length_invalid",
            f"kv-cache-max-len must be between 2 and {MAX_CONTEXT_LENGTH}",
        )
    if prefill_seq_len >= kv_cache_max_len:
        raise ConversionInputError(
            "length_order_invalid",
            "prefill-seq-len must be smaller than kv-cache-max-len",
        )


def base_preflight_report(
    *,
    model_size: str,
    quantization: str,
) -> dict[str, Any]:
    return {
        "schema": "alarmcontrol-gemma-conversion-preflight-v1",
        "status": "STATIC_ONLY",
        "runtime_status": "RUNTIME_UNVERIFIED",
        "candidate": {
            "model_size": model_size,
            "quantization": quantization,
        },
        "input_valid": False,
        "capability_passed": False,
        "checks": {
            "litert_torch_pin": False,
            "build_model_270m": False,
            "build_model_1b": False,
            "quantization_option": False,
            "conversion_entrypoint": False,
            "transposed_kv_cache": False,
            "export_config": False,
        },
        "installed_litert_torch": "UNAVAILABLE",
        "model_constructed": False,
        "conversion_executed": False,
        "task_bundle_created": False,
        "mediapipe_tasks_genai_0_10_35": "UNVERIFIED",
        "device_validation": "REQUIRED",
        "errors": [],
    }


def inspect_converter_api(
    *,
    model_size: str,
    quantization: str,
    module_loader: Callable[[str], Any] = importlib.import_module,
    version_reader: Callable[[str], str] = importlib.metadata.version,
) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    """Inspect converter symbols without constructing or converting a model."""
    report = base_preflight_report(
        model_size=model_size,
        quantization=quantization,
    )
    report["input_valid"] = True

    try:
        installed_version = version_reader("litert-torch")
    except importlib.metadata.PackageNotFoundError:
        report["errors"].append("litert_torch_not_installed")
    else:
        report["installed_litert_torch"] = installed_version
        report["checks"]["litert_torch_pin"] = (
            installed_version == LITERT_TORCH_VERSION
        )
        if not report["checks"]["litert_torch_pin"]:
            report["errors"].append("litert_torch_version_mismatch")

    modules: dict[str, Any] = {}
    for key, module_name in MODULE_NAMES.items():
        try:
            modules[key] = module_loader(module_name)
        except Exception:
            report["errors"].append(f"{key}_import_failed")

    gemma3_module = modules.get("gemma3")
    converter_module = modules.get("converter")
    kv_cache_module = modules.get("kv_cache")
    export_config_module = modules.get("export_config")

    report["checks"]["build_model_270m"] = callable(
        getattr(gemma3_module, "build_model_270m", None)
    )
    report["checks"]["build_model_1b"] = callable(
        getattr(gemma3_module, "build_model_1b", None)
    )

    quantization_names: set[str] = set()
    quantization_enum = getattr(converter_module, "QuantizationName", None)
    if quantization_enum is not None:
        try:
            quantization_names = {str(item.value) for item in quantization_enum}
        except (AttributeError, TypeError):
            quantization_names = set()
    report["checks"]["quantization_option"] = (
        DEFAULT_QUANTIZATION in quantization_names
        and quantization in quantization_names
    )

    conversion_entrypoint = getattr(converter_module, "convert_to_tflite", None)
    if callable(conversion_entrypoint):
        try:
            parameters = inspect.signature(conversion_entrypoint).parameters
        except (TypeError, ValueError):
            parameters = {}
        report["checks"]["conversion_entrypoint"] = {
            "prefill_seq_len",
            "kv_cache_max_len",
            "quantize",
        }.issubset(parameters)
    report["checks"]["transposed_kv_cache"] = hasattr(
        kv_cache_module,
        "KV_LAYOUT_TRANSPOSED",
    )
    report["checks"]["export_config"] = callable(
        getattr(export_config_module, "ExportConfig", None)
    )

    missing_checks = [
        name for name, passed in report["checks"].items() if not passed
    ]
    report["errors"].extend(
        f"{name}_unavailable"
        for name in missing_checks
        if f"{name}_unavailable" not in report["errors"]
    )
    report["capability_passed"] = not missing_checks
    return (modules if report["capability_passed"] else None), report


def convert(
    *,
    modules: dict[str, Any],
    model_dir: Path,
    output_dir: Path,
    name: str,
    model_size: str,
    prefill_seq_len: int,
    kv_cache_max_len: int,
    quantization: str,
) -> Path:
    gemma3_module = modules["gemma3"]
    converter_module = modules["converter"]
    kv_cache_module = modules["kv_cache"]
    export_config_class = modules["export_config"].ExportConfig

    builder = (
        gemma3_module.build_model_270m
        if model_size == "270m"
        else gemma3_module.build_model_1b
    )
    model = builder(str(model_dir))
    export_config = export_config_class()
    export_config.kvcache_layout = kv_cache_module.KV_LAYOUT_TRANSPOSED
    export_config.mask_as_input = True
    emitted = converter_module.convert_to_tflite(
        model,
        output_path=str(output_dir),
        output_name_prefix=name,
        prefill_seq_len=prefill_seq_len,
        kv_cache_max_len=kv_cache_max_len,
        quantize=quantization,
        export_config=export_config,
    )
    candidate = Path(emitted).resolve()
    if (
        candidate.parent != output_dir
        or not candidate.name.startswith(name)
        or candidate.suffix != ".tflite"
        or not candidate.is_file()
        or candidate.stat().st_size == 0
    ):
        raise SystemExit("Converter did not emit the expected non-empty LiteRT output")
    return candidate


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    configure_offline_cpu_environment()
    model_dir = args.model_dir.resolve()
    output_dir = args.output_dir.resolve()
    name = model_name(args.model_size, args.name)

    try:
        validate_conversion_inputs(
            model_dir,
            output_dir,
            name,
            args.prefill_seq_len,
            args.kv_cache_max_len,
        )
    except ConversionInputError as error:
        if args.preflight_only:
            report = base_preflight_report(
                model_size=args.model_size,
                quantization=args.quantize,
            )
            report["errors"].append(error.code)
            print(json.dumps(report, sort_keys=True))
            return 2
        raise SystemExit(str(error)) from error

    modules, report = inspect_converter_api(
        model_size=args.model_size,
        quantization=args.quantize,
    )
    if args.preflight_only:
        print(json.dumps(report, sort_keys=True))
        return 0 if report["capability_passed"] else 2
    if modules is None:
        codes = ", ".join(report["errors"])
        raise SystemExit(f"Static converter preflight failed: {codes}")

    validate_conversion_inputs(
        model_dir,
        output_dir,
        name,
        args.prefill_seq_len,
        args.kv_cache_max_len,
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    candidate = convert(
        modules=modules,
        model_dir=model_dir,
        output_dir=output_dir,
        name=name,
        model_size=args.model_size,
        prefill_seq_len=args.prefill_seq_len,
        kv_cache_max_len=args.kv_cache_max_len,
        quantization=args.quantize,
    )
    print(f"LiteRT output: {candidate} ({candidate.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
