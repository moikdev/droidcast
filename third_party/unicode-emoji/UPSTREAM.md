# Unicode emoji and search annotations

The Android picker supplements the existing GitHub gemoji catalog with all 3,963 fully qualified sequences and nine standalone components from Unicode Emoji 18.0. It retains the original 1,940 entries in their existing order and appends missing sequences in Unicode file order. IDs encode exact code points, including variation selectors, joiners and individual skin tones. Android supplies glyph artwork.

Keyword packs use CLDR 49 English, German, Spanish, French, Japanese, Korean, Portuguese, Russian and Chinese annotations. Simplified and Traditional Chinese use separate packs. English stays active alongside the matching Android language preferences. These packs supply search terms. Displayed names and the surrounding interface use English.

Run `python3 scripts/generate-emoji.py` from an offline checkout to regenerate the catalog, ten keyword packs and bundled Unicode notice. The script verifies each input against `sources.json` before reading it. `sources.zip` contains the original bytes at the immutable source URLs in that manifest. Source datasets use the Unicode-3.0 license. The full copyright and permission notice ships in `app/src/main/assets/UNICODE-LICENSE`.

The vendored `emoji-test.txt` matches the [Unicode Consortium’s published 18.0 file](https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt) byte for byte (SHA-256 `8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a`).

[Unicode Emoji 18.0 data](https://github.com/unicode-org/unicodetools/tree/7c451e2cfe2c85990dc0f4cb3396de05e2aa599d/unicodetools/data/emoji/18.0) · [CLDR 49 source](https://github.com/unicode-org/cldr/tree/96e54b273d2da4ec6fbad350ba19ff23fa24a588/common)
