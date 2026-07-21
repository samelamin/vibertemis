#!/usr/bin/env python3

import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse


APP_ID = "com.artemisdesktop.ArtemisDesktopDev"
REQUIRED_FINISH_ARGS = (
    "--filesystem=xdg-run/gamescope-0",
    "--device=all",
    "--unset-env=LIBVA_DRIVER_NAME",
    "--unset-env=LIBVA_DRIVERS_PATH",
)
REQUIRED_FFMPEG_OPTIONS = (
    "--enable-decoder=h264",
    "--enable-decoder=hevc",
    "--enable-decoder=av1",
    "--enable-hwaccel=h264_vaapi",
    "--enable-hwaccel=h264_vulkan",
    "--enable-hwaccel=hevc_vaapi",
    "--enable-hwaccel=hevc_vulkan",
    "--enable-hwaccel=av1_vaapi",
    "--enable-hwaccel=av1_vulkan",
)
LIBPLACEBO_PATCH = "libplacebo-disable-internally-synchronized-queues.patch"
COMMIT_PATTERN = re.compile(r"[0-9a-fA-F]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
SCP_STYLE_WITH_USER_PATTERN = re.compile(r"[^@\s/:]+@[^:\s/]+:.+")
SCP_STYLE_DOTTED_HOST_PATTERN = re.compile(
    r"(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,63}:"
    r"(?:[^\\\s:]+/[^\\\s]+|[^\\\s:]+\.git)"
)


def walk_modules(modules):
    for module in modules if isinstance(modules, list) else ():
        if not isinstance(module, dict):
            continue
        yield module
        yield from walk_modules(module.get("modules", []))


def has_key(value, key):
    if isinstance(value, dict):
        return key in value or any(has_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(has_key(child, key) for child in value)
    return False


def is_network_url(url):
    return isinstance(url, str) and (
        urlparse(url).scheme in {"ftp", "ftps", "git", "http", "https", "ssh"}
        or SCP_STYLE_WITH_USER_PATTERN.fullmatch(url) is not None
        or SCP_STYLE_DOTTED_HOST_PATTERN.fullmatch(url) is not None
    )


def validate_network_sources(modules):
    errors = []
    for module in modules:
        for source in module.get("sources", []):
            if not isinstance(source, dict):
                continue
            url = source.get("url")
            if not is_network_url(url):
                continue
            if source.get("type") == "git":
                if COMMIT_PATTERN.fullmatch(str(source.get("commit", ""))) is None:
                    errors.append(
                        f"network git source {url} must use a pinned 40-character commit"
                    )
            elif SHA256_PATTERN.fullmatch(str(source.get("sha256", ""))) is None:
                errors.append(f"network source {url} must use a pinned SHA-256")
    return errors


def validate_manifest(manifest):
    errors = []
    if manifest.get("app-id") != APP_ID:
        errors.append(f"app-id must be {APP_ID}")
    if manifest.get("runtime") != "org.kde.Platform":
        errors.append("runtime must be org.kde.Platform")
    if str(manifest.get("runtime-version", "")) != "6.10":
        errors.append("runtime-version must be 6.10")

    finish_args = manifest.get("finish-args", [])
    for argument in REQUIRED_FINISH_ARGS:
        if argument not in finish_args:
            errors.append(f"finish-args must include {argument}")

    if has_key(manifest, "add-extensions"):
        errors.append("custom add-extensions are not allowed")

    modules = list(walk_modules(manifest.get("modules", [])))
    errors.extend(validate_network_sources(modules))

    ffmpeg_options = {
        option
        for module in modules
        if str(module.get("name", "")).lower() == "ffmpeg"
        for option in module.get("config-opts", [])
    }
    for option in REQUIRED_FFMPEG_OPTIONS:
        if option not in ffmpeg_options:
            errors.append(f"FFmpeg must include {option}")

    artemis_modules = [
        module
        for module in modules
        if module.get("name") == "artemis"
    ]
    if not any(
        source.get("type") == "dir" and source.get("path") == "../.."
        for module in artemis_modules
        for source in module.get("sources", [])
        if isinstance(source, dict)
    ):
        errors.append(
            "Artemis module must include a local source with type=dir and path=../.."
        )

    libplacebo_modules = [
        module
        for module in modules
        if str(module.get("name", "")).lower() == "libplacebo"
    ]
    if not any(
        source.get("type") == "patch"
        and source.get("path") == LIBPLACEBO_PATCH
        for module in libplacebo_modules
        for source in module.get("sources", [])
        if isinstance(source, dict)
    ):
        errors.append(f"libplacebo module must include {LIBPLACEBO_PATCH}")

    return errors


def load_manifest(path):
    try:
        with path.open(encoding="utf-8") as manifest_file:
            manifest = json.load(manifest_file)
    except FileNotFoundError:
        print(f"ERROR: manifest not found: {path}", file=sys.stderr)
        return None
    except (OSError, json.JSONDecodeError) as error:
        print(f"ERROR: cannot load manifest {path}: {error}", file=sys.stderr)
        return None
    if not isinstance(manifest, dict):
        print(f"ERROR: manifest root must be a JSON object: {path}", file=sys.stderr)
        return None
    return manifest


def main(arguments):
    if len(arguments) != 1:
        print(f"usage: {Path(sys.argv[0]).name} MANIFEST.json", file=sys.stderr)
        return 2

    manifest_path = Path(arguments[0])
    manifest = load_manifest(manifest_path)
    if manifest is None:
        return 2

    errors = validate_manifest(manifest)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"manifest contract satisfied: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
