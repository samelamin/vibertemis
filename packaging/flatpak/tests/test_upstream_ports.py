import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]


class UpstreamPortContractTests(unittest.TestCase):
    def test_xcb_platform_always_selects_sdl_x11_driver(self):
        source = (REPOSITORY_ROOT / "app/main.cpp").read_text(encoding="utf-8")

        self.assertRegex(
            source,
            re.compile(
                r'if \(QGuiApplication::platformName\(\) == "xcb"\) \{'
                r'.*?if \(WMUtils::isRunningWayland\(\)\) \{'
                r'.*?qputenv\("SDL_VIDEODRIVER", "x11"\);',
                re.DOTALL,
            ),
        )

    def test_sdl_audio_backpressure_uses_queued_duration(self):
        header = (
            REPOSITORY_ROOT / "app/streaming/audio/renderers/sdl.h"
        ).read_text(encoding="utf-8")
        source = (
            REPOSITORY_ROOT / "app/streaming/audio/renderers/sdlaud.cpp"
        ).read_text(encoding="utf-8")

        self.assertIn("Uint32 m_FrameDurationMs;", header)
        self.assertIn(
            "m_FrameDurationMs = opusConfig->samplesPerFrame / "
            "(opusConfig->sampleRate / 1000);",
            source,
        )
        self.assertIn(
            "SDL_GetQueuedAudioSize(m_AudioDevice) / m_FrameSize * "
            "m_FrameDurationMs <= 50",
            source,
        )

    def test_combo_boxes_handle_left_and_right_navigation(self):
        source = (
            REPOSITORY_ROOT / "app/gui/AutoResizingComboBox.qml"
        ).read_text(encoding="utf-8")

        self.assertRegex(
            source,
            re.compile(r"Keys\.onLeftPressed:\s*\{\s*decrementCurrentIndex\(\)\s*\}"),
        )
        self.assertRegex(
            source,
            re.compile(r"Keys\.onRightPressed:\s*\{\s*incrementCurrentIndex\(\)\s*\}"),
        )


if __name__ == "__main__":
    unittest.main()
