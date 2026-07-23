import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


VALIDATOR = Path(__file__).parents[1] / "validate-manifest.py"
MAIN_CPP = Path(__file__).parents[3] / "app" / "main.cpp"
REFRESH_RATE_PRO = Path(__file__).parents[3] / "tests" / "refreshrate" / "refreshrate.pro"
REFRESH_RATE_TEST = (
    Path(__file__).parents[3] / "tests" / "refreshrate" / "tst_refreshrate.cpp"
)
WORKFLOW = Path(__file__).parents[3] / ".github" / "workflows" / "dev-build.yml"
CODEC_PROBE = Path(__file__).parents[1] / "artemis-codec-probe.cpp"
TRACKED_MANIFEST = (
    Path(__file__).parents[1] / "com.artemisdesktop.ArtemisDesktopDev.json"
)
PREPARE_CI_MANIFEST = Path(__file__).parents[1] / "prepare-ci-manifest.py"


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
                "buildsystem": "qmake",
                "config-opts": ["CONFIG+=build_tests"],
                "sources": [{"type": "dir", "path": "../.."}],
            },
            *(
                {
                    "name": module_name,
                    "build-options": {
                        "config-opts": ["-DCMAKE_INSTALL_LIBDIR=lib"]
                    },
                }
                for module_name in ("SDL3", "SDL2-compat", "SDL2_ttf")
            ),
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

    def run_manifest_preparer(
        self,
        manifest,
        *,
        commit="a" * 40,
        channel="rolling",
        sequence="1234",
        application_id="com.artemisdesktop.ArtemisDesktopDev",
        output_outside_flatpak=False,
    ):
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        root = Path(temporary_directory.name)
        flatpak_directory = root / "packaging" / "flatpak"
        flatpak_directory.mkdir(parents=True)
        input_path = (
            flatpak_directory / "com.artemisdesktop.ArtemisDesktopDev.json"
        )
        output_path = (
            root / "outside.json"
            if output_outside_flatpak
            else flatpak_directory
            / "com.artemisdesktop.ArtemisDesktopDev.ci.json"
        )
        input_bytes = json.dumps(manifest, indent=4).encode("utf-8")
        input_path.write_bytes(input_bytes)
        result = subprocess.run(
            [
                sys.executable,
                str(PREPARE_CI_MANIFEST),
                "--input",
                str(input_path),
                "--output",
                str(output_path),
                "--commit",
                commit,
                "--channel",
                channel,
                "--sequence",
                sequence,
                "--application-id",
                application_id,
            ],
            capture_output=True,
            check=False,
            text=True,
        )
        return result, input_path, output_path, input_bytes

    def test_accepts_manifest_that_satisfies_contract(self):
        result = self.run_validator(valid_manifest())

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("manifest contract satisfied", result.stdout)

    def test_tracked_manifest_adds_only_downloads_filesystem_write_access(self):
        manifest = json.loads(TRACKED_MANIFEST.read_text(encoding="utf-8"))
        filesystem_permissions = {
            argument
            for argument in manifest["finish-args"]
            if argument.startswith("--filesystem=")
        }

        self.assertEqual(
            filesystem_permissions,
            {
                "--filesystem=host-os:ro",
                "--filesystem=xdg-download",
                "--filesystem=xdg-run/gamescope-0",
            },
        )
        for forbidden in (
            "--filesystem=home",
            "--filesystem=host",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, manifest["finish-args"])
        serialized = json.dumps(manifest)
        for forbidden in ("flatpak-spawn", "org.freedesktop.Flatpak"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, serialized)

    def test_ci_manifest_preparer_is_deterministic_and_preserves_input(self):
        manifest = json.loads(TRACKED_MANIFEST.read_text(encoding="utf-8"))
        result, input_path, output_path, input_bytes = self.run_manifest_preparer(
            manifest
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(input_path.read_bytes(), input_bytes)
        self.assertEqual(output_path.parent, input_path.parent)
        prepared = json.loads(output_path.read_text(encoding="utf-8"))
        artemis = [
            module
            for module in prepared["modules"]
            if module.get("name") == "artemis"
        ]
        self.assertEqual(len(artemis), 1)
        self.assertEqual(
            artemis[0]["config-opts"][-4:],
            [
                f"VIBERTEMIS_BUILD_COMMIT={'a' * 40}",
                "VIBERTEMIS_UPDATE_CHANNEL=rolling",
                "VIBERTEMIS_BUILD_SEQUENCE=1234",
                "VIBERTEMIS_APPLICATION_ID="
                "com.artemisdesktop.ArtemisDesktopDev",
            ],
        )

    def test_ci_manifest_preparer_enforces_identity_policy(self):
        invalid_identities = (
            ("uppercase SHA", "A" * 40, "rolling", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("short SHA", "a" * 39, "rolling", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("unsafe SHA", "a" * 39 + ";", "rolling", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("invalid channel", "a" * 40, "nightly", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("unsafe sequence", "a" * 40, "rolling", "1;touch",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("leading-zero sequence", "a" * 40, "rolling", "01",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("wrong app ID", "a" * 40, "rolling", "1",
             "com.artemis_desktop.Artemis"),
            ("rolling zero", "a" * 40, "rolling", "0",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("stable nonzero", "a" * 40, "stable", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
            ("none nonzero", "a" * 40, "none", "1",
             "com.artemisdesktop.ArtemisDesktopDev"),
        )
        for name, commit, channel, sequence, application_id in invalid_identities:
            with self.subTest(name=name):
                result, _, output_path, _ = self.run_manifest_preparer(
                    valid_manifest(),
                    commit=commit,
                    channel=channel,
                    sequence=sequence,
                    application_id=application_id,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output_path.exists())

        for channel, sequence in (("rolling", "1"), ("stable", "0"), ("none", "0")):
            with self.subTest(valid_channel=channel):
                result, _, output_path, _ = self.run_manifest_preparer(
                    valid_manifest(), channel=channel, sequence=sequence
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(output_path.exists())

    def test_ci_manifest_preparer_enforces_uint64_sequence_bounds(self):
        uint64_max = "18446744073709551615"
        for name, sequence, expected_success in (
            ("maximum uint64", uint64_max, True),
            ("uint64 overflow", "18446744073709551616", False),
            ("much larger than uint64", "9" * 200, False),
        ):
            with self.subTest(name=name):
                result, _, output_path, _ = self.run_manifest_preparer(
                    valid_manifest(),
                    channel="rolling",
                    sequence=sequence,
                )
                if expected_success:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    prepared = json.loads(
                        output_path.read_text(encoding="utf-8")
                    )
                    self.assertIn(
                        f"VIBERTEMIS_BUILD_SEQUENCE={uint64_max}",
                        prepared["modules"][2]["config-opts"],
                    )
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output_path.exists())

    def test_ci_manifest_preparer_rejects_ambiguous_or_unsafe_manifest(self):
        duplicate_module = valid_manifest()
        duplicate_module["modules"].append(
            {
                "name": "artemis",
                "buildsystem": "qmake",
                "config-opts": [],
                "sources": [{"type": "dir", "path": "../.."}],
            }
        )
        duplicate_option = valid_manifest()
        duplicate_option["modules"][2]["config-opts"].append(
            "VIBERTEMIS_UPDATE_CHANNEL=none"
        )
        nested_duplicate_option = valid_manifest()
        nested_duplicate_option["modules"][2]["build-options"] = {
            "config-opts": [" VIBERTEMIS_BUILD_SEQUENCE=0"]
        }
        wrong_buildsystem = valid_manifest()
        wrong_buildsystem["modules"][2]["buildsystem"] = "simple"

        for name, manifest in (
            ("duplicate module", duplicate_module),
            ("duplicate option", duplicate_option),
            ("nested duplicate option", nested_duplicate_option),
            ("non-qmake module", wrong_buildsystem),
        ):
            with self.subTest(name=name):
                result, _, output_path, _ = self.run_manifest_preparer(manifest)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output_path.exists())

        result, _, output_path, _ = self.run_manifest_preparer(
            valid_manifest(), output_outside_flatpak=True
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output_path.exists())

    def test_ci_manifest_preparer_rejects_every_qmake_identity_mutation(self):
        mutation_forms = (
            "{key}=value",
            "{key} = value",
            " {key}= value ",
            "{key}+=value",
            "{key} += value",
            "{key}+= value",
            "{key}-=value",
            "{key} -= value",
            "{key}*=value",
            "{key} *= value",
            "{key}~=s/value/replacement/",
            "{key} ~= s/value/replacement/",
        )
        for key in (
            "VIBERTEMIS_BUILD_COMMIT",
            "VIBERTEMIS_UPDATE_CHANNEL",
            "VIBERTEMIS_BUILD_SEQUENCE",
            "VIBERTEMIS_APPLICATION_ID",
        ):
            for index, mutation_form in enumerate(mutation_forms):
                option = mutation_form.format(key=key)
                manifest = valid_manifest()
                artemis = manifest["modules"][2]
                if index % 2:
                    artemis["build-options"] = {
                        "nested": {"config-opts": [option]}
                    }
                else:
                    artemis["config-opts"].append(option)

                with self.subTest(key=key, option=option):
                    result, _, output_path, _ = self.run_manifest_preparer(
                        manifest
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output_path.exists())

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

    def test_rejects_string_module_and_source_includes(self):
        manifest = valid_manifest()
        manifest["modules"].append("modules/extra.json")
        manifest["modules"][0]["sources"].append("sources/extra.json")

        result = self.run_validator(manifest)

        self.assertIn(
            "string module includes are not supported: modules/extra.json",
            result.stderr,
        )
        self.assertIn(
            "string source includes are not supported in module libplacebo: "
            "sources/extra.json",
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

    def test_rejects_host_only_scp_git_url_without_path_separator(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"] = [
            {"type": "git", "url": "example.com:repo.git"}
        ]

        self.assert_contract_error(
            manifest,
            "network git source example.com:repo.git must use a pinned "
            "40-character commit",
        )

    def test_rejects_unpinned_git_source_using_ssh_host_alias(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"] = [
            {"type": "git", "url": "github:owner/repo.git"}
        ]

        self.assert_contract_error(
            manifest,
            "network git source github:owner/repo.git must use a pinned "
            "40-character commit",
        )

    def test_rejects_unpinned_no_slash_ssh_host_aliases(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"] = [
            {"type": "git", "url": "github:repo.git"},
            {"type": "git", "url": "buildhost:src"},
        ]

        result = self.run_validator(manifest)

        for url in ("github:repo.git", "buildhost:src"):
            with self.subTest(url=url):
                self.assertIn(
                    f"network git source {url} must use a pinned 40-character commit",
                    result.stderr,
                )

    def test_does_not_treat_local_paths_or_ordinary_colons_as_network_urls(self):
        manifest = valid_manifest()
        manifest["modules"][1]["modules"][0]["sources"].extend(
            [
                {"type": "git", "url": "C:/artemis/local.git"},
                {"type": "git", "url": r"C:\artemis\local.git"},
                {"type": "git", "url": r"C:artemis\local.git"},
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

    def test_requires_fractional_refresh_tests_in_artemis_build(self):
        manifest = valid_manifest()
        manifest["modules"][2]["config-opts"] = []

        self.assert_contract_error(
            manifest,
            "Artemis module must include CONFIG+=build_tests",
        )

    def test_requires_sdl_cmake_modules_to_install_in_flatpak_libdir(self):
        manifest = valid_manifest()
        for module in manifest["modules"]:
            if module.get("name") in ("SDL3", "SDL2-compat", "SDL2_ttf"):
                module["build-options"]["config-opts"] = []

        result = self.run_validator(manifest)

        for module_name in ("SDL3", "SDL2-compat", "SDL2_ttf"):
            with self.subTest(module_name=module_name):
                self.assertIn(
                    f"{module_name} must include -DCMAKE_INSTALL_LIBDIR=lib",
                    result.stderr,
                )

    def test_installs_fractional_refresh_test_to_stable_libexec_path(self):
        project = REFRESH_RATE_PRO.read_text(encoding="utf-8")

        self.assertIn("target.path = /app/libexec", project)
        self.assertIn("INSTALLS += target", project)

    def test_fractional_refresh_qml_singleton_test_uses_registered_instance(self):
        source = REFRESH_RATE_TEST.read_text(encoding="utf-8")

        self.assertIn(
            "engine.singletonInstance<RefreshRateParser*>(typeId)", source
        )
        self.assertNotIn("QQmlComponent", source)

    def test_workflow_runs_fractional_refresh_test_before_bundling(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        builder_command = (
            "flatpak-builder --force-clean --install-deps-from=flathub "
            "--repo=flatpak-repo flatpak-build "
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.ci.json"
        )
        test_command = (
            "flatpak build flatpak-build env QT_QPA_PLATFORM=offscreen "
            "/app/libexec/tst_refreshrate"
        )
        bundle_command = (
            'flatpak build-bundle flatpak-repo "artemis-flatpak-$VERSION.flatpak" '
            "com.artemisdesktop.ArtemisDesktopDev"
        )

        self.assertIn(test_command, workflow)
        self.assertLess(workflow.index(builder_command), workflow.index(test_command))
        self.assertLess(workflow.index(test_command), workflow.index(bundle_command))

    def test_flatpak_job_uses_builder_with_current_appstream_compose_support(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        flatpak_job = workflow[workflow.index("  build-flatpak-dev:") :]
        flatpak_job = flatpak_job[: flatpak_job.index("\n  build-raspberry-pi-dev:")]

        self.assertIn("runs-on: ubuntu-24.04", flatpak_job)

    def test_workflow_allows_local_submodule_mirrors_before_flatpak_builder(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        mirror_configuration = "git config --global protocol.file.allow always"
        builder_command = (
            "flatpak-builder --force-clean --install-deps-from=flathub "
            "--repo=flatpak-repo flatpak-build "
            "packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.ci.json"
        )

        self.assertIn(mirror_configuration, workflow)
        self.assertLess(
            workflow.index(mirror_configuration), workflow.index(builder_command)
        )

    def test_codec_probe_checks_every_registered_decoder(self):
        probe = CODEC_PROBE.read_text(encoding="utf-8")

        self.assertIn("av_codec_iterate", probe)
        self.assertIn(
            "hasHardwareDevice(AVCodecID codecId, AVHWDeviceType deviceType)",
            probe,
        )
        self.assertNotIn("avcodec_find_decoder", probe)

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

    def test_desktop_file_name_uses_id_without_desktop_suffix(self):
        main_cpp = MAIN_CPP.read_text(encoding="utf-8")

        self.assertIn(
            "app.setDesktopFileName(QString::fromUtf8(desktopId));", main_cpp
        )
        self.assertIn(
            'qputenv("SDL_VIDEO_WAYLAND_WMCLASS", desktopId);', main_cpp
        )
        self.assertIn('qputenv("SDL_VIDEO_X11_WMCLASS", desktopId);', main_cpp)

    def test_disabled_modules_do_not_satisfy_required_contracts(self):
        manifest = valid_manifest()
        for module in manifest["modules"]:
            module["disabled"] = True

        result = self.run_validator(manifest)

        self.assertIn("FFmpeg must include --enable-decoder=h264", result.stderr)
        self.assertIn(
            "Artemis module must include a local source with type=dir and path=../..",
            result.stderr,
        )
        self.assertIn(
            "libplacebo module must include "
            "libplacebo-disable-internally-synchronized-queues.patch",
            result.stderr,
        )

    def test_disabled_sources_do_not_satisfy_required_contracts(self):
        manifest = valid_manifest()
        manifest["modules"][0]["sources"][1]["disabled"] = True
        manifest["modules"][2]["sources"][0]["disabled"] = True

        result = self.run_validator(manifest)

        self.assertIn(
            "Artemis module must include a local source with type=dir and path=../..",
            result.stderr,
        )
        self.assertIn(
            "libplacebo module must include "
            "libplacebo-disable-internally-synchronized-queues.patch",
            result.stderr,
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
