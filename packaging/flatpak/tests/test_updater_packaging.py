from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
QML_RESOURCE = REPOSITORY_ROOT / "app" / "qml.qrc"
MAIN_QML = REPOSITORY_ROOT / "app" / "gui" / "main.qml"
SETTINGS_QML = REPOSITORY_ROOT / "app" / "gui" / "SettingsView.qml"
UPDATE_DIALOG_QML = REPOSITORY_ROOT / "app" / "gui" / "UpdateDialog.qml"


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

    def test_update_dialog_is_packaged_as_a_qml_resource(self):
        self.assertTrue(UPDATE_DIALOG_QML.is_file())
        self.assertIn("<file>gui/UpdateDialog.qml</file>", self.resource)
        self.assertRegex(self.dialog, r"\bNavigableDialog\s*\{")

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
                r"AutoUpdateChecker\.downloadedPath\s*!==\s*\"\".*?"
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
        self.assertRegex(
            self.dialog,
            re.compile(
                r"property\s+string\s+manualCommand:\s*"
                r"'flatpak install --user --or-update \"'\s*\+\s*"
                r"AutoUpdateChecker\.downloadedPath\s*\+\s*'\"'",
            ),
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
            r"(?:onStateChanged:|function\s+onStateChanged\(\))"
            r"[\s\S]{0,300}focus",
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


if __name__ == "__main__":
    unittest.main()
