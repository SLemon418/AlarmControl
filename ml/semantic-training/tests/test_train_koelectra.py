from __future__ import annotations

import hashlib
import json
import multiprocessing
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

TRAINING_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TRAINING_DIR))

import train_koelectra as trainer  # noqa: E402
from semantic_contract import resolve_training_model_bundle  # noqa: E402


CHECKPOINT_FILES = (
    "config.json",
    "checkpoint.json",
    "optimizer.pt",
    "model.safetensors",
)


def _write_checkpoint_bundle(directory: Path, marker: bytes) -> None:
    for name in CHECKPOINT_FILES:
        (directory / name).write_bytes(marker + b"-" + name.encode())


def _publish_checkpoint_process(
    target_value: str,
    marker: bytes,
    start: multiprocessing.synchronize.Event,
) -> None:
    start.wait()
    trainer._replace_directory(
        Path(target_value),
        lambda directory: _write_checkpoint_bundle(directory, marker),
    )


def _publish_checkpoint_then_pause_process(
    target_value: str,
    marker: bytes,
    ready: multiprocessing.synchronize.Event,
) -> None:
    def writer(directory: Path) -> None:
        _write_checkpoint_bundle(directory, marker)
        ready.set()
        while True:
            time.sleep(1)

    trainer._replace_directory(Path(target_value), writer)


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

            examples, dataset_sha256 = trainer.load_dataset_snapshot(dataset)
            manifest = trainer.initial_manifest(
                options,
                examples,
                dataset_sha256,
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

    def test_training_manifest_reuses_the_loaded_dataset_snapshot_hash(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            dataset = root / "dataset.jsonl"
            self._write_dataset(dataset)
            original = dataset.read_bytes()
            base_model = root / "base-model"
            base_model.mkdir()
            (base_model / "config.json").write_text("{}\n", encoding="utf-8")
            real_loader = trainer.validated_training_rows_snapshot

            def load_then_replace(path):
                snapshot = real_loader(path)
                dataset.write_bytes(original + b"\n")
                return snapshot

            with patch.object(
                trainer,
                "validated_training_rows_snapshot",
                side_effect=load_then_replace,
            ):
                examples, dataset_sha256 = trainer.load_dataset_snapshot(
                    dataset
                )

            options = trainer.TrainingOptions(
                base_model=base_model,
                dataset=dataset,
                output=root / "output",
                epochs=1,
                batch_size=1,
                learning_rate=1e-5,
                seed=1,
                max_rss_bytes=trainer.DEFAULT_MAX_RSS_BYTES,
            )
            manifest = trainer.initial_manifest(
                options,
                examples,
                dataset_sha256,
                torch_version="test-torch",
                transformers_version="test-transformers",
            )

            self.assertEqual(
                hashlib.sha256(original).hexdigest(),
                manifest["inputs"]["dataset_sha256"],
            )
            self.assertNotEqual(
                manifest["inputs"]["dataset_sha256"],
                hashlib.sha256(dataset.read_bytes()).hexdigest(),
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

    def test_concurrent_checkpoint_publishers_commit_one_complete_generation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = Path(temporary_directory) / "best"
            context = multiprocessing.get_context("fork")
            start = context.Event()
            processes = [
                context.Process(
                    target=_publish_checkpoint_process,
                    args=(str(target), marker, start),
                )
                for marker in (b"first", b"second")
            ]
            for process in processes:
                process.start()
            start.set()
            for process in processes:
                process.join(timeout=10)
                self.assertEqual(0, process.exitcode)

            generation = resolve_training_model_bundle(target)
            markers = {
                (generation / name).read_bytes().split(b"-", maxsplit=1)[0]
                for name in CHECKPOINT_FILES
            }
            self.assertIn(markers, ({b"first"}, {b"second"}))

    def test_sigkill_before_checkpoint_commit_preserves_old_generation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = Path(temporary_directory) / "checkpoint"
            trainer._replace_directory(
                target,
                lambda directory: _write_checkpoint_bundle(directory, b"old"),
            )
            original = {
                name: (resolve_training_model_bundle(target) / name).read_bytes()
                for name in CHECKPOINT_FILES
            }
            context = multiprocessing.get_context("fork")
            ready = context.Event()
            process = context.Process(
                target=_publish_checkpoint_then_pause_process,
                args=(str(target), b"new", ready),
            )
            process.start()
            self.assertTrue(ready.wait(timeout=10))
            process.kill()
            process.join(timeout=10)

            self.assertIsNotNone(process.exitcode)
            self.assertNotEqual(0, process.exitcode)
            generation = resolve_training_model_bundle(target)
            self.assertEqual(
                original,
                {
                    name: (generation / name).read_bytes()
                    for name in CHECKPOINT_FILES
                },
            )
            _, generations_name = trainer.training_generation_names(target)
            generations = target.parent / generations_name
            self.assertTrue(list(generations.glob(".pending-*")))

            trainer._replace_directory(
                target,
                lambda directory: _write_checkpoint_bundle(
                    directory,
                    b"recovered",
                ),
            )

            self.assertEqual([], list(generations.glob(".pending-*")))
            recovered = resolve_training_model_bundle(target)
            self.assertEqual(
                {b"recovered"},
                {
                    (recovered / name).read_bytes().split(b"-", maxsplit=1)[0]
                    for name in CHECKPOINT_FILES
                },
            )

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
