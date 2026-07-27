#!/usr/bin/env python3
"""Package a tested .task with the documentation required for distribution."""

from __future__ import annotations

import argparse
import hashlib
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAX_APP_MODEL_BYTES = 4 * 1024 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", type=Path, required=True)
    parser.add_argument("--model-card", type=Path, required=True)
    parser.add_argument(
        "--gemma-terms",
        type=Path,
        required=True,
        help="Local copy of the applicable Gemma Terms obtained by the distributor",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=HERE / "release" / "alarmcontrol-gemma3-270m-dynint8.zip",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    task = args.task.resolve()
    model_card = args.model_card.resolve()
    gemma_terms = args.gemma_terms.resolve()
    notice = HERE / "NOTICE.template"
    for required in (task, model_card, gemma_terms, notice):
        if not required.is_file() or required.stat().st_size == 0:
            raise SystemExit(f"Required release input is missing or empty: {required}")
    if task.suffix != ".task":
        raise SystemExit("The app release artifact must be a MediaPipe .task bundle")
    if task.stat().st_size >= MAX_APP_MODEL_BYTES:
        raise SystemExit("Task is at or above AlarmControl's 4 GiB import limit")
    model_card_text = model_card.read_text(encoding="utf-8")
    if "Replace this section" in model_card_text or "Paste the generated" in model_card_text:
        raise SystemExit("Complete the model card and evaluation record before release")

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    checksums = {
        task.name: sha256(task),
        "MODEL_CARD.md": sha256(model_card),
        "NOTICE": sha256(notice),
        f"GEMMA_TERMS{gemma_terms.suffix}": sha256(gemma_terms),
    }
    checksum_text = "".join(
        f"{fingerprint}  {name}\n" for name, fingerprint in sorted(checksums.items())
    )
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        archive.write(task, task.name)
        archive.write(model_card, "MODEL_CARD.md")
        archive.write(notice, "NOTICE")
        archive.write(gemma_terms, f"GEMMA_TERMS{gemma_terms.suffix}")
        archive.writestr("SHA256SUMS", checksum_text)
    print(f"Release package: {output}")
    print("Extract the .task before selecting it in AlarmControl.")


if __name__ == "__main__":
    main()
