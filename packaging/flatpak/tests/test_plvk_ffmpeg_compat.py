import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
PLVK_SOURCE = REPOSITORY_ROOT / "app/streaming/video/ffmpeg-renderers/plvk.cpp"


class PlVkFfmpegCompatibilityContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = PLVK_SOURCE.read_text(encoding="utf-8")
        start = source.index("bool PlVkRenderer::populateQueues(int videoFormat)")
        end = source.index(
            "bool PlVkRenderer::isPresentModeSupportedByPhysicalDevice", start
        )
        cls.populate_queues = source[start:end]

    def test_queue_population_has_three_explicit_ffmpeg_api_eras(self):
        intermediate_marker = "#elif LIBAVUTIL_VERSION_MAJOR >= 57"
        legacy_marker = "#else\n"

        self.assertIn(
            "#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(59, 34, 100)",
            self.populate_queues,
        )
        self.assertIn(intermediate_marker, self.populate_queues)

        modern, remainder = self.populate_queues.split(intermediate_marker, 1)
        intermediate, legacy = remainder.split(legacy_marker, 1)

        self.assertIn("vkDeviceContext->qf[i]", modern)
        self.assertIn("vkDeviceContext->nb_qf", modern)
        self.assertIn("vkDeviceContext->queue_family_decode_index", intermediate)
        self.assertIn("vkDeviceContext->nb_decode_queues", intermediate)

        self.assertIn("Q_UNUSED(videoFormat);", legacy)
        self.assertIn("vkDeviceContext->queue_family_index", legacy)
        self.assertIn("vkDeviceContext->queue_family_tx_index", legacy)
        self.assertIn("vkDeviceContext->queue_family_comp_index", legacy)
        self.assertNotIn("queue_family_decode_index", legacy)
        self.assertNotIn("nb_decode_queues", legacy)
        self.assertNotIn("vkDeviceContext->qf[", legacy)
        self.assertNotIn("vkDeviceContext->nb_qf", legacy)


if __name__ == "__main__":
    unittest.main()
