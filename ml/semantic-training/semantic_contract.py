#!/usr/bin/env python3
"""Shared, dependency-free contract for semantic model training and evaluation."""

from __future__ import annotations

import hashlib
import json
import math
import struct
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

LABELS = (
    "MARKETING",
    "TRANSACTIONAL",
    "SECURITY",
    "DELIVERY",
    "SOCIAL",
    "OTHER",
    "AMBIGUOUS",
)
MAX_SEQUENCE_LENGTH = 128
TEXT_SEPARATOR = " "
RUNTIME_TEXT_FORMAT_VERSION = "runtime-title-body-space-nfc-v2"
# The runtime stores and compares probabilities as float32. Keep the release
# floor in that exact representation so Python, Kotlin, and generated JSON all
# make the same boundary decision.
RELEASE_CONFIDENCE_THRESHOLD_FLOOR = struct.unpack(
    ">f",
    struct.pack(">f", 0.95),
)[0]
SEMANTIC_THRESHOLD_KEYS = frozenset({"general", "marketing"})


class ContractError(ValueError):
    """Raised when an input cannot satisfy the deployed model contract."""


def _release_threshold(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{context} must be a finite number")
    threshold = float(value)
    if (
        not math.isfinite(threshold)
        or not RELEASE_CONFIDENCE_THRESHOLD_FLOOR <= threshold <= 1.0
    ):
        raise ContractError(
            f"{context} must be within "
            f"[{RELEASE_CONFIDENCE_THRESHOLD_FLOOR}, 1]"
        )
    if struct.unpack(">f", struct.pack(">f", threshold))[0] != threshold:
        raise ContractError(f"{context} must be exactly representable as float32")
    return threshold


@dataclass(frozen=True)
class SemanticThresholds:
    """Exact two-threshold release abstention contract."""

    general: float
    marketing: float

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            "general",
            _release_threshold(self.general, "thresholds.general"),
        )
        object.__setattr__(
            self,
            "marketing",
            _release_threshold(self.marketing, "thresholds.marketing"),
        )

    @classmethod
    def from_mapping(
        cls,
        value: Mapping[str, Any],
    ) -> "SemanticThresholds":
        """Parse an exact-key JSON-compatible threshold object."""

        if not isinstance(value, Mapping) or set(value) != SEMANTIC_THRESHOLD_KEYS:
            raise ContractError(
                "thresholds must contain exactly general and marketing"
            )
        return cls(
            general=value["general"],
            marketing=value["marketing"],
        )

    def as_dict(self) -> dict[str, float]:
        """Return the closed JSON object used by release evidence."""

        return {
            "general": self.general,
            "marketing": self.marketing,
        }


DEFAULT_SEMANTIC_THRESHOLDS = SemanticThresholds(
    general=RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
    marketing=RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
)


def sha256_file(path: Path) -> str:
    """Return the lower-case SHA-256 digest for one regular file."""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def model_bundle_hashes(model_dir: Path) -> tuple[dict[str, str], str]:
    """Hash every regular bundle file and derive one stable bundle digest."""

    file_hashes: dict[str, str] = {}
    for path in sorted(model_dir.rglob("*")):
        if path.is_symlink():
            raise ContractError(f"model bundle symlink is not allowed: {path}")
        if path.is_file():
            relative = path.relative_to(model_dir).as_posix()
            file_hashes[relative] = sha256_file(path)
    if not file_hashes:
        raise ContractError("model bundle contains no regular files")
    digest = hashlib.sha256()
    for relative, file_hash in file_hashes.items():
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(file_hash.encode("ascii"))
        digest.update(b"\n")
    return file_hashes, digest.hexdigest()


def notification_text(title: str, body: str) -> str:
    """Mirror NotificationSnapshot.classifiableText() in the Android runtime."""

    joined = TEXT_SEPARATOR.join(part for part in (title, body) if part).strip()
    return unicodedata.normalize("NFC", joined)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    """Load non-blank JSON objects and reject duplicate keys."""

    def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, child in pairs:
            if key in value:
                raise ContractError(f"duplicate JSON key: {key}")
            value[key] = child
        return value

    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            raise ContractError(f"{path}:{line_number}: blank JSONL line")
        try:
            value = json.loads(line, object_pairs_hook=strict_object)
        except (json.JSONDecodeError, ContractError) as error:
            raise ContractError(f"{path}:{line_number}: invalid JSON: {error}") from error
        if not isinstance(value, dict):
            raise ContractError(f"{path}:{line_number}: expected a JSON object")
        records.append(value)
    return records


def validated_training_rows(path: Path) -> list[dict[str, Any]]:
    """Load the builder output and validate fields consumed by trainers."""

    records = load_jsonl(path)
    ids: set[str] = set()
    for index, record in enumerate(records, 1):
        context = f"{path}:{index}"
        identifier = record.get("id")
        if not isinstance(identifier, str) or not identifier:
            raise ContractError(f"{context}: missing id")
        if identifier in ids:
            raise ContractError(f"{context}: duplicate id {identifier!r}")
        ids.add(identifier)
        if record.get("intent") not in LABELS:
            raise ContractError(f"{context}: unsupported intent")
        if record.get("split") not in {"train", "validation", "test"}:
            raise ContractError(f"{context}: unsupported split")
        if not isinstance(record.get("title"), str) or not isinstance(record.get("body"), str):
            raise ContractError(f"{context}: title and body must be strings")
        if not notification_text(record["title"], record["body"]):
            raise ContractError(f"{context}: empty notification text")
    return records


@dataclass(frozen=True)
class EncodedInput:
    """One fixed-shape BERT-compatible input."""

    input_ids: tuple[int, ...]
    attention_mask: tuple[int, ...]
    token_type_ids: tuple[int, ...]


class WordPieceTokenizer:
    """Python mirror of the small Kotlin tokenizer bundled in ``:ml``."""

    def __init__(
        self,
        vocabulary: Sequence[str],
        max_sequence_length: int = MAX_SEQUENCE_LENGTH,
        *,
        lowercase: bool = False,
    ) -> None:
        if max_sequence_length < 4:
            raise ContractError("max_sequence_length must be at least four")
        self.vocabulary = tuple(vocabulary)
        self.token_ids = {token: index for index, token in enumerate(vocabulary)}
        if len(self.token_ids) != len(self.vocabulary):
            raise ContractError("vocabulary contains duplicate tokens")
        missing = [
            token
            for token in ("[PAD]", "[UNK]", "[CLS]", "[SEP]")
            if token not in self.token_ids
        ]
        if missing:
            raise ContractError(f"vocabulary is missing special tokens: {missing}")
        self.max_sequence_length = max_sequence_length
        self.lowercase = lowercase

    @classmethod
    def from_file(
        cls,
        path: Path,
        max_sequence_length: int = MAX_SEQUENCE_LENGTH,
        *,
        lowercase: bool = False,
    ) -> "WordPieceTokenizer":
        vocabulary = path.read_text(encoding="utf-8").splitlines()
        if not vocabulary or any(not token for token in vocabulary):
            raise ContractError(f"invalid vocabulary: {path}")
        return cls(vocabulary, max_sequence_length, lowercase=lowercase)

    def encode(self, text: str) -> EncodedInput:
        content_ids: list[int] = []
        unknown_id = self.token_ids["[UNK]"]
        maximum_content = self.max_sequence_length - 2
        for token in self._basic_tokenize(text):
            for piece in self._word_pieces(token, unknown_id):
                if len(content_ids) >= maximum_content:
                    break
                content_ids.append(piece)
            if len(content_ids) >= maximum_content:
                break

        sequence = [
            self.token_ids["[CLS]"],
            *content_ids,
            self.token_ids["[SEP]"],
        ]
        padding_count = self.max_sequence_length - len(sequence)
        input_ids = tuple(sequence + [self.token_ids["[PAD]"]] * padding_count)
        attention_mask = tuple([1] * len(sequence) + [0] * padding_count)
        return EncodedInput(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=(0,) * self.max_sequence_length,
        )

    def encode_records(
        self,
        records: Iterable[dict[str, Any]],
    ) -> tuple[list[list[int]], list[list[int]], list[list[int]]]:
        ids: list[list[int]] = []
        masks: list[list[int]] = []
        types: list[list[int]] = []
        for record in records:
            encoded = self.encode(notification_text(record["title"], record["body"]))
            ids.append(list(encoded.input_ids))
            masks.append(list(encoded.attention_mask))
            types.append(list(encoded.token_type_ids))
        return ids, masks, types

    def _basic_tokenize(self, text: str) -> list[str]:
        normalized = unicodedata.normalize("NFC", text)
        tokens: list[str] = []
        current: list[str] = []

        def flush() -> None:
            if current:
                value = "".join(current)
                tokens.append(value.lower() if self.lowercase else value)
                current.clear()

        for character in normalized:
            category = unicodedata.category(character)
            if character in {" ", "\t", "\n", "\r"} or category.startswith("Z"):
                flush()
            elif (
                ord(character) in {0, 0xFFFD}
                or category in {"Cc", "Cf"}
            ):
                continue
            elif self._is_punctuation(character) or self._is_cjk(character):
                flush()
                tokens.append(character)
            else:
                current.append(character)
        flush()
        return tokens

    def _word_pieces(self, token: str, unknown_id: int) -> list[int]:
        direct = self.token_ids.get(token)
        if direct is not None:
            return [direct]
        if len(token) > 100:
            return [unknown_id]

        pieces: list[int] = []
        start = 0
        while start < len(token):
            matched_id: int | None = None
            matched_end = start
            for end in range(len(token), start, -1):
                candidate = token[start:end]
                if start:
                    candidate = f"##{candidate}"
                candidate_id = self.token_ids.get(candidate)
                if candidate_id is not None:
                    matched_id = candidate_id
                    matched_end = end
                    break
            if matched_id is None:
                return [unknown_id]
            pieces.append(matched_id)
            start = matched_end
        return pieces

    @staticmethod
    def _is_punctuation(character: str) -> bool:
        return unicodedata.category(character) in {
            "Pc",
            "Pd",
            "Ps",
            "Pe",
            "Pi",
            "Pf",
            "Po",
        }

    @staticmethod
    def _is_cjk(character: str) -> bool:
        code = ord(character)
        return (
            0x4E00 <= code <= 0x9FFF
            or 0x3400 <= code <= 0x4DBF
            or 0xF900 <= code <= 0xFAFF
        )
