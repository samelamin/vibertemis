#!/usr/bin/env python3
"""Exercise the shipped QML recovery dialog, including synchronous close order.

Requires qmltestrunner and QtTest QML module. No headset, host or display needed.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / 'app/gui/main.qml').read_text()
start = source.index('    NavigableMessageDialog {\n        id: streamSegueRecoveryDialog')
end = source.index('\n    NavigableDialog {', start)
dialog = source[start:end].replace('Qt.createComponent("StreamSegue.qml")',
    'Qt.createComponent("' + (ROOT / 'app/gui/StreamSegue.qml').as_uri() + '")')
runner = shutil.which('qmltestrunner') or '/usr/lib/qt6/bin/qmltestrunner'
wrapper = '''import QtQuick 2.15
import QtQuick.Controls 2.15
import QtTest 1.3
import "GUI_PATH"
Item {
ApplicationWindow {
    id: window
    width: 800; height: 600; visible: true
    Item { id: stackView; anchors.fill: parent; focus: true }
    QtObject {
        id: owner
        property int resumes: 0
        property int dismissals: 0
        function resumeSession() { resumes++ }
        function dismissRecovery() { dismissals++ }
    }
    DIALOG
    TestCase {
        name: "ShippedRecoveryDialog"
        when: windowShown
        function init() {
            owner.resumes = 0
            owner.dismissals = 0
            streamSegueRecoveryDialog.recoveryOwner = owner
            streamSegueRecoveryDialog.text = "Stream interrupted"
            streamSegueRecoveryDialog.open()
            tryCompare(streamSegueRecoveryDialog, "opened", true)
        }
        function test_resumeIsNotCancelledByClose() {
            compare(streamSegueRecoveryDialog.footer.standardButton(Dialog.Yes).text, "Resume")
            streamSegueRecoveryDialog.accept()
            tryCompare(streamSegueRecoveryDialog, "visible", false)
            wait(50)
            compare(owner.resumes, 1)
            compare(owner.dismissals, 0)
            compare(streamSegueRecoveryDialog.recoveryOwner, null)
        }
        function test_disconnectDoesNotResume() {
            streamSegueRecoveryDialog.reject()
            tryCompare(streamSegueRecoveryDialog, "visible", false)
            wait(50)
            compare(owner.resumes, 0)
            compare(owner.dismissals, 1)
        }
        function test_closeDoesNotStrandSegue() {
            streamSegueRecoveryDialog.close()
            tryCompare(streamSegueRecoveryDialog, "visible", false)
            tryCompare(owner, "dismissals", 1)
            compare(owner.resumes, 0)
        }
        function test_missingOwnerIsSafe() {
            streamSegueRecoveryDialog.recoveryOwner = null
            streamSegueRecoveryDialog.accept()
            tryCompare(streamSegueRecoveryDialog, "visible", false)
            wait(50)
            compare(owner.resumes, 0)
            compare(owner.dismissals, 0)
        }
    }
}
}
'''.replace('GUI_PATH', (ROOT / 'app/gui').as_uri()).replace('DIALOG', dialog)
# Import lightweight transport doubles while loading the real StreamSegue and
# dialog. These tests cover QML ownership and repeated Resume, not fake sockets.
session_double = """import QtQml 2.15
QtObject {
    property bool cleaned: false
    property bool consumed: false
    property bool resumeOnly: false
    property var makeSession
    signal stageStarting(string stage)
    signal stageFailed(string stage, int errorCode, string failingPorts)
    signal connectionStarted()
    signal displayLaunchError(string text)
    signal displayLaunchRecovery(string text, bool reconnectAvailable)
    signal displayLaunchWarning(string text)
    signal quitStarting()
    signal sessionFinished(int portTestResult)
    signal readyForDeletion()
    function exec(window) {}
    function cancelRetry() {}
    function cancelSession() { consumed = true; if (cleaned) destroy() }
    function recoveryAvailable() { return cleaned && !consumed }
    function retainForRecovery() { return recoveryAvailable() }
    function createResumeSession() {
        if (!recoveryAvailable()) return null
        consumed = true
        var next = makeSession()
        next.resumeOnly = true
        destroy()
        return next
    }
}
"""
journey = """import QtQuick 2.15
import QtQuick.Controls 2.15
import QtTest 1.3
import Session 1.0
import "GUI_PATH"
Item {
ApplicationWindow {
    id: window
    width: 800; height: 600; visible: true
    Item { id: toolBar }
    function makeSession() {
        return Qt.createComponent("Session/Session.qml").createObject(null, {makeSession: window.makeSession})
    }
    StackView { id: stackView; anchors.fill: parent; initialItem: Item {} }
    QtObject {
        id: streamSegueErrorDialog
        property string text: ""
        property bool quitAfter: false
        function open() { throw new Error("Unexpected launch error: " + text) }
    }
    DIALOG
    TestCase {
        name: "ShippedStreamSegue"
        when: windowShown
        function test_cancelledRecoveryReturnsToAppList() {
            var current = window.makeSession()
            var component = Qt.createComponent("GUI_PATH/StreamSegue.qml")
            stackView.push(component.createObject(stackView, {session: current, appName: "Desktop"}))
            tryCompare(stackView, "busy", false)
            wait(150)
            current.displayLaunchRecovery("Stream interrupted", true)
            current.cleaned = true
            current.sessionFinished(0)
            current.readyForDeletion()
            tryCompare(streamSegueRecoveryDialog, "opened", true)
            current.consumed = true
            streamSegueRecoveryDialog.accept()
            tryCompare(stackView, "busy", false)
            compare(stackView.depth, 1)
        }
        function test_quitProgressKeepsCompletionHandler() {
            var current = window.makeSession()
            var component = Qt.createComponent("GUI_PATH/StreamSegue.qml")
            stackView.push(component.createObject(stackView, {session: current, appName: "Desktop"}))
            tryCompare(stackView, "busy", false)
            wait(150)
            current.quitStarting()
            compare(stackView.currentItem.session, current)
            compare(stackView.currentItem.stageText, "Quitting Desktop...")
            current.cleaned = true
            current.sessionFinished(0)
            current.readyForDeletion()
            tryCompare(stackView, "busy", false)
            compare(stackView.depth, 1)
            current.destroy()
        }
        function test_repeatedFreshResumeAndDisconnect() {
            var sessionComponent = Qt.createComponent("Session/Session.qml")
            compare(sessionComponent.status, Component.Ready)
            var original = window.makeSession()
            var component = Qt.createComponent("GUI_PATH/StreamSegue.qml")
            compare(component.status, Component.Ready, component.errorString())
            stackView.push(component.createObject(stackView, {session: original, appName: "Desktop", quitAfter: true}))
            tryCompare(stackView, "busy", false)
            wait(150)
            var childCount = stackView.children.length
            for (var cycle = 0; cycle < 3; cycle++) {
                var oldSession = stackView.currentItem.session
                streamSegueErrorDialog.text = "obsolete error"
                oldSession.displayLaunchRecovery("Stream interrupted", true)
                compare(streamSegueRecoveryDialog.visible, false)
                oldSession.cleaned = true
                oldSession.sessionFinished(0)
                oldSession.readyForDeletion()
                tryCompare(streamSegueRecoveryDialog, "opened", true)
                compare(stackView.currentItem.session, oldSession)
                compare(streamSegueErrorDialog.text, "")
                streamSegueRecoveryDialog.accept()
                tryCompare(stackView, "busy", false)
                wait(150)
                verify(stackView.currentItem.session !== oldSession)
                verify(stackView.currentItem.session.resumeOnly)
                compare(stackView.currentItem.appName, "Desktop")
                compare(stackView.currentItem.quitAfter, true)
                compare(stackView.children.length, childCount)
                compare(stackView.depth, 2)
            }
            stackView.currentItem.quitAfter = false
            var lastSession = stackView.currentItem.session
            lastSession.displayLaunchRecovery("Stream interrupted", true)
            lastSession.cleaned = true
            lastSession.sessionFinished(0)
            lastSession.readyForDeletion()
            tryCompare(streamSegueRecoveryDialog, "opened", true)
            streamSegueRecoveryDialog.reject()
            tryCompare(stackView, "busy", false)
            compare(stackView.depth, 1)
        }
    }
}
}
""".replace('GUI_PATH', (ROOT / 'app/gui').as_uri()).replace('DIALOG', dialog)
def main():
    assert Path(runner).exists(), 'Install Qt declarative development tools / qmltestrunner'
    with tempfile.TemporaryDirectory(prefix='vibertemis-dialog-') as tmp:
        path = Path(tmp)
        (path / 'tst_recovery.qml').write_text(wrapper)
        (path / 'tst_journey.qml').write_text(journey)
        (path / 'Session').mkdir()
        (path / 'Session/qmldir').write_text('module Session\nSession 1.0 Session.qml\n')
        (path / 'Session/Session.qml').write_text(session_double)
        (path / 'SdlGamepadKeyNavigation').mkdir()
        (path / 'SdlGamepadKeyNavigation/qmldir').write_text(
            'module SdlGamepadKeyNavigation\nsingleton SdlGamepadKeyNavigation 1.0 Navigation.qml\n')
        (path / 'SdlGamepadKeyNavigation/Navigation.qml').write_text(
            'pragma Singleton\nimport QtQml 2.15\nQtObject { function enable() {} '
            'function disable() {} function getConnectedGamepads() { return 1 } }\n')
        env = dict(os.environ, QT_QPA_PLATFORM='offscreen', QT_QUICK_BACKEND='software')
        result = subprocess.run([runner, '-import', str(path), '-input', str(path)], env=env)
        return result.returncode

if __name__ == '__main__':
    raise SystemExit(main())
