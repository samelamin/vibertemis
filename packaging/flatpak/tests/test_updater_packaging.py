from pathlib import Path
import json
import re
import subprocess
import sys
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
QML_RESOURCE = REPOSITORY_ROOT / "app" / "qml.qrc"
MAIN_QML = REPOSITORY_ROOT / "app" / "gui" / "main.qml"
SETTINGS_QML = REPOSITORY_ROOT / "app" / "gui" / "SettingsView.qml"
UPDATE_DIALOG_QML = REPOSITORY_ROOT / "app" / "gui" / "UpdateDialog.qml"
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "dev-build.yml"
UPDATE_MANIFEST_GENERATOR = (
    REPOSITORY_ROOT / "packaging" / "flatpak" / "generate-update-manifest.py"
)
WINDOWS_BUILD_SCRIPT = REPOSITORY_ROOT / "scripts" / "build-artemis-arch.bat"
MACOS_DMG_SCRIPT = REPOSITORY_ROOT / "scripts" / "generate-dmg.sh"
MACOS_VERIFY_SCRIPT = REPOSITORY_ROOT / "scripts" / "verify-macos-bundle.sh"
WINDOWS_WIX_PRODUCT = REPOSITORY_ROOT / "wix" / "Artemis" / "Product.wxs"


class UpdaterQmlContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resource = QML_RESOURCE.read_text(encoding="utf-8")
        cls.main = MAIN_QML.read_text(encoding="utf-8")
        cls.settings = SETTINGS_QML.read_text(encoding="utf-8")
        cls.dialog = (
            UPDATE_DIALOG_QML.read_text(encoding="utf-8")
            if UPDATE_DIALOG_QML.exists()
            else ""
        )
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.windows_build_script = WINDOWS_BUILD_SCRIPT.read_text(
            encoding="utf-8"
        )
        cls.macos_dmg_script = MACOS_DMG_SCRIPT.read_text(encoding="utf-8")
        cls.macos_verify_script = MACOS_VERIFY_SCRIPT.read_text(
            encoding="utf-8"
        )
        cls.windows_wix_product = WINDOWS_WIX_PRODUCT.read_text(
            encoding="utf-8"
        )

    def test_update_dialog_is_packaged_as_a_qml_resource(self):
        self.assertTrue(UPDATE_DIALOG_QML.is_file())
        self.assertIn("<file>gui/UpdateDialog.qml</file>", self.resource)
        self.assertRegex(self.dialog, r"\bNavigableDialog\s*\{")

    def test_python_packaging_distribution_is_not_shadowed(self):
        for marker in (
            REPOSITORY_ROOT / "packaging" / "__init__.py",
            REPOSITORY_ROOT / "packaging" / "flatpak" / "__init__.py",
            REPOSITORY_ROOT / "packaging" / "flatpak" / "tests" / "__init__.py",
        ):
            with self.subTest(marker=marker):
                self.assertFalse(marker.exists())

        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "from packaging.version import Version; "
                "assert Version('1.2.3') < Version('2')",
            ],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

        plan = (
            REPOSITORY_ROOT
            / "docs"
            / "superpowers"
            / "plans"
            / "2026-07-23-steam-deck-quick-start-and-updater.md"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "vibertemis_packaging_tests.test_updater_packaging",
            plan,
        )
        self.assertNotIn(
            "packaging.flatpak.tests.test_updater_packaging",
            plan,
        )
        alias_probe = subprocess.run(
            [
                sys.executable,
                "-c",
                "import vibertemis_packaging_tests.test_updater_packaging as m; "
                "assert m.UpdateManifestGeneratorTests is "
                "m._canonical.UpdateManifestGeneratorTests",
            ],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(alias_probe.returncode, 0, alias_probe.stderr)

    def test_toolbar_opens_modal_only_from_a_user_click(self):
        self.assertRegex(
            self.main,
            re.compile(
                r"id:\s*updateButton.*?onClicked:\s*\{.*?"
                r"if\s*\(AutoUpdateChecker\.rollingInstallSupported\).*?"
                r"updateDialog\.openForUserAction\(\).*?"
                r"else.*?AutoUpdateChecker\.openReleasePage\(\)",
                re.DOTALL,
            ),
        )
        self.assertNotRegex(
            self.main,
            r"visible:\s*AutoUpdateChecker\.releaseUrl\s*!==\s*\"\"",
        )
        self.assertRegex(
            self.main,
            re.compile(
                r"function\s+updateEntryVisible\(\)\s*\{.*?"
                r"AutoUpdateChecker\.releaseUrl\s*===\s*\"\".*?"
                r"!AutoUpdateChecker\.rollingInstallSupported.*?"
                r"AutoUpdateChecker\.state\s*===\s*AutoUpdateChecker\.Available.*?"
                r"AutoUpdateChecker\.Downloading.*?"
                r"AutoUpdateChecker\.Verifying.*?"
                r"AutoUpdateChecker\.ReadyForDesktop.*?"
                r"AutoUpdateChecker\.ReadyToHandOff.*?"
                r"AutoUpdateChecker\.HandOffRequested.*?"
                r"AutoUpdateChecker\.DownloadError.*?"
                r"AutoUpdateChecker\.VerificationError.*?"
                r"AutoUpdateChecker\.RestoreError.*?"
                r"AutoUpdateChecker\.HandOffError",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            self.main,
            r"visible:\s*window\.updateEntryVisible\(\)",
        )
        self.assertNotRegex(
            self.main,
            re.compile(
                r"(?:onStateChanged|stateChanged\.connect)"
                r"[\s\S]{0,240}updateDialog\.(?:open|openForUserAction)",
            ),
        )
        self.assertRegex(
            self.main,
            re.compile(
                r"Component\.onCompleted:\s*\{\s*"
                r"AutoUpdateChecker\.start\(\)\s*\}",
            ),
        )

    def test_settings_manual_check_is_controller_focusable_and_user_visible(self):
        self.assertRegex(
            self.settings,
            re.compile(
                r"Button\s*\{.*?id:\s*checkForUpdatesButton.*?"
                r"text:\s*qsTr\(\"Check for updates\"\).*?"
                r"activeFocusOnTab:\s*true.*?"
                r"Keys\.onReturnPressed:\s*clicked\(\).*?"
                r"Keys\.onEnterPressed:\s*clicked\(\).*?"
                r"onClicked:\s*\{\s*window\.checkForUpdatesFromSettings\(\)",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            self.main,
            re.compile(
                r"function\s+checkForUpdatesFromSettings\(\)\s*\{.*?"
                r"AutoUpdateChecker\.checkNow\(\).*?"
                r"updateDialog\.openForUserAction\(\)",
                re.DOTALL,
            ),
        )

    def test_dialog_projects_backend_state_and_has_all_explicit_actions(self):
        for state in (
            "Available",
            "Downloading",
            "ReadyForDesktop",
            "ReadyToHandOff",
            "HandOffRequested",
            "CheckError",
            "DownloadError",
            "VerificationError",
            "NoUpdate",
        ):
            with self.subTest(state=state):
                self.assertIn(f"AutoUpdateChecker.{state}", self.dialog)

        for label in (
            "Download",
            "Cancel",
            "Retry",
            "Later",
            "View release",
            "Open installer",
            "Copy manual command",
        ):
            with self.subTest(label=label):
                self.assertIn(f'qsTr("{label}")', self.dialog)

        for action in (
            "downloadUpdate",
            "cancel",
            "retry",
            "openReleasePage",
            "openInstaller",
        ):
            with self.subTest(action=action):
                self.assertIn(f"AutoUpdateChecker.{action}()", self.dialog)

    def test_dialog_shows_build_identity_and_expected_download_size(self):
        for label in (
            "Current build:",
            "Available build:",
            "Download size:",
        ):
            with self.subTest(label=label):
                self.assertIn(f'qsTr("{label}")', self.dialog)

        self.assertIn("AutoUpdateChecker.currentBuild", self.dialog)
        self.assertIn("AutoUpdateChecker.availableBuild", self.dialog)
        self.assertIn("AutoUpdateChecker.expectedDownloadBytes", self.dialog)
        self.assertRegex(
            self.dialog,
            r"function\s+formatDownloadSize\(bytes\)\s*\{",
        )
        for unit in ("KiB", "MiB", "GiB"):
            with self.subTest(unit=unit):
                self.assertIn(f'qsTr("{unit}")', self.dialog)
        self.assertRegex(
            self.dialog,
            r"formatDownloadSize\(\s*"
            r"AutoUpdateChecker\.expectedDownloadBytes\s*\)",
        )

    def test_installer_and_progress_are_gated_by_verified_state(self):
        self.assertRegex(
            self.dialog,
            re.compile(
                r"id:\s*openInstallerButton.*?"
                r"visible:\s*AutoUpdateChecker\.state\s*===\s*"
                r"AutoUpdateChecker\.ReadyToHandOff.*?"
                r"enabled:\s*AutoUpdateChecker\.state\s*===\s*"
                r"AutoUpdateChecker\.ReadyToHandOff",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            self.dialog,
            r"indeterminate:\s*AutoUpdateChecker\.bytesTotal\s*<=\s*0",
        )
        self.assertRegex(
            self.dialog,
            r"value:\s*AutoUpdateChecker\.bytesTotal\s*>\s*0\s*\?",
        )
        self.assertRegex(
            self.dialog,
            re.compile(
                r"function\s+manualCommandAvailable\(\)\s*\{.*?"
                r"AutoUpdateChecker\.manualInstallCommand\s*!==\s*\"\".*?"
                r"AutoUpdateChecker\.ReadyForDesktop.*?"
                r"AutoUpdateChecker\.ReadyToHandOff.*?"
                r"AutoUpdateChecker\.HandOffRequested.*?"
                r"AutoUpdateChecker\.HandOffError",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            self.dialog,
            r"id:\s*copyManualCommandButton[\s\S]{0,300}"
            r"visible:\s*updateDialog\.manualCommandAvailable\(\)",
        )

    def test_mode_switch_and_handoff_copy_do_not_claim_installation(self):
        self.assertRegex(
            self.dialog,
            re.compile(
                r"AutoUpdateChecker\.ReadyForDesktop[\s\S]{0,700}"
                r"switch to Desktop Mode[\s\S]{0,700}"
                r"(?:remain|keep|preserv)[^\"]*(?:Downloads|downloaded file)",
                re.IGNORECASE,
            ),
        )
        self.assertIn("installer requested", self.dialog.lower())
        self.assertNotIn("installed", self.dialog.lower())
        self.assertNotIn("updated successfully", self.dialog.lower())

    def test_manual_command_uses_only_the_verified_downloads_path(self):
        self.assertNotIn("property string manualCommand", self.dialog)
        self.assertNotIn("AutoUpdateChecker.downloadedPath", self.dialog)
        self.assertRegex(
            self.dialog,
            r"id:\s*manualCommandField[\s\S]{0,300}"
            r"text:\s*AutoUpdateChecker\.manualInstallCommand",
        )
        self.assertRegex(
            self.dialog,
            re.compile(
                r"id:\s*copyManualCommandButton[\s\S]{0,500}"
                r"manualCommandField\.selectAll\(\)[\s\S]{0,200}"
                r"manualCommandField\.copy\(\)",
            ),
        )

    def test_controller_navigation_activation_dismissal_and_focus_are_explicit(self):
        self.assertRegex(
            self.dialog,
            r"onOpened:\s*\{[\s\S]{0,500}forceActiveFocus\(Qt\.TabFocus\)",
        )
        self.assertRegex(
            self.dialog,
            r"Connections\s*\{[\s\S]{0,300}"
            r"target:\s*AutoUpdateChecker[\s\S]{0,500}"
            r"onStateChanged:[\s\S]{0,300}focus",
        )
        self.assertNotRegex(
            self.dialog,
            r"Connections\s*\{[\s\S]{0,300}"
            r"target:\s*AutoUpdateChecker[\s\S]{0,500}"
            r"function\s+onStateChanged\(\)",
        )
        self.assertIn(
            "nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)",
            self.dialog,
        )
        self.assertIn(
            "nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)",
            self.dialog,
        )
        self.assertIn("Keys.onReturnPressed: clicked()", self.dialog)
        self.assertIn("Keys.onEnterPressed: clicked()", self.dialog)
        self.assertRegex(
            self.dialog,
            r"Keys\.onEscapePressed:\s*\{[\s\S]{0,300}dismissOrCancel\(\)",
        )
        self.assertRegex(
            self.dialog,
            r"Keys\.onBackPressed:\s*\{[\s\S]{0,300}dismissOrCancel\(\)",
        )
        self.assertRegex(
            self.dialog,
            r"footer:\s*DialogButtonBox\s*\{[\s\S]{0,300}"
            r"id:\s*actionButtons[\s\S]{0,300}"
            r"Keys\.onEscapePressed:",
        )
        self.assertRegex(
            self.dialog,
            r"function\s+dismissOrCancel\(\)\s*\{[\s\S]{0,500}"
            r"AutoUpdateChecker\.cancel\(\)[\s\S]{0,500}(?:close|reject)\(\)",
        )
        self.assertRegex(
            self.dialog,
            r"onAboutToHide:\s*\{[\s\S]{0,300}"
            r"(?:previousFocusItem|stackView)\.forceActiveFocus",
        )

    def test_workflow_sets_safe_native_identity_defaults(self):
        workflow_env = self.workflow[
            self.workflow.index("env:") : self.workflow.index("\njobs:")
        ]
        self.assertIn("VIBERTEMIS_BUILD_COMMIT: ${{ github.sha }}", workflow_env)
        self.assertRegex(
            workflow_env,
            re.compile(
                r"VIBERTEMIS_UPDATE_CHANNEL:\s*>-.*?"
                r"github\.repository\s*==\s*'samelamin/vibertemis'.*?"
                r"github\.ref\s*==\s*'refs/heads/main'.*?"
                r"'stable'\s*\|\|\s*'none'",
                re.DOTALL,
            ),
        )
        self.assertIn("VIBERTEMIS_BUILD_SEQUENCE: 0", workflow_env)
        self.assertIn(
            "VIBERTEMIS_APPLICATION_ID: com.artemis_desktop.Artemis",
            workflow_env,
        )

    def test_flatpak_job_derives_and_embeds_exact_rolling_identity(self):
        flatpak_job = self.workflow[self.workflow.index("  build-flatpak-dev:") :]
        flatpak_job = flatpak_job[
            : flatpak_job.index("\n  publish-steam-deck-release:")
        ]
        for token in (
            "id: flatpak_identity",
            'GITHUB_REPOSITORY" == "samelamin/vibertemis',
            'GITHUB_REF" == "refs/heads/main',
            "channel=rolling",
            "sequence=$GITHUB_RUN_ID",
            "channel=none",
            "sequence=0",
            "packaging/flatpak/prepare-ci-manifest.py",
            "--commit \"${{ steps.flatpak_identity.outputs.commit }}\"",
            "--channel \"${{ steps.flatpak_identity.outputs.channel }}\"",
            "--sequence \"${{ steps.flatpak_identity.outputs.sequence }}\"",
            "--application-id com.artemisdesktop.ArtemisDesktopDev",
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.ci.json",
        ):
            with self.subTest(token=token):
                self.assertIn(token, flatpak_job)
        self.assertNotIn(
            "flatpak-builder --force-clean --install-deps-from=flathub "
            "--repo=flatpak-repo flatpak-build "
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json",
            flatpak_job,
        )

    def test_workflow_verifies_every_package_build_identity_before_upload(self):
        for binary in (
            r"build\build-x64-release\app\release\Artemis.exe",
            r"build\build-arm64-release\app\release\Artemis.exe",
            "build/build-Release/app/Vibertemis.app/Contents/MacOS/Artemis",
            "app/artemis",
            "/app/bin/artemis",
        ):
            with self.subTest(binary=binary):
                self.assertIn(binary, self.workflow)
        for field in (
            "schema",
            "applicationId",
            "commit",
            "channel",
            "sequence",
            "version",
        ):
            with self.subTest(field=field):
                self.assertRegex(
                    self.workflow,
                    rf"(?:buildInfo|data).{field}|{field}.+(?:buildInfo|data)",
                )
        self.assertIn("flatpak-build-info.json", self.workflow)
        flatpak_upload = self.workflow[
            self.workflow.index("- name: Upload Flatpak Development Artifacts") :
        ]
        self.assertIn("flatpak-build-info.json", flatpak_upload)

    def test_compile_sanity_runs_updater_suite_on_linux_and_macos(self):
        compile_sanity = self.workflow[
            self.workflow.index("  compile-sanity:") :
            self.workflow.index("\n  build-windows-x64-portable:")
        ]
        self.assertIn("matrix:", compile_sanity)
        self.assertIn("os: [macos-latest, ubuntu-22.04]", compile_sanity)
        self.assertIn("qmake6 ../../tests/autoupdate/autoupdate.pro", compile_sanity)
        self.assertIn("QT_QPA_PLATFORM=offscreen ./tst_autoupdate", compile_sanity)
        self.assertRegex(
            compile_sanity,
            r"sudo apt-get install[\s\S]*libdbus-1-dev",
        )

    def test_windows_portable_builds_use_architecture_compatible_runners(self):
        x64_job = self.workflow[
            self.workflow.index("  build-windows-x64-portable:") :
            self.workflow.index("\n  build-windows-arm64-portable:")
        ]
        arm64_job = self.workflow[
            self.workflow.index("  build-windows-arm64-portable:") :
            self.workflow.index("\n  build-windows-msi-components:")
        ]

        self.assertIn("runs-on: windows-latest", x64_job)
        self.assertIn("arch: 'win64_msvc2022_64'", x64_job)
        self.assertIn(
            r'build\build-x64-release\app\release\Artemis.exe',
            x64_job,
        )

        self.assertIn("runs-on: windows-11-arm", arm64_job)
        self.assertNotIn("runs-on: windows-latest", arm64_job)
        self.assertIn("host: 'windows_arm64'", arm64_job)
        self.assertIn("arch: 'win64_msvc2022_arm64'", arm64_job)
        self.assertNotIn("win64_msvc2022_arm64_cross_compiled", arm64_job)
        self.assertRegex(arm64_job, r"(?m)^\s+arch: arm64\s*$")
        self.assertNotIn("arch: amd64_arm64", arm64_job)
        self.assertIn(
            r'build\build-arm64-release\app\release\Artemis.exe',
            arm64_job,
        )

    def test_windows_msi_components_use_native_matrix_and_artifact_handoff(self):
        component_marker = "  build-windows-msi-components:"
        self.assertIn(component_marker, self.workflow)
        components_job = self.workflow[
            self.workflow.index(component_marker) :
            self.workflow.index("\n  build-windows-universal-installer:")
        ]
        universal_job = self.workflow[
            self.workflow.index("  build-windows-universal-installer:") :
            self.workflow.index("\n  build-macos-dev:")
        ]

        self.assertIn("architecture: [x64, arm64]", components_job)
        self.assertIn(
            "runs-on: ${{ matrix.architecture == 'arm64' && "
            "'windows-11-arm' || 'windows-latest' }}",
            components_job,
        )
        self.assertIn(
            "arch: ${{ matrix.architecture == 'arm64' && "
            "'arm64' || 'x64' }}",
            components_job,
        )
        self.assertIn("if: matrix.architecture == 'x64'", components_job)
        self.assertIn("uses: jurplel/install-qt-action@v3", components_job)
        self.assertIn("host: 'windows'", components_job)
        self.assertIn("arch: 'win64_msvc2022_64'", components_job)
        self.assertIn("if: matrix.architecture == 'arm64'", components_job)
        self.assertIn("uses: jurplel/install-qt-action@v4", components_job)
        self.assertIn("host: 'windows_arm64'", components_job)
        self.assertIn("arch: 'win64_msvc2022_arm64'", components_job)
        self.assertNotIn(
            "win64_msvc2022_arm64_cross_compiled",
            components_job,
        )
        self.assertNotIn("arch: amd64_arm64", components_job)
        self.assertIn(
            r'build\build-x64-release\app\release\Artemis.exe',
            components_job,
        )
        self.assertIn(
            r'build\build-arm64-release\app\release\Artemis.exe',
            components_job,
        )
        self.assertIn(
            "name: vibertemis-windows-msi-${{ matrix.architecture }}-"
            "${{ needs.setup-version.outputs.version }}",
            components_job,
        )

        self.assertIn(
            "needs: [setup-version, build-windows-msi-components]",
            universal_job,
        )
        self.assertIn("runs-on: windows-latest", universal_job)
        for architecture in ("x64", "arm64"):
            artifact_name = (
                f"name: vibertemis-windows-msi-{architecture}-"
                "${{ needs.setup-version.outputs.version }}"
            )
            with self.subTest(architecture=architecture):
                self.assertIn(artifact_name, universal_job)
                self.assertIn(
                    f"path: build/build-{architecture}-release",
                    universal_job,
                )
        self.assertNotIn(
            r'build\build-arm64-release\app\release\Artemis.exe',
            universal_job,
        )

    def test_native_workflow_verifies_build_info_before_packaging(self):
        x64_job = self.workflow[
            self.workflow.index("  build-windows-x64-portable:") :
            self.workflow.index("\n  build-windows-arm64-portable:")
        ]
        arm64_job = self.workflow[
            self.workflow.index("  build-windows-arm64-portable:") :
            self.workflow.index("\n  build-windows-msi-components:")
        ]
        msi_job = self.workflow[
            self.workflow.index("  build-windows-msi-components:") :
            self.workflow.index("\n  build-windows-universal-installer:")
        ]
        macos_job = self.workflow[
            self.workflow.index("  build-macos-dev:") :
            self.workflow.index("\n  build-linux-dev:")
        ]

        required_tokens = (
            "release build-only",
            "release package-only",
            "- name: Package verified x64 executable",
            "- name: Package verified ARM64 executable",
            "- name: Verify MSI component identity",
            "- name: Package verified MSI component",
            './scripts/generate-dmg.sh Release "$VERSION" build-only',
            './scripts/generate-dmg.sh Release "$VERSION" package-only',
        )
        self.assertEqual(
            [],
            [token for token in required_tokens if token not in self.workflow],
        )

        for name, job, verification_step, packaging_step in (
            (
                "x64 portable",
                x64_job,
                "- name: Verify x64 native build identity",
                "- name: Package verified x64 executable",
            ),
            (
                "ARM64 portable",
                arm64_job,
                "- name: Verify ARM64 native build identity",
                "- name: Package verified ARM64 executable",
            ),
        ):
            with self.subTest(job=name):
                build = job.index(
                    'cmd /c "scripts\\build-artemis-arch.bat '
                    'release build-only"'
                )
                verify = job.index(verification_step)
                package_step = job.index(packaging_step)
                package = job.index(
                    'cmd /c "scripts\\build-artemis-arch.bat '
                    'release package-only"'
                )
                rename = job.index("- name: Rename", package)
                self.assertLess(build, verify)
                self.assertLess(verify, package_step)
                self.assertLess(package_step, package)
                self.assertLess(package, rename)

        msi_build = msi_job.index(
            'cmd /c "scripts\\build-artemis-arch.bat release build-only"'
        )
        msi_verify = msi_job.index("- name: Verify MSI component identity")
        msi_package_step = msi_job.index(
            "- name: Package verified MSI component"
        )
        msi_package = msi_job.index(
            'cmd /c "scripts\\build-artemis-arch.bat release package-only"'
        )
        msi_handoff = msi_job.index("Copy-Item $msiFile.FullName")
        msi_upload = msi_job.index("- name: Upload verified MSI component")
        self.assertLess(msi_build, msi_verify)
        self.assertLess(msi_verify, msi_package_step)
        self.assertLess(msi_package_step, msi_package)
        self.assertLess(msi_package, msi_handoff)
        self.assertLess(msi_handoff, msi_upload)

        macos_build = macos_job.index(
            './scripts/generate-dmg.sh Release "$VERSION" build-only'
        )
        macos_preflight = macos_job.index(
            "build/build-Release/app/Artemis.app/Contents/MacOS/Artemis"
        )
        macos_package = macos_job.index(
            './scripts/generate-dmg.sh Release "$VERSION" package-only'
        )
        macos_postflight = macos_job.index(
            "build/build-Release/app/Vibertemis.app/Contents/MacOS/Artemis",
            macos_package,
        )
        mounted_verification = macos_job.index(
            "./scripts/verify-macos-bundle.sh",
            macos_postflight,
        )
        upload = macos_job.index("- name: Upload macOS DMG")
        self.assertLess(macos_build, macos_preflight)
        self.assertLess(macos_preflight, macos_package)
        self.assertLess(macos_package, macos_postflight)
        self.assertLess(macos_postflight, mounted_verification)
        self.assertLess(mounted_verification, upload)

    def test_packaging_scripts_preserve_default_and_split_phase_binding(self):
        self.assertIn("build-only", self.windows_build_script)
        self.assertIn("package-only", self.windows_build_script)
        self.assertIn("build-only", self.macos_dmg_script)
        self.assertIn("package-only", self.macos_dmg_script)
        for phase in ("full", "build-only", "package-only"):
            with self.subTest(windows_phase=phase):
                self.assertIn(phase, self.windows_build_script)
            with self.subTest(macos_phase=phase):
                self.assertIn(phase, self.macos_dmg_script)

        self.assertIn(
            'if /I "%BUILD_PHASE%"=="package-only" goto Packaging',
            self.windows_build_script,
        )
        self.assertIn(
            'if /I "%BUILD_PHASE%"=="build-only"',
            self.windows_build_script,
        )
        windows_package_jump = self.windows_build_script.index(
            'if /I "%BUILD_PHASE%"=="package-only" goto Packaging'
        )
        windows_clean = self.windows_build_script.index(
            "echo Cleaning output directories"
        )
        windows_build_only = self.windows_build_script.index(
            'if /I "%BUILD_PHASE%"=="build-only"'
        )
        windows_package_label = self.windows_build_script.index(":Packaging")
        windows_package = self.windows_build_script.index("echo Building MSI")
        self.assertLess(windows_package_jump, windows_clean)
        self.assertLess(windows_clean, windows_build_only)
        self.assertLess(windows_build_only, windows_package_label)
        self.assertLess(windows_package_label, windows_package)
        self.assertRegex(
            self.windows_build_script,
            re.compile(
                r'if not exist "%BUILD_FOLDER%\\app\\%BUILD_CONFIG%'
                r'\\Artemis\.exe" \(\s*'
                r"echo ERROR: Cannot package because the verified executable "
                r"is missing\s*"
                r"exit /b 1\s*"
                r"\)",
            ),
        )

        verified_executable = (
            r"%BUILD_FOLDER%\app\%BUILD_CONFIG%\Artemis.exe"
        )
        self.assertIn(
            f"copy {verified_executable} %DEPLOY_FOLDER%\\Artemis.exe",
            self.windows_build_script,
        )
        self.assertIn(
            'Source="$(var.BuildDir)\\app\\$(var.Configuration)'
            '\\Artemis.exe"',
            self.windows_wix_product,
        )

        self.assertIn(
            'if [[ "$BUILD_PHASE" != "package-only" ]]',
            self.macos_dmg_script,
        )
        self.assertIn(
            'if [[ "$BUILD_PHASE" == "build-only" ]]',
            self.macos_dmg_script,
        )
        macos_package_guard = self.macos_dmg_script.index(
            'if [[ "$BUILD_PHASE" != "package-only" ]]'
        )
        macos_clean = self.macos_dmg_script.index(
            'rm -rf "$BUILD_FOLDER" "$INSTALLER_FOLDER"'
        )
        macos_build_only = self.macos_dmg_script.index(
            'if [[ "$BUILD_PHASE" == "build-only" ]]'
        )
        macos_move = self.macos_dmg_script.index(
            'mv "$BUILT_APP_PATH" "$APP_PATH"'
        )
        self.assertLess(macos_package_guard, macos_clean)
        self.assertLess(macos_clean, macos_build_only)
        self.assertLess(macos_build_only, macos_move)

    def test_macos_verifier_inspects_source_and_mounted_build_identity(self):
        self.assertIn('"$executable" --build-info', self.macos_verify_script)
        for field in (
            "schema",
            "applicationId",
            "commit",
            "channel",
            "sequence",
            "version",
            "internallyConsistent",
        ):
            with self.subTest(field=field):
                self.assertIn(f'data["{field}"]', self.macos_verify_script)
        self.assertIn('verify_app "$APP_PATH"', self.macos_verify_script)
        self.assertIn(
            'verify_app "$MOUNT_DIR/Vibertemis.app"',
            self.macos_verify_script,
        )


class UpdateManifestGeneratorTests(unittest.TestCase):
    def run_generator(self, **overrides):
        defaults = {
            "source_commit": "a" * 40,
            "build_sequence": "1234567890123456789",
            "release_id": "9007199254740993",
            "flatpak_asset_id": "9007199254740995",
            "flatpak_size": "18446744073709551615",
            "flatpak_sha256": "b" * 64,
            "published_at": "2026-07-23T14:25:53Z",
        }
        defaults.update(overrides)
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        output = Path(temporary_directory.name) / "update.json"
        command = [
            sys.executable,
            str(UPDATE_MANIFEST_GENERATOR),
            "--output",
            str(output),
            "--source-commit",
            defaults["source_commit"],
            "--build-sequence",
            defaults["build_sequence"],
            "--release-id",
            defaults["release_id"],
            "--flatpak-asset-id",
            defaults["flatpak_asset_id"],
            "--flatpak-size",
            defaults["flatpak_size"],
            "--flatpak-sha256",
            defaults["flatpak_sha256"],
            "--published-at",
            defaults["published_at"],
        ]
        result = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            check=False,
            text=True,
        )
        return result, output

    def test_generator_writes_exact_sorted_compact_manifest(self):
        result, output = self.run_generator()

        self.assertEqual(0, result.returncode, result.stderr)
        expected = {
            "application_id": "com.artemisdesktop.ArtemisDesktopDev",
            "build_sequence": "1234567890123456789",
            "flatpak": {
                "asset_id": "9007199254740995",
                "name": "artemis-steam-deck.flatpak",
                "sha256": "b" * 64,
                "size": "18446744073709551615",
            },
            "published_at": "2026-07-23T14:25:53Z",
            "release_id": "9007199254740993",
            "repository": "samelamin/vibertemis",
            "schema": 1,
            "source_commit": "a" * 40,
            "tag": "steam-deck-latest",
            "tag_commit": "a" * 40,
        }
        expected_bytes = (
            json.dumps(expected, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode("utf-8")
        self.assertEqual(expected_bytes, output.read_bytes())

    def test_generator_rejects_noncanonical_or_out_of_range_inputs(self):
        invalid_inputs = (
            ("uppercase commit", {"source_commit": "A" * 40}),
            ("short commit", {"source_commit": "a" * 39}),
            ("uppercase digest", {"flatpak_sha256": "B" * 64}),
            ("short digest", {"flatpak_sha256": "b" * 63}),
            ("zero sequence", {"build_sequence": "0"}),
            ("leading-zero sequence", {"build_sequence": "01"}),
            ("negative sequence", {"build_sequence": "-1"}),
            (
                "sequence overflow",
                {"build_sequence": "18446744073709551616"},
            ),
            ("zero release id", {"release_id": "0"}),
            ("fractional release id", {"release_id": "1.5"}),
            (
                "release id overflow",
                {"release_id": "18446744073709551616"},
            ),
            ("zero asset id", {"flatpak_asset_id": "0"}),
            ("unsafe asset id", {"flatpak_asset_id": "1;touch"}),
            ("zero size", {"flatpak_size": "0"}),
            ("leading-zero size", {"flatpak_size": "01"}),
            (
                "size overflow",
                {"flatpak_size": "18446744073709551616"},
            ),
            (
                "non-UTC timestamp",
                {"published_at": "2026-07-23T15:25:53+01:00"},
            ),
            (
                "fractional timestamp",
                {"published_at": "2026-07-23T14:25:53.123Z"},
            ),
            ("invalid date", {"published_at": "2026-02-30T14:25:53Z"}),
        )
        for name, overrides in invalid_inputs:
            with self.subTest(name=name):
                result, output = self.run_generator(**overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("ERROR:", result.stderr)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
