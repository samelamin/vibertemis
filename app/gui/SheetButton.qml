import QtQuick 2.9
import QtQuick.Controls 2.2

// Dialog action in the Vibertemis style: the primary action is filled
// mint, the rest are quiet. A light ring marks keyboard/controller focus.
Button {
    id: control
    property bool primary: false

    leftPadding: 20
    rightPadding: 20
    implicitHeight: 48
    // DialogButtonBox keeps hidden buttons in its row; take no width when
    // hidden so the visible actions (Later especially) stay inside the sheet.
    implicitWidth: visible ? Math.max(implicitBackgroundWidth + leftInset + rightInset,
                                      implicitContentWidth + leftPadding + rightPadding) : 0
    font.bold: primary

    background: Rectangle {
        implicitHeight: 48
        radius: 14
        color: control.primary
               ? (control.down ? Qt.darker(window.ui.accent, 1.15) : window.ui.accent)
               : (control.down || control.hovered ? window.ui.raised : "transparent")
        border.width: control.visualFocus || control.activeFocus ? 3 : (control.primary ? 0 : 1)
        border.color: control.visualFocus || control.activeFocus ? window.ui.text : window.ui.line
    }

    contentItem: Label {
        text: control.text
        font: control.font
        color: control.primary ? window.ui.accentInk : window.ui.text
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
}
