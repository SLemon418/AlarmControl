#!/usr/bin/env python3
"""Validate and render the frozen, synthetic final holdout exactly once."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from contract import INTENTS
from prepare_dataset import HERE, _validate_source_row, render_row

DEFAULT_SOURCE = HERE / "data" / "final_holdout_examples.jsonl"
DEFAULT_OUTPUT = HERE / "artifacts" / "final-holdout"
EXPECTED_SOURCE_SHA256 = "58b1e4d06f73646bafc8d2196f19c242f3d806bdd5945fa5bc461461c86c47ca"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_final_holdout(path: Path) -> list[dict[str, Any]]:
    actual_sha256 = file_sha256(path)
    if actual_sha256 != EXPECTED_SOURCE_SHA256:
        raise ValueError(
            "Final holdout changed after it was frozen: "
            f"expected {EXPECTED_SOURCE_SHA256}, got {actual_sha256}"
        )

    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            _validate_source_row(row, path, line_number)
            if row["split"] != "test":
                raise ValueError(f"{path}:{line_number}: final holdout must use test split")
            rows.append(row)

    ids = [row["id"] for row in rows]
    texts = [row["text"] for row in rows]
    if len(rows) != 28 or len(ids) != len(set(ids)) or len(texts) != len(set(texts)):
        raise ValueError("Final holdout must contain 28 unique ids and notification texts")

    counts = Counter((row["intent"], row["locale"]) for row in rows)
    hard_counts = Counter(
        (row["intent"], row["locale"]) for row in rows if "hard" in row["tags"]
    )
    injection_counts = Counter(
        (row["intent"], row["locale"])
        for row in rows
        if "prompt-injection" in row["tags"]
    )
    for intent in INTENTS:
        for locale in ("en", "ko"):
            if counts[(intent, locale)] != 2:
                raise ValueError(f"Final holdout requires 2 rows for {intent}/{locale}")
            if hard_counts[(intent, locale)] != 1:
                raise ValueError(f"Final holdout requires 1 hard row for {intent}/{locale}")
            if injection_counts[(intent, locale)] != 1:
                raise ValueError(
                    f"Final holdout requires 1 prompt-injection row for {intent}/{locale}"
                )
    if any(
        ("hard" in row["tags"]) != ("prompt-injection" in row["tags"])
        for row in rows
    ):
        raise ValueError("Final holdout hard rows must be prompt-injection rows")
    return sorted(rows, key=lambda row: row["id"])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source = args.source.resolve()
    output_dir = args.output_dir.resolve()
    rows = load_final_holdout(source)
    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "test.jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(
                json.dumps(render_row(row), ensure_ascii=False, separators=(",", ":"))
                + "\n"
            )
    (output_dir / "manifest.json").write_text(
        json.dumps(
            {
                "contains_real_notifications": False,
                "counts": Counter(row["intent"] for row in rows),
                "frozen_source_sha256": EXPECTED_SOURCE_SHA256,
                "rows": len(rows),
                "source": str(source.relative_to(HERE)),
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Prepared frozen final holdout: rows={len(rows)}")


if __name__ == "__main__":
    main()
