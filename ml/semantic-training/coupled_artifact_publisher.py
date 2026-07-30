#!/usr/bin/env python3
"""Serialize and durably publish coupled local artifacts as one transaction."""

from __future__ import annotations

import fcntl
import json
import os
import re
import stat
import tempfile
import uuid
from pathlib import Path
from typing import Iterable, Mapping

from atomic_generation import _fsync_directory


class CoupledArtifactError(RuntimeError):
    """Raised when a coupled publication cannot be proven safe."""


TEMPORARY_TOKEN_PATTERN = re.compile(r"[a-z0-9_]+")


def json_bytes(value: object) -> bytes:
    """Return the canonical pretty JSON encoding used by artifact manifests."""

    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def jsonl_bytes(records: Iterable[object]) -> bytes:
    """Return the compact UTF-8 JSONL encoding used by datasets and predictions."""

    return "".join(
        f"{json.dumps(record, ensure_ascii=False, separators=(',', ':'))}\n"
        for record in records
    ).encode("utf-8")


def _plain_name(name: object, context: str) -> str:
    if (
        not isinstance(name, str)
        or not name
        or Path(name).name != name
        or name in {".", ".."}
    ):
        raise CoupledArtifactError(f"{context} must be one plain filename")
    return name


def _transaction_id(value: object, context: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 32
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise CoupledArtifactError(f"{context} must be a 32-character hex id")
    return value


def _backup_transaction_id(target_name: str, backup_name: str) -> str:
    prefix = f".{target_name}."
    suffix = ".backup"
    if not backup_name.startswith(prefix) or not backup_name.endswith(suffix):
        raise CoupledArtifactError(
            f"invalid publication backup for {target_name}: {backup_name}"
        )
    transaction, separator, random_name = backup_name[
        len(prefix) : -len(suffix)
    ].partition(".")
    if not separator or not random_name:
        raise CoupledArtifactError(
            f"invalid publication backup for {target_name}: {backup_name}"
        )
    return _transaction_id(transaction, "journal backup transaction")


def _regular_or_missing(path: Path, context: str) -> None:
    if path.is_symlink():
        raise CoupledArtifactError(f"{context} must not be a symlink: {path}")
    if path.exists() and not path.is_file():
        raise CoupledArtifactError(f"{context} must be a regular file: {path}")


def _mkdir_synced(directory: Path) -> None:
    """Create a directory chain and durably record every new parent entry."""

    missing: list[Path] = []
    current = directory
    while not current.exists():
        missing.append(current)
        if current == current.parent:
            break
        current = current.parent
    directory.mkdir(parents=True, exist_ok=True)
    for created in reversed(missing):
        _fsync_directory(created)
        _fsync_directory(created.parent)


def normalize_publication_path(path: Path) -> Path:
    """Canonicalize only the parent and preserve the requested leaf identity."""

    requested = path.expanduser()
    name = _plain_name(requested.name, "artifact path")
    parent = requested.parent
    if parent.is_symlink():
        raise CoupledArtifactError(
            f"publication directory must not be a symlink: {parent}"
        )
    _mkdir_synced(parent)
    target = parent.resolve(strict=True) / name
    _regular_or_missing(target, "publication target")
    return target


def _write_staging(directory: Path, prefix: str, suffix: str, content: bytes) -> Path:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=prefix,
        suffix=suffix,
        dir=directory,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        return temporary
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _copy_backup(source: Path, transaction_id: str) -> Path:
    backup = _write_staging(
        source.parent,
        f".{source.name}.{transaction_id}.",
        ".backup",
        source.read_bytes(),
    )
    return backup


def _copy_recovery(
    source: Path,
    target: Path,
    transaction_id: str,
) -> Path:
    return _write_staging(
        source.parent,
        f".{target.name}.{transaction_id}.",
        ".recovery.staging",
        source.read_bytes(),
    )


def _temporary_name_matches(
    name: str,
    prefix: str,
    suffix: str,
) -> bool:
    if not name.startswith(prefix) or not name.endswith(suffix):
        return False
    transaction, separator, token = name[
        len(prefix) : -len(suffix)
    ].partition(".")
    return (
        bool(separator)
        and bool(TEMPORARY_TOKEN_PATTERN.fullmatch(token))
        and len(transaction) == 32
        and all(character in "0123456789abcdef" for character in transaction)
    )


def _cleanup_orphan_temporaries(
    directory: Path,
    expected_targets: set[str],
    journal_name: str,
) -> None:
    removed = False
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file():
            continue
        owned = any(
            _temporary_name_matches(
                path.name,
                f".{target}.",
                suffix,
            )
            for target in expected_targets
            for suffix in (".staging", ".backup", ".recovery.staging")
        ) or _temporary_name_matches(
            path.name,
            f".{journal_name}.",
            ".staging",
        )
        if owned:
            path.unlink()
            removed = True
    if removed:
        _fsync_directory(directory)


def _open_lock(path: Path):
    _regular_or_missing(path, "publication lock")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError as error:
        raise CoupledArtifactError(f"cannot safely open publication lock: {path}") from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        os.close(descriptor)
        raise CoupledArtifactError(f"publication lock is not regular: {path}")
    return os.fdopen(descriptor, "a+b")


def _load_journal(journal: Path) -> dict[str, object] | None:
    if not journal.exists():
        return None
    _regular_or_missing(journal, "publication journal")
    try:
        value = json.loads(journal.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CoupledArtifactError(f"invalid publication journal: {journal}") from error
    if not isinstance(value, dict) or value.get("schema_version") != 1:
        raise CoupledArtifactError(f"invalid publication journal: {journal}")
    return value


def _recover_journal(
    directory: Path,
    journal: Path,
    expected_targets: set[str],
) -> None:
    value = _load_journal(journal)
    if value is None:
        return
    entries = value.get("entries")
    if not isinstance(entries, list) or not entries:
        raise CoupledArtifactError(f"invalid publication journal: {journal}")
    journal_transaction = _transaction_id(
        value.get("transaction_id"),
        "journal transaction_id",
    )
    recovery: list[tuple[Path, Path | None]] = []
    seen_targets: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise CoupledArtifactError(f"invalid publication journal: {journal}")
        target_name = _plain_name(entry.get("target"), "journal target")
        if target_name not in expected_targets or target_name in seen_targets:
            raise CoupledArtifactError(f"invalid publication journal target: {target_name}")
        seen_targets.add(target_name)
        backup_value = entry.get("backup")
        target = directory / target_name
        _regular_or_missing(target, "publication target")
        if backup_value is None:
            recovery.append((target, None))
            continue
        backup_name = _plain_name(backup_value, "journal backup")
        backup_transaction = _backup_transaction_id(target_name, backup_name)
        if backup_transaction != journal_transaction:
            raise CoupledArtifactError(
                f"publication backup transaction mismatch: {backup_name}"
            )
        backup = directory / backup_name
        _regular_or_missing(backup, "publication backup")
        if not backup.is_file():
            raise CoupledArtifactError(f"publication backup is missing: {backup}")
        recovery.append((target, backup))
    if seen_targets != expected_targets:
        raise CoupledArtifactError(f"publication journal target set mismatch: {journal}")

    backups: list[Path] = []
    for target, backup in recovery:
        _regular_or_missing(target, "publication target")
        if backup is None:
            target.unlink(missing_ok=True)
            continue
        _regular_or_missing(backup, "publication backup")
        if not backup.is_file():
            raise CoupledArtifactError(f"publication backup is missing: {backup}")
        restored = _copy_recovery(
            backup,
            target,
            journal_transaction,
        )
        try:
            os.replace(restored, target)
        finally:
            restored.unlink(missing_ok=True)
        backups.append(backup)
    _fsync_directory(directory)
    journal.unlink()
    _fsync_directory(directory)
    for backup in backups:
        backup.unlink(missing_ok=True)
    _fsync_directory(directory)


def publish_coupled_files(
    artifacts: Mapping[Path, bytes],
    *,
    lock_name: str,
) -> None:
    """Publish same-directory files under one writer lock with durable rollback."""

    if len(artifacts) < 2:
        raise CoupledArtifactError("coupled publication requires at least two files")
    lock_name = _plain_name(lock_name, "lock_name")
    requested = list(artifacts.items())
    directory = normalize_publication_path(requested[0][0]).parent
    normalized: list[tuple[Path, bytes]] = []
    names: set[str] = set()
    for requested_path, content in requested:
        target = normalize_publication_path(requested_path)
        if target.parent != directory:
            raise CoupledArtifactError("coupled artifacts must share one directory")
        name = target.name
        if name in names:
            raise CoupledArtifactError(f"duplicate coupled artifact: {name}")
        if not isinstance(content, bytes):
            raise CoupledArtifactError(f"artifact content must be bytes: {name}")
        names.add(name)
        normalized.append((target, content))

    lock = directory / lock_name
    journal = directory / f"{lock_name}.transaction"
    transaction_id = uuid.uuid4().hex
    staging: list[Path] = []
    backups: dict[str, Path | None] = {}
    with _open_lock(lock) as lock_stream:
        fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX)
        try:
            _recover_journal(directory, journal, names)
            _cleanup_orphan_temporaries(
                directory,
                names,
                journal.name,
            )
            for target, content in normalized:
                staging.append(
                    _write_staging(
                        directory,
                        f".{target.name}.{transaction_id}.",
                        ".staging",
                        content,
                    )
                )
                backups[target.name] = (
                    _copy_backup(target, transaction_id) if target.exists() else None
                )
            _fsync_directory(directory)

            journal_value = {
                "schema_version": 1,
                "transaction_id": transaction_id,
                "entries": [
                    {
                        "target": target.name,
                        "backup": (
                            backups[target.name].name
                            if backups[target.name] is not None
                            else None
                        ),
                    }
                    for target, _ in normalized
                ],
            }
            journal_staging = _write_staging(
                directory,
                f".{journal.name}.{transaction_id}.",
                ".staging",
                json_bytes(journal_value),
            )
            try:
                os.replace(journal_staging, journal)
            finally:
                journal_staging.unlink(missing_ok=True)
            _fsync_directory(directory)

            try:
                for (target, _), temporary in zip(normalized, staging, strict=True):
                    os.replace(temporary, target)
                _fsync_directory(directory)
                journal.unlink()
                _fsync_directory(directory)
            except BaseException:
                _recover_journal(directory, journal, names)
                raise
        finally:
            for temporary in staging:
                temporary.unlink(missing_ok=True)
            if not journal.exists():
                for backup in backups.values():
                    if backup is not None:
                        backup.unlink(missing_ok=True)
            _fsync_directory(directory)
            fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN)
