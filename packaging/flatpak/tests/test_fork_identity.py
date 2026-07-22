from pathlib import Path
import subprocess
import unittest
from urllib.parse import unquote


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
UPSTREAM_REPOSITORY = "wjbeckett/artemis"

# README ownership and installation guidance is deliberately migrated in Task 4.
# These exact pre-migration lines are temporary: variants and new upstream routes
# are rejected by the general URL audit below.
TEMPORARY_README_ALLOWLIST = {
    "[![Build Status](https://github.com/wjbeckett/artemis/workflows/Build%20Artemis%20Qt/badge.svg)](https://github.com/wjbeckett/artemis/actions)",
    "[![Downloads](https://img.shields.io/github/downloads/wjbeckett/artemis/total)](https://github.com/wjbeckett/artemis/releases)",
    "All downloads are available in [Releases](https://github.com/wjbeckett/artemis/releases)",
    "git clone https://github.com/wjbeckett/artemis.git",
    "Want to help test new features? Check out our [development releases](https://github.com/wjbeckett/artemis/releases?q=prerelease%3Atrue)!",
}

FORK_README_DISTRIBUTION_ROUTES = {
    "build badge": "https://github.com/samelamin/artemis/workflows/Build%20Artemis%20Qt/badge.svg",
    "build actions": "https://github.com/samelamin/artemis/actions",
    "download badge": "https://img.shields.io/github/downloads/samelamin/artemis/total",
    "clone command": "git clone https://github.com/samelamin/artemis.git",
    "release downloads": "https://github.com/samelamin/artemis/releases",
}

README_UPSTREAM_LINE_ALLOWLIST = {
    "README.md": {
        "[Artemis Qt](https://github.com/wjbeckett/artemis) is an enhanced cross-platform client for NVIDIA GameStream and [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine) servers. It brings the advanced features from [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) to desktop platforms.",
        *TEMPORARY_README_ALLOWLIST,
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


def validate_readme_distribution_phase(readme):
    readme_lines = {line.strip() for line in readme.splitlines()}
    legacy_lines = TEMPORARY_README_ALLOWLIST & readme_lines
    fork_routes = {
        route_name
        for route_name, route in FORK_README_DISTRIBUTION_ROUTES.items()
        if route in readme
    }
    errors = []

    if fork_routes:
        missing_fork_routes = set(FORK_README_DISTRIBUTION_ROUTES) - fork_routes
        if missing_fork_routes:
            errors.append(
                "partial README migration; missing fork routes: "
                + ", ".join(sorted(missing_fork_routes))
            )
        if legacy_lines:
            errors.append(
                "legacy README routes remain after fork migration: "
                + " | ".join(sorted(legacy_lines))
            )
    else:
        missing_legacy_lines = TEMPORARY_README_ALLOWLIST - legacy_lines
        if missing_legacy_lines:
            errors.append(
                "pre-migration README must retain the complete deferred route set: "
                + " | ".join(sorted(missing_legacy_lines))
            )

    return errors


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
                "https://api.github.com/repos/samelamin/artemis/releases",
            "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml": (
                '<url type="homepage">https://github.com/samelamin/artemis</url>',
                '<url type="bugtracker">https://github.com/samelamin/artemis/issues</url>',
            ),
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml": (
                '<url type="homepage">https://github.com/samelamin/artemis</url>',
                '<url type="bugtracker">https://github.com/samelamin/artemis/issues</url>',
            ),
            "app/gui/main.qml":
                'Qt.openUrlExternally("https://github.com/samelamin/artemis/releases")',
            "wix/ArtemisSetup/Bundle.wxs":
                'UpdateUrl="https://github.com/samelamin/artemis/releases"',
            "wix/MoonlightSetup/Bundle.wxs":
                'UpdateUrl="https://github.com/samelamin/artemis/releases"',
            "docs/DEVELOPMENT.md":
                "**Repository**: https://github.com/samelamin/artemis",
        }

        for relative_path, routes in expected_routes.items():
            text = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8-sig")
            if isinstance(routes, str):
                routes = (routes,)
            for route in routes:
                with self.subTest(path=relative_path, route=route):
                    self.assertIn(route, text)

    def test_readme_distribution_routes_are_fork_owned_or_exactly_deferred(self):
        readme = (REPOSITORY_ROOT / "README.md").read_text(encoding="utf-8")
        self.assertEqual([], validate_readme_distribution_phase(readme))

    def test_readme_phase_rejects_partial_migration_and_legacy_reintroduction(self):
        current_readme = (REPOSITORY_ROOT / "README.md").read_text(encoding="utf-8")
        first_legacy_line = sorted(TEMPORARY_README_ALLOWLIST)[0]

        partial_legacy = current_readme.replace(first_legacy_line, "", 1)
        self.assertTrue(validate_readme_distribution_phase(partial_legacy))

        partial_migration = current_readme.replace(
            first_legacy_line,
            "https://github.com/samelamin/artemis/releases",
            1,
        )
        self.assertTrue(validate_readme_distribution_phase(partial_migration))

        final_readme = current_readme
        for legacy_line in TEMPORARY_README_ALLOWLIST:
            final_readme = final_readme.replace(legacy_line, "")
        final_readme += "\n" + "\n".join(FORK_README_DISTRIBUTION_ROUTES.values())
        self.assertEqual([], validate_readme_distribution_phase(final_readme))

        reintroduced = final_readme + "\n" + first_legacy_line
        self.assertTrue(validate_readme_distribution_phase(reintroduced))

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


if __name__ == "__main__":
    unittest.main()
