from __future__ import annotations

import sys
import unittest
from pathlib import Path

LLM_TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LLM_TRAINING_DIR))

from train import CausalLmCollator, encode_rows, select_device_and_dtype


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


class TrainingContractTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
