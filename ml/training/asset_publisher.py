"""Failure-safe publisher for the legacy classifier's coupled assets."""

import fcntl
import os
import shutil
import stat
import uuid
from contextlib import contextmanager
from pathlib import Path

CURRENT_POINTER = "classifier_current.txt"
GENERATIONS_DIRECTORY = "classifier_generations"
GENERATION_ID_LENGTH = 32
PENDING_GENERATION_PREFIX = ".pending-"
PENDING_MARKER = ".publication-pending"


def publish_asset_set(directory, payloads, validate):
    """Publish one immutable generation selected by a single atomic pointer replacement."""
    destination = _regular_directory(
        Path(directory),
        "asset destination",
        create=True,
    )
    _validate_payloads(payloads)
    lock_path = destination.parent / f".{destination.name}.classifier-publisher.lock"
    with _exclusive_lock(lock_path):
        generation_id = uuid.uuid4().hex
        generations = _regular_directory(
            destination / GENERATIONS_DIRECTORY,
            "asset generations",
            create=True,
        )
        pointer = destination / CURRENT_POINTER
        _recover_pending_generations(destination, generations, pointer)
        pending_generation = (
            generations / f"{PENDING_GENERATION_PREFIX}{generation_id}"
        )
        generation = generations / generation_id
        pending_generation.mkdir()
        _fsync_directory(generations)
        _write_synced(
            pending_generation / PENDING_MARKER,
            f"{generation_id}\n".encode("ascii"),
        )
        _fsync_directory(pending_generation)
        active_generation = pending_generation
        staged = {name: pending_generation / name for name in payloads}
        pointer_staging = destination / f".{CURRENT_POINTER}.{generation_id}.staging"
        committed = False
        try:
            for name, payload in payloads.items():
                _write_synced(staged[name], payload)
            _validate_pending_marker(pending_generation, generation_id)
            _validate_generation(pending_generation, payloads)
            _fsync_directory(pending_generation)
            validate(dict(staged))
            _validate_pending_marker(pending_generation, generation_id)
            _validate_generation(pending_generation, payloads)
            for path in staged.values():
                _fsync_file(path)
            _fsync_directory(pending_generation)
            _fsync_directory(generations)

            try:
                os.replace(pending_generation, generation)
            finally:
                if generation.is_dir() and not generation.is_symlink():
                    active_generation = generation
            _fsync_directory(generations)
            _write_synced(pointer_staging, f"{generation_id}\n".encode("ascii"))
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
                    _fsync_directory(destination)
            if _pointer_selection(pointer, generation_id) is True:
                _clear_pending_marker(generation, generation_id)
                _fsync_directory(generations)
        finally:
            if pointer_staging is not None:
                _remove_all((pointer_staging,))
                _fsync_directory(destination)
            if not committed:
                shutil.rmtree(active_generation, ignore_errors=True)
                _fsync_directory(generations)


def resolve_committed_asset_set(directory, asset_names):
    """Resolve the committed generation, falling back to the pre-generation root contract."""
    destination = Path(directory)
    names = tuple(asset_names)
    _validate_names(names)
    if destination.is_symlink():
        raise ValueError(f"asset destination must not be a symlink: {destination}")
    pointer = destination / CURRENT_POINTER
    if pointer.is_symlink():
        return {name: destination / name for name in names}
    try:
        generation_id = pointer.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError):
        return {name: destination / name for name in names}
    if not _is_generation_id(generation_id):
        return {name: destination / name for name in names}
    generations = destination / GENERATIONS_DIRECTORY
    if generations.is_symlink() or not generations.is_dir():
        return {name: destination / name for name in names}
    generation = generations / generation_id
    if generation.is_symlink() or not generation.is_dir():
        return {name: destination / name for name in names}
    resolved = {name: generation / name for name in names}
    if all(path.is_file() and not path.is_symlink() for path in resolved.values()):
        return resolved
    return {name: destination / name for name in names}


def _validate_payloads(payloads):
    if not payloads:
        raise ValueError("at least one asset is required")
    _validate_names(payloads)
    for name, payload in payloads.items():
        if not isinstance(payload, bytes):
            raise TypeError(f"asset payload must be bytes: {name}")


def _validate_names(names):
    for name in names:
        if not name or Path(name).name != name:
            raise ValueError(f"asset name must be a plain filename: {name!r}")


def _regular_directory(path, context, *, create):
    if path.is_symlink():
        raise ValueError(f"{context} must not be a symlink: {path}")
    if create:
        path.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or not path.is_dir():
        raise ValueError(f"{context} must be a regular directory: {path}")
    return path


def _validate_generation(directory, payloads):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError(f"asset generation must be a regular directory: {directory}")
    entries = tuple(directory.iterdir())
    if {path.name for path in entries} != set(payloads) | {PENDING_MARKER}:
        raise ValueError("asset generation entries changed during validation")
    for path in entries:
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"asset generation entry must be regular: {path}")


def _pending_generation_id(name):
    if not name.startswith(PENDING_GENERATION_PREFIX):
        return None
    generation_id = name[len(PENDING_GENERATION_PREFIX) :]
    return generation_id if _is_generation_id(generation_id) else None


def _validate_pending_marker(directory, generation_id):
    marker = directory / PENDING_MARKER
    if marker.is_symlink() or not marker.is_file():
        raise ValueError(f"pending asset marker is invalid: {marker}")
    try:
        selected = marker.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise ValueError(f"pending asset marker is unreadable: {marker}") from error
    if selected != generation_id:
        raise ValueError(f"pending asset marker does not match: {marker}")
    return marker


def _clear_pending_marker(directory, generation_id):
    marker = _validate_pending_marker(directory, generation_id)
    marker.unlink()
    _fsync_directory(directory)


def _recover_pending_generations(destination, generations, pointer):
    owned_ids = set()
    changed_generations = False
    for candidate in tuple(generations.iterdir()):
        pending_id = _pending_generation_id(candidate.name)
        if pending_id is not None:
            if candidate.is_symlink() or not candidate.is_dir():
                raise ValueError(
                    f"pending asset generation is not regular: {candidate}"
                )
            shutil.rmtree(candidate)
            owned_ids.add(pending_id)
            changed_generations = True
            continue
        if not _is_generation_id(candidate.name):
            continue
        if candidate.is_symlink() or not candidate.is_dir():
            continue
        marker = candidate / PENDING_MARKER
        if not marker.is_symlink() and not marker.exists():
            continue
        _validate_pending_marker(candidate, candidate.name)
        selection = _pointer_selection(pointer, candidate.name)
        if selection is None:
            raise ValueError(
                f"cannot safely recover pending asset generation: {candidate}"
            )
        owned_ids.add(candidate.name)
        if selection:
            _clear_pending_marker(candidate, candidate.name)
        else:
            shutil.rmtree(candidate)
        changed_generations = True

    changed_destination = False
    for generation_id in owned_ids:
        staging = destination / f".{CURRENT_POINTER}.{generation_id}.staging"
        if not staging.is_symlink() and not staging.exists():
            continue
        if staging.is_symlink() or not staging.is_file():
            raise ValueError(f"pending asset pointer staging is invalid: {staging}")
        staging.unlink()
        changed_destination = True
    if changed_generations:
        _fsync_directory(generations)
    if changed_destination:
        _fsync_directory(destination)


def _write_synced(path, payload):
    with path.open("xb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())


def _fsync_file(path):
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"asset generation entry must be regular: {path}")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _remove_all(paths):
    for path in paths:
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def _is_generation_id(value):
    return (
        len(value) == GENERATION_ID_LENGTH
        and all(character in "0123456789abcdef" for character in value)
    )


def _pointer_selection(path, generation_id):
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


@contextmanager
def _exclusive_lock(path):
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError(f"publisher lock must be a regular file: {path}")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        os.close(descriptor)
        raise ValueError(f"publisher lock must be a regular file: {path}")
    with os.fdopen(descriptor, "a+b") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _fsync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
