from __future__ import annotations

import struct
import sys
import unittest
from pathlib import Path

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import predict_tflite as predictor  # noqa: E402
from semantic_contract import MAX_SEQUENCE_LENGTH  # noqa: E402


class TflitePredictionScriptTest(unittest.TestCase):
    def test_prediction_manifest_uses_common_v2_binding(self) -> None:
        self.assertEqual(
            "alarmcontrol-semantic-prediction-v1",
            predictor.PREDICTION_SCHEMA_VERSION,
        )
        self.assertEqual(
            "alarmcontrol-semantic-prediction-manifest-v2",
            predictor.MANIFEST_SCHEMA_VERSION,
        )
        self.assertEqual("tensorflow-lite", predictor.BACKEND)
        self.assertEqual(128, MAX_SEQUENCE_LENGTH)

    def test_source_validation_is_lightweight_and_strict(self) -> None:
        record = {
            "id": "en-other-001-clean",
            "locale": "en",
            "intent": "OTHER",
            "pair_id": "en-other-001",
            "title": "Reminder",
            "body": "Bring a notebook",
            "injection": False,
        }

        predictor._validate_source_record(record, "source")
        record["injection"] = 1
        with self.assertRaises(ValueError):
            predictor._validate_source_record(record, "source")

    def test_softmax_matches_deployed_float32_arithmetic(self) -> None:
        probabilities = predictor._softmax(
            [1.0, 0.0, -1.0, -2.0, -3.0, -4.0, -5.0]
        )
        bits = [
            struct.unpack(">I", struct.pack(">f", value))[0]
            for value in probabilities
        ]

        self.assertEqual(
            [
                0x3F21F877,
                0x3E6E57B3,
                0x3DAF5CD7,
                0x3D01064E,
                0x3C3DDCAF,
                0x3B8BB154,
                0x3ACD8F6E,
            ],
            bits,
        )
        self.assertTrue(
            all(
                value == predictor._float32(value)
                for value in probabilities
            )
        )


if __name__ == "__main__":
    unittest.main()
