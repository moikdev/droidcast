from pathlib import Path
import shutil
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from html_viewer_evidence import visible_body_method

RECORDS = ROOT / "docs/validation/viewer-body"
IMAGES = ROOT / "docs/screenshots/viewer-body"
TITLE = "tinycast-soak-document.txt"
BODY = "Tinycast extended text file test."


class HtmlViewerEvidenceTests(unittest.TestCase):
    def test_exposed_body(self):
        tree = ET.parse(RECORDS / "historical-exposed-body.xml")
        self.assertEqual("accessibility_text", visible_body_method(tree, b"", TITLE, BODY))
        self.assertIsNone(visible_body_method(tree, b"", TITLE, BODY[:-1]))
        self.assertIsNone(visible_body_method(tree, b"", "wrong-title.txt", BODY))

    @unittest.skipUnless(shutil.which("tesseract"), "Requires Tesseract with English data")
    def test_rendered_body_omitted_from_hierarchy(self):
        tree = ET.parse(RECORDS / "historical-missing-body.xml")
        self.assertFalse(any(n.get("text", "").strip() == BODY for n in tree.iter("node")))
        png = (IMAGES / "historical-visible.png").read_bytes()
        self.assertEqual("screenshot_ocr", visible_body_method(tree, png, TITLE, BODY))
        self.assertIsNone(visible_body_method(tree, png, TITLE, BODY[:-1]))
        self.assertIsNone(visible_body_method(tree, png, TITLE, TITLE))

    def test_package_required(self):
        tree = ET.parse(RECORDS / "historical-exposed-body.xml")
        for node in tree.iter("node"):
            node.set("package", "other.viewer")
        self.assertIsNone(visible_body_method(tree, b"", TITLE, BODY))
