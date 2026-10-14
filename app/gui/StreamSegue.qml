import QtQuick 2.0
import QtQuick.Controls 2.2
import QtQuick.Window 2.2

import SdlGamepadKeyNavigation 1.0
import Session 1.0

Item {
    id: streamSegue
    property Session session
    property string appName
    property string stageText : isResume ? qsTr("Resuming %1...").arg(appName) :
                                           qsTr("Starting %1...").arg(appName)
    property bool isResume : false
    property bool quitAfter : false
    property string recoveryText: ""
    property bool waitingForRecovery: false

    function stageStarting(stage)
    {
        // Update the spinner text
        stageText = stage === "Preparing virtual display..." ? stage : qsTr("Starting %1...").arg(stage)
    }

    function stageFailed(stage, errorCode, failingPorts)
    {
        // Display the error dialog after Session::exec() returns
        streamSegueErrorDialog.text = qsTr("Starting %1 failed: Error %2").arg(stage).arg(errorCode)

        if (failingPorts) {
            streamSegueErrorDialog.text += "\n\n" + qsTr("Check your firewall and port forwarding rules for port(s): %1").arg(failingPorts)
        }
    }

    function connectionStarted()
    {
        // Hide the UI contents so the user doesn't
        // see them briefly when we pop off the StackView
        stageSpinner.visible = false
        stageLabel.visible = false
        hintText.visible = false

        // Hide the window now that streaming has begun
        window.visible = false
    }

    function displayLaunchError(text)
    {
        // Display the error dialog after Session::exec() returns
        streamSegueErrorDialog.text = text
        console.error(text)
    }

    function displayLaunchRecovery(text, reconnectAvailable)
    {
        if (!reconnectAvailable) {
            displayLaunchError(text)
            return
        }
        // The streaming thread still owns SDL here. Only show the dialog
        // after sessionFinished confirms all connection resources are free.
        recoveryText = text
    }

    function resumeSession()
    {
        if (!waitingForRecovery || !session)
            return
        var freshSession = session.createResumeSession()
        if (!freshSession) {
            dismissRecovery()
            return
        }
        session = null
        waitingForRecovery = false
        streamSegueRecoveryDialog.replaceWithResumedSession(freshSession, appName, quitAfter)
    }

    function dismissRecovery()
    {
        if (!waitingForRecovery)
            return
        waitingForRecovery = false
        if (session) session.cancelSession()
        session = null
        if (quitAfter) {
            Qt.quit()
            return
        }
        stackView.pop()
        window.visible = true
    }

    function displayLaunchWarning(text)
    {
        // This toast appears for 3 seconds, just shorter than how long
        // Session will wait for it to be displayed. This gives it time
        // to transition to invisible before continuing.
        var toast = Qt.createQmlObject('import QtQuick.Controls 2.2; ToolTip {}', parent, '')
        toast.text = text
        toast.timeout = 3000
        toast.visible = true
        console.warn(text)
    }

    function quitStarting()
    {
        stageText = qsTr("Quitting %1...").arg(appName)
        stageLabel.visible = true
        stageSpinner.visible = true
        stageSpinner.running = true
        hintText.visible = false
        window.visible = true
    }

    function sessionFinished(portTestResult)
    {
        if (portTestResult !== 0 && portTestResult !== -1 && streamSegueErrorDialog.text) {
            streamSegueErrorDialog.text += "\n\n" + qsTr("This PC's Internet connection is blocking Vibertemis. Streaming over the Internet may not work while connected to this network.")
        }

        // Re-enable GUI gamepad usage only after SDL input is destroyed.
        SdlGamepadKeyNavigation.enable()

        if (recoveryText && session && session.retainForRecovery()) {
            waitingForRecovery = true
            streamSegueErrorDialog.text = ""
            stageSpinner.running = false
            window.visible = true
            streamSegueRecoveryDialog.recoveryOwner = streamSegue
            streamSegueRecoveryDialog.text = recoveryText
            streamSegueRecoveryDialog.open()
            return
        }

        if (quitAfter) {
            if (streamSegueErrorDialog.text) {
                // Quit when the error dialog is acknowledged
                streamSegueErrorDialog.quitAfter = quitAfter
                streamSegueErrorDialog.open()
            }
            else {
                // Quit immediately
                Qt.quit()
            }
        } else {
            // Exit this view
            stackView.pop()

            // Show the Qt window again after streaming
            window.visible = true

            // Display any launch errors. We do this after
            // the Qt UI is visible again to prevent losing
            // focus on the dialog which would impact gamepad
            // users.
            if (streamSegueErrorDialog.text) {
                streamSegueErrorDialog.quitAfter = quitAfter
                streamSegueErrorDialog.open()
            }
        }
    }

    function sessionReadyForDeletion()
    {
        // Garbage collect the Session object since it's pretty heavyweight
        // and keeps other libraries (like SDL_TTF) around until it is deleted.
        if (!waitingForRecovery) {
            session = null
            gc()
        }
    }

    StackView.onDeactivating: {
        if (session) session.cancelRetry()

        // Show the toolbar again when popped off the stack
        toolBar.visible = true

        // Re-enable GUI gamepad usage now
        SdlGamepadKeyNavigation.enable()
    }

    StackView.onRemoved: destroy()

    Component.onDestruction: {
        if (waitingForRecovery && session) session.cancelSession()
        if (streamSegueRecoveryDialog.recoveryOwner === streamSegue) {
            streamSegueRecoveryDialog.recoveryOwner = null
            streamSegueRecoveryDialog.close()
        }
    }

    StackView.onActivated: {
        // Hide the toolbar before we start loading
        toolBar.visible = false

        // Hook up our signals
        session.stageStarting.connect(stageStarting)
        session.stageFailed.connect(stageFailed)
        session.connectionStarted.connect(connectionStarted)
        session.displayLaunchError.connect(displayLaunchError)
        session.displayLaunchRecovery.connect(displayLaunchRecovery)
        session.displayLaunchWarning.connect(displayLaunchWarning)
        session.quitStarting.connect(quitStarting)
        session.sessionFinished.connect(sessionFinished)
        session.readyForDeletion.connect(sessionReadyForDeletion)

        // Kick off the stream
        spinnerTimer.start()
        streamLoader.active = true
    }

    Timer {
        id: spinnerTimer

        // Display the spinner appearance a bit to allow us to reach
        // the code in Session.exec() that pumps the event loop.
        // If we display it immediately, it will briefly hang in the
        // middle of the animation on Windows, which looks very
        // obviously broken.
        interval: 100
        onTriggered: stageSpinner.running = true
    }

    Loader {
        id: streamLoader
        active: false
        asynchronous: true

        onLoaded: {
            // Set the hint text. We do this here rather than
            // in the hintText control itself to synchronize
            // with Session.exec() which requires no concurrent
            // gamepad usage.
            hintText.text = qsTr("Tip:") + " " + qsTr("Press %1 to disconnect your session").arg(SdlGamepadKeyNavigation.getConnectedGamepads() > 0 ?
                                                  qsTr("Start+Select+L1+R1") : qsTr("Ctrl+Alt+Shift+Q"))

            // Stop GUI gamepad usage now
            SdlGamepadKeyNavigation.disable()

            // Garbage collect QML stuff before we start streaming,
            // since we'll probably be streaming for a while and we
            // won't be able to GC during the stream.
            gc()

            // Run the streaming session to completion
            session.exec(window)
        }

        sourceComponent: Item {}
    }

    Row {
        anchors.centerIn: parent
        spacing: 5

        BusyIndicator {
            id: stageSpinner
            running: false
        }

        Label {
            id: stageLabel
            height: stageSpinner.height
            text: stageText
            font.pointSize: 20
            verticalAlignment: Text.AlignVCenter

            wrapMode: Text.Wrap
        }
    }

    Label {
        id: hintText
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 50
        anchors.horizontalCenter: parent.horizontalCenter
        font.pointSize: 18
        verticalAlignment: Text.AlignVCenter

        wrapMode: Text.Wrap
    }
}
