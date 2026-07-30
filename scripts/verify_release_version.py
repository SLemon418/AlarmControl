#!/usr/bin/env python3
"""Verify strict SemVer tag/Gradle parity and monotonic versionName plus versionCode."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


RELEASE_TAG = re.compile(
    r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$"
)
VERSION_FILE = "app/version.json"


class VerificationError(RuntimeError):
    """Raised when release-version history is unsafe."""


@dataclass(frozen=True)
class AppVersion:
    code: int
    name: str


def semantic_version(tag: str) -> tuple[int, int, int]:
    """Return a strict MAJOR.MINOR.PATCH tuple without accepting leading zeroes."""

    match = RELEASE_TAG.fullmatch(tag)
    if match is None:
        raise VerificationError(
            "Release tag must use strict vMAJOR.MINOR.PATCH without leading zeroes"
        )
    return tuple(int(component) for component in match.groups())


def git(repo: Path, *arguments: str) -> str:
    result = subprocess.run(
        ("git", "-C", str(repo), *arguments),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise VerificationError(
            f"git {' '.join(arguments)} failed: {detail or 'unknown error'}"
        )
    return result.stdout


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    result = subprocess.run(
        ("git", "-C", str(repo), "merge-base", "--is-ancestor", ancestor, descendant),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    detail = result.stderr.strip() or result.stdout.strip()
    raise VerificationError(
        f"git merge-base --is-ancestor failed: {detail or 'unknown error'}"
    )


def parse_version(raw: str, source: str) -> AppVersion:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise VerificationError(f"{source} is not valid JSON: {error.msg}") from error
    if not isinstance(value, dict) or set(value) != {"versionCode", "versionName"}:
        raise VerificationError(
            f"{source} must contain only versionCode and versionName"
        )
    code = value["versionCode"]
    name = value["versionName"]
    if isinstance(code, bool) or not isinstance(code, int) or code <= 0:
        raise VerificationError(f"{source} versionCode must be a positive integer")
    if not isinstance(name, str) or RELEASE_TAG.fullmatch(f"v{name}") is None:
        raise VerificationError(
            f"{source} versionName must be strict MAJOR.MINOR.PATCH "
            "without leading zeroes"
        )
    return AppVersion(code=code, name=name)


def version_at_tag(repo: Path, tag: str) -> AppVersion:
    raw = git(repo, "show", f"{tag}^{{commit}}:{VERSION_FILE}")
    version = parse_version(raw, f"{tag}:{VERSION_FILE}")
    if version.name != tag.removeprefix("v"):
        raise VerificationError(f"{tag} does not match its versionName {version.name}")
    return version


def verify(repo: Path, current_tag: str, history_ref: str) -> str:
    current_semantic_version = semantic_version(current_tag)

    current_commit = git(repo, "rev-parse", "--verify", f"{current_tag}^{{commit}}").strip()
    history_commit = git(
        repo, "rev-parse", "--verify", f"{history_ref}^{{commit}}"
    ).strip()
    if not is_ancestor(repo, current_commit, history_commit):
        raise VerificationError(
            f"{current_tag} is not reachable from release history ref {history_ref}"
        )

    current = version_at_tag(repo, current_tag)
    merged_tags = git(repo, "tag", "--merged", history_commit, "--list").splitlines()
    previous_tags = sorted(
        tag
        for tag in merged_tags
        if tag != current_tag and RELEASE_TAG.fullmatch(tag) is not None
    )
    if not previous_tags:
        return (
            f"{current_tag} is the first reachable SemVer release; "
            f"versionName {current.name} and versionCode {current.code} accepted"
        )

    previous_versions = [(tag, version_at_tag(repo, tag)) for tag in previous_tags]
    previous_tag, previous = max(
        previous_versions,
        key=lambda tagged_version: tagged_version[1].code,
    )
    if current.code <= previous.code:
        raise VerificationError(
            f"{current_tag} versionCode {current.code} must be greater than "
            f"{previous_tag} versionCode {previous.code}"
        )
    highest_semantic_tag = max(previous_tags, key=semantic_version)
    if current_semantic_version <= semantic_version(highest_semantic_tag):
        raise VerificationError(
            f"{current_tag} versionName must be greater than prior release "
            f"{highest_semantic_tag}"
        )
    return (
        f"{current_tag} versionName and versionCode {current.code} are greater than all "
        f"{len(previous_versions)} prior SemVer release tag(s); "
        f"previous maximum was {previous.code} at {previous_tag} and "
        f"previous versionName maximum was {highest_semantic_tag}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--tag", required=True)
    parser.add_argument("--history-ref", required=True)
    arguments = parser.parse_args()
    try:
        print(
            verify(
                arguments.repo.resolve(),
                arguments.tag,
                arguments.history_ref,
            )
        )
    except VerificationError as error:
        print(f"Release version verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
