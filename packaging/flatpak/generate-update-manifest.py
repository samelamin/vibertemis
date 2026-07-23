#!/usr/bin/env python3
"""Generate the exact rolling Steam Deck update manifest."""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys
import tempfile


UINT64_MAX = (1 << 64) - 1
LOWERCASE_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
LOWERCASE_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
CANONICAL_DECIMAL = re.compile(r"[1-9][0-9]*\Z")
UTC_RFC3339 = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z")


def positive_uint64(value: str, field: str) -> str:
    if not CANONICAL_DECIMAL.fullmatch(value):
        raise ValueError(f"{field} must be a canonical positive decimal integer")
    if int(value) > UINT64_MAX:
        raise ValueError(f"{field} exceeds uint64")
    return value


def utc_rfc3339(value: str) -> str:
    if not UTC_RFC3339.fullmatch(value):
        raise ValueError("published_at must be UTC RFC3339 with whole seconds")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ValueError("published_at is not a valid UTC date and time") from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
        raise ValueError("published_at is not canonical UTC RFC3339")
    return value


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--build-sequence", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--flatpak-asset-id", required=True)
    parser.add_argument("--flatpak-size", required=True)
    parser.add_argument("--flatpak-sha256", required=True)
    parser.add_argument("--published-at", required=True)
    return parser.parse_args()


def main() -> int:
    options = arguments()
    if not LOWERCASE_COMMIT.fullmatch(options.source_commit):
        raise ValueError("source_commit must be a full lowercase Git commit")
    if not LOWERCASE_SHA256.fullmatch(options.flatpak_sha256):
        raise ValueError("flatpak_sha256 must be 64 lowercase hexadecimal characters")

    build_sequence = positive_uint64(options.build_sequence, "build_sequence")
    release_id = positive_uint64(options.release_id, "release_id")
    asset_id = positive_uint64(options.flatpak_asset_id, "flatpak_asset_id")
    flatpak_size = positive_uint64(options.flatpak_size, "flatpak_size")
    published_at = utc_rfc3339(options.published_at)

    manifest = {
        "application_id": "com.artemisdesktop.ArtemisDesktopDev",
        "build_sequence": build_sequence,
        "flatpak": {
            "asset_id": asset_id,
            "name": "artemis-steam-deck.flatpak",
            "sha256": options.flatpak_sha256,
            "size": flatpak_size,
        },
        "published_at": published_at,
        "release_id": release_id,
        "repository": "samelamin/vibertemis",
        "schema": 1,
        "source_commit": options.source_commit,
        "tag": "steam-deck-latest",
        "tag_commit": options.source_commit,
    }
    encoded = json.dumps(
        manifest,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ) + "\n"

    output = Path(options.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            dir=output.parent,
            encoding="utf-8",
            newline="\n",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(encoded)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, output)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
