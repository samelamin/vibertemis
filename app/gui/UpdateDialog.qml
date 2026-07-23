import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
import QtQuick.Window 2.2

import AutoUpdateChecker 1.0

NavigableDialog {
    id: updateDialog

    property var previousFocusItem: null
    property string manualCommand: 'flatpak install --user --or-update "' +
                                   AutoUpdateChecker.downloadedPath + '"'

    modal: true
    title: qsTr("Application update")
    width: Math.min(parent ? parent.width - 40 : 640, 640)

    function isBusyState() {
        return AutoUpdateChecker.state === AutoUpdateChecker.RestoringPending ||
               AutoUpdateChecker.state === AutoUpdateChecker.Checking ||
               AutoUpdateChecker.state === AutoUpdateChecker.Downloading ||
               AutoUpdateChecker.state === AutoUpdateChecker.Verifying ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandingOff
    }

    function canCancelOperation() {
        return AutoUpdateChecker.state === AutoUpdateChecker.RestoringPending ||
               AutoUpdateChecker.state === AutoUpdateChecker.Checking ||
               AutoUpdateChecker.state === AutoUpdateChecker.Downloading ||
               AutoUpdateChecker.state === AutoUpdateChecker.Verifying
    }

    function isRetryState() {
        return AutoUpdateChecker.state === AutoUpdateChecker.CheckError ||
               AutoUpdateChecker.state === AutoUpdateChecker.DownloadError ||
               AutoUpdateChecker.state === AutoUpdateChecker.VerificationError ||
               AutoUpdateChecker.state === AutoUpdateChecker.RestoreError ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandOffError ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandOffRequested ||
               AutoUpdateChecker.state === AutoUpdateChecker.ReadyForDesktop
    }

    function manualCommandAvailable() {
        return AutoUpdateChecker.downloadedPath !== "" &&
               (AutoUpdateChecker.state === AutoUpdateChecker.ReadyForDesktop ||
                AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandingOff ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandOffRequested ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandOffError)
    }

    function candidateDetailsVisible() {
        return AutoUpdateChecker.releaseUrl !== "" &&
               (AutoUpdateChecker.state === AutoUpdateChecker.Available ||
                AutoUpdateChecker.state === AutoUpdateChecker.Downloading ||
                AutoUpdateChecker.state === AutoUpdateChecker.Verifying ||
                AutoUpdateChecker.state === AutoUpdateChecker.ReadyForDesktop ||
                AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandingOff ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandOffRequested ||
                AutoUpdateChecker.state === AutoUpdateChecker.DownloadError ||
                AutoUpdateChecker.state === AutoUpdateChecker.VerificationError ||
                AutoUpdateChecker.state === AutoUpdateChecker.RestoreError ||
                AutoUpdateChecker.state === AutoUpdateChecker.HandOffError)
    }

    function formatDownloadSize(bytes) {
        var value = bytes
        var unit = qsTr("bytes")
        if (bytes >= 1024 * 1024 * 1024) {
            value = bytes / (1024 * 1024 * 1024)
            unit = qsTr("GiB")
        } else if (bytes >= 1024 * 1024) {
            value = bytes / (1024 * 1024)
            unit = qsTr("MiB")
        } else if (bytes >= 1024) {
            value = bytes / 1024
            unit = qsTr("KiB")
        }
        return value.toFixed(value >= 10 ? 0 : 1) + " " + unit
    }

    function stateHeading() {
        switch (AutoUpdateChecker.state) {
        case AutoUpdateChecker.RestoringPending:
            return qsTr("Checking a downloaded update")
        case AutoUpdateChecker.Checking:
            return qsTr("Checking for updates")
        case AutoUpdateChecker.NoUpdate:
            return qsTr("The application is up to date")
        case AutoUpdateChecker.Available:
            return qsTr("Update available")
        case AutoUpdateChecker.Downloading:
            return qsTr("Downloading update")
        case AutoUpdateChecker.Verifying:
            return qsTr("Verifying update")
        case AutoUpdateChecker.ReadyForDesktop:
            return qsTr("Ready for Desktop Mode")
        case AutoUpdateChecker.ReadyToHandOff:
            return qsTr("Ready to open the installer")
        case AutoUpdateChecker.HandingOff:
            return qsTr("Requesting installer")
        case AutoUpdateChecker.HandOffRequested:
            return qsTr("Installer requested")
        case AutoUpdateChecker.CheckError:
            return qsTr("Update check failed")
        case AutoUpdateChecker.DownloadError:
            return qsTr("Download failed")
        case AutoUpdateChecker.VerificationError:
            return qsTr("Verification failed")
        case AutoUpdateChecker.RestoreError:
            return qsTr("Downloaded update unavailable")
        case AutoUpdateChecker.HandOffError:
            return qsTr("Could not request the installer")
        case AutoUpdateChecker.Cancelled:
            return qsTr("Update cancelled")
        default:
            return qsTr("Updates")
        }
    }

    function stateMessage() {
        switch (AutoUpdateChecker.state) {
        case AutoUpdateChecker.RestoringPending:
            return qsTr("Re-checking the verified file in Downloads.")
        case AutoUpdateChecker.Checking:
            return qsTr("Looking for a newer build.")
        case AutoUpdateChecker.NoUpdate:
            return qsTr("No newer update is available.")
        case AutoUpdateChecker.Available:
            return AutoUpdateChecker.rollingInstallSupported ?
                       qsTr("Build %1 is ready to download.").arg(AutoUpdateChecker.availableBuild) :
                       qsTr("Version %1 is available on the release page.").arg(AutoUpdateChecker.availableBuild)
        case AutoUpdateChecker.Downloading:
            return qsTr("The Flatpak is being saved to your Downloads folder.")
        case AutoUpdateChecker.Verifying:
            return qsTr("Checking the file size, checksum, and release identity.")
        case AutoUpdateChecker.ReadyForDesktop:
            return qsTr("Switch to Desktop Mode, reopen the application, and choose Retry. " +
                        "The verified downloaded file will remain in Downloads.")
        case AutoUpdateChecker.ReadyToHandOff:
            return qsTr("The verified Flatpak is ready. Opening it requests your desktop installer; you remain in control.")
        case AutoUpdateChecker.HandingOff:
            return qsTr("Requesting the desktop installer for the verified Flatpak.")
        case AutoUpdateChecker.HandOffRequested:
            return qsTr("The installer was requested. Complete or cancel the visible installation there.")
        case AutoUpdateChecker.CheckError:
        case AutoUpdateChecker.DownloadError:
        case AutoUpdateChecker.VerificationError:
        case AutoUpdateChecker.RestoreError:
        case AutoUpdateChecker.HandOffError:
            return AutoUpdateChecker.errorMessage
        case AutoUpdateChecker.Cancelled:
            return qsTr("No update action is in progress.")
        default:
            return qsTr("Use Check for updates in Settings to look for a newer build.")
        }
    }

    function firstAction() {
        if (downloadButton.visible && downloadButton.enabled) {
            return downloadButton
        }
        if (openInstallerButton.visible && openInstallerButton.enabled) {
            return openInstallerButton
        }
        if (retryButton.visible && retryButton.enabled) {
            return retryButton
        }
        if (viewReleaseButton.visible && viewReleaseButton.enabled) {
            return viewReleaseButton
        }
        if (cancelButton.visible && cancelButton.enabled) {
            return cancelButton
        }
        return laterButton
    }

    function openForUserAction() {
        previousFocusItem = Window.activeFocusItem
        open()
    }

    function dismissOrCancel() {
        if (canCancelOperation()) {
            AutoUpdateChecker.cancel()
        }
        close()
    }

    onOpened: {
        firstAction().forceActiveFocus(Qt.TabFocus)
    }

    Timer {
        id: focusStateAction
        interval: 0
        onTriggered: {
            if (updateDialog.visible) {
                updateDialog.firstAction().forceActiveFocus(Qt.TabFocus)
            }
        }
    }

    Connections {
        target: AutoUpdateChecker
        onStateChanged: {
            if (updateDialog.visible) {
                focusStateAction.restart()
            }
        }
    }

    onAboutToHide: {
        if (previousFocusItem) {
            previousFocusItem.forceActiveFocus(Qt.TabFocus)
        } else {
            stackView.forceActiveFocus(Qt.TabFocus)
        }
        previousFocusItem = null
    }

    ColumnLayout {
        width: updateDialog.availableWidth
        spacing: 12

        Label {
            Layout.fillWidth: true
            text: updateDialog.stateHeading()
            font.bold: true
            font.pointSize: 16
            wrapMode: Text.Wrap
        }

        Label {
            Layout.fillWidth: true
            text: updateDialog.stateMessage()
            wrapMode: Text.Wrap
        }

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            columnSpacing: 12
            rowSpacing: 4

            Label {
                text: qsTr("Current build:")
                font.bold: true
            }
            Label {
                Layout.fillWidth: true
                text: AutoUpdateChecker.currentBuild
                elide: Text.ElideMiddle
            }

            Label {
                visible: updateDialog.candidateDetailsVisible()
                text: qsTr("Available build:")
                font.bold: true
            }
            Label {
                Layout.fillWidth: true
                visible: updateDialog.candidateDetailsVisible()
                text: AutoUpdateChecker.availableBuild
                elide: Text.ElideMiddle
            }

            Label {
                visible: updateDialog.candidateDetailsVisible() &&
                         AutoUpdateChecker.expectedDownloadBytes > 0
                text: qsTr("Download size:")
                font.bold: true
            }
            Label {
                Layout.fillWidth: true
                visible: updateDialog.candidateDetailsVisible() &&
                         AutoUpdateChecker.expectedDownloadBytes > 0
                text: updateDialog.formatDownloadSize(
                          AutoUpdateChecker.expectedDownloadBytes)
            }
        }

        BusyIndicator {
            Layout.alignment: Qt.AlignHCenter
            running: updateDialog.isBusyState() &&
                     AutoUpdateChecker.state !== AutoUpdateChecker.Downloading
            visible: running
        }

        ProgressBar {
            Layout.fillWidth: true
            visible: AutoUpdateChecker.state === AutoUpdateChecker.Downloading
            indeterminate: AutoUpdateChecker.bytesTotal <= 0
            value: AutoUpdateChecker.bytesTotal > 0 ?
                       AutoUpdateChecker.bytesReceived / AutoUpdateChecker.bytesTotal : 0
        }

        TextField {
            id: manualCommandField
            Layout.fillWidth: true
            visible: updateDialog.manualCommandAvailable()
            readOnly: true
            text: updateDialog.manualCommand
            selectByMouse: true
        }
    }

    footer: DialogButtonBox {
        id: actionButtons

        // SDL maps the controller B button to Escape. Back covers platforms
        // that expose a dedicated controller-back key instead. These handlers
        // live on an Item so key events bubble up from every focused button.
        Keys.onEscapePressed: {
            updateDialog.dismissOrCancel()
        }

        Keys.onBackPressed: {
            updateDialog.dismissOrCancel()
        }

        Button {
            id: downloadButton
            text: qsTr("Download")
            visible: AutoUpdateChecker.state === AutoUpdateChecker.Available &&
                     AutoUpdateChecker.rollingInstallSupported
            enabled: visible
            activeFocusOnTab: visible && enabled
            onClicked: AutoUpdateChecker.downloadUpdate()
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: cancelButton
            text: qsTr("Cancel")
            visible: updateDialog.canCancelOperation()
            enabled: visible
            activeFocusOnTab: visible && enabled
            onClicked: {
                AutoUpdateChecker.cancel()
                updateDialog.close()
            }
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: retryButton
            text: qsTr("Retry")
            visible: updateDialog.isRetryState()
            enabled: visible
            activeFocusOnTab: visible && enabled
            onClicked: AutoUpdateChecker.retry()
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: viewReleaseButton
            text: qsTr("View release")
            visible: updateDialog.candidateDetailsVisible()
            enabled: visible
            activeFocusOnTab: visible && enabled
            onClicked: AutoUpdateChecker.openReleasePage()
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: openInstallerButton
            text: qsTr("Open installer")
            visible: AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff
            enabled: AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff
            activeFocusOnTab: visible && enabled
            onClicked: AutoUpdateChecker.openInstaller()
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: copyManualCommandButton
            text: qsTr("Copy manual command")
            visible: updateDialog.manualCommandAvailable()
            enabled: visible
            activeFocusOnTab: visible && enabled
            onClicked: {
                manualCommandField.selectAll()
                manualCommandField.copy()
                manualCommandField.deselect()
            }
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }

        Button {
            id: laterButton
            text: qsTr("Later")
            visible: true
            enabled: true
            activeFocusOnTab: true
            onClicked: updateDialog.close()
            Keys.onReturnPressed: clicked()
            Keys.onEnterPressed: clicked()
            Keys.onRightPressed: nextItemInFocusChain(true).forceActiveFocus(Qt.TabFocus)
            Keys.onLeftPressed: nextItemInFocusChain(false).forceActiveFocus(Qt.TabFocus)
        }
    }
}
