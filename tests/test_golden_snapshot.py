import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from golden_snapshot import GoldenSnapshotRecorder, normalize_nodes


SAMPLE_XML = """<hierarchy><node text="赚金币" content-desc="入口" resource-id="coin" class="android.view.View" bounds="[1,2][3,4]" clickable="true" scrollable="false" enabled="true" /></hierarchy>"""


class GoldenSnapshotRecorderTest(unittest.TestCase):
    def test_normalized_node_shape(self):
        node = normalize_nodes(ET.fromstring(SAMPLE_XML))[0]
        self.assertEqual(node["text"], "赚金币")
        self.assertEqual(node["contentDescription"], "入口")
        self.assertEqual(node["bounds"], [1, 2, 3, 4])
        self.assertTrue(node["clickable"])
        self.assertTrue(node["enabled"])

    def test_opt_in_atomic_capture_and_deduplication(self):
        old_value = os.environ.get("TJB_GOLDEN_SNAPSHOT")
        os.environ["TJB_GOLDEN_SNAPSHOT"] = "1"
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                recorder = GoldenSnapshotRecorder(
                    temp_dir,
                    "test-version",
                    "0",
                    lambda: {"app_version": "rules-v1"},
                )
                root = ET.fromstring(SAMPLE_XML)
                recorder.capture(root, "com.taobao.taobao", "Activity", "coin_home", ("coin_home",), "test")
                recorder.capture(root, "com.taobao.taobao", "Activity", "coin_home", ("coin_home",), "test")
                recorder.flush()

                captures = list((Path(temp_dir) / "runtime" / "golden-captures").glob("coin_home-*"))
                self.assertEqual(len(captures), 1)
                metadata = json.loads((captures[0] / "metadata.json").read_text(encoding="utf-8"))
                self.assertEqual(metadata["schemaVersion"], 1)
                self.assertEqual(metadata["legacyPageType"], "coin_home")
                self.assertEqual(metadata["rulesVersion"], "rules-v1")
                self.assertFalse(metadata["ocrRequested"])
                self.assertTrue((captures[0] / "page.xml").exists())
        finally:
            if old_value is None:
                os.environ.pop("TJB_GOLDEN_SNAPSHOT", None)
            else:
                os.environ["TJB_GOLDEN_SNAPSHOT"] = old_value


if __name__ == "__main__":
    unittest.main()
