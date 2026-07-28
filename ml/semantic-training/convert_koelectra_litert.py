#!/usr/bin/env python3
"""Convert a local trained KoELECTRA classifier to a fixed LiteRT model.

The conversion is offline-only. The default ``auto`` mode first attempts
calibration-free PT2E dynamic int8 weight quantization with litert-torch 0.9.1
and falls back to float32 only when that backend conversion is unsupported.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import resource
import shutil
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from semantic_contract import LABELS, MAX_SEQUENCE_LENGTH, model_bundle_hashes

GIB = 1024**3
MIB = 1024**2
MAX_THREADS = 2
DEFAULT_MAX_RSS_BYTES = 4 * GIB
DEFAULT_MAX_MODEL_BYTES = 45 * MIB
SUPPORTED_LITERT_TORCH_VERSION = "0.9.1"
DYNAMIC_INT8_FALLBACK_REASON = "dynamic-int8-backend-conversion-failed"
MODEL_FILENAME = "semantic_classifier.tflite"
MANIFEST_FILENAME = "conversion_manifest.json"
VOCAB_FILENAME = "vocab.txt"
MANIFEST_SCHEMA_VERSION = "koelectra-litert-conversion-v2"
QUANTIZATION_AUDIT_SCHEMA_VERSION = "koelectra-dynamic-int8-audit-v1"
QUANTIZATION_MODES = ("auto", "dynamic-int8", "float32")
OFFLINE_CPU_ENVIRONMENT = {
    "CUDA_VISIBLE_DEVICES": "",
    "HF_HUB_OFFLINE": "1",
    "TRANSFORMERS_OFFLINE": "1",
    "OMP_NUM_THREADS": str(MAX_THREADS),
    "MKL_NUM_THREADS": str(MAX_THREADS),
    "OPENBLAS_NUM_THREADS": str(MAX_THREADS),
    "VECLIB_MAXIMUM_THREADS": str(MAX_THREADS),
    "NUMEXPR_NUM_THREADS": str(MAX_THREADS),
    "RAYON_NUM_THREADS": str(MAX_THREADS),
    "TOKENIZERS_PARALLELISM": "false",
    "PYTORCH_ENABLE_MPS_FALLBACK": "0",
}


class ConversionError(RuntimeError):
    """Raised when local conversion cannot satisfy the deployment contract."""


class ResourceLimitExceeded(ConversionError):
    """Raised when peak resident memory reaches the hard limit."""


class BackendConversionError(ConversionError):
    """Raised when LiteRT Torch cannot lower or export the model."""


class ArtifactValidationError(ConversionError):
    """Raised when the exported flatbuffer violates its hard contract."""


@dataclass(frozen=True)
class ConversionOptions:
    """Validated conversion inputs."""

    model_dir: Path
    output_dir: Path
    quantization: str
    include_token_type_ids: bool
    max_rss_bytes: int
    max_model_bytes: int


def configure_offline_cpu_environment() -> None:
    """Force offline CPU execution before importing ML dependencies."""

    os.environ.update(OFFLINE_CPU_ENVIRONMENT)


def _maximum_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def enforce_memory_ceiling(limit_bytes: int, context: str) -> int:
    """Fail when peak RSS reaches the configured 4 GiB default ceiling."""

    current = _maximum_rss_bytes()
    if current >= limit_bytes:
        raise ResourceLimitExceeded(
            f"{context}: peak RSS reached {current} bytes; limit is {limit_bytes}"
        )
    return current


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _expected_id2label() -> dict[str, str]:
    return {str(index): label for index, label in enumerate(LABELS)}


def _expected_label2id() -> dict[str, int]:
    return {label: index for index, label in enumerate(LABELS)}


def validate_local_model_dir(path: Path) -> tuple[Path, dict[str, Any]]:
    """Require a local ElectraForSequenceClassification bundle."""

    expanded = path.expanduser()
    if not expanded.is_dir():
        raise ConversionError(
            f"--model-dir must be an existing local directory: {expanded}"
        )
    resolved = expanded.resolve(strict=True)
    config_path = resolved / "config.json"
    if config_path.is_symlink() or not config_path.is_file():
        raise ConversionError(f"local model is missing regular config.json: {resolved}")
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ConversionError(f"invalid local model config: {config_path}") from error
    if not isinstance(config, dict):
        raise ConversionError(f"model config must be a JSON object: {config_path}")
    if config.get("model_type") != "electra":
        raise ConversionError("model config must declare model_type='electra'")
    architectures = config.get("architectures")
    if (
        not isinstance(architectures, list)
        or "ElectraForSequenceClassification" not in architectures
    ):
        raise ConversionError(
            "model config must declare ElectraForSequenceClassification"
        )
    if config.get("id2label") != _expected_id2label():
        raise ConversionError(
            "model id2label must match the exact semantic_contract.LABELS order"
        )
    if config.get("label2id") != _expected_label2id():
        raise ConversionError(
            "model label2id must match the exact semantic_contract.LABELS order"
        )
    vocab_path = resolved / VOCAB_FILENAME
    if vocab_path.is_symlink() or not vocab_path.is_file():
        raise ConversionError(
            f"local model is missing regular {VOCAB_FILENAME}: {resolved}"
        )
    if vocab_path.stat().st_size <= 0:
        raise ConversionError(f"{VOCAB_FILENAME} must not be empty")
    return resolved, config


def validate_options(options: ConversionOptions) -> ConversionOptions:
    model_dir, _ = validate_local_model_dir(options.model_dir)
    output_dir = options.output_dir.expanduser().resolve(strict=False)
    if output_dir.exists():
        raise ConversionError(
            "--output-dir must not already exist, preserving atomic output: "
            f"{output_dir}"
        )
    if output_dir == model_dir or model_dir in output_dir.parents:
        raise ConversionError("--output-dir must not be inside the source model")
    if options.quantization not in QUANTIZATION_MODES:
        raise ConversionError(
            f"unsupported quantization mode: {options.quantization}"
        )
    if options.max_rss_bytes <= 0:
        raise ConversionError("--max-rss-bytes must be positive")
    if options.max_rss_bytes > DEFAULT_MAX_RSS_BYTES:
        raise ConversionError("--max-rss-bytes cannot exceed the 4 GiB hard ceiling")
    if options.max_model_bytes <= 0:
        raise ConversionError("--max-model-bytes must be positive")
    if options.max_model_bytes > DEFAULT_MAX_MODEL_BYTES:
        raise ConversionError(
            "--max-model-bytes cannot exceed the 45 MiB deployment ceiling"
        )
    return ConversionOptions(
        model_dir=model_dir,
        output_dir=output_dir,
        quantization=options.quantization,
        include_token_type_ids=options.include_token_type_ids,
        max_rss_bytes=options.max_rss_bytes,
        max_model_bytes=options.max_model_bytes,
    )


def _require_dependency_version() -> str:
    try:
        version = importlib.metadata.version("litert-torch")
    except importlib.metadata.PackageNotFoundError as error:
        raise ConversionError(
            "litert-torch is not installed in this Python environment"
        ) from error
    if version != SUPPORTED_LITERT_TORCH_VERSION:
        raise ConversionError(
            "this converter is pinned to litert-torch "
            f"{SUPPORTED_LITERT_TORCH_VERSION}, found {version}"
        )
    return version


def _build_wrapper(torch: Any, model: Any, include_token_type_ids: bool) -> Any:
    """Wrap Hugging Face output and preserve int32 LiteRT inputs."""

    if include_token_type_ids:

        class ElectraLogitsWithTokenTypes(torch.nn.Module):
            def __init__(self, wrapped: Any) -> None:
                super().__init__()
                self.model = wrapped

            def forward(
                self,
                input_ids: Any,
                attention_mask: Any,
                token_type_ids: Any,
            ) -> Any:
                output = self.model(
                    input_ids=input_ids.to(dtype=torch.int64),
                    attention_mask=attention_mask.to(dtype=torch.int64),
                    token_type_ids=token_type_ids.to(dtype=torch.int64),
                    return_dict=False,
                )
                return output[0].to(dtype=torch.float32)

        return ElectraLogitsWithTokenTypes(model).eval()

    class ElectraLogits(torch.nn.Module):
        def __init__(self, wrapped: Any) -> None:
            super().__init__()
            self.model = wrapped

        def forward(self, input_ids: Any, attention_mask: Any) -> Any:
            output = self.model(
                input_ids=input_ids.to(dtype=torch.int64),
                attention_mask=attention_mask.to(dtype=torch.int64),
                return_dict=False,
            )
            return output[0].to(dtype=torch.float32)

    return ElectraLogits(model).eval()


def _sample_kwargs(
    torch: Any,
    include_token_type_ids: bool,
) -> dict[str, Any]:
    shape = (1, MAX_SEQUENCE_LENGTH)
    values = {
        "input_ids": torch.zeros(
            shape,
            dtype=torch.int32,
            device="cpu",
        ),
        "attention_mask": torch.ones(
            shape,
            dtype=torch.int32,
            device="cpu",
        ),
    }
    if include_token_type_ids:
        values["token_type_ids"] = torch.zeros(
            shape,
            dtype=torch.int32,
            device="cpu",
        )
    return values


def _validate_torch_contract(
    torch: Any,
    wrapper: Any,
    sample_kwargs: Mapping[str, Any],
) -> None:
    with torch.inference_mode():
        logits = wrapper(**sample_kwargs)
    if tuple(logits.shape) != (1, len(LABELS)):
        raise ConversionError(
            f"wrapped torch logits must have shape [1, 7], found {tuple(logits.shape)}"
        )
    if logits.dtype != torch.float32:
        raise ConversionError(
            f"wrapped torch logits must be float32, found {logits.dtype}"
        )
    if not bool(torch.isfinite(logits).all()):
        raise ConversionError("wrapped torch logits must be finite")


def _dynamic_int8_module(
    torch: Any,
    litert_torch: Any,
    wrapper: Any,
    sample_kwargs: Mapping[str, Any],
) -> tuple[Any, Any]:
    """Create calibration-free PT2E dynamic int8 linear weights."""

    try:
        from litert_torch.quantize import pt2e_quantizer
        from litert_torch.quantize.quant_config import QuantConfig
        from torchao.quantization.pt2e import quantize_pt2e
    except ImportError as error:
        raise BackendConversionError(
            "installed LiteRT Torch lacks PT2E dynamic quantization dependencies"
        ) from error

    exported = torch.export.export(
        wrapper,
        (),
        dict(sample_kwargs),
        strict=False,
    ).module()
    quantizer = pt2e_quantizer.PT2EQuantizer().set_global(
        pt2e_quantizer.get_symmetric_quantization_config(
            is_per_channel=True,
            is_dynamic=True,
        )
    )
    prepared = quantize_pt2e.prepare_pt2e(exported, quantizer)
    _initialize_dynamic_observers(torch, prepared, sample_kwargs)
    quantized = quantize_pt2e.convert_pt2e(
        prepared,
        # LiteRT Torch 0.9.1 cannot lower folded PT2E quantized constants.
        fold_quantize=False,
    )
    _clear_stale_training_metadata(quantized)
    return quantized, QuantConfig(pt2e_quantizer=quantizer)


def _initialize_dynamic_observers(
    torch: Any,
    prepared: Any,
    sample_kwargs: Mapping[str, Any],
) -> None:
    """Initialize per-channel weight observers without calibration data."""

    # Dynamic activations use PlaceholderObserver, but the per-channel weight
    # observers still need one graph execution before convert_pt2e.
    with torch.inference_mode():
        prepared(**dict(sample_kwargs))


def _clear_stale_training_metadata(quantized: Any) -> None:
    """Record that the graph was captured from the eager eval wrapper."""

    # PT2E disables eval(), but leaves this metadata flag stale. LiteRT reads
    # the flag when deciding whether to emit its training-mode warning.
    quantized.training = False


def _export_once(
    torch: Any,
    litert_torch: Any,
    wrapper: Any,
    sample_kwargs: Mapping[str, Any],
    target: Path,
    mode: str,
) -> None:
    try:
        if mode == "dynamic-int8":
            convertible, quant_config = _dynamic_int8_module(
                torch,
                litert_torch,
                wrapper,
                sample_kwargs,
            )
        elif mode == "float32":
            convertible = wrapper
            quant_config = None
        else:
            raise AssertionError(f"unexpected concrete conversion mode: {mode}")
        converted = litert_torch.convert(
            convertible,
            sample_kwargs=dict(sample_kwargs),
            strict_export="auto",
            quant_config=quant_config,
            dynamic_shapes=None,
            lightweight_conversion=True,
            enable_x64=False,
        )
        converted.export(str(target))
        strip_nonessential_tflite_strings(target)
    except (
        ResourceLimitExceeded,
        ArtifactValidationError,
        KeyboardInterrupt,
    ):
        raise
    except Exception as error:
        target.unlink(missing_ok=True)
        detail = str(error).strip()
        suffix = f": {detail}" if detail else ""
        raise BackendConversionError(
            f"{mode} LiteRT conversion failed: {type(error).__name__}{suffix}"
        ) from error


def strip_nonessential_tflite_strings(
    path: Path,
    *,
    flatbuffer_utils_module: Any | None = None,
) -> int:
    """Strip internal tensor names while preserving public tensor names."""

    if flatbuffer_utils_module is None:
        try:
            import flatbuffers
            from ai_edge_litert import schema_py_generated as schema
        except ImportError as error:
            raise ArtifactValidationError(
                "LiteRT FlatBuffer utilities are required to trim the model"
            ) from error

        class LiteRtFlatbufferUtils:
            @staticmethod
            def read_model(model_path: str) -> Any:
                raw = bytearray(Path(model_path).read_bytes())
                root = schema.Model.GetRootAsModel(raw, 0)
                return schema.ModelT.InitFromObj(root)

            @staticmethod
            def convert_object_to_bytearray(model: Any) -> bytes:
                builder = flatbuffers.Builder(1024)
                offset = model.Pack(builder)
                builder.Finish(offset, file_identifier=b"TFL3")
                return bytes(builder.Output())

        flatbuffer_utils_module = LiteRtFlatbufferUtils

    try:
        model = flatbuffer_utils_module.read_model(str(path))
        model.description = None
        for subgraph in model.subgraphs:
            subgraph.name = None
            interface_indices = {
                int(index)
                for index in (*subgraph.inputs, *subgraph.outputs)
            }
            for index, tensor in enumerate(subgraph.tensors):
                if index not in interface_indices:
                    tensor.name = None
        serialized = bytes(
            flatbuffer_utils_module.convert_object_to_bytearray(model)
        )
    except Exception as error:
        raise ArtifactValidationError(
            f"could not trim TFLite strings: {type(error).__name__}"
        ) from error
    if not serialized:
        raise ArtifactValidationError("trimmed TFLite artifact must not be empty")

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".trimmed.tmp",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(serialized)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return len(serialized)


def validate_artifact_size(path: Path, max_size_bytes: int) -> int:
    if path.is_symlink() or not path.is_file():
        raise ArtifactValidationError(f"missing regular TFLite artifact: {path}")
    size = path.stat().st_size
    if size <= 0:
        raise ArtifactValidationError("TFLite artifact must not be empty")
    if size > max_size_bytes:
        raise ArtifactValidationError(
            f"TFLite artifact is {size} bytes; hard maximum is {max_size_bytes}"
        )
    return size


def _tensor_name(name: str) -> str:
    without_index = name.split(":", 1)[0]
    for prefix in ("serving_default_", "main_"):
        if without_index.startswith(prefix):
            return without_index[len(prefix) :]
    return without_index


def validate_tflite_contract(
    path: Path,
    *,
    include_token_type_ids: bool,
    interpreter_factory: Callable[..., Any] | None = None,
    numpy_module: Any | None = None,
) -> dict[str, Any]:
    """Allocate and invoke the flatbuffer, enforcing exact tensor contract."""

    if numpy_module is None:
        try:
            import numpy as numpy_module
        except ImportError as error:
            raise ArtifactValidationError(
                "numpy is required to validate the TFLite artifact"
            ) from error
    if interpreter_factory is None:
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError as error:
            raise ArtifactValidationError(
                "ai_edge_litert interpreter is required for artifact validation"
            ) from error
        interpreter_factory = Interpreter

    try:
        interpreter = interpreter_factory(
            model_path=str(path),
            num_threads=MAX_THREADS,
        )
        interpreter.allocate_tensors()
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
    except Exception as error:
        raise ArtifactValidationError(
            f"LiteRT interpreter could not load artifact: {type(error).__name__}"
        ) from error

    expected_names = ["input_ids", "attention_mask"]
    if include_token_type_ids:
        expected_names.append("token_type_ids")
    actual_by_name = {
        _tensor_name(str(detail["name"])): detail
        for detail in input_details
    }
    if set(actual_by_name) != set(expected_names):
        raise ArtifactValidationError(
            f"LiteRT inputs must be exactly {expected_names}, "
            f"found {sorted(actual_by_name)}"
        )
    for name in expected_names:
        detail = actual_by_name[name]
        if tuple(int(value) for value in detail["shape"]) != (
            1,
            MAX_SEQUENCE_LENGTH,
        ):
            raise ArtifactValidationError(
                f"{name} must have fixed shape [1, 128]"
            )
        if numpy_module.dtype(detail["dtype"]) != numpy_module.dtype(
            numpy_module.int32
        ):
            raise ArtifactValidationError(f"{name} must be int32")
    if len(output_details) != 1:
        raise ArtifactValidationError("LiteRT model must have exactly one output")
    output = output_details[0]
    if tuple(int(value) for value in output["shape"]) != (1, len(LABELS)):
        raise ArtifactValidationError("LiteRT logits must have shape [1, 7]")
    if numpy_module.dtype(output["dtype"]) != numpy_module.dtype(
        numpy_module.float32
    ):
        raise ArtifactValidationError("LiteRT logits must be float32")

    try:
        for name in expected_names:
            detail = actual_by_name[name]
            values = (
                numpy_module.ones((1, MAX_SEQUENCE_LENGTH), dtype=numpy_module.int32)
                if name == "attention_mask"
                else numpy_module.zeros(
                    (1, MAX_SEQUENCE_LENGTH),
                    dtype=numpy_module.int32,
                )
            )
            interpreter.set_tensor(detail["index"], values)
        interpreter.invoke()
        logits = numpy_module.asarray(interpreter.get_tensor(output["index"]))
    except Exception as error:
        raise ArtifactValidationError(
            f"LiteRT smoke inference failed: {type(error).__name__}"
        ) from error
    if logits.shape != (1, len(LABELS)):
        raise ArtifactValidationError("LiteRT smoke logits shape changed")
    if logits.dtype != numpy_module.dtype(numpy_module.float32):
        raise ArtifactValidationError("LiteRT smoke logits dtype changed")
    if not bool(numpy_module.isfinite(logits).all()):
        raise ArtifactValidationError("LiteRT smoke logits must be finite")
    return {
        "inputs": [
            {
                "name": name,
                "dtype": "int32",
                "shape": [1, MAX_SEQUENCE_LENGTH],
            }
            for name in expected_names
        ],
        "output": {
            "name": _tensor_name(str(output["name"])),
            "dtype": "float32",
            "shape": [1, len(LABELS)],
        },
    }


def audit_dynamic_int8_artifact(
    path: Path,
    *,
    interpreter_factory: Callable[..., Any] | None = None,
    numpy_module: Any | None = None,
) -> dict[str, Any]:
    """Prove that the exported artifact contains INT8 tensors and QUANTIZE ops."""

    if numpy_module is None:
        try:
            import numpy as numpy_module
        except ImportError as error:
            raise ArtifactValidationError(
                "numpy is required to audit TFLite quantization"
            ) from error
    if interpreter_factory is None:
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError as error:
            raise ArtifactValidationError(
                "ai_edge_litert interpreter is required to audit quantization"
            ) from error
        interpreter_factory = Interpreter

    try:
        interpreter = interpreter_factory(
            model_path=str(path),
            num_threads=MAX_THREADS,
        )
        interpreter.allocate_tensors()
        tensor_details = interpreter.get_tensor_details()
        operations_reader = getattr(interpreter, "_get_ops_details", None)
        if not callable(operations_reader):
            raise ArtifactValidationError(
                "LiteRT interpreter cannot expose operator details"
            )
        operation_details = operations_reader()
    except ArtifactValidationError:
        raise
    except Exception as error:
        raise ArtifactValidationError(
            "LiteRT quantization audit could not inspect artifact: "
            f"{type(error).__name__}"
        ) from error

    int8_tensor_count = sum(
        numpy_module.dtype(detail.get("dtype"))
        == numpy_module.dtype(numpy_module.int8)
        for detail in tensor_details
    )
    quantize_operator_count = sum(
        detail.get("op_name") == "QUANTIZE"
        for detail in operation_details
    )
    if int8_tensor_count <= 0:
        raise ArtifactValidationError(
            "dynamic-int8 artifact contains no INT8 tensors"
        )
    if quantize_operator_count <= 0:
        raise ArtifactValidationError(
            "dynamic-int8 artifact contains no QUANTIZE operators"
        )
    return {
        "schema_version": QUANTIZATION_AUDIT_SCHEMA_VERSION,
        "method": "litert-interpreter-tensor-and-operator-inspection",
        "tensor_count": len(tensor_details),
        "int8_tensor_count": int8_tensor_count,
        "operator_count": len(operation_details),
        "quantize_operator_count": quantize_operator_count,
        "passed": True,
    }


def _source_manifest_hash(model_dir: Path) -> str | None:
    for path in (
        model_dir / "training_manifest.json",
        model_dir.parent / "training_manifest.json",
    ):
        if path.is_file() and not path.is_symlink():
            return _sha256_file(path)
    return None


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def atomic_output_directory(
    output_dir: Path,
    writer: Callable[[Path], Any],
) -> Any:
    """Write a complete sibling directory and atomically rename it."""

    if output_dir.exists():
        raise ConversionError(f"output already exists: {output_dir}")
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(
            prefix=f".{output_dir.name}.",
            suffix=".tmp",
            dir=output_dir.parent,
        )
    )
    try:
        result = writer(temporary)
        os.replace(temporary, output_dir)
        return result
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def convert(options: ConversionOptions) -> dict[str, Any]:
    """Perform one local conversion and atomically commit model plus manifest."""

    options = validate_options(options)
    configure_offline_cpu_environment()
    enforce_memory_ceiling(options.max_rss_bytes, "before dependency import")
    dependency_version = _require_dependency_version()
    try:
        import litert_torch
        import torch
        import transformers
        from transformers import ElectraForSequenceClassification
    except ImportError as error:
        raise ConversionError(
            "conversion requires local torch, transformers, litert-torch, "
            "and ai-edge-litert installations"
        ) from error

    torch.set_num_threads(MAX_THREADS)
    torch.set_num_interop_threads(MAX_THREADS)
    enforce_memory_ceiling(options.max_rss_bytes, "before model load")
    model = ElectraForSequenceClassification.from_pretrained(
        str(options.model_dir),
        local_files_only=True,
        trust_remote_code=False,
    )
    model.eval()
    model.to(torch.device("cpu"))
    enforce_memory_ceiling(options.max_rss_bytes, "after model load")
    wrapper = _build_wrapper(
        torch,
        model,
        options.include_token_type_ids,
    )
    sample_kwargs = _sample_kwargs(
        torch,
        options.include_token_type_ids,
    )
    _validate_torch_contract(torch, wrapper, sample_kwargs)
    _, source_model_bundle_sha256 = model_bundle_hashes(options.model_dir)

    def writer(directory: Path) -> dict[str, Any]:
        model_path = directory / MODEL_FILENAME
        fallback_reason: str | None = None
        requested = options.quantization
        applied = requested
        if requested == "auto":
            try:
                _export_once(
                    torch,
                    litert_torch,
                    wrapper,
                    sample_kwargs,
                    model_path,
                    "dynamic-int8",
                )
                applied = "dynamic-int8"
            except BackendConversionError as error:
                fallback_reason = DYNAMIC_INT8_FALLBACK_REASON
                print(
                    f"convert_koelectra_litert: {error}; retrying float32",
                    file=sys.stderr,
                )
                _export_once(
                    torch,
                    litert_torch,
                    wrapper,
                    sample_kwargs,
                    model_path,
                    "float32",
                )
                applied = "float32"
        else:
            _export_once(
                torch,
                litert_torch,
                wrapper,
                sample_kwargs,
                model_path,
                requested,
            )
        enforce_memory_ceiling(options.max_rss_bytes, "after LiteRT export")
        size_bytes = validate_artifact_size(
            model_path,
            options.max_model_bytes,
        )
        tensor_contract = validate_tflite_contract(
            model_path,
            include_token_type_ids=options.include_token_type_ids,
        )
        quantization_audit = (
            audit_dynamic_int8_artifact(model_path)
            if applied == "dynamic-int8"
            else None
        )
        peak_rss = enforce_memory_ceiling(
            options.max_rss_bytes,
            "after LiteRT validation",
        )
        manifest = {
            "schema_version": MANIFEST_SCHEMA_VERSION,
            "labels": list(LABELS),
            "source": {
                "config_sha256": _sha256_file(
                    options.model_dir / "config.json"
                ),
                "model_bundle_sha256": source_model_bundle_sha256,
                "vocab_sha256": _sha256_file(
                    options.model_dir / VOCAB_FILENAME
                ),
                "training_manifest_sha256": _source_manifest_hash(
                    options.model_dir
                ),
            },
            "converter": {
                "litert_torch_version": dependency_version,
                "torch_version": torch.__version__,
                "transformers_version": transformers.__version__,
                "local_files_only": True,
            },
            "quantization": {
                "requested": requested,
                "applied": applied,
                "calibration_used": False,
                "experimental_backend": applied == "dynamic-int8",
                "fallback_reason": fallback_reason,
            },
            "quantization_audit": quantization_audit,
            "resources": {
                "device": "cpu",
                "threads": MAX_THREADS,
                "max_rss_bytes": options.max_rss_bytes,
                "observed_peak_rss_bytes": peak_rss,
            },
            "tensor_contract": tensor_contract,
            "artifact": {
                "file": MODEL_FILENAME,
                "size_bytes": size_bytes,
                "max_size_bytes": options.max_model_bytes,
                "sha256": _sha256_file(model_path),
            },
        }
        _write_json(directory / MANIFEST_FILENAME, manifest)
        return manifest

    return atomic_output_directory(options.output_dir, writer)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--quantization",
        choices=QUANTIZATION_MODES,
        default="auto",
    )
    parser.add_argument("--include-token-type-ids", action="store_true")
    parser.add_argument(
        "--max-rss-bytes",
        type=int,
        default=DEFAULT_MAX_RSS_BYTES,
    )
    parser.add_argument(
        "--max-model-bytes",
        type=int,
        default=DEFAULT_MAX_MODEL_BYTES,
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    options = ConversionOptions(
        model_dir=args.model_dir,
        output_dir=args.output_dir,
        quantization=args.quantization,
        include_token_type_ids=args.include_token_type_ids,
        max_rss_bytes=args.max_rss_bytes,
        max_model_bytes=args.max_model_bytes,
    )
    try:
        manifest = convert(options)
    except (ConversionError, ValueError) as error:
        print(f"convert_koelectra_litert: {error}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "output_dir": str(options.output_dir),
                "artifact": manifest["artifact"],
                "quantization": manifest["quantization"],
                "tensor_contract": manifest["tensor_contract"],
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
