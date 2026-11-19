"""Verify the dedicated one-line TXT fixture shown by Android HTML Viewer."""
import io
import re
import shutil
import subprocess

VIEWER = "com.android.htmlviewer"


def visible_body_method(hierarchy, screenshot, title, expected_body):
    """Return the evidence source, or None when the captured view doesn't match."""
    nodes = [node for node in hierarchy.iter("node") if node.get("package") == VIEWER]
    webviews = [node for node in nodes if node.get("class") == "android.webkit.WebView"]
    if not webviews:
        return None
    # A WebView can publish another WebView as its accessibility root.
    webview = webviews[0]
    content = set(webview.iter("node"))
    if any(node not in content for node in webviews):
        return None
    if not any(node not in content and node.get("class") == "android.widget.TextView"
               and node.get("text") == title for node in nodes):
        return None

    exposed = [node.get("text").strip() for node in nodes
               if node in content and node.get("text", "").strip()]
    if exposed:
        return "accessibility_text" if exposed == [expected_body] else None

    # Only the missing-body path needs host OCR. Keep the fixture title, toolbar,
    # and system bars outside the sampled rectangle.
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", webview.get("bounds", ""))
    if match is None:
        return None
    left, top, right, bottom = map(int, match.groups())
    try:
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("The WebView omitted its body. Install Pillow to check the screenshot.") from error
    with Image.open(io.BytesIO(screenshot)) as image:
        if not (0 <= left < right <= image.width and 0 <= top < bottom <= image.height):
            return None
        crop = io.BytesIO()
        image.crop((left, top, right, bottom)).save(crop, format="PNG")

    executable = shutil.which("tesseract")
    if executable is None:
        raise RuntimeError("The WebView omitted its body. Install Tesseract with English data to verify the screenshot.")
    result = subprocess.run([executable, "stdin", "stdout", "-l", "eng", "--psm", "6"],
                            input=crop.getvalue(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=True, timeout=5)
    text = result.stdout.decode("utf-8").strip()
    return "screenshot_ocr" if text == expected_body else None
