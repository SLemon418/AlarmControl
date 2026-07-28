from __future__ import annotations

import sys
import unittest
from pathlib import Path

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import train_conv1d as trainer  # noqa: E402


class Conv1dTrainingScriptTest(unittest.TestCase):
    def test_manifest_records_runtime_nfc_text_contract(self) -> None:
        self.assertEqual(
            {
                "version": "runtime-title-body-space-nfc-v2",
                "template": "{title} {body}",
                "normalization": (
                    "join nonempty title/body with one literal ASCII space, "
                    "strip outer whitespace, then normalize to NFC"
                ),
            },
            trainer._text_format_manifest(),
        )


if __name__ == "__main__":
    unittest.main()
