from pathlib import Path
import json
import os
import plistlib
import re
import subprocess
import tempfile
import textwrap
import unittest
from urllib.parse import unquote
from xml.etree import ElementTree


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
UPSTREAM_REPOSITORY = "wjbeckett/artemis"
FORK_REPOSITORY = "samelamin/vibertemis"
OBSOLETE_FORK_REPOSITORY = "samelamin/artemis"

FORK_README_DISTRIBUTION_ROUTES = {
    "build badge": "https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml/badge.svg?branch=codex%2Fsteam-deck",
    "build actions": "https://github.com/samelamin/vibertemis/actions/workflows/dev-build.yml?query=branch%3Acodex%2Fsteam-deck",
    "download badge": "https://img.shields.io/github/downloads/samelamin/vibertemis/total",
    "clone command": "git clone https://github.com/samelamin/vibertemis.git",
    "release downloads": "https://github.com/samelamin/vibertemis/releases",
    "rolling Flatpak": "https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak",
    "rolling checksum": "https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak.sha256",
    "atomic bundle": "https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz",
}

README_FORK_ATTRIBUTION = (
    "Sam Elamin maintains Vibertemis, a Steam Deck-focused fork of "
    "[upstream Artemis](https://github.com/wjbeckett/artemis), originally "
    "created by William Beckett."
)

README_REQUIRED_HEADINGS = (
    "Why Vibertemis?",
    "AI-enhanced development",
    "Beta testers wanted",
    "Known validation gaps",
)

README_UPSTREAM_LINE_ALLOWLIST = {
    "README.md": {
        README_FORK_ATTRIBUTION,
    },
}

RENDERABLE_HELP_LINE_ALLOWLIST = {
    ".github/ISSUE_TEMPLATE/bug_report.md": {
        "If you're here because something basic is not working (like gamepad input, video, or similar), it's probably something specific to your setup, so make sure you've gone through the Upstream Artemis Troubleshooting Guide first: https://github.com/wjbeckett/artemis/wiki/Troubleshooting",
        "- Instructions for streaming the desktop are in the Upstream Artemis Setup Guide: https://github.com/wjbeckett/artemis/wiki/Setup-Guide",
    },
    "app/gui/main.qml": {
        'onClicked: Qt.openUrlExternally("https://github.com/wjbeckett/artemis/wiki/Setup-Guide");',
        'helpUrl: "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems"',
        'helpUrl: "https://github.com/wjbeckett/artemis/wiki/Gamepad-Mapping"',
    },
}

# These formats expose only a URL field to consuming platforms. The nearby source
# comments document why the exceptions remain, but are not treated as UI labels.
NON_RENDERED_METADATA_HELP_EXCEPTIONS = {
    "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml": {
        '<url type="help">https://github.com/wjbeckett/artemis/wiki</url>',
    },
    "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml": {
        '<url type="help">https://github.com/wjbeckett/artemis/wiki</url>',
    },
    "wix/ArtemisSetup/Bundle.wxs": {
        'HelpUrl="https://github.com/wjbeckett/artemis/wiki/Setup-Guide"',
    },
    "wix/MoonlightSetup/Bundle.wxs": {
        'HelpUrl="https://github.com/wjbeckett/artemis/wiki/Setup-Guide"',
    },
}

QML_LITERAL_QSTR_PATTERN = re.compile(
    r'qsTr\(\s*(?P<body>"(?:\\.|[^"\\])*"'
    r'(?:\s*\+\s*"(?:\\.|[^"\\])*")*)\s*\)',
    re.DOTALL,
)
QML_STRING_LITERAL_PATTERN = re.compile(r'"(?:\\.|[^"\\])*"')

BLOCKING_VIBERTEMIS_SOURCE = (
    "This PC's Internet connection is blocking Vibertemis. Streaming over "
    "the Internet may not work while connected to this network."
)

# These existing translations describe the blocked connection without naming
# the client. They should remain translated rather than being replaced with an
# English product name solely to satisfy the branding assertion.
NAMELESS_VIBERTEMIS_TRANSLATIONS = {
    (
        "qml_de.ts",
        "SettingsView",
        "Mutes Vibertemis's audio when you Alt+Tab out of the stream or "
        "click on a different window.",
    ),
    ("qml_de.ts", "main", "Update available for Vibertemis: Version %1"),
    (
        "qml_fr.ts",
        "SettingsView",
        "Mutes Vibertemis's audio when you Alt+Tab out of the stream or "
        "click on a different window.",
    ),
    (
        "qml_ru.ts",
        "SettingsView",
        "NOTE: Certain keyboard shortcuts like Ctrl+Alt+Del on Windows "
        "cannot be intercepted by any application, including Vibertemis.",
    ),
    ("qml_zh_CN.ts", "PcView", BLOCKING_VIBERTEMIS_SOURCE),
    (
        "qml_zh_CN.ts",
        "PcView",
        "Your PC's current network connection seems to be blocking "
        "Vibertemis. Streaming over the Internet may not work while "
        "connected to this network.",
    ),
    ("qml_zh_CN.ts", "StreamSegue", BLOCKING_VIBERTEMIS_SOURCE),
    ("qml_zh_TW.ts", "StreamSegue", BLOCKING_VIBERTEMIS_SOURCE),
}

# These four populated entries were already unfinished before their source key
# changed. Retaining that state avoids silently upgrading translation quality.
PREEXISTING_UNFINISHED_VIBERTEMIS_TRANSLATIONS = {
    (catalog, "StreamSegue", BLOCKING_VIBERTEMIS_SOURCE)
    for catalog in ("qml_hi.ts", "qml_lt.ts", "qml_pt_BR.ts", "qml_th.ts")
}

UPSTREAM_MOONLIGHT_QML_SOURCES = {
    (
        "PcView",
        "The network test could not be performed because none of the "
        "upstream Moonlight connection testing servers were reachable from "
        "this PC. Check your Internet connection or try again later.",
    ),
    (
        "PcView",
        "If you are trying to stream over the Internet, install the Moonlight "
        "Internet Hosting Tool on your gaming PC and run the included "
        "Internet Streaming Tester to check your gaming PC's Internet "
        "connection.",
    ),
}


def tracked_identity_files():
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPOSITORY_ROOT,
        check=True,
        capture_output=True,
    )
    for raw_path in result.stdout.split(b"\0"):
        if not raw_path:
            continue
        relative_path = raw_path.decode("utf-8")
        if relative_path.startswith("docs/superpowers/"):
            continue
        if relative_path == "packaging/flatpak/tests/test_fork_identity.py":
            continue
        yield relative_path


def literal_qstr_sources_by_context():
    sources_by_context = {}
    for qml_path in sorted((REPOSITORY_ROOT / "app/gui").rglob("*.qml")):
        context_sources = sources_by_context.setdefault(qml_path.stem, set())
        qml_text = qml_path.read_text(encoding="utf-8")
        for match in QML_LITERAL_QSTR_PATTERN.finditer(qml_text):
            source = "".join(
                json.loads(literal.group(0))
                for literal in QML_STRING_LITERAL_PATTERN.finditer(
                    match.group("body")
                )
            )
            context_sources.add(source)
    return sources_by_context


def repeatedly_url_decode(value):
    for _ in range(8):
        decoded = unquote(value)
        if decoded == value:
            break
        value = decoded
    return value


def allowed_upstream_lines(relative_path):
    return set().union(
        README_UPSTREAM_LINE_ALLOWLIST.get(relative_path, set()),
        RENDERABLE_HELP_LINE_ALLOWLIST.get(relative_path, set()),
        NON_RENDERED_METADATA_HELP_EXCEPTIONS.get(relative_path, set()),
    )


def unexpected_upstream_occurrences(relative_path, text):
    unexpected = []
    allowed_lines = allowed_upstream_lines(relative_path)
    for line_number, line in enumerate(text.splitlines(), start=1):
        normalized_line = repeatedly_url_decode(line).casefold()
        if UPSTREAM_REPOSITORY.casefold() not in normalized_line:
            continue
        if line.strip() in allowed_lines:
            continue
        unexpected.append(f"{relative_path}:{line_number}: {line.strip()}")
    return unexpected


def obsolete_fork_occurrences(relative_path, text):
    occurrences = []
    obsolete_repository_pattern = re.compile(
        rf"{re.escape(OBSOLETE_FORK_REPOSITORY)}(?:\.git)?"
        r"(?![a-z0-9_.-])",
        re.IGNORECASE,
    )
    for line_number, line in enumerate(text.splitlines(), start=1):
        normalized_line = repeatedly_url_decode(line).casefold()
        if obsolete_repository_pattern.search(normalized_line):
            occurrences.append(
                f"{relative_path}:{line_number}: {line.strip()}"
            )
    return occurrences


def desktop_entry_fields(text):
    fields = {}
    active_section = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            active_section = line[1:-1]
            continue
        if active_section != "Desktop Entry" or "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields.setdefault(key, []).append(value)
    return fields


def active_cpp_lines(text):
    without_block_comments = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return [
        line.split("//", 1)[0].strip()
        for line in without_block_comments.splitlines()
        if line.split("//", 1)[0].strip()
    ]


def validate_final_readme_distribution(readme):
    errors = []
    readme_urls = set(re.findall(r"https?://[^\s)\]>]+", readme))
    missing_fork_routes = {
        route_name
        for route_name, route in FORK_README_DISTRIBUTION_ROUTES.items()
        if (
            route not in readme
            if route_name == "clone command"
            else route not in readme_urls
        )
    }
    if missing_fork_routes:
        errors.append(
            "final README is missing fork routes: "
            + ", ".join(sorted(missing_fork_routes))
        )
    if README_FORK_ATTRIBUTION not in readme:
        errors.append("final README is missing the exact fork attribution")
    return errors


def workflow_job_block(workflow, job_name):
    marker = f"  {job_name}:\n"
    start = workflow.find(marker)
    if start == -1:
        return ""
    next_job = workflow.find("\n  ", start + len(marker))
    while next_job != -1:
        candidate_end = workflow.find("\n", next_job + 1)
        if candidate_end == -1:
            candidate_end = len(workflow)
        candidate = workflow[next_job + 1:candidate_end]
        if candidate.endswith(":") and not candidate.startswith("    "):
            return workflow[start:next_job]
        next_job = workflow.find("\n  ", candidate_end)
    return workflow[start:]


def workflow_step_run_script(workflow, job_name, step_name):
    job = workflow_job_block(workflow, job_name)
    step_marker = f"      - name: {step_name}\n"
    step_start = job.index(step_marker)
    run_marker = "        run: |\n"
    run_start = job.index(run_marker, step_start) + len(run_marker)
    next_step = job.find("\n      - name: ", run_start)
    if next_step == -1:
        next_step = len(job)
    return textwrap.dedent(job[run_start:next_step])


class ForkIdentityTests(unittest.TestCase):
    def test_upstream_routes_are_exactly_allowlisted(self):
        unexpected = []

        for relative_path in tracked_identity_files():
            path = REPOSITORY_ROOT / relative_path
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                continue

            unexpected.extend(unexpected_upstream_occurrences(relative_path, text))

        self.assertEqual(
            [],
            unexpected,
            "fork user journeys must not route through the upstream repository",
        )

    def test_runtime_and_distribution_routes_use_the_fork(self):
        expected_routes = {
            "app/backend/autoupdatechecker.cpp":
                "https://api.github.com/repos/samelamin/vibertemis/releases",
            "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml": (
                '<url type="homepage">https://github.com/samelamin/vibertemis</url>',
                '<url type="bugtracker">https://github.com/samelamin/vibertemis/issues</url>',
            ),
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml": (
                '<url type="homepage">https://github.com/samelamin/vibertemis</url>',
                '<url type="bugtracker">https://github.com/samelamin/vibertemis/issues</url>',
            ),
            "app/gui/main.qml":
                'Qt.openUrlExternally("https://github.com/samelamin/vibertemis/releases")',
            "wix/ArtemisSetup/Bundle.wxs":
                'UpdateUrl="https://github.com/samelamin/vibertemis/releases"',
            "wix/MoonlightSetup/Bundle.wxs":
                'UpdateUrl="https://github.com/samelamin/vibertemis/releases"',
            "docs/DEVELOPMENT.md":
                "**Repository**: https://github.com/samelamin/vibertemis",
        }

        for relative_path, routes in expected_routes.items():
            text = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8-sig")
            if isinstance(routes, str):
                routes = (routes,)
            for route in routes:
                with self.subTest(path=relative_path, route=route):
                    self.assertIn(route, text)

    def test_public_product_identity_is_vibertemis(self):
        desktop_files = (
            "app/deploy/linux/com.artemis_desktop.Artemis.desktop",
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.desktop",
        )
        for relative_path in desktop_files:
            text = (REPOSITORY_ROOT / relative_path).read_text(
                encoding="utf-8-sig"
            )
            with self.subTest(path=relative_path):
                self.assertEqual(
                    ["Vibertemis"],
                    desktop_entry_fields(text).get("Name"),
                    f"{relative_path} must have exactly one active "
                    "Name=Vibertemis field and no active Name=Artemis field",
                )

        appstream_files = (
            "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml",
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml",
        )
        for relative_path in appstream_files:
            root = ElementTree.parse(REPOSITORY_ROOT / relative_path).getroot()
            current_names = [
                element.text
                for element in root.findall("./name")
            ]
            with self.subTest(path=relative_path):
                self.assertEqual(
                    ["Vibertemis"],
                    current_names,
                    f"{relative_path} must have one current top-level "
                    "Vibertemis name and no current Artemis name",
                )

        main_cpp = (REPOSITORY_ROOT / "app/main.cpp").read_text(
            encoding="utf-8"
        )
        display_name_pattern = re.compile(
            r'(?P<target>Q(?:Core|Gui)Application::|app\.)'
            r'setApplicationDisplayName\(\s*"(?P<name>[^"]*)"\s*\);'
        )
        active_display_name_calls = []
        for line in active_cpp_lines(main_cpp):
            match = display_name_pattern.fullmatch(line)
            if match:
                active_display_name_calls.append(
                    (match.group("target"), match.group("name"))
                )
        with self.subTest(path="app/main.cpp"):
            self.assertEqual(
                1,
                len(active_display_name_calls),
                "app/main.cpp must have exactly one active display-name call",
            )
            target, display_name = active_display_name_calls[0]
            self.assertIn(
                target,
                ("QGuiApplication::", "app."),
                "the display-name call must use QGuiApplication or its app instance",
            )
            self.assertEqual(
                "Vibertemis",
                display_name,
                "the active application display name must be Vibertemis",
            )

        with (REPOSITORY_ROOT / "app/Info.plist").open("rb") as plist_file:
            info_plist = plistlib.load(plist_file)
        with self.subTest(path="app/Info.plist"):
            self.assertEqual(
                "Vibertemis",
                info_plist.get("CFBundleDisplayName"),
                "CFBundleDisplayName must be Vibertemis, not Artemis",
            )

    def test_vibertemis_qml_sources_remain_translated(self):
        qml_sources_by_context = literal_qstr_sources_by_context()
        expected_sources = {
            (context_name, source)
            for context_name, sources in qml_sources_by_context.items()
            for source in sources
            if "Vibertemis" in source
        }
        self.assertTrue(expected_sources, "no Vibertemis qsTr() sources found")
        self.assertEqual(
            UPSTREAM_MOONLIGHT_QML_SOURCES,
            {
                (context_name, source)
                for context_name, sources in qml_sources_by_context.items()
                for source in sources
                if "Moonlight" in source
            },
            "Moonlight may remain only for the upstream hosting tool and "
            "connection-testing service",
        )

        catalog_paths = sorted(
            (REPOSITORY_ROOT / "app/languages").glob("qml_*.ts")
        )
        self.assertEqual(30, len(catalog_paths), "all QML catalogs must be checked")

        inactive_types = {"obsolete", "vanished"}
        for catalog_path in catalog_paths:
            catalog_root = ElementTree.parse(catalog_path).getroot()
            messages = {}
            for context in catalog_root.findall("context"):
                context_name = context.findtext("name")
                for message in context.findall("message"):
                    source = message.findtext("source")
                    messages.setdefault((context_name, source), []).append(message)

            for context_name, source in sorted(expected_sources):
                active_messages = []
                for message in messages.get((context_name, source), []):
                    translation = message.find("translation")
                    translation_type = (
                        translation.get("type") if translation is not None else None
                    )
                    if (
                        message.get("type") not in inactive_types
                        and translation_type not in inactive_types
                    ):
                        active_messages.append(message)

                previous_source = source.replace("Vibertemis", "Moonlight")
                previous_active_messages = []
                for message in messages.get((context_name, previous_source), []):
                    translation = message.find("translation")
                    translation_type = (
                        translation.get("type") if translation is not None else None
                    )
                    if (
                        message.get("type") not in inactive_types
                        and translation_type not in inactive_types
                    ):
                        previous_active_messages.append(message)

                with self.subTest(
                    catalog=catalog_path.name,
                    context=context_name,
                    source=source,
                ):
                    self.assertEqual(
                        1,
                        len(active_messages),
                        "each active Vibertemis qsTr() key must have one "
                        "non-obsolete catalog entry",
                    )
                    self.assertFalse(
                        previous_active_messages,
                        "the superseded Moonlight product key must not remain "
                        "active",
                    )
                    translation = active_messages[0].find("translation")
                    self.assertIsNotNone(translation)
                    translated_text = "".join(translation.itertext()).strip()
                    if not translated_text:
                        continue

                    message_key = (catalog_path.name, context_name, source)
                    if translation.get("type") == "unfinished":
                        self.assertIn(
                            message_key,
                            PREEXISTING_UNFINISHED_VIBERTEMIS_TRANSLATIONS,
                            "an existing translated sentence must not become "
                            "unfinished",
                        )
                    if message_key not in NAMELESS_VIBERTEMIS_TRANSLATIONS:
                        self.assertIn(
                            "Vibertemis",
                            translated_text,
                            "a translated current-product name must migrate "
                            "to Vibertemis",
                        )
                    self.assertNotIn(
                        "Artemis",
                        translated_text,
                        "Artemis must not remain as the current product name",
                    )
                    self.assertLessEqual(
                        translated_text.count("Moonlight"),
                        source.count("Moonlight"),
                        "Moonlight may remain only where the source names an "
                        "upstream service or tool",
                    )

            for context_name, source in sorted(UPSTREAM_MOONLIGHT_QML_SOURCES):
                active_messages = []
                for message in messages.get((context_name, source), []):
                    translation = message.find("translation")
                    translation_type = (
                        translation.get("type") if translation is not None else None
                    )
                    if (
                        message.get("type") not in inactive_types
                        and translation_type not in inactive_types
                    ):
                        active_messages.append(message)
                with self.subTest(
                    catalog=catalog_path.name,
                    context=context_name,
                    source=source,
                ):
                    self.assertEqual(
                        1,
                        len(active_messages),
                        "each upstream Moonlight service/tool key must remain "
                        "active exactly once",
                    )

    def test_macos_public_packages_use_vibertemis_names(self):
        expected_packaging = {
            "scripts/generate-dmg.sh": (
                'BUILT_APP_PATH="$BUILD_FOLDER/app/Artemis.app"',
                'APP_PATH="$BUILD_FOLDER/app/Vibertemis.app"',
                'mv "$BUILT_APP_PATH" "$APP_PATH"',
                'DMG_NAME="Vibertemis-$VERSION.dmg"',
                '--volname "Vibertemis"',
                '--icon "Vibertemis.app"',
                '--hide-extension "Vibertemis.app"',
                '    -volname "Vibertemis" \\\n',
                'xattr -cr Vibertemis.app',
            ),
            "scripts/verify-macos-bundle.sh":
                'verify_app "$MOUNT_DIR/Vibertemis.app"',
            ".github/workflows/dev-build.yml": (
                "build/build-Release/app/Vibertemis.app",
                "name: vibertemis-macos-${{ steps.macos_arch.outputs.suffix }}-${{ needs.setup-version.outputs.version }}",
                "path: build/installer-Release/Vibertemis-${{ needs.setup-version.outputs.version }}.dmg",
            ),
        }

        for relative_path, contracts in expected_packaging.items():
            text = (REPOSITORY_ROOT / relative_path).read_text(
                encoding="utf-8-sig"
            )
            if isinstance(contracts, str):
                contracts = (contracts,)
            for contract in contracts:
                with self.subTest(path=relative_path, contract=contract):
                    self.assertIn(contract, text)

        generate_dmg = (
            REPOSITORY_ROOT / "scripts/generate-dmg.sh"
        ).read_text(encoding="utf-8")
        for obsolete_public_bundle_reference in (
            'DMG_NAME="Artemis-$VERSION.dmg"',
            '--volname "Artemis"',
            '--icon "Artemis.app"',
            '--hide-extension "Artemis.app"',
            '    -volname "Artemis" \\\n',
            'xattr -cr Artemis.app',
        ):
            with self.subTest(obsolete=obsolete_public_bundle_reference):
                self.assertNotIn(
                    obsolete_public_bundle_reference,
                    generate_dmg,
                    "Artemis.app is only the qmake build output; public bundle "
                    "references must use Vibertemis.app",
                )

        self.assertEqual(
            ['BUILT_APP_PATH="$BUILD_FOLDER/app/Artemis.app"'],
            [
                line.strip()
                for line in generate_dmg.splitlines()
                if "Artemis.app" in line
            ],
            "the qmake output path is the only permitted Artemis.app bundle "
            "reference",
        )

        verify_bundle = (
            REPOSITORY_ROOT / "scripts/verify-macos-bundle.sh"
        ).read_text(encoding="utf-8")
        self.assertNotIn('verify_app "$MOUNT_DIR/Artemis.app"', verify_bundle)

        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        for obsolete_public_package_reference in (
            "build/build-Release/app/Artemis.app",
            '"build/installer-Release/Artemis-$VERSION.dmg"',
            "name: artemis-macos-",
            "path: build/installer-Release/Artemis-",
        ):
            with self.subTest(obsolete=obsolete_public_package_reference):
                self.assertNotIn(obsolete_public_package_reference, workflow)

    def test_readme_has_vibertemis_launch_sections(self):
        readme = (REPOSITORY_ROOT / "README.md").read_text(encoding="utf-8")
        for heading in README_REQUIRED_HEADINGS:
            with self.subTest(heading=heading):
                self.assertRegex(
                    readme,
                    rf"(?m)^##+ {re.escape(heading)}$",
                )

    def test_obsolete_fork_routes_are_absent_from_maintained_files(self):
        obsolete = []

        for relative_path in tracked_identity_files():
            path = REPOSITORY_ROOT / relative_path
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                continue
            obsolete.extend(obsolete_fork_occurrences(relative_path, text))

        self.assertEqual(
            [],
            obsolete,
            "maintained fork routes must use " + FORK_REPOSITORY,
        )

    def test_compatibility_identifiers_and_assets_remain_stable(self):
        compatibility_contracts = {
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json": (
                '"app-id": "com.artemisdesktop.ArtemisDesktopDev"',
                '"command": "artemis"',
            ),
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml": (
                "<id>com.artemisdesktop.ArtemisDesktopDev</id>",
                '<launchable type="desktop-id">com.artemisdesktop.ArtemisDesktopDev.desktop</launchable>',
            ),
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.desktop": (
                "Icon=com.artemisdesktop.ArtemisDesktopDev",
                "StartupWMClass=com.artemisdesktop.ArtemisDesktopDev",
            ),
            "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml": (
                "<id>com.artemis_desktop.Artemis</id>",
                '<launchable type="desktop-id">com.artemis_desktop.Artemis.desktop</launchable>',
            ),
            "app/deploy/linux/com.artemis_desktop.Artemis.desktop":
                "Icon=artemis",
            "app/Info.plist": (
                "<string>com.artemis_desktop.Artemis</string>",
                "<key>CFBundleExecutable</key>\n\t<string>Artemis</string>",
            ),
            "app/app.pro":
                "TARGET = Artemis",
            "app/main.cpp":
                'QCoreApplication::setApplicationName("Artemis")',
            "app/backend/computermanager.cpp":
                'QUrl::fromUserInput("art://" + address)',
            "scripts/generate-dmg.sh":
                "Contents/MacOS/Artemis",
            "scripts/verify-macos-bundle.sh":
                "Contents/MacOS/Artemis",
            ".github/workflows/dev-build.yml": (
                "artemis-steam-deck.flatpak",
                "artemis-steam-deck.flatpak.sha256",
                "artemis-steam-deck-bundle.tar.gz",
            ),
        }

        self.assertTrue(
            (REPOSITORY_ROOT / "artemis.pro").is_file(),
            "artemis.pro is a compatibility build-system entry point",
        )
        for relative_path, contracts in compatibility_contracts.items():
            text = (REPOSITORY_ROOT / relative_path).read_text(
                encoding="utf-8-sig"
            )
            if isinstance(contracts, str):
                contracts = (contracts,)
            for contract in contracts:
                with self.subTest(path=relative_path, contract=contract):
                    self.assertIn(contract, text)

        native_desktop = (
            REPOSITORY_ROOT
            / "app/deploy/linux/com.artemis_desktop.Artemis.desktop"
        ).read_text(encoding="utf-8")
        native_wm_classes = [
            line
            for line in native_desktop.splitlines()
            if line.startswith("StartupWMClass=")
        ]
        if native_wm_classes:
            self.assertEqual(
                ["StartupWMClass=com.artemis_desktop.Artemis"],
                native_wm_classes,
            )

    def test_readme_has_final_fork_attribution_and_distribution_routes(self):
        readme = (REPOSITORY_ROOT / "README.md").read_text(encoding="utf-8")
        self.assertEqual([], validate_final_readme_distribution(readme))

    def test_readme_final_audit_rejects_missing_routes_and_upstream_reintroduction(self):
        final_fixture = README_FORK_ATTRIBUTION
        final_fixture += "\n" + "\n".join(FORK_README_DISTRIBUTION_ROUTES.values())
        self.assertEqual([], validate_final_readme_distribution(final_fixture))

        for route_name, route in FORK_README_DISTRIBUTION_ROUTES.items():
            with self.subTest(missing=route_name):
                self.assertTrue(
                    validate_final_readme_distribution(
                        final_fixture.replace(route, "", 1)
                    )
                )

        reintroduced = final_fixture + "\nhttps://github.com/wjbeckett/artemis/releases"
        self.assertTrue(unexpected_upstream_occurrences("README.md", reintroduced))

    def test_audit_rejects_root_doc_uppercase_and_encoded_bypasses(self):
        self.assertIn("CONTRIBUTING.md", set(tracked_identity_files()))

        mutations = {
            "root document": "https://github.com/wjbeckett/artemis/releases",
            "uppercase owner": "https://github.com/WJBECKETT/ARTEMIS/releases",
            "repeatedly encoded separator": (
                "https://github.com/wjbeckett%252Fartemis/issues"
            ),
        }

        for mutation_name, route in mutations.items():
            with self.subTest(mutation=mutation_name):
                self.assertTrue(
                    unexpected_upstream_occurrences("CONTRIBUTING.md", route)
                )

    def test_obsolete_fork_audit_matches_only_the_exact_repository(self):
        obsolete_routes = (
            "https://github.com/samelamin/artemis/issues",
            "git clone https://github.com/samelamin/artemis.git",
            "https://github.com/samelamin%252Fartemis/releases",
        )
        for route in obsolete_routes:
            with self.subTest(obsolete=route):
                self.assertTrue(
                    obsolete_fork_occurrences("README.md", route),
                    f"obsolete route was not detected: {route}",
                )

        allowed_similar_routes = (
            "https://github.com/samelamin/artemis-tools",
            "https://github.com/samelamin/artemis.tools",
            "https://github.com/samelamin/artemis_next",
        )
        for route in allowed_similar_routes:
            with self.subTest(allowed=route):
                self.assertEqual(
                    [],
                    obsolete_fork_occurrences("README.md", route),
                    f"distinct repository was rejected: {route}",
                )

    def test_renderable_upstream_help_is_visibly_labelled(self):
        qml = (REPOSITORY_ROOT / "app/gui/main.qml").read_text(encoding="utf-8")
        issue_template = (
            REPOSITORY_ROOT / ".github/ISSUE_TEMPLATE/bug_report.md"
        ).read_text(encoding="utf-8")

        help_button_start = qml.index("id: helpButton")
        help_button_end = qml.index(
            "\n            NavigableToolButton {", help_button_start
        )
        help_button = qml[help_button_start:help_button_end]
        self.assertIn('ToolTip.text: qsTr("Upstream Artemis Help")', help_button)
        self.assertIn(
            'Qt.openUrlExternally("https://github.com/wjbeckett/artemis/wiki/Setup-Guide")',
            help_button,
        )

        labelled_dialog_routes = (
            (
                'helpText: qsTr("Click the Help button to open the Upstream Artemis documentation for solving this problem.")',
                'helpUrl: "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems"',
            ),
            (
                'helpText: qsTr("Click the Help button to open the Upstream Artemis documentation.")',
                'helpUrl: "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems"',
            ),
            (
                'helpText: qsTr("Click the Help button to open the Upstream Artemis documentation for mapping gamepads.")',
                'helpUrl: "https://github.com/wjbeckett/artemis/wiki/Gamepad-Mapping"',
            ),
        )
        for help_text, help_url in labelled_dialog_routes:
            with self.subTest(help_url=help_url):
                self.assertIn(f"{help_text}\n        {help_url}", qml)

        self.assertIn("Upstream Artemis Troubleshooting Guide", issue_template)
        self.assertIn("Upstream Artemis Setup Guide", issue_template)

    def test_release_jobs_use_least_privilege_permissions(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        workflow_defaults = workflow[:workflow.index("\njobs:")]
        self.assertIn("permissions:\n  contents: read", workflow_defaults)

        for job_name in ("create-dev-release", "publish-steam-deck-release"):
            with self.subTest(job=job_name):
                job = workflow_job_block(workflow, job_name)
                self.assertIn("    permissions:\n      contents: write", job)

    def test_release_workflow_uses_supported_release_action(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("uses: softprops/action-gh-release@v2", workflow)
        self.assertNotIn("uses: softprops/action-gh-release@v1", workflow)

    def test_setup_version_passes_commit_message_through_environment(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        setup_version = workflow_job_block(workflow, "setup-version")

        head_commit_expression = "${{ github.event.head_commit.message }}"
        self.assertEqual(1, workflow.count(head_commit_expression))
        self.assertIn(
            "        env:\n"
            f"          HEAD_COMMIT_MESSAGE: {head_commit_expression}\n"
            "        run: |",
            setup_version,
        )
        self.assertIn(
            'if echo "$HEAD_COMMIT_MESSAGE" | grep -E',
            setup_version,
        )

    def test_setup_version_handles_zero_match_counts_under_pipefail(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        script = workflow_step_run_script(
            workflow,
            "setup-version",
            "Check for meaningful changes",
        ).replace("${{ github.ref_name }}", "codex/steam-deck")

        cases = (
            ("code only", "app/example.cpp", "int main() {}\n", "true"),
            (
                "workflow only",
                ".github/workflows/example.yml",
                "name: example\n",
                "true",
            ),
            ("documentation only", "README.md", "docs\n", "false"),
        )

        for history in ("regular", "merge"):
            for case_name, relative_path, contents, expected in cases:
                with self.subTest(history=history, changes=case_name):
                    with tempfile.TemporaryDirectory() as temp_dir:
                        repository = Path(temp_dir)
                        subprocess.run(
                            ["git", "init", "--initial-branch=main"],
                            cwd=repository,
                            check=True,
                            capture_output=True,
                        )
                        subprocess.run(
                            ["git", "config", "user.name", "Artemis CI Test"],
                            cwd=repository,
                            check=True,
                        )
                        subprocess.run(
                            ["git", "config", "user.email", "ci@example.invalid"],
                            cwd=repository,
                            check=True,
                        )
                        (repository / "initial.txt").write_text(
                            "initial\n", encoding="utf-8"
                        )
                        subprocess.run(
                            ["git", "add", "initial.txt"],
                            cwd=repository,
                            check=True,
                        )
                        subprocess.run(
                            ["git", "commit", "-m", "initial"],
                            cwd=repository,
                            check=True,
                            capture_output=True,
                        )

                        if history == "merge":
                            subprocess.run(
                                ["git", "switch", "-c", "feature"],
                                cwd=repository,
                                check=True,
                                capture_output=True,
                            )

                        changed_path = repository / relative_path
                        changed_path.parent.mkdir(parents=True, exist_ok=True)
                        changed_path.write_text(contents, encoding="utf-8")
                        subprocess.run(
                            ["git", "add", relative_path],
                            cwd=repository,
                            check=True,
                        )
                        subprocess.run(
                            ["git", "commit", "-m", case_name],
                            cwd=repository,
                            check=True,
                            capture_output=True,
                        )

                        if history == "merge":
                            subprocess.run(
                                ["git", "switch", "main"],
                                cwd=repository,
                                check=True,
                                capture_output=True,
                            )
                            subprocess.run(
                                [
                                    "git",
                                    "merge",
                                    "--no-ff",
                                    "feature",
                                    "-m",
                                    "merge feature",
                                ],
                                cwd=repository,
                                check=True,
                                capture_output=True,
                            )

                        output_path = repository / "github-output"
                        environment = os.environ.copy()
                        environment.update(
                            {
                                "GITHUB_OUTPUT": str(output_path),
                                "HEAD_COMMIT_MESSAGE": case_name,
                            }
                        )
                        result = subprocess.run(
                            [
                                "bash",
                                "--noprofile",
                                "--norc",
                                "-e",
                                "-o",
                                "pipefail",
                                "-c",
                                script,
                            ],
                            cwd=repository,
                            env=environment,
                            text=True,
                            capture_output=True,
                        )

                        self.assertEqual(
                            0,
                            result.returncode,
                            result.stdout + result.stderr,
                        )
                        self.assertEqual(
                            [f"should_build={expected}"],
                            output_path.read_text(encoding="utf-8").splitlines(),
                        )

    def test_compile_sanity_has_no_duplicate_version_setup(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        compile_sanity = workflow_job_block(workflow, "compile-sanity")

        self.assertEqual(1, compile_sanity.count("      - name: Checkout\n"))
        self.assertNotIn("        id: check-changes\n", compile_sanity)
        self.assertNotIn("        id: version\n", compile_sanity)

    def test_steam_deck_publisher_has_exact_gate_and_artifact_contract(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("  publish-steam-deck-release:\n", workflow)
        job = workflow_job_block(workflow, "publish-steam-deck-release")

        self.assertEqual(
            1,
            job.splitlines().count(
                "    needs: [setup-version, build-flatpak-dev]"
            ),
        )
        self.assertIn("    needs: [setup-version, build-flatpak-dev]", job)
        exact_gate = (
            "    if: >-\n"
            "      github.repository == 'samelamin/vibertemis' &&\n"
            "      github.ref == 'refs/heads/codex/steam-deck' &&\n"
            "      needs.setup-version.outputs.should_build == 'true'\n"
        )
        self.assertEqual(1, job.splitlines().count("    if: >-"))
        self.assertIn(f"{exact_gate}    env:\n", job)
        self.assertIn(
            "    concurrency:\n"
            "      group: steam-deck-rolling-publisher\n"
            "      cancel-in-progress: false",
            job,
        )
        self.assertIn("uses: actions/download-artifact@v4", job)
        self.assertIn(
            "name: artemis-flatpak-${{ needs.setup-version.outputs.version }}",
            job,
        )
        self.assertIn("artemis-steam-deck.flatpak", job)
        self.assertIn("artemis-steam-deck.flatpak.sha256", job)
        self.assertIn("artemis-steam-deck-bundle.tar.gz", job)
        self.assertIn("steam-deck-latest", job)
        self.assertIn(
            "sha256sum artemis-steam-deck.flatpak > artemis-steam-deck.flatpak.sha256",
            job,
        )
        self.assertIn(
            "tar -czf artemis-steam-deck-bundle.tar.gz \\\n"
            "              artemis-steam-deck.flatpak \\\n"
            "              artemis-steam-deck.flatpak.sha256",
            job,
        )
        self.assertIn("if gh release view steam-deck-latest", job)
        self.assertIn("gh release create steam-deck-latest", job)
        self.assertGreaterEqual(job.count("--prerelease"), 2)

    def test_steam_deck_publisher_rejects_superseded_runs_before_download(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        job = workflow_job_block(workflow, "publish-steam-deck-release")

        self.assertIn("id: branch-head", job)
        guard = job.index("id: branch-head")
        artifact_download = job.index("uses: actions/download-artifact@v4")
        self.assertLess(guard, artifact_download)
        self.assertIn(
            'branch_head="$(gh api "repos/$GH_REPO/git/ref/heads/codex/steam-deck" '
            "--jq '.object.sha')\"",
            job,
        )
        self.assertIn('if [ "$branch_head" != "$SOURCE_COMMIT" ]; then', job)
        self.assertIn('echo "publish=false" >> "$GITHUB_OUTPUT"', job)
        self.assertIn('echo "publish=true" >> "$GITHUB_OUTPUT"', job)
        self.assertEqual(
            7,
            job.count("if: steps.branch-head.outputs.publish == 'true'"),
            "every mutating or artifact-handling step must be gated by the branch-head check",
        )

    def test_steam_deck_publisher_deletes_only_confirmed_existing_assets(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        job = workflow_job_block(workflow, "publish-steam-deck-release")

        asset_query = (
            "gh release view steam-deck-latest --json assets "
            "--jq '.assets[].name'"
        )
        self.assertEqual(1, job.count(asset_query))
        self.assertIn('for asset_name in \\\n', job)
        self.assertIn('grep -Fqx "$asset_name" <<< "$existing_assets"', job)
        self.assertIn(
            'gh release delete-asset steam-deck-latest "$asset_name" --yes',
            job,
        )
        delete_commands = [
            line for line in job.splitlines() if "gh release delete-asset" in line
        ]
        self.assertTrue(delete_commands)
        for delete_command in delete_commands:
            self.assertNotIn("|| true", delete_command)
        self.assertNotIn(
            "delete-asset steam-deck-latest "
            "artemis-steam-deck.flatpak.sha256 --yes 2>/dev/null || true",
            job,
        )
        self.assertNotIn(
            "delete-asset steam-deck-latest "
            "artemis-steam-deck-bundle.tar.gz --yes 2>/dev/null || true",
            job,
        )

    def test_steam_deck_publisher_verifies_download_before_companion_assets(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("  publish-steam-deck-release:\n", workflow)
        job = workflow_job_block(workflow, "publish-steam-deck-release")

        flatpak_upload = job.index(
            "gh release upload steam-deck-latest release-assets/artemis-steam-deck.flatpak --clobber"
        )
        published_download = job.index(
            "gh release download steam-deck-latest --pattern artemis-steam-deck.flatpak"
        )
        digest_comparison = job.index(
            'test "$expected_sha" = "$published_sha"'
        )
        tag_patch_command = (
            'gh api --method PATCH '
            '"repos/$GH_REPO/git/refs/tags/steam-deck-latest"'
        )
        self.assertIn(tag_patch_command, job)
        tag_patch = job.index(tag_patch_command)
        tag_sha_query = job.index(
            'gh api "repos/$GH_REPO/git/ref/tags/steam-deck-latest" '
            "--jq '.object.sha'"
        )
        tag_sha_comparison = job.index(
            'test "$published_tag_sha" = "$SOURCE_COMMIT"'
        )
        companion_upload = job.index(
            "gh release upload steam-deck-latest \\\n"
            "            release-assets/artemis-steam-deck.flatpak.sha256 \\\n"
            "            release-assets/artemis-steam-deck-bundle.tar.gz --clobber"
        )

        self.assertLess(flatpak_upload, published_download)
        self.assertLess(published_download, digest_comparison)
        self.assertLess(digest_comparison, tag_patch)
        self.assertLess(tag_patch, tag_sha_query)
        self.assertLess(tag_sha_query, tag_sha_comparison)
        self.assertLess(tag_sha_comparison, companion_upload)
        self.assertIn("rm -rf published-flatpak", job)
        self.assertIn("mkdir published-flatpak", job)
        self.assertIn("Version: \\`$VERSION\\`", job)
        self.assertIn("Commit: \\`$SOURCE_COMMIT\\`", job)
        self.assertIn("Workflow run: $RUN_URL", job)
        self.assertIn("SHA-256: \\`$expected_sha\\`", job)
        self.assertIn("replaced by every later successful build", job)
        self.assertIn("verified after re-downloading", job)

    def test_steam_deck_publisher_force_moves_rolling_tag_each_time(self):
        workflow = (
            REPOSITORY_ROOT / ".github/workflows/dev-build.yml"
        ).read_text(encoding="utf-8")
        job = workflow_job_block(workflow, "publish-steam-deck-release")

        self.assertIn(
            'gh api --method PATCH '
            '"repos/$GH_REPO/git/refs/tags/steam-deck-latest" \\\n'
            '            -f sha="$SOURCE_COMMIT" \\\n'
            "            -F force=true",
            job,
        )
        self.assertIn(
            'published_tag_sha="$(gh api '
            '"repos/$GH_REPO/git/ref/tags/steam-deck-latest" '
            "--jq '.object.sha')\"",
            job,
        )


if __name__ == "__main__":
    unittest.main()
