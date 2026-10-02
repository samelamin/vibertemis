import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.3
import QtQuick.Window 2.2
import QtQuick.Controls.Material 2.2

import ComputerManager 1.0
import AutoUpdateChecker 1.0
import StreamingPreferences 1.0
import SystemProperties 1.0
import SdlGamepadKeyNavigation 1.0

ApplicationWindow {
    property bool pollingActive: false

    // Set by SettingsView to force the back operation to pop all
    // pages except the initial view. This is required when doing
    // a retranslate() because AppView breaks for some reason.
    property bool clearOnBack: false

    id: window
    width: 1280
    height: 600

    // Design tokens from the Vibertemis Redesign canvas. Pages reach them
    // through the window id, e.g. window.ui.surface.
    readonly property QtObject ui: QtObject {
        readonly property color ground: "#0A0D11"
        readonly property color surface: "#11161C"
        readonly property color raised: "#171E26"
        readonly property color line: "#232C36"
        readonly property color text: "#EEF3F6"
        readonly property color muted: "#9AA7B3"
        readonly property color faint: "#7D8A97"
        readonly property color accent: "#5EE6CF"
        readonly property color accentInk: "#052520"
        readonly property color ok: "#5EE6A0"
        readonly property color warn: "#F5C451"
        readonly property color bannerFill: "#0F2622"
        readonly property color bannerLine: "#1F5A50"
        readonly property color bannerMuted: "#A9C9C3"
        readonly property color screenTint: "#7CB8FF"
    }

    // Update banner state. The banner is dismissed per build; a newer
    // build shows it again.
    property string dismissedUpdateBuild: ""
    property double lastUpdateCheckMs: Date.now()
    readonly property int updateRecheckIntervalMs: 30 * 60000

    // This function runs prior to creation of the initial StackView item
    function doEarlyInit() {
        // Vibertemis ink ground (shared with the Quest app). Set on every
        // Qt version so Material 2 and Material 3 builds look the same.
        Material.background = window.ui.ground

        SdlGamepadKeyNavigation.enable()
    }

    Component.onCompleted: {
        // Show the window according to the user's preferences
        if (SystemProperties.hasDesktopEnvironment) {
            if (StreamingPreferences.uiDisplayMode == StreamingPreferences.UI_MAXIMIZED) {
                window.showMaximized()
            }
            else if (StreamingPreferences.uiDisplayMode == StreamingPreferences.UI_FULLSCREEN) {
                window.showFullScreen()
            }
            else {
                window.show()
            }
        } else {
            window.showFullScreen()
        }

        // Display any modal dialogs for configuration warnings
        if (SystemProperties.isWow64) {
            wow64Dialog.open()
        }
        else if (!SystemProperties.hasHardwareAcceleration && StreamingPreferences.videoDecoderSelection !== StreamingPreferences.VDS_FORCE_SOFTWARE) {
            if (SystemProperties.isRunningXWayland) {
                xWaylandDialog.open()
            }
            else {
                noHwDecoderDialog.open()
            }
        }

        if (SystemProperties.unmappedGamepads) {
            unmappedGamepadDialog.unmappedGamepads = SystemProperties.unmappedGamepads
            unmappedGamepadDialog.open()
        }
    }
  
    // It would be better to use TextMetrics here, but it always lays out
    // the text slightly more compactly than real Text does in ToolTip,
    // causing unexpected line breaks to be inserted
    Text {
        id: tooltipTextLayoutHelper
        visible: false
        font: ToolTip.toolTip.font
        text: ToolTip.toolTip.text
    }

    // This configures the maximum width of the singleton attached QML ToolTip. If left unconstrained,
    // it will never insert a line break and just extend on forever.
    // Note: ToolTip must be attached to an Item, not ApplicationWindow
    Item {
        id: tooltipHelper
        ToolTip.toolTip.contentWidth: Math.min(tooltipTextLayoutHelper.width, 400)
    }

    function goBack() {
        if (clearOnBack) {
            // Pop all items except the first one
            stackView.pop(null)
            clearOnBack = false
        }
        else {
            stackView.pop()
        }
    }

    function checkForUpdatesFromSettings() {
        AutoUpdateChecker.checkNow()
        updateDialog.openForUserAction()
    }

    function updateEntryVisible() {
        if (AutoUpdateChecker.releaseUrl === "") {
            return false
        }
        if (!AutoUpdateChecker.rollingInstallSupported) {
            return AutoUpdateChecker.state === AutoUpdateChecker.Available
        }
        return AutoUpdateChecker.state === AutoUpdateChecker.Available ||
               AutoUpdateChecker.state === AutoUpdateChecker.Downloading ||
               AutoUpdateChecker.state === AutoUpdateChecker.Verifying ||
               AutoUpdateChecker.state === AutoUpdateChecker.ReadyForDesktop ||
               AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandingOff ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandOffRequested ||
               AutoUpdateChecker.state === AutoUpdateChecker.DownloadError ||
               AutoUpdateChecker.state === AutoUpdateChecker.VerificationError ||
               AutoUpdateChecker.state === AutoUpdateChecker.RestoreError ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandOffError
    }

    function updateBannerVisible() {
        if (AutoUpdateChecker.availableBuild === ""
                || AutoUpdateChecker.availableBuild === dismissedUpdateBuild) {
            return false
        }
        if (!AutoUpdateChecker.rollingInstallSupported) {
            return AutoUpdateChecker.state === AutoUpdateChecker.Available
        }
        return AutoUpdateChecker.state === AutoUpdateChecker.Available ||
               AutoUpdateChecker.state === AutoUpdateChecker.ReadyForDesktop ||
               AutoUpdateChecker.state === AutoUpdateChecker.ReadyToHandOff ||
               AutoUpdateChecker.state === AutoUpdateChecker.DownloadError ||
               AutoUpdateChecker.state === AutoUpdateChecker.VerificationError ||
               AutoUpdateChecker.state === AutoUpdateChecker.HandOffError
    }

    function openUpdateFromBanner() {
        if (AutoUpdateChecker.rollingInstallSupported) {
            updateDialog.openForUserAction()
        } else {
            AutoUpdateChecker.openReleasePage()
        }
    }

    // Re-check for updates while the app stays open (Game Mode sessions
    // can run for days). Only from a settled "nothing to do" state, so a
    // download, verification, hand-off or a user's Cancel is never
    // interrupted or undone.
    function maybeRecheckForUpdates() {
        var state = AutoUpdateChecker.state
        if (state !== AutoUpdateChecker.NoUpdate && state !== AutoUpdateChecker.CheckError) {
            return
        }
        if (Date.now() - lastUpdateCheckMs < updateRecheckIntervalMs) {
            return
        }
        lastUpdateCheckMs = Date.now()
        AutoUpdateChecker.checkNow()
    }

    Timer {
        id: updateRecheckTimer
        interval: 5 * 60000
        repeat: true
        running: true
        onTriggered: window.maybeRecheckForUpdates()
    }

    Rectangle {
        id: updateBanner
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: visible ? 16 : 0
        height: visible ? bannerRow.implicitHeight + 24 : 0
        visible: window.updateBannerVisible()
        radius: 16
        color: window.ui.bannerFill
        border.color: window.ui.bannerLine
        border.width: 1

        RowLayout {
            id: bannerRow
            anchors.fill: parent
            anchors.leftMargin: 20
            anchors.rightMargin: 12
            spacing: 14

            Rectangle {
                width: 40; height: 40; radius: 12
                color: "#14332D"
                Layout.alignment: Qt.AlignVCenter

                Label {
                    anchors.centerIn: parent
                    text: "\u2193"
                    font.pointSize: 18
                    font.bold: true
                    color: window.ui.accent
                }
            }

            ColumnLayout {
                spacing: 2
                Layout.fillWidth: true

                Label {
                    Layout.fillWidth: true
                    font.pointSize: 13
                    font.bold: true
                    color: window.ui.text
                    elide: Label.ElideRight
                    text: {
                        switch (AutoUpdateChecker.state) {
                        case AutoUpdateChecker.ReadyForDesktop:
                        case AutoUpdateChecker.ReadyToHandOff:
                            return qsTr("A new build is ready to install")
                        case AutoUpdateChecker.DownloadError:
                        case AutoUpdateChecker.VerificationError:
                        case AutoUpdateChecker.HandOffError:
                            return qsTr("The update needs your attention")
                        default:
                            return qsTr("A new version is available")
                        }
                    }
                }

                Label {
                    Layout.fillWidth: true
                    font.pointSize: 10
                    color: window.ui.bannerMuted
                    elide: Label.ElideRight
                    text: AutoUpdateChecker.rollingInstallSupported
                          ? qsTr("Downloaded and verified before it installs. The app restarts to finish.")
                          : qsTr("Version %1 is on the releases page.").arg(AutoUpdateChecker.availableBuild)
                }
            }

            Button {
                id: bannerLaterButton
                flat: true
                text: qsTr("Later")
                activeFocusOnTab: true
                Keys.onReturnPressed: clicked()
                Keys.onEnterPressed: clicked()
                KeyNavigation.right: bannerUpdateButton
                Keys.onDownPressed: stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                onClicked: window.dismissedUpdateBuild = AutoUpdateChecker.availableBuild
            }

            Button {
                id: bannerUpdateButton
                text: qsTr("Update now")
                activeFocusOnTab: true
                leftPadding: 20
                rightPadding: 20
                background: Rectangle {
                    implicitHeight: 44
                    radius: 12
                    color: bannerUpdateButton.down ? Qt.darker(window.ui.accent, 1.15) : window.ui.accent
                    border.width: bannerUpdateButton.visualFocus ? 3 : 0
                    border.color: window.ui.text
                }
                contentItem: Label {
                    text: bannerUpdateButton.text
                    font.bold: true
                    color: window.ui.accentInk
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                Keys.onReturnPressed: clicked()
                Keys.onEnterPressed: clicked()
                Keys.onDownPressed: stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                onClicked: window.openUpdateFromBanner()
            }
        }
    }

    StackView {
        id: stackView
        anchors.top: updateBanner.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        focus: true

        Component.onCompleted: {
            // Perform our early initialization before constructing
            // the initial view and pushing it to the StackView
            doEarlyInit()
            push(initialView)
        }

        onCurrentItemChanged: {
            // Ensure focus travels to the next view when going back
            if (currentItem) {
                currentItem.forceActiveFocus()
            }
        }

        Keys.onEscapePressed: {
            if (depth > 1) {
                goBack()
            }
            else {
                quitConfirmationDialog.open()
            }
        }

        Keys.onBackPressed: {
            if (depth > 1) {
                goBack()
            }
            else {
                quitConfirmationDialog.open()
            }
        }

        Keys.onMenuPressed: {
            settingsButton.clicked()
        }

        // This is a keypress we've reserved for letting the
        // SdlGamepadKeyNavigation object tell us to show settings
        // when Menu is consumed by a focused control.
        Keys.onHangupPressed: {
            settingsButton.clicked()
        }
    }

    // This timer keeps us polling for 5 minutes of inactivity
    // to allow the user to work with Moonlight on a second display
    // while dealing with configuration issues. This will ensure
    // machines come online even if the input focus isn't on Moonlight.
    Timer {
        id: inactivityTimer
        interval: 5 * 60000
        onTriggered: {
            if (!active && pollingActive) {
                ComputerManager.stopPollingAsync()
                pollingActive = false
            }
        }
    }

    onVisibleChanged: {
        // When we become invisible while streaming is going on,
        // stop polling immediately.
        if (!visible) {
            inactivityTimer.stop()

            if (pollingActive) {
                ComputerManager.stopPollingAsync()
                pollingActive = false
            }
        }
        else if (active) {
            // When we become visible and active again, start polling
            inactivityTimer.stop()

            // Restart polling if it was stopped
            if (!pollingActive) {
                ComputerManager.startPolling()
                pollingActive = true
            }
        }

        // Poll for gamepad input only when the window is in focus
        SdlGamepadKeyNavigation.notifyWindowFocus(visible && active)
    }

    onActiveChanged: {
        if (active) {
            // Stop the inactivity timer
            inactivityTimer.stop()

            // Coming back to the app is a natural moment to look again
            maybeRecheckForUpdates()

            // Restart polling if it was stopped
            if (!pollingActive) {
                ComputerManager.startPolling()
                pollingActive = true
            }
        }
        else {
            // Start the inactivity timer to stop polling
            // if focus does not return within a few minutes.
            inactivityTimer.restart()
        }

        // Poll for gamepad input only when the window is in focus
        SdlGamepadKeyNavigation.notifyWindowFocus(visible && active)
    }

    // Workaround for lack of instanceof in Qt 5.9.
    //
    // Based on https://stackoverflow.com/questions/13923794/how-to-do-a-is-a-typeof-or-instanceof-in-qml
    function qmltypeof(obj, className) { // QtObject, string -> bool
        // className plus "(" is the class instance without modification
        // className plus "_QML" is the class instance with user-defined properties
        var str = obj.toString();
        return str.startsWith(className + "(") || str.startsWith(className + "_QML");
    }

    function navigateTo(url, objectType)
    {
        var existingItem = stackView.find(function(item, index) {
            return qmltypeof(item, objectType)
        })

        if (existingItem !== null) {
            // Pop to the existing item
            stackView.pop(existingItem)
        }
        else {
            // Create a new item
            stackView.push(url)
        }
    }

    // Controller hints for the current page ("Steam Deck" artboards). Only
    // shown while a gamepad is connected; the mapping matches
    // SdlGamepadKeyNavigation (A = activate, B = back, X = menu,
    // Y / Start = settings).
    property int connectedGamepads: SdlGamepadKeyNavigation.getConnectedGamepads()

    Timer {
        interval: 3000
        repeat: true
        running: window.visible
        onTriggered: window.connectedGamepads = SdlGamepadKeyNavigation.getConnectedGamepads()
    }

    function controllerHints() {
        var page = stackView.currentItem
        if (!page) {
            return []
        }
        if (qmltypeof(page, "PcView")) {
            return [["A", qsTr("Open")], ["X", qsTr("PC options")], ["Y", qsTr("Settings")]]
        }
        if (qmltypeof(page, "AppView")) {
            return [["A", qsTr("Play")], ["X", qsTr("Game options")], ["B", qsTr("Back")]]
        }
        if (qmltypeof(page, "SettingsView")) {
            return [["A", qsTr("Change")], ["B", qsTr("Back")]]
        }
        return [["B", qsTr("Back")]]
    }

    footer: Rectangle {
        id: hintBar
        visible: window.connectedGamepads > 0 && window.controllerHints().length > 0
        height: visible ? 52 : 0
        color: window.ui.ground

        Rectangle {
            width: parent.width
            height: 1
            color: window.ui.line
        }

        Row {
            anchors.right: parent.right
            anchors.rightMargin: 32
            anchors.verticalCenter: parent.verticalCenter
            spacing: 28

            Repeater {
                model: window.controllerHints()

                Row {
                    spacing: 8

                    Rectangle {
                        width: 26; height: 26; radius: 13
                        color: "#2A3440"
                        anchors.verticalCenter: parent.verticalCenter

                        Label {
                            anchors.centerIn: parent
                            text: modelData[0]
                            font.bold: true
                            font.pointSize: 10
                            color: window.ui.text
                        }
                    }
                    Label {
                        text: modelData[1]
                        font.pointSize: 11
                        color: "#C9D3DA"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }
        }
    }

    header: ToolBar {
        id: toolBar
        height: 64
        anchors.topMargin: 5
        anchors.bottomMargin: 5

        background: Rectangle {
            color: window.ui.ground

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 1
                color: window.ui.line
            }
        }

        Label {
            id: titleLabel
            visible: toolBar.width > 700
            anchors.fill: parent
            text: stackView.currentItem.objectName
            font.pointSize: 18
            font.bold: true
            elide: Label.ElideRight
            // Left-aligned page title per the Deck artboards; clear the
            // back button when it is shown.
            leftPadding: stackView.depth > 1 ? 72 : 32
            rightPadding: 360
            horizontalAlignment: Qt.AlignLeft
            verticalAlignment: Qt.AlignVCenter
        }

        RowLayout {
            spacing: 10
            anchors.leftMargin: 10
            anchors.rightMargin: 10
            anchors.fill: parent

            NavigableToolButton {
                // Only make the button visible if the user has navigated somewhere.
                visible: stackView.depth > 1

                iconSource: "qrc:/res/arrow_left.svg"

                onClicked: goBack()

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            // This label will appear when the window gets too small and
            // we need to ensure the toolbar controls don't collide
            Label {
                id: titleRowLabel
                font.pointSize: titleLabel.font.pointSize
                elide: Label.ElideRight
                horizontalAlignment: Qt.AlignHCenter
                verticalAlignment: Qt.AlignVCenter
                Layout.fillWidth: true

                // We need this label to always be visible so it can occupy
                // the remaining space in the RowLayout. To "hide" it, we
                // just set the text to empty string.
                text: !titleLabel.visible ? stackView.currentItem.objectName : ""
            }

            Label {
                id: versionLabel
                visible: qmltypeof(stackView.currentItem, "SettingsView")
                text: qsTr("Version %1").arg(SystemProperties.versionString)
                font.pointSize: 12
                horizontalAlignment: Qt.AlignRight
                verticalAlignment: Qt.AlignVCenter
            }

            NavigableToolButton {
                id: discordButton
                visible: false // Temporarily disabled for Artemis

                iconSource: "qrc:/res/discord.svg"

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Join our community on Discord")

                // TODO need to make sure browser is brought to foreground.
                onClicked: Qt.openUrlExternally("https://moonlight-stream.org/discord");

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            NavigableToolButton {
                id: addPcButton
                visible: qmltypeof(stackView.currentItem, "PcView")

                iconSource:  "qrc:/res/ic_add_to_queue_white_48px.svg"

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Add PC manually") + (newPcShortcut.nativeText ? (" ("+newPcShortcut.nativeText+")") : "")

                Shortcut {
                    id: newPcShortcut
                    sequence: StandardKey.New
                    onActivated: addPcButton.clicked()
                }

                onClicked: {
                    addPcDialog.open()
                }

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            NavigableToolButton {
                id: updateButton

                iconSource: "qrc:/res/update.svg"

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered || visible

                // Keep the entry available while a verified download is
                // preserved so Later never strands controller-only users.
                visible: window.updateEntryVisible()

                onClicked: {
                    if (AutoUpdateChecker.rollingInstallSupported) {
                        updateDialog.openForUserAction()
                    } else {
                        AutoUpdateChecker.openReleasePage()
                    }
                }

                ToolTip.text: qsTr("Update available for Vibertemis: Version %1")
                                  .arg(AutoUpdateChecker.availableBuild)

                Component.onCompleted: {
                    AutoUpdateChecker.start()
                }

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            NavigableToolButton {
                id: helpButton
                visible: SystemProperties.hasBrowser

                iconSource: "qrc:/res/question_mark.svg"

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Upstream Artemis Help") + (helpShortcut.nativeText ? (" ("+helpShortcut.nativeText+")") : "")

                Shortcut {
                    id: helpShortcut
                    sequence: StandardKey.HelpContents
                    onActivated: helpButton.clicked()
                }

                // TODO need to make sure browser is brought to foreground.
                // Upstream Artemis documentation (the fork has no equivalent wiki yet).
                onClicked: Qt.openUrlExternally("https://github.com/wjbeckett/artemis/wiki/Setup-Guide");

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            NavigableToolButton {
                // TODO: Implement gamepad mapping then unhide this button
                visible: false

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Gamepad Mapper")

                iconSource: "qrc:/res/ic_videogame_asset_white_48px.svg"

                onClicked: navigateTo("qrc:/gui/GamepadMapper.qml", "GamepadMapper")

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }
            }

            NavigableToolButton {
                id: settingsButton

                iconSource:  "qrc:/res/settings.svg"

                onClicked: navigateTo("qrc:/gui/SettingsView.qml", "SettingsView")

                Keys.onDownPressed: {
                    stackView.currentItem.forceActiveFocus(Qt.TabFocus)
                }

                Shortcut {
                    id: settingsShortcut
                    sequence: StandardKey.Preferences
                    onActivated: settingsButton.clicked()
                }

                ToolTip.delay: 1000
                ToolTip.timeout: 3000
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Settings") + (settingsShortcut.nativeText ? (" ("+settingsShortcut.nativeText+")") : "")
            }
        }
    }

    ErrorMessageDialog {
        id: noHwDecoderDialog
        text: qsTr("No functioning hardware accelerated video decoder was detected by Vibertemis. " +
                   "Your streaming performance may be severely degraded in this configuration.")
        helpText: qsTr("Click the Help button to open the Upstream Artemis documentation for solving this problem.")
        helpUrl: "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems"
    }

    UpdateDialog {
        id: updateDialog
    }

    ErrorMessageDialog {
        id: xWaylandDialog
        text: qsTr("Hardware acceleration doesn't work on XWayland. Continuing on XWayland may result in poor streaming performance. " +
                   "Try running with QT_QPA_PLATFORM=wayland or switch to X11.")
        helpText: qsTr("Click the Help button to open the Upstream Artemis documentation.")
        helpUrl: "https://github.com/wjbeckett/artemis/wiki/Fixing-Hardware-Decoding-Problems"
    }

    NavigableMessageDialog {
        id: wow64Dialog
        standardButtons: Dialog.Ok | Dialog.Cancel
        text: qsTr("This version of Vibertemis isn't optimized for your PC. Please download the '%1' version of Vibertemis for the best streaming performance.").arg(SystemProperties.friendlyNativeArchName)
        onAccepted: {
            Qt.openUrlExternally("https://github.com/samelamin/vibertemis/releases");
        }
    }

    ErrorMessageDialog {
        id: unmappedGamepadDialog
        property string unmappedGamepads : ""
        text: qsTr("Vibertemis detected gamepads without a mapping:") + "\n" + unmappedGamepads
        helpTextSeparator: "\n\n"
        helpText: qsTr("Click the Help button to open the Upstream Artemis documentation for mapping gamepads.")
        helpUrl: "https://github.com/wjbeckett/artemis/wiki/Gamepad-Mapping"
    }

    // This dialog appears when quitting via keyboard or gamepad button
    NavigableMessageDialog {
        id: quitConfirmationDialog
        standardButtons: Dialog.Yes | Dialog.No
        text: qsTr("Are you sure you want to quit?")
        // For keyboard/gamepad navigation
        onAccepted: Qt.quit()
    }

    // HACK: This belongs in StreamSegue but keeping a dialog around after the parent
    // dies can trigger bugs in Qt 5.12 that cause the app to crash. For now, we will
    // host this dialog in a QML component that is never destroyed.
    //
    // To repro: Start a stream, cut the network connection to trigger the "Connection
    // terminated" dialog, wait until the app grid times out back to the PC grid, then
    // try to dismiss the dialog.
    ErrorMessageDialog {
        id: streamSegueErrorDialog

        property bool quitAfter: false

        onClosed: {
            if (quitAfter) {
                Qt.quit()
            }

            // StreamSegue assumes its dialog will be re-created each time we
            // start streaming, so fake it by wiping out the text each time.
            text = ""
        }
    }

    NavigableMessageDialog {
        id: streamSegueRecoveryDialog

        property bool reconnectAvailable: false
        property var reconnectSession: null
        standardButtons: reconnectAvailable ? Dialog.Yes | Dialog.No : Dialog.Ok

        onAccepted: {
            window.visible = false
            reconnectSession.requestReconnect()
        }

        onRejected: {
            window.visible = false
            reconnectSession.cancelSession()
        }

        onClosed: {
            reconnectAvailable = false
            reconnectSession = null
        }
    }

    NavigableDialog {
        id: addPcDialog
        property string label: qsTr("Enter the IP address of your host PC:")

        standardButtons: Dialog.Ok | Dialog.Cancel

        onOpened: {
            // Force keyboard focus on the textbox so keyboard navigation works
            editText.forceActiveFocus()
        }

        onClosed: {
            editText.clear()
        }

        onAccepted: {
            if (editText.text) {
                ComputerManager.addNewHostManually(editText.text.trim())
            }
        }

        ColumnLayout {
            Label {
                text: addPcDialog.label
                font.bold: true
            }

            TextField {
                id: editText
                Layout.fillWidth: true
                focus: true

                Keys.onReturnPressed: {
                    addPcDialog.accept()
                }

                Keys.onEnterPressed: {
                    addPcDialog.accept()
                }
            }
        }
    }
}
