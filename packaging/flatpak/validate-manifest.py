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
REQUIRED_SDL_MODULES = ("SDL3", "SDL2-compat", "SDL2_ttf")
FLATPAK_CMAKE_LIBDIR = "-DCMAKE_INSTALL_LIBDIR=lib"
COMMIT_PATTERN = re.compile(r"[0-9a-fA-F]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
SCP_STYLE_WITH_USER_PATTERN = re.compile(r"[^@\s/:]+@[^:\s/]+:.+")
SCP_STYLE_ALIAS_PATTERN = re.compile(r"[A-Za-z0-9._-]+:[^\\\s:]+")
WINDOWS_DRIVE_PATH_PATTERN = re.compile(r"[A-Za-z]:.*")


def walk_modules(modules):
    for module in modules if isinstance(modules, list) else ():
        if not isinstance(module, dict):
            continue
        if module.get("disabled") is True:
            continue
        yield module
        yield from walk_modules(module.get("modules", []))


def active_sources(module):
    for source in module.get("sources", []):
        if isinstance(source, dict) and source.get("disabled") is not True:
            yield source


def validate_string_includes(modules):
    errors = []
    for module in modules if isinstance(modules, list) else ():
        if isinstance(module, str):
            errors.append(f"string module includes are not supported: {module}")
            continue
        if not isinstance(module, dict):
            continue
        module_name = module.get("name", "<unnamed>")
        for source in module.get("sources", []):
            if isinstance(source, str):
                errors.append(
                    "string source includes are not supported in module "
                    f"{module_name}: {source}"
                )
        errors.extend(validate_string_includes(module.get("modules", [])))
    return errors


def has_key(value, key):
    if isinstance(value, dict):
        return key in value or any(has_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(has_key(child, key) for child in value)
    return False


def module_config_options(module):
    options = module.get("config-opts", [])
    if not isinstance(options, list):
        options = []
    build_options = module.get("build-options", {})
    if isinstance(build_options, dict):
        build_config_options = build_options.get("config-opts", [])
        if isinstance(build_config_options, list):
            options = [*options, *build_config_options]
    return options


def is_network_url(url, source_type):
    if not isinstance(url, str):
        return False
    if urlparse(url).scheme in {"ftp", "ftps", "git", "http", "https", "ssh"}:
        return True
    if source_type != "git" or WINDOWS_DRIVE_PATH_PATTERN.fullmatch(url):
        return False
    return any(
        pattern.fullmatch(url) is not None
        for pattern in (
            SCP_STYLE_WITH_USER_PATTERN,
            SCP_STYLE_ALIAS_PATTERN,
        )
    )


def validate_network_sources(modules):
    errors = []
    for module in modules:
        for source in active_sources(module):
            url = source.get("url")
            source_type = source.get("type")
            if not is_network_url(url, source_type):
                continue
            if source_type == "git":
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

    module_entries = manifest.get("modules", [])
    errors.extend(validate_string_includes(module_entries))
    modules = list(walk_modules(module_entries))
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

    for module_name in REQUIRED_SDL_MODULES:
        matching_modules = [
            module for module in modules if module.get("name") == module_name
        ]
        if not matching_modules or any(
            FLATPAK_CMAKE_LIBDIR not in module_config_options(module)
            for module in matching_modules
        ):
            errors.append(f"{module_name} must include {FLATPAK_CMAKE_LIBDIR}")

    artemis_modules = [
        module
        for module in modules
        if module.get("name") == "artemis"
    ]
    if not any(
        source.get("type") == "dir" and source.get("path") == "../.."
        for module in artemis_modules
        for source in active_sources(module)
    ):
        errors.append(
            "Artemis module must include a local source with type=dir and path=../.."
        )
    if not any(
        "CONFIG+=build_tests" in module.get("config-opts", [])
        for module in artemis_modules
    ):
        errors.append("Artemis module must include CONFIG+=build_tests")

    libplacebo_modules = [
        module
        for module in modules
        if str(module.get("name", "")).lower() == "libplacebo"
    ]
    if not any(
        source.get("type") == "patch"
        and source.get("path") == LIBPLACEBO_PATCH
        for module in libplacebo_modules
        for source in active_sources(module)
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
