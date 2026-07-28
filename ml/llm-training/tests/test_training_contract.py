from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

LLM_TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LLM_TRAINING_DIR))

from train import (
    CausalLmCollator,
    LORA_TARGET_PROFILES,
    encode_rows,
    parse_args,
    select_device_and_dtype,
    tokenizer_artifact_paths,
    trainer_evaluation_enabled,
    validate_initial_adapter,
    validate_loaded_adapter_contract,
    validate_lora_target_profile,
)


class FakeChatTokenizer:
    def __init__(self) -> None:
        self.calls: list[tuple[list[dict[str, str]], bool]] = []

    def apply_chat_template(
        self,
        messages: list[dict[str, str]],
        *,
        tokenize: bool,
        add_generation_prompt: bool,
    ) -> list[int]:
        self.calls.append((messages, add_generation_prompt))
        if add_generation_prompt:
            return [2, 10, 11]
        return [2, 10, 11, 20, 106, 107]


class FakeIntentTokenizer(FakeChatTokenizer):
    def apply_chat_template(
        self,
        messages: list[dict[str, str]],
        *,
        tokenize: bool,
        add_generation_prompt: bool,
    ) -> list[int]:
        self.calls.append((messages, add_generation_prompt))
        if add_generation_prompt:
            return [2, 10, 11]
        return [2, 10, 11, 20, 30, 31, 40, 50]

    @staticmethod
    def encode(value: str, *, add_special_tokens: bool) -> list[int]:
        if value != "OTHER" or add_special_tokens:
            raise AssertionError("Unexpected intent tokenization")
        return [30, 31]


class TrainingContractTest(unittest.TestCase):
    def test_lora_target_profiles_are_explicit_and_default_safe(self) -> None:
        self.assertEqual(
            ("q_proj", "k_proj", "v_proj", "o_proj"),
            LORA_TARGET_PROFILES["attention"],
        )
        self.assertEqual(
            (
                "q_proj",
                "k_proj",
                "v_proj",
                "o_proj",
                "gate_proj",
                "up_proj",
                "down_proj",
            ),
            LORA_TARGET_PROFILES["attention-mlp"],
        )
        self.assertEqual(
            LORA_TARGET_PROFILES["attention"],
            validate_lora_target_profile("lora", "attention"),
        )
        with self.assertRaisesRegex(
            SystemExit,
            "--lora-target-profile is supported only",
        ):
            validate_lora_target_profile("full", "attention-mlp")

    def test_lora_target_profile_cli_defaults_to_attention(self) -> None:
        required = [
            "train.py",
            "--base-model",
            "local-model",
            "--base-revision",
            "revision",
        ]
        with patch.object(sys, "argv", required):
            self.assertEqual("attention", parse_args().lora_target_profile)
        with patch.object(
            sys,
            "argv",
            required + ["--lora-target-profile", "attention-mlp"],
        ):
            self.assertEqual(
                "attention-mlp",
                parse_args().lora_target_profile,
            )

    def test_loaded_adapter_must_match_rank_and_target_profile(self) -> None:
        attention = SimpleNamespace(
            r=16,
            target_modules={"q_proj", "k_proj", "v_proj", "o_proj"},
        )
        validate_loaded_adapter_contract(
            {"default": attention},
            expected_rank=16,
            expected_targets=LORA_TARGET_PROFILES["attention"],
        )

        with self.assertRaisesRegex(
            SystemExit,
            "target modules do not match",
        ):
            validate_loaded_adapter_contract(
                {"default": attention},
                expected_rank=16,
                expected_targets=LORA_TARGET_PROFILES["attention-mlp"],
            )

    def test_fast_tokenizer_is_a_valid_local_training_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            model_dir = Path(temporary)
            tokenizer_json = model_dir / "tokenizer.json"
            tokenizer_json.write_text("{}", encoding="utf-8")

            self.assertEqual(
                [tokenizer_json],
                tokenizer_artifact_paths(model_dir),
            )

    def test_initial_adapter_requires_lora_and_local_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            adapter = Path(temporary)
            for name in ("adapter_config.json", "adapter_model.safetensors"):
                (adapter / name).write_text(name, encoding="utf-8")

            self.assertEqual(
                adapter.resolve(),
                validate_initial_adapter("lora", adapter),
            )
            with self.assertRaisesRegex(SystemExit, "only with --method=lora"):
                validate_initial_adapter("full", adapter)
            (adapter / "adapter_model.safetensors").unlink()
            with self.assertRaisesRegex(
                SystemExit,
                "missing adapter_model.safetensors",
            ):
                validate_initial_adapter("lora", adapter)

    def test_encode_masks_every_message_before_the_final_assistant(self) -> None:
        tokenizer = FakeChatTokenizer()
        messages = [
            {"role": "system", "content": "contract"},
            {"role": "user", "content": "notification"},
            {"role": "assistant", "content": '{"intent":"OTHER"}'},
        ]

        encoded = encode_rows(
            [{"id": "multi-turn", "messages": messages}],
            tokenizer,
            max_length=32,
        )

        self.assertEqual(messages[:-1], tokenizer.calls[0][0])
        self.assertTrue(tokenizer.calls[0][1])
        self.assertEqual([-100, -100, -100, 20, 106, 107], encoded[0]["labels"])

    def test_intent_loss_scope_masks_json_structure_and_reason(self) -> None:
        tokenizer = FakeIntentTokenizer()
        messages = [
            {"role": "user", "content": "notification"},
            {
                "role": "assistant",
                "content": (
                    '{"intent":"OTHER","confidence":0.9,"reason":"Device status"}'
                ),
            },
        ]

        encoded = encode_rows(
            [{"id": "intent-only", "messages": messages}],
            tokenizer,
            max_length=32,
            loss_scope="intent",
        )

        self.assertEqual(
            [-100, -100, -100, -100, 30, 31, -100, -100],
            encoded[0]["labels"],
        )

    def test_intent_loss_scope_requires_one_tokenized_intent(self) -> None:
        tokenizer = FakeIntentTokenizer()
        tokenizer.apply_chat_template = lambda *args, **kwargs: (
            [2, 10, 11]
            if kwargs["add_generation_prompt"]
            else [2, 10, 11, 20, 40, 50]
        )

        with self.assertRaisesRegex(ValueError, "expected one tokenized intent"):
            encode_rows(
                [
                    {
                        "id": "missing-intent",
                        "messages": [
                            {"role": "user", "content": "notification"},
                            {
                                "role": "assistant",
                                "content": (
                                    '{"intent":"OTHER","confidence":0.9,'
                                    '"reason":"Device status"}'
                                ),
                            },
                        ],
                    }
                ],
                tokenizer,
                max_length=32,
                loss_scope="intent",
            )

    def test_encode_requires_a_final_assistant_message(self) -> None:
        tokenizer = FakeChatTokenizer()

        with self.assertRaisesRegex(ValueError, "final chat message"):
            encode_rows(
                [
                    {
                        "id": "invalid",
                        "messages": [{"role": "user", "content": "notification"}],
                    }
                ],
                tokenizer,
                max_length=32,
            )

    def test_collator_masks_uneven_right_padding(self) -> None:
        import torch

        collator = CausalLmCollator(torch, pad_token_id=0)
        batch = collator(
            [
                {"input_ids": [2, 3, 4], "labels": [-100, 3, 4]},
                {"input_ids": [5], "labels": [5]},
            ]
        )

        self.assertEqual([[2, 3, 4], [5, 0, 0]], batch["input_ids"].tolist())
        self.assertEqual([[1, 1, 1], [1, 0, 0]], batch["attention_mask"].tolist())
        self.assertEqual([[-100, 3, 4], [5, -100, -100]], batch["labels"].tolist())

    def test_mps_bfloat16_loads_model_without_trainer_mixed_precision(self) -> None:
        class UnavailableCuda:
            @staticmethod
            def is_available() -> bool:
                return False

        class AvailableMps:
            @staticmethod
            def is_available() -> bool:
                return True

        class Backends:
            mps = AvailableMps()

        class FakeTorch:
            cuda = UnavailableCuda()
            backends = Backends()
            float32 = "float32"
            bfloat16 = "bfloat16"

        device, dtype, use_fp16, use_bf16 = select_device_and_dtype(
            FakeTorch(),
            mps_dtype="bfloat16",
        )

        self.assertEqual("mps", device)
        self.assertEqual("bfloat16", dtype)
        self.assertFalse(use_fp16)
        self.assertFalse(use_bf16)

    def test_trainer_evaluation_can_be_skipped_for_memory_limited_hosts(self) -> None:
        self.assertTrue(
            trainer_evaluation_enabled(
                smoke_test=False,
                skip_trainer_evaluation=False,
            )
        )
        self.assertFalse(
            trainer_evaluation_enabled(
                smoke_test=False,
                skip_trainer_evaluation=True,
            )
        )
        self.assertFalse(
            trainer_evaluation_enabled(
                smoke_test=True,
                skip_trainer_evaluation=False,
            )
        )


if __name__ == "__main__":
    unittest.main()
