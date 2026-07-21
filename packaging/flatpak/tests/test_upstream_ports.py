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


if __name__ == "__main__":
    unittest.main()
