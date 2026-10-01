import QtQuick 2.9
import QtQuick.Controls 2.2

// A titled settings section drawn as a Vibertemis card: surface fill,
// hairline border, bold title. Drop-in replacement for GroupBox.
GroupBox {
    id: card
    padding: 20
    topPadding: 60
    font.pointSize: 12

    background: Rectangle {
        radius: 20
        color: window.ui.surface
        border.width: 1
        border.color: window.ui.line
    }

    label: Label {
        x: 20
        y: 20
        width: card.availableWidth
        text: card.title
        font.pointSize: 15
        font.bold: true
        color: window.ui.text
        elide: Label.ElideRight
    }
}
