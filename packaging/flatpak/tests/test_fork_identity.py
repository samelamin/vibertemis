from pathlib import Path
import re
import subprocess
import unittest


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

UPSTREAM_ATTRIBUTION_ALLOWLIST = {
    (
        "README.md",
        "[Artemis Qt](https://github.com/wjbeckett/artemis) is an enhanced cross-platform client for NVIDIA GameStream and [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine) servers. It brings the advanced features from [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) to desktop platforms.",
    ),
}

UPSTREAM_WIKI_ALLOWLIST = {
    ".github/ISSUE_TEMPLATE/bug_report.md": {
        "https://github.com/wjbeckett/artemis/wiki/Troubleshooting",
        "https://github.com/wjbeckett/artemis/wiki/Setup-Guide",
    },
    "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml": {
        "https://github.com/wjbeckett/artemis/wiki",
    },
    "app/gui/main.qml": {
        "https://github.com/wjbeckett/artemis/wiki/Setup-Guide",
        "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems",
        "https://github.com/wjbeckett/artemis/wiki/Gamepad-Mapping",
    },
    "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml": {
        "https://github.com/wjbeckett/artemis/wiki",
    },
    "wix/ArtemisSetup/Bundle.wxs": {
        "https://github.com/wjbeckett/artemis/wiki/Setup-Guide",
    },
    "wix/MoonlightSetup/Bundle.wxs": {
        "https://github.com/wjbeckett/artemis/wiki/Setup-Guide",
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
        if relative_path == "README.md" or relative_path.startswith(
            ("app/", "docs/", "packaging/", "wix/", ".github/")
        ):
            yield relative_path


class ForkIdentityTests(unittest.TestCase):
    def test_upstream_routes_are_narrowly_allowlisted_and_labelled(self):
        unexpected = []

        for relative_path in tracked_identity_files():
            path = REPOSITORY_ROOT / relative_path
            if not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                continue

            if UPSTREAM_REPOSITORY not in text:
                continue

            lines = text.splitlines()
            for line_number, line in enumerate(lines, start=1):
                if UPSTREAM_REPOSITORY not in line:
                    continue
                stripped_line = line.strip()
                occurrence = (relative_path, stripped_line)
                if occurrence in UPSTREAM_ATTRIBUTION_ALLOWLIST:
                    continue
                if (
                    relative_path == "README.md"
                    and stripped_line in TEMPORARY_README_ALLOWLIST
                ):
                    continue
                upstream_urls = set(
                    re.findall(
                        r"https://github\.com/wjbeckett/artemis(?:/[^\s)\"'<>]*)?",
                        line,
                    )
                )
                allowed_wiki_urls = UPSTREAM_WIKI_ALLOWLIST.get(relative_path, set())
                if upstream_urls and upstream_urls <= allowed_wiki_urls:
                    label_window = "\n".join(
                        lines[max(0, line_number - 8):line_number + 7]
                    )
                    self.assertIn(
                        "Upstream Artemis",
                        label_window,
                        f"{relative_path}:{line_number} must label its upstream-only wiki route",
                    )
                    continue
                unexpected.append(f"{relative_path}:{line_number}: {stripped_line}")

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
        route_pairs = {
            "build badge": (
                "https://github.com/samelamin/artemis/workflows/Build%20Artemis%20Qt/badge.svg",
                "https://github.com/wjbeckett/artemis/workflows/Build%20Artemis%20Qt/badge.svg",
            ),
            "download badge": (
                "https://img.shields.io/github/downloads/samelamin/artemis/total",
                "https://img.shields.io/github/downloads/wjbeckett/artemis/total",
            ),
            "clone command": (
                "git clone https://github.com/samelamin/artemis.git",
                "git clone https://github.com/wjbeckett/artemis.git",
            ),
            "release download": (
                "https://github.com/samelamin/artemis/releases",
                "https://github.com/wjbeckett/artemis/releases",
            ),
        }

        for route_name, (fork_route, deferred_route) in route_pairs.items():
            with self.subTest(route=route_name):
                self.assertTrue(
                    fork_route in readme or deferred_route in readme,
                    f"README must contain the fork {route_name}",
                )


if __name__ == "__main__":
    unittest.main()
