import json
import struct
import tempfile
import unittest
from pathlib import Path

from semantic_contract import (
    ContractError,
    RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
    SemanticThresholds,
    WordPieceTokenizer,
    notification_text,
    validated_training_rows,
)


class SemanticContractTest(unittest.TestCase):
    def setUp(self):
        self.tokenizer = WordPieceTokenizer(
            ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "알림", "sale", "##s", "!", "中"],
            max_sequence_length=10,
        )

    def test_runtime_text_join_and_word_piece_encoding(self):
        encoded = self.tokenizer.encode(notification_text("알림", "sales!"))

        self.assertEqual((2, 4, 5, 6, 7, 3, 0, 0, 0, 0), encoded.input_ids)
        self.assertEqual((1, 1, 1, 1, 1, 1, 0, 0, 0, 0), encoded.attention_mask)
        self.assertEqual((0,) * 10, encoded.token_type_ids)

    def test_release_threshold_floor_is_exact_float32_095(self):
        floor = RELEASE_CONFIDENCE_THRESHOLD_FLOOR
        self.assertEqual(0.949999988079071, floor)
        self.assertEqual(floor, struct.unpack(">f", struct.pack(">f", floor))[0])

    def test_semantic_thresholds_require_closed_float32_object(self):
        floor = RELEASE_CONFIDENCE_THRESHOLD_FLOOR
        thresholds = SemanticThresholds.from_mapping(
            {"general": floor, "marketing": floor}
        )

        self.assertEqual(
            {"general": floor, "marketing": floor},
            thresholds.as_dict(),
        )
        for invalid in (
            {"general": floor},
            {"general": floor, "marketing": floor, "legacy": floor},
        ):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ContractError):
                    SemanticThresholds.from_mapping(invalid)
        with self.assertRaises(ContractError):
            SemanticThresholds.from_mapping(
                {"general": 0.95, "marketing": floor}
            )

    def test_truncation_retains_separator(self):
        tokenizer = WordPieceTokenizer(
            ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "알림"],
            max_sequence_length=4,
        )

        self.assertEqual((2, 4, 4, 3), tokenizer.encode("알림 알림 알림").input_ids)

    def test_bert_clean_text_removes_controls_without_splitting_words(self):
        tokenizer = WordPieceTokenizer(
            ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "sa", "##le", "le"],
            max_sequence_length=8,
        )
        joined = (2, 4, 5, 3, 0, 0, 0, 0)
        separated = (2, 4, 6, 3, 0, 0, 0, 0)

        for character in ("\u0000", "\u0007", "\u0085", "\u200b", "\u202e", "\ufffd"):
            with self.subTest(character=ascii(character)):
                self.assertEqual(
                    joined,
                    tokenizer.encode(f"sa{character}le").input_ids,
                )
        for character in ("\t", "\n", "\r", "\u00a0", "\u2028", "\u2029"):
            with self.subTest(character=ascii(character)):
                self.assertEqual(
                    separated,
                    tokenizer.encode(f"sa{character}le").input_ids,
                )

    def test_notification_text_normalizes_to_nfc(self):
        self.assertEqual("보안 알림", notification_text("보안", "알림"))

    def test_missing_special_token_is_rejected(self):
        with self.assertRaises(ContractError):
            WordPieceTokenizer(["[PAD]", "[UNK]", "[CLS]"])

    def test_training_rows_reject_duplicate_ids(self):
        record = {
            "id": "same",
            "intent": "OTHER",
            "split": "train",
            "title": "a",
            "body": "b",
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.jsonl"
            line = json.dumps(record)
            path.write_text(f"{line}\n{line}\n", encoding="utf-8")

            with self.assertRaises(ContractError):
                validated_training_rows(path)


if __name__ == "__main__":
    unittest.main()
