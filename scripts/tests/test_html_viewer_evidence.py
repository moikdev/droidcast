from importlib.util import find_spec
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
OCR_AVAILABLE = find_spec("PIL") is not None and shutil.which("tesseract") is not None


class HtmlViewerEvidenceTests(unittest.TestCase):
    def test_exposed_body(self):
        tree = ET.parse(RECORDS / "historical-exposed-body.xml")
        self.assertEqual("accessibility_text", visible_body_method(tree, b"", TITLE, BODY))
        self.assertIsNone(visible_body_method(tree, b"", TITLE, BODY[:-1]))
        self.assertIsNone(visible_body_method(tree, b"", "wrong-title.txt", BODY))

    @unittest.skipUnless(OCR_AVAILABLE, "Requires Pillow and Tesseract with English data")
    def test_rendered_body_omitted_from_hierarchy(self):
        tree = ET.parse(RECORDS / "historical-missing-body.xml")
        self.assertFalse(any(n.get("text", "").strip() == BODY for n in tree.iter("node")))
        png = (IMAGES / "historical-visible.png").read_bytes()
        self.assertEqual("screenshot_ocr", visible_body_method(tree, png, TITLE, BODY))
        self.assertIsNone(visible_body_method(tree, png, TITLE, BODY[:-1]))
        self.assertIsNone(visible_body_method(tree, png, TITLE, TITLE))

    @unittest.skipUnless(OCR_AVAILABLE, "Requires Pillow and Tesseract with English data")
    def test_same_title_opens_a_different_document_body(self):
        tree = ET.parse(RECORDS / "same-title-wrong-body.xml")
        png = (IMAGES / "wrong-body.png").read_bytes()
        self.assertIsNone(visible_body_method(tree, png, TITLE, BODY))
        self.assertEqual("screenshot_ocr", visible_body_method(tree, png, TITLE, "Tinycast wrong document control."))

    def test_package_required(self):
        tree = ET.parse(RECORDS / "historical-exposed-body.xml")
        for node in tree.iter("node"):
            node.set("package", "other.viewer")
        self.assertIsNone(visible_body_method(tree, b"", TITLE, BODY))

    def test_sibling_webviews_are_ambiguous(self):
        tree = ET.parse(RECORDS / "historical-exposed-body.xml")
        ET.SubElement(tree.getroot(), "node", {"package": "com.android.htmlviewer",
                                              "class": "android.webkit.WebView"})
        self.assertIsNone(visible_body_method(tree, b"", TITLE, BODY))

    def test_out_of_image_webview_rejected(self):
        if find_spec("PIL") is None:
            self.skipTest("Requires Pillow")
        tree = ET.parse(RECORDS / "historical-missing-body.xml")
        webview = next(n for n in tree.iter("node") if n.get("class") == "android.webkit.WebView")
        webview.set("bounds", "[0,0][1921,1200]")
        self.assertIsNone(visible_body_method(tree, (IMAGES / "historical-visible.png").read_bytes(), TITLE, BODY))
