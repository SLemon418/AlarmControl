from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import compare_backend_parity as parity  # noqa: E402
import evaluate_semantic as evaluator  # noqa: E402
import predict_koelectra as predictor  # noqa: E402
import train_koelectra as trainer  # noqa: E402
import validate_holdout as holdout  # noqa: E402


class AtomicFileDurabilityTest(unittest.TestCase):
    def test_atomic_writers_sync_parent_directory_after_replace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            cases = (
                (
                    "prediction-jsonl",
                    predictor,
                    lambda path: predictor._atomic_jsonl(path, [{"id": "row"}]),
                ),
                (
                    "prediction-manifest",
                    predictor,
                    lambda path: predictor._atomic_json(path, {"status": "complete"}),
                ),
                (
                    "parity-report",
                    parity,
                    lambda path: parity._write_json(path, {"status": "complete"}),
                ),
                (
                    "evaluation-report",
                    evaluator,
                    lambda path: evaluator._write_json(path, {"status": "complete"}),
                ),
                (
                    "holdout-manifest",
                    holdout,
                    lambda path: holdout._write_manifest(
                        {"status": "complete"},
                        path,
                    ),
                ),
                (
                    "training-manifest",
                    trainer,
                    lambda path: trainer._atomic_json(path, {"status": "complete"}),
                ),
            )

            for name, module, writer in cases:
                with self.subTest(name=name):
                    destination = root / f"{name}.json"
                    with mock.patch.object(
                        module,
                        "_fsync_directory",
                    ) as directory_sync:
                        writer(destination)

                    self.assertTrue(destination.is_file())
                    directory_sync.assert_called_once_with(root)


if __name__ == "__main__":
    unittest.main()
