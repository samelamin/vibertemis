from pathlib import Path
import json
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class FlatpakDocumentationTests(unittest.TestCase):
    def test_documented_run_commands_match_manifest_app_id(self):
        manifest = json.loads(
            (
                REPOSITORY_ROOT
                / "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json"
            ).read_text(encoding="utf-8")
        )
        expected_app_id = manifest["app-id"]

        for relative_path in (
            "README.md",
            "docs/STEAM_DECK.md",
            ".github/ISSUE_TEMPLATE/bug_report.md",
        ):
            text = (REPOSITORY_ROOT / relative_path).read_text(encoding="utf-8")
            documented_app_ids = re.findall(r"flatpak run\s+([A-Za-z0-9_.-]+)", text)
            with self.subTest(path=relative_path):
                self.assertTrue(documented_app_ids)
                self.assertEqual({expected_app_id}, set(documented_app_ids))


if __name__ == "__main__":
    unittest.main()
