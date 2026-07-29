#!/usr/bin/env python3
"""Validate and package universal and ABI-specific release APKs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


APPLICATION_ID = "com.alarmcontrol"
VARIANT_NAME = "release"
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
OUTPUT_LABELS = ("universal", *SUPPORTED_ABIS)
VERSION_NAME = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


class PackagingError(RuntimeError):
    """Raised when release APK metadata or artifacts are unsafe."""


def _load_metadata(metadata_path: Path) -> dict[str, Any]:
    if not metadata_path.is_file():
        raise PackagingError(f"Metadata is missing or is not a file: {metadata_path}")
    try:
        value = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as error:
        raise PackagingError(f"Could not read metadata: {error}") from error
    except json.JSONDecodeError as error:
        raise PackagingError(f"Metadata is not valid JSON: {error.msg}") from error
    if not isinstance(value, dict):
        raise PackagingError("Metadata root must be a JSON object")
    return value


def _validate_output_file(value: object) -> str:
    if not isinstance(value, str) or not value:
        raise PackagingError("Every metadata element must name an outputFile")
    if (
        value in {".", ".."}
        or "/" in value
        or "\\" in value
        or Path(value).name != value
        or Path(value).suffix != ".apk"
    ):
        raise PackagingError(
            f"outputFile must be a basename ending in .apk: {value!r}"
        )
    return value


def _label_for_filters(filters: object) -> str:
    if not isinstance(filters, list):
        raise PackagingError("Every metadata element must contain a filters list")
    if not filters:
        return "universal"
    if len(filters) != 1:
        raise PackagingError(
            "A split APK must contain exactly one ABI filter and no other filters"
        )
    abi_filter = filters[0]
    if not isinstance(abi_filter, dict):
        raise PackagingError("APK filters must be JSON objects")
    if abi_filter.get("filterType") != "ABI":
        raise PackagingError("A split APK may contain only one ABI filter")
    abi = abi_filter.get("value")
    if not isinstance(abi, str) or abi not in SUPPORTED_ABIS:
        raise PackagingError(f"Unsupported ABI filter: {abi!r}")
    return abi


def _validated_sources(
    metadata_path: Path,
    version: str,
) -> dict[str, Path]:
    metadata = _load_metadata(metadata_path)
    if metadata.get("applicationId") != APPLICATION_ID:
        raise PackagingError(
            f"applicationId must be {APPLICATION_ID}, "
            f"not {metadata.get('applicationId')!r}"
        )
    if metadata.get("variantName") != VARIANT_NAME:
        raise PackagingError(
            f"variantName must be {VARIANT_NAME}, "
            f"not {metadata.get('variantName')!r}"
        )

    elements = metadata.get("elements")
    if not isinstance(elements, list):
        raise PackagingError("Metadata elements must be a JSON list")
    if len(elements) != len(OUTPUT_LABELS):
        raise PackagingError(
            f"Metadata must contain exactly {len(OUTPUT_LABELS)} APK outputs, "
            f"not {len(elements)}"
        )

    metadata_directory = metadata_path.parent.resolve()
    sources: dict[str, Path] = {}
    source_names: set[str] = set()
    for element in elements:
        if not isinstance(element, dict):
            raise PackagingError("Every metadata element must be a JSON object")
        if element.get("versionName") != version:
            raise PackagingError(
                f"Every APK versionName must be {version}, "
                f"not {element.get('versionName')!r}"
            )

        label = _label_for_filters(element.get("filters"))
        if label in sources:
            raise PackagingError(f"Duplicate APK output for {label}")

        output_file = _validate_output_file(element.get("outputFile"))
        if output_file in source_names:
            raise PackagingError(f"Duplicate outputFile in metadata: {output_file}")
        source_names.add(output_file)

        source = metadata_path.parent / output_file
        if source.is_symlink():
            raise PackagingError(f"APK output must not be a symbolic link: {source}")
        if not source.is_file():
            raise PackagingError(f"APK output is missing or is not a file: {source}")
        try:
            resolved_source = source.resolve(strict=True)
        except OSError as error:
            raise PackagingError(f"Could not resolve APK output {source}: {error}") from error
        if resolved_source.parent != metadata_directory:
            raise PackagingError(f"APK output escapes the metadata directory: {source}")
        sources[label] = resolved_source

    missing = set(OUTPUT_LABELS) - sources.keys()
    if missing:
        raise PackagingError(
            "Metadata is missing APK output(s): " + ", ".join(sorted(missing))
        )
    return sources


def _prepare_output_directory(output_directory: Path) -> bool:
    if output_directory.is_symlink():
        raise PackagingError(
            f"Output directory must not be a symbolic link: {output_directory}"
        )
    if output_directory.exists():
        if not output_directory.is_dir():
            raise PackagingError(
                f"Output path exists and is not a directory: {output_directory}"
            )
        if next(output_directory.iterdir(), None) is not None:
            raise PackagingError(
                f"Output directory must be empty: {output_directory}"
            )
        return False
    try:
        output_directory.mkdir(parents=True)
    except OSError as error:
        raise PackagingError(f"Could not create output directory: {error}") from error
    return True


def _copy_with_checksum(source: Path, target: Path) -> Path:
    digest = hashlib.sha256()
    checksum = target.with_name(f"{target.name}.sha256")
    target_created = False
    checksum_created = False
    try:
        with source.open("rb") as source_stream, target.open("xb") as target_stream:
            target_created = True
            while chunk := source_stream.read(1024 * 1024):
                target_stream.write(chunk)
                digest.update(chunk)
        with checksum.open("x", encoding="utf-8", errors="strict") as checksum_stream:
            checksum_created = True
            checksum_stream.write(f"{digest.hexdigest()}  {target.name}\n")
    except OSError as error:
        if checksum_created:
            checksum.unlink(missing_ok=True)
        if target_created:
            target.unlink(missing_ok=True)
        raise PackagingError(f"Could not package {source.name}: {error}") from error
    return checksum


def package_release_apks(
    metadata_path: Path,
    version: str,
    output_directory: Path,
) -> tuple[Path, ...]:
    """Validate AGP metadata and produce named APKs plus SHA-256 sidecars."""

    if VERSION_NAME.fullmatch(version) is None:
        raise PackagingError("Version must use the MAJOR.MINOR.PATCH format")

    metadata_path = metadata_path.resolve()
    sources = _validated_sources(metadata_path, version)
    output_directory = output_directory.absolute()
    created_directory = _prepare_output_directory(output_directory)

    created_paths: list[Path] = []
    packaged_apks: list[Path] = []
    try:
        for label in OUTPUT_LABELS:
            target = output_directory / f"AlarmControl-{version}-{label}.apk"
            checksum = _copy_with_checksum(sources[label], target)
            created_paths.extend((target, checksum))
            packaged_apks.append(target)
    except PackagingError:
        for path in reversed(created_paths):
            path.unlink(missing_ok=True)
        if created_directory:
            try:
                output_directory.rmdir()
            except OSError:
                pass
        raise
    return tuple(packaged_apks)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Package verified universal and ABI-specific AlarmControl APKs"
    )
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    arguments = parser.parse_args()

    try:
        packaged_apks = package_release_apks(
            arguments.metadata,
            arguments.version,
            arguments.output_dir,
        )
    except PackagingError as error:
        print(f"Release APK packaging failed: {error}", file=sys.stderr)
        return 1

    for apk in packaged_apks:
        print(apk)
        print(apk.with_name(f"{apk.name}.sha256"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
