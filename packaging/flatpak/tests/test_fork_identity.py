from pathlib import Path
import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from urllib.parse import unquote


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


def obsolete_fork_occurrences(relative_path, text):
    occurrences = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        normalized_line = repeatedly_url_decode(line).casefold()
        if OBSOLETE_FORK_REPOSITORY.casefold() in normalized_line:
            occurrences.append(
                f"{relative_path}:{line_number}: {line.strip()}"
            )
    return occurrences


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
        expected_identity = {
            "app/deploy/linux/com.artemis_desktop.Artemis.desktop":
                "Name=Vibertemis",
            "app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml":
                "<name>Vibertemis</name>",
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.desktop":
                "Name=Vibertemis",
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml":
                "<name>Vibertemis</name>",
            "app/main.cpp":
                'QCoreApplication::setApplicationDisplayName("Vibertemis")',
            "app/Info.plist": (
                "<key>CFBundleDisplayName</key>\n"
                "\t<string>Vibertemis</string>"
            ),
        }

        for relative_path, identity in expected_identity.items():
            text = (REPOSITORY_ROOT / relative_path).read_text(
                encoding="utf-8-sig"
            )
            with self.subTest(path=relative_path):
                self.assertIn(identity, text)

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
