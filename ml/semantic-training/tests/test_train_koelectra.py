from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import train_koelectra as trainer  # noqa: E402


class KoElectraTrainingScriptTest(unittest.TestCase):
    def test_label_order_and_text_format_are_stable(self) -> None:
        self.assertEqual(
            (
                "MARKETING",
                "TRANSACTIONAL",
                "SECURITY",
                "DELIVERY",
                "SOCIAL",
                "OTHER",
                "AMBIGUOUS",
            ),
            trainer.INTENTS,
        )
        self.assertEqual(
            "Offer title First line",
            trainer.format_notification("Offer title", "First line"),
        )
        self.assertEqual("Body only", trainer.format_notification("", "Body only"))
        self.assertEqual(
            "보안 Alert",
            trainer.format_notification("보안", "Alert"),
        )
        self.assertEqual(
            "runtime-title-body-space-nfc-v2",
            trainer.TEXT_FORMAT_VERSION,
        )
        self.assertEqual(128, trainer.MAX_TOKENS)
        self.assertEqual(2, trainer.MAX_THREADS)
        self.assertEqual(100, trainer.PROGRESS_BATCH_INTERVAL)

    def test_load_dataset_accepts_balanced_train_and_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "dataset.jsonl"
            self._write_dataset(path)

            examples = trainer.load_dataset(path)

            self.assertEqual(14, len(examples["train"]))
            self.assertEqual(7, len(examples["validation"]))
            self.assertEqual(
                list(range(7)),
                [example.label_id for example in examples["validation"]],
            )

    def test_load_dataset_rejects_unknown_and_unbalanced_labels(self) -> None:
        mutations = ("unknown", "unbalanced")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    path = Path(temporary_directory) / "dataset.jsonl"
                    rows = self._dataset_rows()
                    if mutation == "unknown":
                        rows[0]["intent"] = "UNKNOWN_LABEL"
                    else:
                        rows.pop(0)
                    path.write_text(
                        "\n".join(
                            json.dumps(row, separators=(",", ":"))
                            for row in rows
                        )
                        + "\n",
                        encoding="utf-8",
                    )

                    with self.assertRaises(trainer.TrainingInputError):
                        trainer.load_dataset(path)

    def test_manifest_records_reproducibility_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dataset = root / "dataset.jsonl"
            self._write_dataset(dataset)
            base_model = root / "base-model"
            base_model.mkdir()
            (base_model / "config.json").write_text(
                "{}\n",
                encoding="utf-8",
            )
            (base_model / "vocab.txt").write_text(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\n",
                encoding="utf-8",
            )
            options = trainer.TrainingOptions(
                base_model=base_model,
                dataset=dataset,
                output=root / "output",
                epochs=2,
                batch_size=4,
                learning_rate=3e-5,
                seed=17,
                max_rss_bytes=trainer.DEFAULT_MAX_RSS_BYTES,
            )

            manifest = trainer.initial_manifest(
                options,
                trainer.load_dataset(dataset),
                torch_version="test-torch",
                transformers_version="test-transformers",
            )

            self.assertEqual(
                trainer.TRAINING_MANIFEST_VERSION,
                manifest["schema_version"],
            )
            self.assertEqual("cpu", manifest["device"])
            self.assertEqual(list(trainer.INTENTS), manifest["labels"])
            self.assertEqual(128, manifest["max_tokens"])
            self.assertEqual(2, manifest["max_threads"])
            self.assertEqual(
                4 * trainer.GIB,
                manifest["resource_limits"]["max_rss_bytes"],
            )
            self.assertEqual(
                {label: 2 for label in trainer.INTENTS},
                manifest["inputs"]["rows_by_split_and_intent"]["train"],
            )
            self.assertEqual(
                trainer.sha256_file(base_model / "vocab.txt"),
                manifest["inputs"]["base_vocab_sha256"],
            )

    def test_checkpoint_preserves_regular_wordpiece_vocab(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            base_model = root / "base-model"
            target = root / "checkpoint"
            base_model.mkdir()
            target.mkdir()
            vocabulary = "[PAD]\n[UNK]\n[CLS]\n[SEP]\n광고\n"
            (base_model / "vocab.txt").write_text(
                vocabulary,
                encoding="utf-8",
            )

            self.assertTrue(trainer._copy_base_vocab(base_model, target))
            self.assertEqual(
                vocabulary,
                (target / "vocab.txt").read_text(encoding="utf-8"),
            )

    def test_checkpoint_rejects_symlinked_wordpiece_vocab(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            base_model = root / "base-model"
            target = root / "checkpoint"
            base_model.mkdir()
            target.mkdir()
            real_vocab = root / "real-vocab.txt"
            real_vocab.write_text("[PAD]\n", encoding="utf-8")
            try:
                (base_model / "vocab.txt").symlink_to(real_vocab)
            except OSError:
                self.skipTest("symlinks are unavailable")

            with self.assertRaises(trainer.TrainingInputError):
                trainer._copy_base_vocab(base_model, target)

    def test_offline_cpu_environment_disables_accelerators(self) -> None:
        trainer.configure_offline_cpu_environment()

        self.assertEqual("", __import__("os").environ["CUDA_VISIBLE_DEVICES"])
        self.assertEqual(
            "1",
            __import__("os").environ["TRANSFORMERS_OFFLINE"],
        )
        for name in (
            "OMP_NUM_THREADS",
            "MKL_NUM_THREADS",
            "OPENBLAS_NUM_THREADS",
            "VECLIB_MAXIMUM_THREADS",
            "NUMEXPR_NUM_THREADS",
            "RAYON_NUM_THREADS",
        ):
            self.assertEqual("2", __import__("os").environ[name])
        self.assertEqual(
            "false",
            __import__("os").environ["TOKENIZERS_PARALLELISM"],
        )

    def test_memory_ceiling_rejects_peak_rss_at_limit(self) -> None:
        with patch.object(trainer, "_maximum_rss_bytes", return_value=100):
            self.assertEqual(
                100,
                trainer.enforce_memory_ceiling(101, "test"),
            )
            with self.assertRaises(trainer.ResourceLimitExceeded):
                trainer.enforce_memory_ceiling(100, "test")

    def _write_dataset(self, path: Path) -> None:
        path.write_text(
            "\n".join(
                json.dumps(row, separators=(",", ":"))
                for row in self._dataset_rows()
            )
            + "\n",
            encoding="utf-8",
        )

    def _dataset_rows(self) -> list[dict[str, object]]:
        rows: list[dict[str, object]] = []
        for split, copies in (("train", 2), ("validation", 1)):
            for intent in trainer.INTENTS:
                for copy_index in range(copies):
                    row_id = f"{split}-{intent}-{copy_index}"
                    rows.append(
                        {
                            "id": row_id,
                            "intent": intent,
                            "split": split,
                            "title": f"Title {row_id}",
                            "body": f"Body {row_id}",
                        }
                    )
        rows.append(
            {
                "id": "test-row",
                "intent": trainer.INTENTS[0],
                "split": "test",
                "title": "Unused test title",
                "body": "Unused test body",
            }
        )
        return rows


if __name__ == "__main__":
    unittest.main()
