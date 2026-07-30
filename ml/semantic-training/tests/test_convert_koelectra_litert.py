from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType
from unittest import mock

import numpy

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import convert_koelectra_litert as converter
from atomic_generation import publish_generation
from semantic_contract import (
    LABELS,
    MAX_SEQUENCE_LENGTH,
    TRAINING_GENERATION_REQUIRED_FILES,
    training_generation_names,
)


def write_model_config(path: Path) -> Path:
    path.mkdir()
    config = {
        "model_type": "electra",
        "architectures": ["ElectraForSequenceClassification"],
        "id2label": {
            str(index): label for index, label in enumerate(LABELS)
        },
        "label2id": {
            label: index for index, label in enumerate(LABELS)
        },
    }
    (path / "config.json").write_text(
        json.dumps(config),
        encoding="utf-8",
    )
    (path / "vocab.txt").write_text(
        "[PAD]\n[UNK]\n[CLS]\n[SEP]\nword\n",
        encoding="utf-8",
    )
    return path


class FakeInterpreter:
    def __init__(self, model_path: str, num_threads: int) -> None:
        self.model_path = model_path
        self.num_threads = num_threads
        self.values = {}
        self.inputs = [
            {
                "name": "serving_default_input_ids:0",
                "shape": numpy.array([1, MAX_SEQUENCE_LENGTH]),
                "dtype": numpy.int32,
                "index": 0,
            },
            {
                "name": "serving_default_attention_mask:0",
                "shape": numpy.array([1, MAX_SEQUENCE_LENGTH]),
                "dtype": numpy.int32,
                "index": 1,
            },
        ]
        self.outputs = [
            {
                "name": "PartitionedCall:0",
                "shape": numpy.array([1, len(LABELS)]),
                "dtype": numpy.float32,
                "index": 2,
            }
        ]
        self.tensors = [
            *self.inputs,
            {
                "name": "quantized_weight",
                "shape": numpy.array([7, 7]),
                "dtype": numpy.int8,
                "index": 3,
            },
            *self.outputs,
        ]
        self.operations = [
            {"op_name": "QUANTIZE"},
            {"op_name": "FULLY_CONNECTED"},
        ]

    def allocate_tensors(self) -> None:
        return None

    def get_input_details(self):
        return self.inputs

    def get_output_details(self):
        return self.outputs

    def get_tensor_details(self):
        return self.tensors

    def _get_ops_details(self):
        return self.operations

    def set_tensor(self, index, value) -> None:
        self.values[index] = value

    def invoke(self) -> None:
        if set(self.values) != {0, 1}:
            raise AssertionError("missing inputs")

    def get_tensor(self, index):
        if index != 2:
            raise AssertionError("wrong output")
        return numpy.zeros((1, len(LABELS)), dtype=numpy.float32)


class KoElectraLiteRtConversionTest(unittest.TestCase):
    def test_manifest_schema_requires_vocab_provenance(self) -> None:
        self.assertEqual(
            "koelectra-litert-conversion-v2",
            converter.MANIFEST_SCHEMA_VERSION,
        )
        with tempfile.TemporaryDirectory() as directory:
            model_dir = write_model_config(Path(directory) / "model")
            expected = converter._sha256_file(model_dir / "vocab.txt")

            _, _ = converter.validate_local_model_dir(model_dir)

            self.assertEqual(64, len(expected))

    def test_offline_cpu_environment_is_exact(self) -> None:
        converter.configure_offline_cpu_environment()

        for name in (
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
        ):
            self.assertEqual("2", __import__("os").environ[name])
        self.assertEqual("", __import__("os").environ["CUDA_VISIBLE_DEVICES"])
        self.assertEqual("1", __import__("os").environ["HF_HUB_OFFLINE"])
        self.assertEqual("1", __import__("os").environ["TRANSFORMERS_OFFLINE"])
        self.assertEqual(
            "0",
            __import__("os").environ["PYTORCH_ENABLE_MPS_FALLBACK"],
        )

    def test_validates_local_electra_and_exact_label_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_dir = write_model_config(Path(directory) / "model")

            resolved, config = converter.validate_local_model_dir(model_dir)
            self.assertEqual(model_dir.resolve(), resolved)
            self.assertEqual(
                list(LABELS),
                [config["id2label"][str(index)] for index in range(7)],
            )

            config["id2label"]["0"] = "OTHER"
            (model_dir / "config.json").write_text(
                json.dumps(config),
                encoding="utf-8",
            )
            with self.assertRaises(converter.ConversionError):
                converter.validate_local_model_dir(model_dir)

            config["id2label"]["0"] = LABELS[0]
            (model_dir / "config.json").write_text(
                json.dumps(config),
                encoding="utf-8",
            )
            (model_dir / "vocab.txt").unlink()
            with self.assertRaises(converter.ConversionError):
                converter.validate_local_model_dir(model_dir)

    def test_logical_selector_resolves_one_committed_training_generation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_model_config(root / "source")
            target = root / "best"
            pointer_name, generations_name = training_generation_names(target)

            def writer(generation: Path) -> None:
                for path in source.iterdir():
                    (generation / path.name).write_bytes(path.read_bytes())
                (generation / "checkpoint.json").write_text("{}")
                (generation / "optimizer.pt").write_bytes(b"optimizer")
                (generation / "model.safetensors").write_bytes(b"weights")

            generation = publish_generation(
                root,
                pointer_name=pointer_name,
                generations_name=generations_name,
                required_files=TRAINING_GENERATION_REQUIRED_FILES,
                writer=writer,
            )

            resolved, _ = converter.validate_local_model_dir(target)

            self.assertEqual(generation.resolve(), resolved)

    def test_enforces_small_injected_size_limit_without_large_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"tiny-model")

            self.assertEqual(
                10,
                converter.validate_artifact_size(artifact, 10),
            )
            with self.assertRaises(converter.ArtifactValidationError):
                converter.validate_artifact_size(artifact, 9)

    def test_strips_only_non_interface_tflite_strings(self) -> None:
        class Tensor:
            def __init__(self, name: bytes) -> None:
                self.name = name

        class Subgraph:
            name = b"main"
            inputs = [0, 1]
            outputs = [3]
            tensors = [
                Tensor(b"input_ids"),
                Tensor(b"attention_mask"),
                Tensor(b"internal"),
                Tensor(b"logits"),
            ]

        class Model:
            description = b"converted"
            subgraphs = [Subgraph()]

        class FakeFlatbufferUtils:
            model = Model()

            @classmethod
            def read_model(cls, _path):
                return cls.model

            @staticmethod
            def convert_object_to_bytearray(_model):
                return b"trimmed-flatbuffer"

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"untrimmed")

            size = converter.strip_nonessential_tflite_strings(
                artifact,
                flatbuffer_utils_module=FakeFlatbufferUtils,
            )

            self.assertEqual(len(b"trimmed-flatbuffer"), size)
            self.assertEqual(b"trimmed-flatbuffer", artifact.read_bytes())
            self.assertIsNone(FakeFlatbufferUtils.model.description)
            self.assertIsNone(FakeFlatbufferUtils.model.subgraphs[0].name)
            self.assertEqual(
                [
                    b"input_ids",
                    b"attention_mask",
                    None,
                    b"logits",
                ],
                [
                    tensor.name
                    for tensor in FakeFlatbufferUtils.model.subgraphs[0].tensors
                ],
            )

    def test_validates_fixed_int32_inputs_and_float32_logits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"fixture")

            contract = converter.validate_tflite_contract(
                artifact,
                include_token_type_ids=False,
                interpreter_factory=FakeInterpreter,
                numpy_module=numpy,
            )

        self.assertEqual(
            ["input_ids", "attention_mask"],
            [value["name"] for value in contract["inputs"]],
        )
        self.assertEqual([1, 128], contract["inputs"][0]["shape"])
        self.assertEqual("int32", contract["inputs"][0]["dtype"])
        self.assertEqual([1, 7], contract["output"]["shape"])
        self.assertEqual("float32", contract["output"]["dtype"])

    def test_audits_dynamic_int8_tensors_and_quantize_operators(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"fixture")

            audit = converter.audit_dynamic_int8_artifact(
                artifact,
                interpreter_factory=FakeInterpreter,
                numpy_module=numpy,
            )

        self.assertEqual(
            converter.QUANTIZATION_AUDIT_SCHEMA_VERSION,
            audit["schema_version"],
        )
        self.assertEqual(1, audit["int8_tensor_count"])
        self.assertEqual(1, audit["quantize_operator_count"])
        self.assertTrue(audit["passed"])

    def test_quantization_audit_rejects_backend_regression(self) -> None:
        class FloatOnlyInterpreter(FakeInterpreter):
            def __init__(self, model_path: str, num_threads: int) -> None:
                super().__init__(model_path, num_threads)
                self.tensors = [
                    {**detail, "dtype": numpy.float32}
                    for detail in self.tensors
                ]
                self.operations = [{"op_name": "FULLY_CONNECTED"}]

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"fixture")

            with self.assertRaises(converter.ArtifactValidationError):
                converter.audit_dynamic_int8_artifact(
                    artifact,
                    interpreter_factory=FloatOnlyInterpreter,
                    numpy_module=numpy,
                )

    def test_sample_inputs_use_deployment_keyword_names(self) -> None:
        class FakeTorch:
            int32 = "int32"

            @staticmethod
            def zeros(shape, dtype, device):
                return ("zeros", shape, dtype, device)

            @staticmethod
            def ones(shape, dtype, device):
                return ("ones", shape, dtype, device)

        without_types = converter._sample_kwargs(FakeTorch, False)
        with_types = converter._sample_kwargs(FakeTorch, True)

        self.assertEqual(
            ["input_ids", "attention_mask"],
            list(without_types),
        )
        self.assertEqual(
            ["input_ids", "attention_mask", "token_type_ids"],
            list(with_types),
        )

    def test_dynamic_observers_are_initialized_once_before_conversion(self) -> None:
        state = {"inference_mode": False}
        calls = []

        class InferenceMode:
            def __enter__(self):
                state["inference_mode"] = True

            def __exit__(self, _type, _value, _traceback):
                state["inference_mode"] = False

        class FakeTorch:
            @staticmethod
            def inference_mode():
                return InferenceMode()

        def prepared(**kwargs) -> None:
            self.assertTrue(state["inference_mode"])
            calls.append(kwargs)

        converter._initialize_dynamic_observers(
            FakeTorch,
            prepared,
            {
                "input_ids": "ids",
                "attention_mask": "mask",
            },
        )

        self.assertEqual(
            [
                {
                    "input_ids": "ids",
                    "attention_mask": "mask",
                }
            ],
            calls,
        )
        self.assertFalse(state["inference_mode"])

    def test_quantized_graph_clears_stale_training_metadata(self) -> None:
        class QuantizedGraph:
            training = True

        graph = QuantizedGraph()

        converter._clear_stale_training_metadata(graph)

        self.assertFalse(graph.training)

    def test_dynamic_quantization_runs_observers_before_unfolded_conversion(
        self,
    ) -> None:
        events = []

        class Exported:
            def module(self):
                events.append("module")
                return "exported"

        class ExportApi:
            @staticmethod
            def export(_wrapper, args, kwargs, strict):
                self.assertEqual((), args)
                self.assertEqual({"input_ids": "ids"}, kwargs)
                self.assertFalse(strict)
                events.append("export")
                return Exported()

        class InferenceMode:
            def __enter__(self):
                events.append("inference-enter")

            def __exit__(self, _type, _value, _traceback):
                events.append("inference-exit")

        class FakeTorch:
            export = ExportApi

            @staticmethod
            def inference_mode():
                return InferenceMode()

        class Prepared:
            def __call__(_prepared, **kwargs):
                self.assertEqual({"input_ids": "ids"}, kwargs)
                events.append("observe")

        class Quantized:
            training = True

        class FakeQuantizer:
            def set_global(_quantizer, config):
                self.assertEqual("dynamic-per-channel", config)
                events.append("set-global")
                return _quantizer

        quantizer_module = ModuleType("pt2e_quantizer")
        quantizer_module.PT2EQuantizer = FakeQuantizer

        def get_config(*, is_per_channel, is_dynamic):
            self.assertTrue(is_per_channel)
            self.assertTrue(is_dynamic)
            events.append("get-config")
            return "dynamic-per-channel"

        quantizer_module.get_symmetric_quantization_config = get_config

        class QuantConfig:
            def __init__(_config, *, pt2e_quantizer):
                _config.pt2e_quantizer = pt2e_quantizer
                events.append("quant-config")

        quant_config_module = ModuleType("quant_config")
        quant_config_module.QuantConfig = QuantConfig
        litert_quantize_module = ModuleType("litert_torch.quantize")
        litert_quantize_module.pt2e_quantizer = quantizer_module

        def prepare_pt2e(exported, _quantizer):
            self.assertEqual("exported", exported)
            events.append("prepare")
            return Prepared()

        def convert_pt2e(prepared, *, fold_quantize):
            self.assertIsInstance(prepared, Prepared)
            self.assertFalse(fold_quantize)
            events.append("convert")
            return Quantized()

        quantize_pt2e = ModuleType("quantize_pt2e")
        quantize_pt2e.prepare_pt2e = prepare_pt2e
        quantize_pt2e.convert_pt2e = convert_pt2e
        torchao_pt2e_module = ModuleType("torchao.quantization.pt2e")
        torchao_pt2e_module.quantize_pt2e = quantize_pt2e

        with mock.patch.dict(
            sys.modules,
            {
                "litert_torch.quantize": litert_quantize_module,
                "litert_torch.quantize.quant_config": quant_config_module,
                "torchao.quantization.pt2e": torchao_pt2e_module,
            },
        ):
            quantized, quant_config = converter._dynamic_int8_module(
                FakeTorch,
                object(),
                object(),
                {"input_ids": "ids"},
            )

        self.assertEqual(
            [
                "export",
                "module",
                "get-config",
                "set-global",
                "prepare",
                "inference-enter",
                "observe",
                "inference-exit",
                "convert",
                "quant-config",
            ],
            events,
        )
        self.assertFalse(quantized.training)
        self.assertIsInstance(
            quant_config.pt2e_quantizer,
            FakeQuantizer,
        )

    def test_rejects_wrong_tensor_shape_or_dtype(self) -> None:
        class WrongInterpreter(FakeInterpreter):
            def __init__(self, model_path: str, num_threads: int) -> None:
                super().__init__(model_path, num_threads)
                self.inputs[0]["shape"] = numpy.array([1, 64])
                self.inputs[1]["dtype"] = numpy.int64

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.tflite"
            artifact.write_bytes(b"fixture")

            with self.assertRaises(converter.ArtifactValidationError):
                converter.validate_tflite_contract(
                    artifact,
                    include_token_type_ids=False,
                    interpreter_factory=WrongInterpreter,
                    numpy_module=numpy,
                )

    def test_atomic_output_commits_complete_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "final"

            with mock.patch.object(
                converter,
                "_fsync_directory",
                wraps=converter._fsync_directory,
            ) as sync_directory:
                result = converter.atomic_output_directory(
                    output,
                    lambda temporary: (
                        (temporary / "model.tflite").write_bytes(b"model"),
                        "written",
                    )[1],
                )

            self.assertEqual("written", result)
            self.assertEqual(b"model", (output / "model.tflite").read_bytes())
            self.assertEqual(2, sync_directory.call_count)
            self.assertEqual(output.parent, sync_directory.call_args_list[-1].args[0])
            self.assertFalse(
                any(path.name.endswith(".tmp") for path in output.parent.iterdir())
            )

    def test_atomic_output_removes_partial_directory_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "final"

            def failing_writer(temporary: Path) -> None:
                (temporary / "partial").write_text("partial", encoding="utf-8")
                raise RuntimeError("stop")

            with self.assertRaises(RuntimeError):
                converter.atomic_output_directory(output, failing_writer)

            self.assertFalse(output.exists())
            self.assertEqual([], list(output.parent.iterdir()))

    def test_options_reject_existing_output_and_ceiling_relaxation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = write_model_config(root / "model")
            output = root / "output"
            output.mkdir()
            options = converter.ConversionOptions(
                model_dir=model_dir,
                output_dir=output,
                quantization="auto",
                include_token_type_ids=False,
                max_rss_bytes=converter.DEFAULT_MAX_RSS_BYTES,
                max_model_bytes=converter.DEFAULT_MAX_MODEL_BYTES,
            )
            with self.assertRaises(converter.ConversionError):
                converter.validate_options(options)

            output.rmdir()
            relaxed = converter.ConversionOptions(
                model_dir=model_dir,
                output_dir=output,
                quantization="auto",
                include_token_type_ids=False,
                max_rss_bytes=converter.DEFAULT_MAX_RSS_BYTES + 1,
                max_model_bytes=converter.DEFAULT_MAX_MODEL_BYTES + 1,
            )
            with self.assertRaises(converter.ConversionError):
                converter.validate_options(relaxed)


if __name__ == "__main__":
    unittest.main()
