import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
SETTINGS_VIEW = REPOSITORY_ROOT / "app" / "gui" / "SettingsView.qml"


class SettingsRefreshUiContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SETTINGS_VIEW.read_text(encoding="utf-8")

    def test_persisted_fractional_rate_selects_single_custom_row(self):
        self.assertRegex(
            self.source,
            re.compile(
                r"if \(StreamingPreferences\.enableFractionalRefreshRate && "
                r"parsedCustomRefreshRate\.valid\) \{.*?"
                r"currentIndex = updateCustomRefreshRate\(actualFps, "
                r"qsTr\(\"Custom \(%1 Hz\)\"\)\.arg\("
                r"customRefreshRate\.toFixed\(3\)\)\)",
                re.DOTALL,
            ),
        )
        self.assertRegex(
            self.source,
            re.compile(
                r"function updateCustomRefreshRate\(refreshRate, description\) \{.*?"
                r"if \(model\.get\(i\)\.is_custom\) \{.*?return i.*?"
                r"model\.append\(\{.*?\"is_custom\": true.*?"
                r"return model\.count - 1",
                re.DOTALL,
            ),
        )

    def test_standard_selection_disables_fractional_mode_before_update(self):
        self.assertRegex(
            self.source,
            re.compile(
                r"else\s*\{\s*"
                r"StreamingPreferences\.enableFractionalRefreshRate = false\s*"
                r"updateBitrateForSelection\(\)"
            ),
        )

    def test_custom_rate_labels_preserve_millihertz_precision(self):
        self.assertIn(
            'qsTr("Custom (%1 Hz)").arg(refreshRate.toFixed(3))', self.source
        )
        self.assertIn(
            'qsTr("Custom (%1 Hz)").arg(customRefreshRate.toFixed(3))',
            self.source,
        )
        self.assertNotIn(
            'qsTr("Custom (%1 Hz)").arg(refreshRate.toFixed(2))', self.source
        )
        self.assertNotIn(
            'qsTr("Custom (%1 Hz)").arg(customRefreshRate.toFixed(2))',
            self.source,
        )


if __name__ == "__main__":
    unittest.main()
