#!/usr/bin/env python3

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile


MANIFEST_NAME = "com.artemisdesktop.ArtemisDesktopDev.json"
CI_MANIFEST_NAME = "com.artemisdesktop.ArtemisDesktopDev.ci.json"
APPLICATION_ID = "com.artemisdesktop.ArtemisDesktopDev"
UINT64_MAX = 18446744073709551615
IDENTITY_KEYS = (
    "VIBERTEMIS_BUILD_COMMIT",
    "VIBERTEMIS_UPDATE_CHANNEL",
    "VIBERTEMIS_BUILD_SEQUENCE",
    "VIBERTEMIS_APPLICATION_ID",
)
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
SEQUENCE_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)")
QMAKE_ASSIGNMENT_PATTERN = re.compile(
    r"^\s*(?P<key>[A-Za-z_][A-Za-z0-9_]*)\s*(?:\+=|-=|\*=|~=|=)"
)


def fail(message):
    raise ValueError(message)


def parse_arguments(arguments):
    parser = argparse.ArgumentParser(
        description="Create an immutable CI Flatpak manifest with build identity."
    )
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument(
        "--channel",
        required=True,
        choices=("rolling", "stable", "none"),
    )
    parser.add_argument("--sequence", required=True)
    parser.add_argument("--application-id", required=True)
    return parser.parse_args(arguments)


def walk_modules(modules):
    for module in modules if isinstance(modules, list) else ():
        if not isinstance(module, dict):
            continue
        yield module
        yield from walk_modules(module.get("modules", []))


def walk_config_options(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "config-opts" and isinstance(child, list):
                yield from child
            else:
                yield from walk_config_options(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_config_options(child)


def validate_paths(input_argument, output_argument):
    input_path = Path(input_argument)
    output_path = Path(output_argument)
    if input_path.name != MANIFEST_NAME:
        fail(f"--input must name {MANIFEST_NAME}")
    if output_path.name != CI_MANIFEST_NAME:
        fail(f"--output must name {CI_MANIFEST_NAME}")

    input_path = input_path.resolve(strict=True)
    output_parent = output_path.parent.resolve(strict=True)
    if input_path.parent != output_parent or output_parent.name != "flatpak":
        fail("--output must remain beside --input in the flatpak directory")
    output_path = output_parent / output_path.name
    if output_path == input_path:
        fail("--output must differ from --input")
    return input_path, output_path


def validate_identity(arguments):
    if COMMIT_PATTERN.fullmatch(arguments.commit) is None:
        fail("--commit must be exactly 40 lowercase hexadecimal characters")
    if SEQUENCE_PATTERN.fullmatch(arguments.sequence) is None:
        fail("--sequence must be a canonical non-negative decimal integer")
    if arguments.application_id != APPLICATION_ID:
        fail(f"--application-id must be exactly {APPLICATION_ID}")

    sequence = int(arguments.sequence, 10)
    if sequence > UINT64_MAX:
        fail(f"--sequence must not exceed {UINT64_MAX}")
    if arguments.channel == "rolling" and sequence <= 0:
        fail("rolling channel requires a positive sequence")
    if arguments.channel != "rolling" and sequence != 0:
        fail("stable and none channels require sequence zero")


def prepare_manifest(manifest, arguments):
    if not isinstance(manifest, dict):
        fail("manifest root must be a JSON object")
    artemis_modules = [
        module
        for module in walk_modules(manifest.get("modules", []))
        if module.get("name") == "artemis"
    ]
    if len(artemis_modules) != 1:
        fail("manifest must contain exactly one artemis module")

    artemis = artemis_modules[0]
    if artemis.get("buildsystem") != "qmake":
        fail("artemis module must use the qmake buildsystem")
    options = artemis.get("config-opts")
    if not isinstance(options, list) or not all(
        isinstance(option, str) for option in options
    ):
        fail("artemis config-opts must be a list of strings")
    for option in walk_config_options(artemis):
        if not isinstance(option, str):
            fail("artemis config-opts must contain only strings")
        assignment = QMAKE_ASSIGNMENT_PATTERN.match(option)
        if assignment is not None and assignment.group("key") in IDENTITY_KEYS:
            fail(
                "duplicate build identity option: "
                f"{assignment.group('key')}"
            )

    options.extend(
        (
            f"VIBERTEMIS_BUILD_COMMIT={arguments.commit}",
            f"VIBERTEMIS_UPDATE_CHANNEL={arguments.channel}",
            f"VIBERTEMIS_BUILD_SEQUENCE={arguments.sequence}",
            f"VIBERTEMIS_APPLICATION_ID={arguments.application_id}",
        )
    )
    return manifest


def write_manifest(output_path, manifest):
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output_path.name}.",
        dir=str(output_path.parent),
        text=True,
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output_file:
            json.dump(manifest, output_file, indent=4)
            output_file.write("\n")
            output_file.flush()
            os.fsync(output_file.fileno())
        os.replace(str(temporary_path), str(output_path))
    finally:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass


def main(arguments):
    try:
        parsed = parse_arguments(arguments)
        input_path, output_path = validate_paths(parsed.input, parsed.output)
        validate_identity(parsed)
        with input_path.open(encoding="utf-8") as input_file:
            manifest = json.load(input_file)
        write_manifest(output_path, prepare_manifest(manifest, parsed))
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
