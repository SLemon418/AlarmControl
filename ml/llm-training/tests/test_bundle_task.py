from __future__ import annotations

import hashlib
import re
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path
from unittest.mock import Mock

LLM_TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LLM_TRAINING_DIR))

from bundle_task import create_task_bundle, validate_bundle_paths


def pinned_requirements(path: Path) -> dict[str, str]:
    requirements: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([^\s]+)", line)
        if match is None:
            raise AssertionError(f"Requirement is not exactly pinned: {line}")
        name, version = match.groups()
        if name in requirements:
            raise AssertionError(f"Duplicate requirement: {name}")
        requirements[name] = version
    return requirements


class BundleTaskTest(unittest.TestCase):
    def test_bundle_paths_fail_closed_on_collision_and_path_misuse(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model_dir = root / "model"
            model_dir.mkdir()
            tokenizer_model = model_dir / "tokenizer.model"
            tokenizer_model.write_bytes(b"tokenizer")
            tflite_model = root / "model.tflite"
            tflite_model.write_bytes(b"tflite")

            cases = (
                (model_dir / "candidate.task", "inside model-dir"),
                (root / "candidate.bin", ".task suffix"),
            )
            for output, message in cases:
                with self.subTest(output=output), self.assertRaisesRegex(
                    SystemExit,
                    message,
                ):
                    validate_bundle_paths(
                        tflite_model,
                        model_dir,
                        tokenizer_model,
                        output,
                    )

            output = root / "candidate.task"
            output.write_bytes(b"existing")
            with self.assertRaisesRegex(SystemExit, "already exists"):
                validate_bundle_paths(
                    tflite_model,
                    model_dir,
                    tokenizer_model,
                    output,
                )

    def test_bundle_uses_mediapipe_role_specific_prompt_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tflite_model = root / "model.tflite"
            tokenizer_model = root / "tokenizer.model"
            output = root / "release" / "model.task"
            tflite_model.write_bytes(b"tflite")
            tokenizer_model.write_bytes(b"tokenizer")
            bundled_bytes = b"task-bundle"
            config = object()
            bundler = Mock()
            bundler.BundleConfig.return_value = config
            bundler.create_bundle.side_effect = (
                lambda _: output.write_bytes(bundled_bytes)
            )

            fingerprint = create_task_bundle(
                tflite_model,
                tokenizer_model,
                output,
                bundler,
            )

            bundler.BundleConfig.assert_called_once_with(
                tflite_model=str(tflite_model),
                tokenizer_model=str(tokenizer_model),
                start_token="<bos>",
                stop_tokens=["<eos>", "<end_of_turn>"],
                output_filename=str(output),
                prompt_prefix_user="<start_of_turn>user\n",
                prompt_suffix_user="<end_of_turn>\n",
                prompt_prefix_model="<start_of_turn>model\n",
                prompt_suffix_model="<end_of_turn>\n",
            )
            bundler.create_bundle.assert_called_once_with(config)
            expected = hashlib.sha256(bundled_bytes).hexdigest()
            self.assertEqual(expected, fingerprint)
            self.assertEqual(
                f"{expected}  {output.name}\n",
                output.with_suffix(".task.sha256").read_text(encoding="utf-8"),
            )

    def test_bundle_rejects_a_missing_or_empty_output(self) -> None:
        for payload in (None, b""):
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "model.task"
                bundler = Mock()
                if payload is not None:
                    bundler.create_bundle.side_effect = (
                        lambda _: output.write_bytes(payload)
                    )

                with self.assertRaisesRegex(SystemExit, "non-empty task"):
                    create_task_bundle(
                        root / "model.tflite",
                        root / "tokenizer.model",
                        output,
                        bundler,
                    )

                self.assertFalse(output.with_suffix(".task.sha256").exists())

    def test_bundle_requirements_pin_eager_mediapipe_imports(self) -> None:
        bundle_requirements = pinned_requirements(
            LLM_TRAINING_DIR / "requirements-bundle.txt"
        )
        self.assertEqual(
            {
                "jax": "0.11.0",
                "jaxlib": "0.11.0",
                "torch": "2.13.0",
                "sentencepiece": "0.2.2",
                "mediapipe": "0.10.35",
            },
            bundle_requirements,
        )
        training_requirements = pinned_requirements(
            LLM_TRAINING_DIR / "requirements-train.txt"
        )
        self.assertEqual(training_requirements["torch"], bundle_requirements["torch"])
        self.assertEqual(
            training_requirements["sentencepiece"],
            bundle_requirements["sentencepiece"],
        )
        with (LLM_TRAINING_DIR.parents[1] / "gradle" / "libs.versions.toml").open(
            "rb"
        ) as handle:
            version_catalog = tomllib.load(handle)
        self.assertEqual(
            version_catalog["versions"]["mediapipeTasksGenai"],
            bundle_requirements["mediapipe"],
        )

    def test_conversion_and_bundle_dependencies_remain_isolated(self) -> None:
        convert_requirements = pinned_requirements(
            LLM_TRAINING_DIR / "requirements-convert.txt"
        )
        bundle_requirements = pinned_requirements(
            LLM_TRAINING_DIR / "requirements-bundle.txt"
        )

        self.assertEqual("2.21.0", convert_requirements["tensorflow"])
        self.assertEqual("0.9.1", convert_requirements["litert-torch"])
        self.assertNotIn("mediapipe", convert_requirements)
        self.assertNotIn("litert-torch", bundle_requirements)


if __name__ == "__main__":
    unittest.main()
