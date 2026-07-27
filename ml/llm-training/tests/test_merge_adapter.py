from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

LLM_TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LLM_TRAINING_DIR))

from merge_adapter import merge_adapter, parse_args, validate_inputs


def create_valid_inputs(root: Path) -> tuple[Path, Path]:
    base_model = root / "base"
    adapter_checkpoint = root / "checkpoint"
    base_model.mkdir()
    adapter_checkpoint.mkdir()
    for name in ("config.json", "tokenizer.model", "model.safetensors"):
        (base_model / name).write_text(name, encoding="utf-8")
    for name in ("adapter_config.json", "adapter_model.safetensors"):
        (adapter_checkpoint / name).write_text(name, encoding="utf-8")
    return base_model, adapter_checkpoint


class MergeAdapterTest(unittest.TestCase):
    def test_parse_args_requires_all_local_paths(self) -> None:
        with patch.object(
            sys,
            "argv",
            [
                "merge_adapter.py",
                "--base-model",
                "/local/base",
                "--adapter-checkpoint",
                "/local/checkpoint",
                "--output-dir",
                "/local/merged",
            ],
        ):
            args = parse_args()

        self.assertEqual(Path("/local/base"), args.base_model)
        self.assertEqual(Path("/local/checkpoint"), args.adapter_checkpoint)
        self.assertEqual(Path("/local/merged"), args.output_dir)

    def test_validation_requires_base_model_artifacts(self) -> None:
        cases = (
            ("config.json", "missing config.json"),
            ("tokenizer.model", "missing tokenizer.model"),
            ("model.safetensors", "no safetensors weights"),
        )
        for missing, message in cases:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                base_model, adapter_checkpoint = create_valid_inputs(Path(temporary))
                (base_model / missing).unlink()

                with self.assertRaisesRegex(SystemExit, message):
                    validate_inputs(base_model, adapter_checkpoint)

    def test_validation_requires_adapter_checkpoint_artifacts(self) -> None:
        cases = (
            ("adapter_config.json", "missing adapter_config.json"),
            ("adapter_model.safetensors", "missing adapter_model.safetensors"),
        )
        for missing, message in cases:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                base_model, adapter_checkpoint = create_valid_inputs(Path(temporary))
                (adapter_checkpoint / missing).unlink()

                with self.assertRaisesRegex(SystemExit, message):
                    validate_inputs(base_model, adapter_checkpoint)

    def test_merge_loads_locally_and_saves_model_and_tokenizer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base_model, adapter_checkpoint = create_valid_inputs(root)
            output_dir = root / "merged"
            tokenizer = Mock()
            base = Mock()
            adapter = Mock()
            merged = Mock()
            auto_tokenizer_class = Mock()
            auto_model_class = Mock()
            peft_model_class = Mock()
            auto_tokenizer_class.from_pretrained.return_value = tokenizer
            auto_model_class.from_pretrained.return_value = base
            peft_model_class.from_pretrained.return_value = adapter
            adapter.merge_and_unload.return_value = merged

            merge_adapter(
                base_model,
                adapter_checkpoint,
                output_dir,
                auto_model_class=auto_model_class,
                auto_tokenizer_class=auto_tokenizer_class,
                peft_model_class=peft_model_class,
            )

            auto_tokenizer_class.from_pretrained.assert_called_once_with(
                str(base_model),
                local_files_only=True,
            )
            auto_model_class.from_pretrained.assert_called_once_with(
                str(base_model),
                local_files_only=True,
                dtype="auto",
                low_cpu_mem_usage=True,
            )
            peft_model_class.from_pretrained.assert_called_once_with(
                base,
                str(adapter_checkpoint),
                local_files_only=True,
            )
            adapter.merge_and_unload.assert_called_once_with(safe_merge=True)
            merged.save_pretrained.assert_called_once_with(
                str(output_dir),
                safe_serialization=True,
                max_shard_size="2GB",
            )
            tokenizer.save_pretrained.assert_called_once_with(str(output_dir))
            self.assertEqual(
                "tokenizer.model",
                (output_dir / "tokenizer.model").read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
