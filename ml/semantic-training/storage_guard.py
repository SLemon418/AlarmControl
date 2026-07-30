#!/usr/bin/env python3
"""Fail-closed storage and CPU launcher guard for local semantic training."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import stat
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

GIB = 1024**3
ROOT_MARKER = ".semantic-training-run-root"
ROOT_MARKER_CONTENT = {
    "kind": "alarmcontrol-semantic-training-run-root",
    "schema_version": 1,
}
DISPOSABLE_MARKER = ".disposable"
PROTECTED_MARKERS = frozenset({".protected", ".storage-guard-protected"})
DISPOSABLE_CONTAINERS = (
    "cache",
    "tmp",
    "failed-conversions",
    "checkpoints",
    "seed-weights",
)
PROTECTED_TOKENS = frozenset(
    {
        "best",
        "last",
        "selected",
        "dataset",
        "datasets",
        "manifest",
        "manifests",
        "evaluation",
        "evaluations",
        "tokenizer",
        "config",
        "license",
        "licenses",
    }
)
CPU_ENV = {
    "OMP_NUM_THREADS": "2",
    "MKL_NUM_THREADS": "2",
    "OPENBLAS_NUM_THREADS": "2",
    "VECLIB_MAXIMUM_THREADS": "2",
    "NUMEXPR_NUM_THREADS": "2",
    "PYTORCH_ENABLE_MPS_FALLBACK": "0",
    "CUDA_VISIBLE_DEVICES": "",
    "MAX_JOBS": "1",
    "STORAGE_GUARD_MAX_SUBPROCESSES": "1",
}
PRE_RUN_STORAGE_FAILURE_EXIT_CODE = 3
POST_RUN_STORAGE_FAILURE_EXIT_CODE = 4


class GuardError(RuntimeError):
    """Base error for a rejected storage-guard operation."""


class MarkerError(GuardError):
    """Raised when the run-root marker is absent or invalid."""


class SafetyError(GuardError):
    """Raised when a path cannot be proven safe."""


@dataclass(frozen=True)
class StoragePolicy:
    """Byte thresholds used by the guard."""

    soft_bytes: int = 80 * GIB
    cleanup_target_bytes: int = 70 * GIB
    hard_bytes: int = 100 * GIB
    min_free_bytes: int = 100 * GIB

    def __post_init__(self) -> None:
        if not 0 <= self.cleanup_target_bytes < self.soft_bytes < self.hard_bytes:
            raise ValueError("expected cleanup_target < soft < hard")
        if self.min_free_bytes < 0:
            raise ValueError("min_free_bytes must be non-negative")


DEFAULT_POLICY = StoragePolicy()


@dataclass(frozen=True)
class CleanupCandidate:
    """One marker-authorized directory eligible for cleanup."""

    path: Path
    relative_path: str
    size_bytes: int
    modified_ns: int

    def as_dict(self) -> dict[str, object]:
        return {
            "path": self.relative_path,
            "size_bytes": self.size_bytes,
            "modified_ns": self.modified_ns,
        }


@dataclass
class _OpenedDirectory:
    """Pinned directory tree used for no-follow deletion."""

    descriptor: int
    name: str
    path: Path
    metadata: os.stat_result
    files: list[tuple[str, os.stat_result]]
    directories: list["_OpenedDirectory"]


def _canonical_root(root: Path | str) -> Path:
    raw = Path(root).expanduser()
    if not raw.exists():
        raise SafetyError(f"run root does not exist: {raw}")
    if raw.is_symlink():
        raise SafetyError(f"run root must not be a symlink: {raw}")
    resolved = raw.resolve(strict=True)
    if not resolved.is_dir():
        raise SafetyError(f"run root is not a directory: {resolved}")
    return resolved


def _assert_within(root: Path, path: Path) -> Path:
    try:
        path.relative_to(root)
    except ValueError as error:
        raise SafetyError(f"path escapes run root: {path}") from error
    return path


def initialize_root(root: Path | str) -> Path:
    """Create an idempotent, exclusive run-root marker."""

    resolved = _canonical_root(root)
    marker = resolved / ROOT_MARKER
    if marker.exists() or marker.is_symlink():
        check_root_marker(resolved)
        return marker

    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(marker, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(ROOT_MARKER_CONTENT, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
    except BaseException:
        marker.unlink(missing_ok=True)
        raise
    return marker


def check_root_marker(root: Path | str) -> Path:
    """Require the exact regular marker before inspecting or deleting."""

    resolved = _canonical_root(root)
    marker = resolved / ROOT_MARKER
    if marker.is_symlink() or not marker.is_file():
        raise MarkerError(f"missing regular run-root marker: {marker}")
    try:
        content = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MarkerError(f"invalid run-root marker: {marker}") from error
    if content != ROOT_MARKER_CONTENT:
        raise MarkerError(f"unexpected run-root marker content: {marker}")
    return marker


def _walk_directory(directory: Path) -> Iterable[tuple[Path, os.stat_result, bool]]:
    """Yield entries without following links; reject every non-regular entry."""

    pending = [directory]
    while pending:
        current = pending.pop()
        try:
            with os.scandir(current) as iterator:
                entries = sorted(iterator, key=lambda entry: entry.name, reverse=True)
        except OSError as error:
            raise SafetyError(f"cannot inspect directory: {current}") from error

        child_directories: list[Path] = []
        for entry in entries:
            path = Path(entry.path)
            try:
                metadata = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise SafetyError(f"cannot inspect path: {path}") from error
            if stat.S_ISLNK(metadata.st_mode):
                raise SafetyError(f"symlink rejected: {path}")
            if stat.S_ISDIR(metadata.st_mode):
                yield path, metadata, True
                child_directories.append(path)
            elif stat.S_ISREG(metadata.st_mode):
                yield path, metadata, False
            else:
                raise SafetyError(f"non-regular path rejected: {path}")
        pending.extend(child_directories)


def tree_bytes(root: Path | str) -> int:
    """Return logical regular-file bytes while failing closed on unsafe entries."""

    resolved = _canonical_root(root)
    return sum(
        metadata.st_size
        for _, metadata, is_directory in _walk_directory(resolved)
        if not is_directory
    )


def _protected_name(name: str) -> bool:
    lowered = name.casefold()
    if lowered in PROTECTED_MARKERS:
        return True
    if lowered.endswith(".tflite"):
        return True
    tokens = set(re.findall(r"[a-z0-9]+", lowered))
    if tokens & PROTECTED_TOKENS:
        return True
    compact = re.sub(r"[^a-z0-9]", "", lowered)
    return "modelcard" in compact


def _contains_protected_item(root: Path, candidate: Path) -> bool:
    relative = _assert_within(root, candidate).relative_to(root)
    if any(_protected_name(part) for part in relative.parts[1:]):
        return True
    return any(_protected_name(path.name) for path, _, _ in _walk_directory(candidate))


def _is_allowed_candidate(root: Path, candidate: Path) -> bool:
    relative = _assert_within(root, candidate).relative_to(root)
    return len(relative.parts) >= 2 and relative.parts[0] in DISPOSABLE_CONTAINERS


def _regular_disposable_marker(candidate: Path) -> bool:
    marker = candidate / DISPOSABLE_MARKER
    if marker.is_symlink():
        raise SafetyError(f"disposable marker must not be a symlink: {marker}")
    return marker.is_file()


def cleanup_candidates(root: Path | str) -> list[CleanupCandidate]:
    """Return marker-authorized candidates in deterministic oldest-first order."""

    resolved = _canonical_root(root)
    check_root_marker(resolved)
    tree_bytes(resolved)

    eligible_paths: list[Path] = []
    for container_name in DISPOSABLE_CONTAINERS:
        container = resolved / container_name
        if not container.exists():
            continue
        if container.is_symlink() or not container.is_dir():
            raise SafetyError(f"invalid disposable container: {container}")
        directories = [
            path
            for path, _, is_directory in _walk_directory(container)
            if is_directory
        ]
        for directory in directories:
            if not _regular_disposable_marker(directory):
                continue
            if _contains_protected_item(resolved, directory):
                continue
            eligible_paths.append(directory)

    outermost_paths: list[Path] = []
    for path in sorted(eligible_paths, key=lambda item: (len(item.parts), item.as_posix())):
        if any(path.is_relative_to(parent) for parent in outermost_paths):
            continue
        outermost_paths.append(path)

    candidates = [
        CleanupCandidate(
            path=path,
            relative_path=path.relative_to(resolved).as_posix(),
            size_bytes=tree_bytes(path),
            modified_ns=path.stat(follow_symlinks=False).st_mtime_ns,
        )
        for path in outermost_paths
    ]
    return sorted(candidates, key=lambda candidate: (candidate.modified_ns, candidate.relative_path))


def plan_cleanup(
    run_bytes: int,
    candidates: Sequence[CleanupCandidate],
    target_bytes: int,
) -> tuple[list[CleanupCandidate], int]:
    """Select oldest candidates until projected usage is strictly below target."""

    projected = run_bytes
    selected: list[CleanupCandidate] = []
    for candidate in candidates:
        if projected < target_bytes:
            break
        selected.append(candidate)
        projected = max(0, projected - candidate.size_bytes)
    return selected, projected


def _directory_open_flags() -> int:
    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    return flags


def _same_entry(
    expected: os.stat_result,
    actual: os.stat_result,
    *,
    directory: bool,
) -> bool:
    expected_kind = stat.S_ISDIR(expected.st_mode) if directory else stat.S_ISREG(expected.st_mode)
    actual_kind = stat.S_ISDIR(actual.st_mode) if directory else stat.S_ISREG(actual.st_mode)
    return (
        expected_kind
        and actual_kind
        and expected.st_dev == actual.st_dev
        and expected.st_ino == actual.st_ino
    )


def _open_directory_at(
    parent_descriptor: int,
    name: str,
    display_path: Path,
    opened_descriptors: list[int],
) -> tuple[int, os.stat_result]:
    try:
        descriptor = os.open(
            name,
            _directory_open_flags(),
            dir_fd=parent_descriptor,
        )
    except OSError as error:
        raise SafetyError(f"cannot safely open directory: {display_path}") from error
    opened_descriptors.append(descriptor)
    metadata = os.fstat(descriptor)
    if not stat.S_ISDIR(metadata.st_mode):
        raise SafetyError(f"path is no longer a directory: {display_path}")
    return descriptor, metadata


def _check_root_marker_at(root_descriptor: int, root: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    marker = root / ROOT_MARKER
    try:
        descriptor = os.open(ROOT_MARKER, flags, dir_fd=root_descriptor)
    except OSError as error:
        raise MarkerError(f"missing regular run-root marker: {marker}") from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise MarkerError(f"missing regular run-root marker: {marker}")
        with os.fdopen(os.dup(descriptor), "r", encoding="utf-8") as stream:
            content = json.load(stream)
    except (OSError, json.JSONDecodeError) as error:
        raise MarkerError(f"invalid run-root marker: {marker}") from error
    finally:
        os.close(descriptor)
    if content != ROOT_MARKER_CONTENT:
        raise MarkerError(f"unexpected run-root marker content: {marker}")


def _snapshot_open_directory(
    descriptor: int,
    name: str,
    path: Path,
    metadata: os.stat_result,
    opened_descriptors: list[int],
) -> _OpenedDirectory:
    """Pin and validate a complete tree before deleting any entry."""

    try:
        with os.scandir(descriptor) as iterator:
            entries = sorted(iterator, key=lambda entry: entry.name)
    except OSError as error:
        raise SafetyError(f"cannot inspect directory: {path}") from error

    files: list[tuple[str, os.stat_result]] = []
    directories: list[_OpenedDirectory] = []
    for entry in entries:
        entry_path = path / entry.name
        if _protected_name(entry.name):
            raise SafetyError(f"protected item rejected: {entry_path}")
        try:
            entry_metadata = entry.stat(follow_symlinks=False)
        except OSError as error:
            raise SafetyError(f"cannot inspect path: {entry_path}") from error
        if stat.S_ISLNK(entry_metadata.st_mode):
            raise SafetyError(f"symlink rejected: {entry_path}")
        if stat.S_ISREG(entry_metadata.st_mode):
            files.append((entry.name, entry_metadata))
            continue
        if not stat.S_ISDIR(entry_metadata.st_mode):
            raise SafetyError(f"non-regular path rejected: {entry_path}")
        child_descriptor, child_metadata = _open_directory_at(
            descriptor,
            entry.name,
            entry_path,
            opened_descriptors,
        )
        if not _same_entry(entry_metadata, child_metadata, directory=True):
            raise SafetyError(f"directory changed while opening: {entry_path}")
        directories.append(
            _snapshot_open_directory(
                child_descriptor,
                entry.name,
                entry_path,
                child_metadata,
                opened_descriptors,
            )
        )
    return _OpenedDirectory(
        descriptor=descriptor,
        name=name,
        path=path,
        metadata=metadata,
        files=files,
        directories=directories,
    )


def _current_entry(
    parent_descriptor: int,
    name: str,
    display_path: Path,
) -> os.stat_result:
    try:
        return os.stat(
            name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
    except OSError as error:
        raise SafetyError(f"path changed before deletion: {display_path}") from error


def _delete_open_directory(tree: _OpenedDirectory) -> int:
    deleted_bytes = 0
    for child in tree.directories:
        deleted_bytes += _delete_open_directory(child)
        current = _current_entry(tree.descriptor, child.name, child.path)
        if not _same_entry(child.metadata, current, directory=True):
            raise SafetyError(f"directory changed before deletion: {child.path}")
        os.rmdir(child.name, dir_fd=tree.descriptor)
    for name, metadata in tree.files:
        path = tree.path / name
        current = _current_entry(tree.descriptor, name, path)
        if not _same_entry(metadata, current, directory=False):
            raise SafetyError(f"path changed before deletion: {path}")
        os.unlink(name, dir_fd=tree.descriptor)
        deleted_bytes += metadata.st_size
    return deleted_bytes


def delete_candidate(root: Path | str, candidate: Path | str) -> int:
    """Delete one revalidated candidate without following links."""

    resolved = _canonical_root(root)
    check_root_marker(resolved)
    raw_candidate = Path(candidate)
    if raw_candidate.is_symlink():
        raise SafetyError(f"candidate must not be a symlink: {raw_candidate}")
    try:
        resolved_candidate = raw_candidate.resolve(strict=True)
    except OSError as error:
        raise SafetyError(f"candidate does not exist: {raw_candidate}") from error
    if not _is_allowed_candidate(resolved, resolved_candidate):
        raise SafetyError(f"candidate is outside disposable containers: {resolved_candidate}")
    if not _regular_disposable_marker(resolved_candidate):
        raise SafetyError(f"candidate lacks {DISPOSABLE_MARKER}: {resolved_candidate}")
    if _contains_protected_item(resolved, resolved_candidate):
        raise SafetyError(f"candidate contains a protected item: {resolved_candidate}")

    relative = resolved_candidate.relative_to(resolved)
    if any(_protected_name(part) for part in relative.parts[1:]):
        raise SafetyError(f"protected candidate path rejected: {resolved_candidate}")

    opened_descriptors: list[int] = []
    try:
        try:
            root_descriptor = os.open(resolved, _directory_open_flags())
        except OSError as error:
            raise SafetyError(f"cannot safely open run root: {resolved}") from error
        opened_descriptors.append(root_descriptor)
        _check_root_marker_at(root_descriptor, resolved)

        parent_descriptor = root_descriptor
        parent_path = resolved
        candidate_descriptor = -1
        candidate_metadata: os.stat_result | None = None
        for part in relative.parts:
            parent_path /= part
            candidate_descriptor, candidate_metadata = _open_directory_at(
                parent_descriptor,
                part,
                parent_path,
                opened_descriptors,
            )
            if part != relative.parts[-1]:
                parent_descriptor = candidate_descriptor

        if candidate_metadata is None:
            raise SafetyError(f"candidate is the run root: {resolved_candidate}")
        tree = _snapshot_open_directory(
            candidate_descriptor,
            relative.parts[-1],
            resolved_candidate,
            candidate_metadata,
            opened_descriptors,
        )
        marker = next(
            (
                metadata
                for name, metadata in tree.files
                if name == DISPOSABLE_MARKER
            ),
            None,
        )
        if marker is None:
            raise SafetyError(
                f"candidate lacks regular {DISPOSABLE_MARKER}: {resolved_candidate}"
            )

        size_bytes = _delete_open_directory(tree)
        current = _current_entry(
            parent_descriptor,
            tree.name,
            resolved_candidate,
        )
        if not _same_entry(tree.metadata, current, directory=True):
            raise SafetyError(
                f"candidate changed before deletion: {resolved_candidate}"
            )
        os.rmdir(tree.name, dir_fd=parent_descriptor)
        return size_bytes
    finally:
        for descriptor in reversed(opened_descriptors):
            try:
                os.close(descriptor)
            except OSError:
                pass


def _hard_reasons(run_bytes: int, free_bytes: int, policy: StoragePolicy) -> list[str]:
    reasons: list[str] = []
    if run_bytes >= policy.hard_bytes:
        reasons.append("run-bytes-at-or-above-hard-limit")
    if free_bytes < policy.min_free_bytes:
        reasons.append("filesystem-free-below-hard-minimum")
    return reasons


def inspect_storage(
    root: Path | str,
    policy: StoragePolicy = DEFAULT_POLICY,
    *,
    free_bytes: int | None = None,
) -> dict[str, object]:
    """Inspect usage and produce a deterministic dry-run cleanup plan."""

    resolved = _canonical_root(root)
    check_root_marker(resolved)
    run_bytes = tree_bytes(resolved)
    available = shutil.disk_usage(resolved).free if free_bytes is None else free_bytes
    if available < 0:
        raise ValueError("free_bytes must be non-negative")

    candidates = cleanup_candidates(resolved)
    cleanup_required = run_bytes >= policy.soft_bytes
    selected: list[CleanupCandidate] = []
    projected = run_bytes
    if cleanup_required:
        selected, projected = plan_cleanup(run_bytes, candidates, policy.cleanup_target_bytes)

    hard_reasons = _hard_reasons(run_bytes, available, policy)
    if hard_reasons:
        status = "hard-stop"
    elif cleanup_required:
        status = "cleanup-required"
    else:
        status = "ready"
    return {
        "root": str(resolved),
        "status": status,
        "run_bytes": run_bytes,
        "free_bytes": available,
        "hard_reasons": hard_reasons,
        "cleanup": {
            "required": cleanup_required,
            "applied": False,
            "target_bytes": policy.cleanup_target_bytes,
            "projected_bytes": projected,
            "candidates": [candidate.as_dict() for candidate in candidates],
            "planned": [candidate.as_dict() for candidate in selected],
            "deleted": [],
        },
        "thresholds": {
            "soft_bytes": policy.soft_bytes,
            "cleanup_target_bytes": policy.cleanup_target_bytes,
            "hard_bytes": policy.hard_bytes,
            "min_free_bytes": policy.min_free_bytes,
        },
    }


def enforce_storage(
    root: Path | str,
    policy: StoragePolicy = DEFAULT_POLICY,
    *,
    apply: bool = False,
    free_bytes: int | None = None,
) -> dict[str, object]:
    """Inspect storage and optionally apply only the planned safe deletions."""

    report = inspect_storage(root, policy, free_bytes=free_bytes)
    cleanup = report["cleanup"]
    if not isinstance(cleanup, dict):
        raise AssertionError("cleanup report must be a dictionary")
    if not apply or not cleanup["required"]:
        return report

    resolved = Path(str(report["root"]))
    planned_paths = [
        resolved / str(item["path"])
        for item in cleanup["planned"]
        if isinstance(item, dict)
    ]
    deleted: list[dict[str, object]] = []
    released_bytes = 0
    for path in planned_paths:
        size_bytes = delete_candidate(resolved, path)
        released_bytes += size_bytes
        deleted.append({"path": path.relative_to(resolved).as_posix(), "size_bytes": size_bytes})

    final_run_bytes = tree_bytes(resolved)
    final_free_bytes = (
        shutil.disk_usage(resolved).free
        if free_bytes is None
        else free_bytes + released_bytes
    )
    final_reasons = _hard_reasons(final_run_bytes, final_free_bytes, policy)
    target_reached = final_run_bytes < policy.cleanup_target_bytes
    report["run_bytes"] = final_run_bytes
    report["free_bytes"] = final_free_bytes
    report["hard_reasons"] = final_reasons
    cleanup["applied"] = True
    cleanup["deleted"] = deleted
    cleanup["projected_bytes"] = final_run_bytes
    if final_reasons:
        report["status"] = "hard-stop"
    elif not target_reached:
        report["status"] = "cleanup-incomplete"
    else:
        report["status"] = "ready"
    return report


def launch_environment(base: Mapping[str, str] | None = None) -> dict[str, str]:
    """Return a CPU-only environment with one advertised worker process."""

    environment = dict(os.environ if base is None else base)
    environment.update(CPU_ENV)
    return environment


def launch_guarded(
    root: Path | str,
    command: Sequence[str],
    policy: StoragePolicy = DEFAULT_POLICY,
    *,
    apply: bool = False,
    dry_run: bool = False,
    free_bytes: int | None = None,
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
) -> tuple[int, dict[str, object]]:
    """Run one direct child and return the storage state enforced after it exits."""

    if not command:
        raise GuardError("launch requires a command")
    report = enforce_storage(root, policy, apply=apply, free_bytes=free_bytes)
    if dry_run:
        return 0, report
    if report["status"] != "ready":
        return PRE_RUN_STORAGE_FAILURE_EXIT_CODE, report
    completed = runner(
        list(command),
        cwd=str(report["root"]),
        env=launch_environment(),
        check=False,
    )
    child_exit_code = int(completed.returncode)
    post_run_report = enforce_storage(root, policy, apply=apply, free_bytes=free_bytes)
    if child_exit_code != 0:
        return child_exit_code, post_run_report
    if post_run_report["status"] != "ready":
        return POST_RUN_STORAGE_FAILURE_EXIT_CODE, post_run_report
    return 0, post_run_report


def _print_report(report: Mapping[str, object]) -> None:
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def _cleanup_mode(parser: argparse.ArgumentParser) -> None:
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--dry-run", action="store_true", help="report only; never delete or launch")
    group.add_argument("--apply", action="store_true", help="apply marker-authorized cleanup")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="marked training run root")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    subparsers.add_parser("init", help="initialize or validate the run-root marker")
    subparsers.add_parser("report", help="report run bytes, free bytes, and policy status")

    check_parser = subparsers.add_parser("check", help="check policy and optionally clean")
    _cleanup_mode(check_parser)

    launch_parser = subparsers.add_parser("launch", help="guard and run one CPU-only subprocess")
    _cleanup_mode(launch_parser)
    launch_parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.subcommand == "init":
            marker = initialize_root(args.root)
            report = inspect_storage(args.root)
            report["marker"] = str(marker)
            _print_report(report)
            return 0
        if args.subcommand == "report":
            _print_report(inspect_storage(args.root))
            return 0
        if args.subcommand == "check":
            report = enforce_storage(args.root, apply=args.apply)
            _print_report(report)
            return 0 if report["status"] == "ready" else PRE_RUN_STORAGE_FAILURE_EXIT_CODE
        if args.subcommand == "launch":
            command = list(args.command)
            if command and command[0] == "--":
                command = command[1:]
            code, report = launch_guarded(
                args.root,
                command,
                apply=args.apply,
                dry_run=args.dry_run,
            )
            _print_report(report)
            return code
        raise AssertionError(f"unknown subcommand: {args.subcommand}")
    except GuardError as error:
        print(f"storage_guard: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
