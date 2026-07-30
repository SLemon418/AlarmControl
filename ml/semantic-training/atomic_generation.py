#!/usr/bin/env python3
"""Crash-safe immutable-generation publication for local model bundles."""

from __future__ import annotations

import fcntl
import os
import re
import shutil
import stat
import tempfile
import uuid
from pathlib import Path
from typing import Callable, Iterable

GENERATION_ID_PATTERN = re.compile(r"[0-9a-f]{32}")
PENDING_GENERATION_PREFIX = ".pending-"
PENDING_MARKER = ".publication-pending"


class GenerationError(ValueError):
    """Raised when a committed generation pointer or bundle is invalid."""


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _regular_directory(path: Path, context: str, *, create: bool) -> Path:
    if path.is_symlink():
        raise GenerationError(f"{context} must not be a symlink: {path}")
    if create:
        path.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or not path.is_dir():
        raise GenerationError(f"{context} must be a regular directory: {path}")
    return path.resolve(strict=True)


def _open_lock(path: Path):
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise GenerationError(f"generation lock must be a regular file: {path}")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError as error:
        raise GenerationError(f"cannot safely open generation lock: {path}") from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        os.close(descriptor)
        raise GenerationError(f"generation lock must be a regular file: {path}")
    return os.fdopen(descriptor, "a+b")


def _pointer_selection(path: Path, generation_id: str) -> bool | None:
    """Return false only when the pointer is proven not to select this generation."""

    if path.is_symlink():
        return None
    try:
        if not path.is_file():
            return False
        value = path.read_text(encoding="ascii").strip()
    except FileNotFoundError:
        return False
    except (OSError, UnicodeError):
        return None
    return value == generation_id


def _pending_generation_id(name: str) -> str | None:
    if not name.startswith(PENDING_GENERATION_PREFIX):
        return None
    generation_id = name[len(PENDING_GENERATION_PREFIX) :]
    return (
        generation_id
        if GENERATION_ID_PATTERN.fullmatch(generation_id) is not None
        else None
    )


def _validate_pending_marker(directory: Path, generation_id: str) -> Path:
    marker = directory / PENDING_MARKER
    if marker.is_symlink() or not marker.is_file():
        raise GenerationError(
            f"pending generation marker is invalid: {marker}"
        )
    try:
        selected = marker.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise GenerationError(
            f"pending generation marker is unreadable: {marker}"
        ) from error
    if selected != generation_id:
        raise GenerationError(
            f"pending generation marker does not match: {marker}"
        )
    return marker


def _clear_pending_marker(directory: Path, generation_id: str) -> None:
    marker = _validate_pending_marker(directory, generation_id)
    marker.unlink()
    _fsync_directory(directory)


def _pointer_staging_generation_id(
    name: str,
    pointer_name: str,
) -> str | None:
    prefix = f".{pointer_name}."
    suffix = ".staging"
    if not name.startswith(prefix) or not name.endswith(suffix):
        return None
    generation_id, separator, token = name[
        len(prefix) : -len(suffix)
    ].partition(".")
    if (
        not separator
        or not token
        or GENERATION_ID_PATTERN.fullmatch(generation_id) is None
    ):
        return None
    return generation_id


def _recover_pending_generations(
    root: Path,
    generations: Path,
    pointer: Path,
    pointer_name: str,
) -> None:
    """Recover only generations carrying this publisher's pending identity."""

    owned_ids: set[str] = set()
    changed_generations = False
    for candidate in tuple(generations.iterdir()):
        pending_id = _pending_generation_id(candidate.name)
        if pending_id is not None:
            if candidate.is_symlink() or not candidate.is_dir():
                raise GenerationError(
                    f"pending generation is not a regular directory: {candidate}"
                )
            shutil.rmtree(candidate)
            owned_ids.add(pending_id)
            changed_generations = True
            continue
        if GENERATION_ID_PATTERN.fullmatch(candidate.name) is None:
            continue
        if candidate.is_symlink() or not candidate.is_dir():
            continue
        marker = candidate / PENDING_MARKER
        if not marker.is_symlink() and not marker.exists():
            continue
        _validate_pending_marker(candidate, candidate.name)
        selection = _pointer_selection(pointer, candidate.name)
        if selection is None:
            raise GenerationError(
                f"cannot safely recover pending generation: {candidate}"
            )
        owned_ids.add(candidate.name)
        if selection:
            _clear_pending_marker(candidate, candidate.name)
        else:
            shutil.rmtree(candidate)
        changed_generations = True

    changed_root = False
    for candidate in tuple(root.iterdir()):
        staging_id = _pointer_staging_generation_id(
            candidate.name,
            pointer_name,
        )
        if staging_id not in owned_ids:
            continue
        if candidate.is_symlink() or not candidate.is_file():
            raise GenerationError(
                f"pending pointer staging is not regular: {candidate}"
            )
        candidate.unlink()
        changed_root = True
    if changed_generations:
        _fsync_directory(generations)
    if changed_root:
        _fsync_directory(root)


def _validate_leaf_name(value: str, context: str) -> str:
    if not value or Path(value).name != value or value in {".", ".."}:
        raise GenerationError(f"{context} must be one plain filename")
    return value


def _validate_relative_path(value: str) -> Path:
    path = Path(value)
    if (
        not value
        or path.is_absolute()
        or ".." in path.parts
        or path.as_posix() in {".", ".."}
    ):
        raise GenerationError(f"invalid required generation path: {value!r}")
    return path


def _validate_generation(
    directory: Path,
    required_files: Iterable[str],
) -> None:
    if directory.is_symlink() or not directory.is_dir():
        raise GenerationError(f"generation must be a regular directory: {directory}")
    for relative_value in required_files:
        relative = _validate_relative_path(relative_value)
        path = directory / relative
        if path.is_symlink() or not path.is_file():
            raise GenerationError(
                f"generation is missing regular file {relative.as_posix()}: "
                f"{directory}"
            )
    for root, directories, filenames in os.walk(directory, followlinks=False):
        root_path = Path(root)
        for name in directories:
            path = root_path / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
                raise GenerationError(
                    f"generation contains unsupported directory entry: {path}"
                )
        for name in filenames:
            path = root_path / name
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
                raise GenerationError(
                    f"generation contains unsupported file entry: {path}"
                )


def _sync_generation(directory: Path) -> None:
    for root, directories, filenames in os.walk(
        directory,
        topdown=False,
        followlinks=False,
    ):
        root_path = Path(root)
        for name in filenames:
            descriptor = os.open(root_path / name, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        for name in directories:
            _fsync_directory(root_path / name)
        _fsync_directory(root_path)


def publish_generation(
    container: Path,
    *,
    pointer_name: str,
    generations_name: str,
    required_files: Iterable[str],
    writer: Callable[[Path], None],
    validate: Callable[[Path], None] | None = None,
) -> Path:
    """Publish one immutable generation and atomically commit its pointer."""

    pointer_name = _validate_leaf_name(pointer_name, "pointer_name")
    generations_name = _validate_leaf_name(
        generations_name,
        "generations_name",
    )
    required = tuple(required_files)
    root = _regular_directory(
        container.expanduser(),
        "generation container",
        create=True,
    )
    generations = root / generations_name
    generations = _regular_directory(
        generations,
        "generations directory",
        create=True,
    )
    generation_id = uuid.uuid4().hex
    pending_generation = (
        generations / f"{PENDING_GENERATION_PREFIX}{generation_id}"
    )
    generation = generations / generation_id
    pointer = root / pointer_name
    lock = root / f"{pointer_name}.lock"
    pointer_staging: Path | None = None
    active_generation: Path | None = None
    committed = False
    with _open_lock(lock) as lock_stream:
        fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX)
        try:
            _recover_pending_generations(
                root,
                generations,
                pointer,
                pointer_name,
            )
            pending_generation.mkdir()
            active_generation = pending_generation
            _fsync_directory(generations)
            marker = pending_generation / PENDING_MARKER
            with marker.open("xb") as stream:
                stream.write(f"{generation_id}\n".encode("ascii"))
                stream.flush()
                os.fsync(stream.fileno())
            _fsync_directory(pending_generation)

            writer(pending_generation)
            _validate_pending_marker(pending_generation, generation_id)
            _validate_generation(pending_generation, required)
            if validate is not None:
                validate(pending_generation)
            _validate_pending_marker(pending_generation, generation_id)
            _validate_generation(pending_generation, required)
            _sync_generation(pending_generation)
            _fsync_directory(generations)

            try:
                os.replace(pending_generation, generation)
            finally:
                if generation.is_dir() and not generation.is_symlink():
                    active_generation = generation
            _fsync_directory(generations)

            descriptor, temporary_name = tempfile.mkstemp(
                prefix=f".{pointer_name}.{generation_id}.",
                suffix=".staging",
                dir=root,
            )
            pointer_staging = Path(temporary_name)
            with os.fdopen(descriptor, "w", encoding="ascii") as stream:
                stream.write(f"{generation_id}\n")
                stream.flush()
                os.fsync(stream.fileno())
            replace_attempted = False
            try:
                replace_attempted = True
                os.replace(pointer_staging, pointer)
                pointer_staging = None
                committed = True
            finally:
                if replace_attempted and not committed:
                    committed = True
                    if _pointer_selection(pointer, generation_id) is False:
                        committed = False
                if committed:
                    _fsync_directory(root)
            if _pointer_selection(pointer, generation_id) is True:
                _clear_pending_marker(generation, generation_id)
                _fsync_directory(generations)
            return generation
        finally:
            if pointer_staging is not None:
                pointer_staging.unlink(missing_ok=True)
                _fsync_directory(root)
            if (
                not committed
                and active_generation is not None
                and active_generation.exists()
            ):
                shutil.rmtree(active_generation)
                _fsync_directory(generations)
            fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN)


def resolve_generation(
    container: Path,
    *,
    pointer_name: str,
    generations_name: str,
    required_files: Iterable[str],
    legacy: Path,
) -> Path:
    """Resolve one committed generation, falling back only when no pointer exists."""

    pointer_name = _validate_leaf_name(pointer_name, "pointer_name")
    generations_name = _validate_leaf_name(
        generations_name,
        "generations_name",
    )
    root = container.expanduser()
    if root.is_symlink():
        raise GenerationError(f"generation container must not be a symlink: {root}")
    pointer = root / pointer_name
    if pointer.is_symlink():
        raise GenerationError(f"generation pointer must be a regular file: {pointer}")
    if not pointer.exists():
        return legacy.expanduser()
    root = _regular_directory(root, "generation container", create=False)
    pointer = root / pointer_name
    if pointer.is_symlink() or not pointer.is_file():
        raise GenerationError(f"generation pointer must be a regular file: {pointer}")
    try:
        generation_id = pointer.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise GenerationError(f"generation pointer is unreadable: {pointer}") from error
    if GENERATION_ID_PATTERN.fullmatch(generation_id) is None:
        raise GenerationError(f"generation pointer is malformed: {pointer}")
    generations = root / generations_name
    if generations.is_symlink() or not generations.is_dir():
        raise GenerationError(
            f"generations directory is unavailable: {generations}"
        )
    generation = generations / generation_id
    _validate_generation(generation, required_files)
    return generation.resolve(strict=True)
