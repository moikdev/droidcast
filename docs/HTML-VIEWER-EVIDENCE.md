# Verifying the TXT shortcut in Android HTML Viewer

During the API 36 tablet run recorded in #41, the viewer displayed the fixture filename and sentence. Its accessibility hierarchy included the filename and two nested WebView nodes, both without body text. The hardware test timed out looking for the sentence. A second retained hierarchy from the same evidence directory exposes the sentence as a TextView. The original hierarchy and screenshot establish the discrepancy. Its cause inside the stock viewer remains undetermined. [Issue #41](https://github.com/moikdev/droidcast/issues/41)

The hardware runner checks the focused viewer package around capture and requires the fixture title outside the WebView. It reads the body only inside the viewer's WebView. If Android exposes text there, the sole sentence must match, including case and punctuation. If Android exposes no body, Tesseract checks the visible pixels in the WebView rectangle. Both paths save the hierarchy and screenshot used for that decision. The result records `accessibility_text` or `screenshot_ocr`. The OCR path needs Pillow and the Tesseract English language model on the host.

| Evidence | Result |
| --- | --- |
| Original screenshot and hierarchy with the body omitted | OCR reads the expected sentence. The XML supplies the title and bounds. [Screenshot](screenshots/viewer-body/historical-visible.png) · [Hierarchy](validation/viewer-body/historical-missing-body.xml) |
| Retained hierarchy exposing the document text | The exact accessibility text passes. A changed filename or a missing final period fails. [Hierarchy](validation/viewer-body/historical-exposed-body.xml) |
| New same-title control on the API 36 tablet | The viewer opens a different TXT with the identical filename. Its XML again omits the body. OCR reads “Tinycast wrong document control.” and rejects the expected fixture sentence. [Screenshot](screenshots/viewer-body/wrong-body.png) · [Hierarchy](validation/viewer-body/same-title-wrong-body.xml) |

The new control used an unprivileged shell and a generated MediaStore document on a disposable session of the Tinycast_Tablet_API_36 emulator at 1920×1200 and 240 dpi. The test verified its bytes before opening the stock HTML Viewer and confirmed deletion afterward. That launch exercises the verifier directly. The historical screenshot came from the original shortcut route. The original APK hash was unavailable in that run's receipt.

These checks apply to a one-line English fixture. OCR verifies its visible rendering. It gives no assurance about accessibility exposure, offscreen content, other document types or other viewer apps.
