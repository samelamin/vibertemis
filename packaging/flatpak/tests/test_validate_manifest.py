import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


VALIDATOR = Path(__file__).parents[1] / "validate-manifest.py"


def valid_manifest():
    ffmpeg_options = [
        "--enable-decoder=h264",
        "--enable-decoder=hevc",
        "--enable-decoder=av1",
    ]
    ffmpeg_options.extend(
        f"--enable-hwaccel={codec}_{backend}"
        for codec in ("h264", "hevc", "av1")
        for backend in ("vaapi", "vulkan")
    )
    return {
        "app-id": "com.artemisdesktop.ArtemisDesktopDev",
        "runtime": "org.kde.Platform",
        "runtime-version": "6.10",
        "finish-args": [
            "--device=all",
            "--filesystem=xdg-run/gamescope-0",
            "--unset-env=LIBVA_DRIVER_NAME",
            "--unset-env=LIBVA_DRIVERS_PATH",
        ],
        "modules": [
            {
                "name": "libplacebo",
                "sources": [
                    {
                        "type": "archive",
                        "url": "https://example.test/libplacebo.tar.gz",
                        "sha256": "1" * 64,
                    },
                    {
                        "type": "patch",
                        "path": "libplacebo-disable-internally-synchronized-queues.patch",
                    },
                ],
            },
            {
                "name": "ffmpeg",
                "config-opts": ffmpeg_options,
                "modules": [
                    {
                        "name": "nested-git-dependency",
                        "sources": [
                            {
                                "type": "git",
                                "url": "https://example.test/dependency.git",
                                "commit": "2" * 40,
                            }
                        ],
                    }
                ],
            },
            {
                "name": "artemis",
                "sources": [{"type": "dir", "path": "../.."}],
            },
        ],
    }


class ManifestValidatorTests(unittest.TestCase):
    def run_validator(self, manifest):
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest_path = Path(temporary_directory) / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(VALIDATOR), str(manifest_path)],
                capture_output=True,
                check=False,
                text=True,
            )

    def assert_contract_error(self, manifest, message):
        result = self.run_validator(manifest)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stderr)

    def test_accepts_manifest_that_satisfies_contract(self):
        result = self.run_validator(valid_manifest())

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("manifest contract satisfied", result.stdout)

    def test_requires_application_id_and_kde_610_runtime(self):
        manifest = valid_manifest()
        manifest["app-id"] = "com.example.Wrong"
        manifest["runtime"] = "org.freedesktop.Platform"
        manifest["runtime-version"] = "24.08"

        result = self.run_validator(manifest)

        self.assertIn(
            "app-id must be com.artemisdesktop.ArtemisDesktopDev", result.stderr
        )
        self.assertIn("runtime must be org.kde.Platform", result.stderr)
        self.assertIn("runtime-version must be 6.10", result.stderr)

    def test_rejects_each_unpinned_network_source_in_nested_modules(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"] = [
            {"type": "git", "url": "https://example.test/unpinned.git"},
            {"type": "archive", "url": "https://example.test/unpinned.tar.gz"},
            {"type": "archive", "url": "ftp://example.test/unpinned.tar.gz"},
            {"type": "file", "url": "ftps://example.test/unpinned.patch"},
            {"type": "git", "url": "git@example.com:org/repo.git"},
        ]

        result = self.run_validator(manifest)

        self.assertIn(
            "network git source https://example.test/unpinned.git must use a pinned "
            "40-character commit",
            result.stderr,
        )
        self.assertIn(
            "network source https://example.test/unpinned.tar.gz must use a pinned "
            "SHA-256",
            result.stderr,
        )
        self.assertIn(
            "network source ftp://example.test/unpinned.tar.gz must use a pinned "
            "SHA-256",
            result.stderr,
        )
        self.assertIn(
            "network source ftps://example.test/unpinned.patch must use a pinned "
            "SHA-256",
            result.stderr,
        )
        self.assertIn(
            "network git source git@example.com:org/repo.git must use a pinned "
            "40-character commit",
            result.stderr,
        )

    def test_rejects_scp_style_git_url_without_username(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"] = [
            {"type": "git", "url": "example.com:org/repo.git"}
        ]

        self.assert_contract_error(
            manifest,
            "network git source example.com:org/repo.git must use a pinned "
            "40-character commit",
        )

    def test_does_not_treat_local_paths_or_ordinary_colons_as_network_urls(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"].extend(
            [
                {"type": "file", "url": "C:/artemis/local.patch"},
                {"type": "file", "url": r"C:\artemis\local.patch"},
                {"type": "file", "url": "label:value/with/slash"},
            ]
        )

        result = self.run_validator(manifest)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_requires_all_ffmpeg_decoder_and_vaapi_vulkan_flags(self):
        manifest = valid_manifest()
        ffmpeg = manifest["modules"][1]
        ffmpeg["config-opts"].remove("--enable-decoder=hevc")
        ffmpeg["config-opts"].remove("--enable-hwaccel=av1_vaapi")
        ffmpeg["config-opts"].remove("--enable-hwaccel=h264_vulkan")

        result = self.run_validator(manifest)

        for option in (
            "--enable-decoder=hevc",
            "--enable-hwaccel=av1_vaapi",
            "--enable-hwaccel=h264_vulkan",
        ):
            with self.subTest(option=option):
                self.assertIn(f"FFmpeg must include {option}", result.stderr)

    def test_requires_gamescope_device_and_libva_finish_arguments(self):
        manifest = valid_manifest()
        manifest["finish-args"] = []

        result = self.run_validator(manifest)

        for argument in (
            "--filesystem=xdg-run/gamescope-0",
            "--device=all",
            "--unset-env=LIBVA_DRIVER_NAME",
            "--unset-env=LIBVA_DRIVERS_PATH",
        ):
            with self.subTest(argument=argument):
                self.assertIn(f"finish-args must include {argument}", result.stderr)

    def test_rejects_custom_add_extensions(self):
        manifest = valid_manifest()
        manifest["add-extensions"] = {"org.example.Driver": {}}

        self.assert_contract_error(
            manifest, "custom add-extensions are not allowed"
        )

    def test_requires_local_artemis_directory_source(self):
        manifest = valid_manifest()
        manifest["modules"][2]["sources"] = [
            {"type": "dir", "path": "../../wrong"}
        ]

        self.assert_contract_error(
            manifest,
            "Artemis module must include a local source with type=dir and path=../..",
        )

    def test_requires_exact_artemis_module_name(self):
        manifest = valid_manifest()
        manifest["modules"][2]["name"] = "not-artemis"

        self.assert_contract_error(
            manifest,
            "Artemis module must include a local source with type=dir and path=../..",
        )

    def test_requires_queue_synchronization_patch_in_libplacebo_module(self):
        manifest = valid_manifest()
        manifest["modules"][0]["sources"].pop()

        self.assert_contract_error(
            manifest,
            "libplacebo module must include "
            "libplacebo-disable-internally-synchronized-queues.patch",
        )

    def test_reports_each_missing_contract_as_a_separate_error(self):
        result = self.run_validator({})

        errors = [line for line in result.stderr.splitlines() if line.startswith("ERROR:")]
        self.assertGreaterEqual(len(errors), 10, result.stderr)
        self.assertEqual(len(errors), len(set(errors)), result.stderr)

    def test_missing_manifest_has_clear_nonzero_diagnostic(self):
        missing_path = Path(tempfile.gettempdir()) / "artemis-manifest-does-not-exist.json"
        result = subprocess.run(
            [sys.executable, str(VALIDATOR), str(missing_path)],
            capture_output=True,
            check=False,
            text=True,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"manifest not found: {missing_path}", result.stderr)


if __name__ == "__main__":
    unittest.main()
