# Emoji & Symbols

Run **Search Emoji & Symbols** (search for `emoji`) from the palette. It can also
receive a per-command hotkey through Settings → Shortcuts. No extra permission,
account or network connection is needed to use the bundled catalog.

- Search by a name, keyword, shortcode such as `:+1:` or a literal glyph. English
  stays active. Android language preferences also enable offline keyword packs
  for German, Spanish, French, Japanese, Korean, Portuguese, Russian, and
  Simplified or Traditional Chinese. For example, Japanese `ネコの顔` and
  German `Katzengesicht` both find 🐱 when their language is active.
  To add your own terms, select a symbol and open **Actions → Edit custom keywords**.
  Enter one word or phrase per line, then tap **Save** or press Ctrl+S. Keywords
  belong to that exact symbol, including its skin tone. An exact glyph ranks
  first, followed by an exact personal phrase, the English name, translated
  names and built-in keywords. Prefix searches can combine words from built-in
  and personal terms. Cancel discards edits; clear all lines and save to remove
  the terms. Each emoji accepts up to 16 distinct phrases of up to 64 code
  points each. These terms are stored on this device. Portable emoji backups
  continue to contain ordered favorites and grid size.

  Multiple search words may appear in any order. Use the category menu for flags,
  people, animals, text symbols, favorites or recent copies.
- Select a cell to copy it, or use the Copy button. The complete Unicode string
  is placed on the Android clipboard. Paste it yourself in the destination app.
- Type and navigate immediately after opening: all four arrows move the selected
  emoji while search keeps typing focus. Home/End select the first/last result;
  Enter, keypad Enter, D-pad Center or Ctrl+Enter copy it. No Tab is needed.
- Ctrl+L enters query editing, where Left/Right/Home/End move the caret normally.
  Modified text-navigation keys also enter editing; ↓/↑ return to result browsing.
  Active IME composition keeps its own candidate navigation. Ctrl+D toggles a
  favorite. Escape leaves query editing first, then clears the query, then returns
  to commands. Back and outside-tap dismissal remain available.
- Resize the grid with Ctrl++ (Ctrl+Shift+=), Ctrl+− and Ctrl+0. Zoom in removes
  one column, zoom out adds one, and Actual size returns to the saved or automatic
  size. The same controls are in Actions. The grid keeps the selected symbol and
  the arrow keys follow the new rows.
- Zoom applies to the open picker. To keep a size across launches, use **Actions →
  Set default to … columns**. **Use automatic grid size** removes that preference.
  The picker limits the number of columns to fit its available width.
- The bottom-right capsule offers Copy and Actions (Ctrl+K). The attached action
  panel searches Copy, Favorite and Copy Unicode actions; Unicode copies each
  codepoint as `U+XXXX`. Escape closes it and restores search focus.
- The footer names the selected symbol and confirms copying. The star toggles a
  favorite. Newly pinned symbols go to the end of Favorites. In **Favorites**,
  clear the search to see the full order, then choose **Move favorite earlier**
  or **Move favorite later** from Actions. Ctrl+Alt+↑ and Ctrl+Alt+↓ move the
  selected symbol one position. Favorites lead the All view in the same order.
  Unpinning and pinning a symbol again appends it. Older unordered favorites
  start in catalog order.
- **Recent history is off by default**, including after an upgrade. To remember
  up to 32 distinct copied symbols on this device, choose **Recent** in the category
  menu and select **Enable recent history**. Enabling also shows any previously
  saved entries. While history is off, copying leaves the saved list untouched
  and the picker excludes that list from search ranking.
- In **Recent**, open **Actions** (Ctrl+K) to pause recording or clear the saved
  list. Pausing hides the list and preserves it for later. Clearing removes it
  without changing your recording preference or favorites. Use **Backup and restore → Emoji favorites and grid** to transfer favorites and the saved
  column count. Recent history and its consent setting stay on this installation.

The 4,042 entries include 3,963 fully qualified Unicode Emoji 18.0 sequences, nine
components and 70 additional text symbols. The first 1,940 rows retain the IDs
and order from the earlier picker. For a mixed-tone handshake, search for
`handshake: medium skin tone, light skin tone` or paste 🫱🏽‍🫲🏻 into search.
Copy transfers the exact two modifiers and joiner. Android's font determines
how the result looks on a particular device. [Catalog sources and generation](../third_party/unicode-emoji/UPSTREAM.md)

The footer displays English names. To change the tone for a copy, select any
member of a supported family and open **Actions → Copy with skin tone…**.
Choose the unmodified default, a single tone, or a combination in Unicode
sequence order. Choosing a row copies that exact emoji. Escape closes the
chooser and keeps the current search and selection. Copying a variant leaves
the original favorite in place. When Recent is enabled it records the variant
that reached the clipboard.

The picker offers 332 families covering 2,040 modified sequences from Unicode
18.0. It looks up complete entries, including joined hands and couples; it
offers a tone action only when the data contains variants. Explicit insertion into a destination app remains tracked in
[#44](https://github.com/moikdev/droidcast/issues/44).

References: [Tinycast baseline picker](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/docs/features/emoji.md)
and [Raycast Emoji & Symbols](https://manual.raycast.com/emoji-symbols).

## Unicode 18 and CLDR 49 verification

On the API 36 emulator, the previous build from `41404809` returned no results for [the Japanese cat-face name](screenshots/unicode-emoji/old-japanese.png) or [the English name “cracking face”](screenshots/unicode-emoji/old-unicode.png). The final instrumentation APK produced both expected assertion failures on that build.

Build `67178422` found [the cat face on tablet](screenshots/unicode-emoji/tablet-japanese.png) and [on phone](screenshots/unicode-emoji/phone-japanese.png). With the picker open, the test changed the Android app language from Japanese to German. It checked that the Japanese query persisted, the previous pack stopped matching, and both German and English searches found the cat face. [Tablet after the language change](screenshots/unicode-emoji/tablet-german.png) · [Phone after the language change](screenshots/unicode-emoji/phone-german.png)

The Unicode case checked exact clipboard strings for 🫫 and 🫱🏽‍🫲🏻. The [tablet](screenshots/unicode-emoji/tablet-mixed-tone.png) and [phone](screenshots/unicode-emoji/phone-mixed-tone.png) screenshots show the selected mixed-tone handshake. On the phone the long query scrolls horizontally. The tests used Compose text replacement and injected Enter. Font support for every sequence, physical typing and translation of the app interface were outside these checks.

The final app passed eight Japanese-start tablet cases, four Japanese-start phone cases and three English-start tablet cases. The previous app failed both matched controls. These four batches recorded 44 successful restoration checks, including app languages, preferences, clipboard and APKs. Both layouts used the dedicated API 36 emulator at 100% font (1920×1200 at 240 dpi and 1080×2340 at 420 dpi). The two earlier batches recorded another 22 restoration checks. The intermediate tablet run at `700b3a2f` remains failed at 6/7. One setup invocation exceeded the ten-second deadline waiting for the launcher and emoji catalog. The final build precomputes folded phrases during generation and builds compact search strings. Later passes do not isolate the timeout’s cause or establish a startup performance bound.

The debug JVM task ran 351 tests. Python passed 146 tests in each mode. Debug and benchmark app and instrumentation builds passed. Lint reported zero errors and 52 warnings per variant. The source archive contains 22 checksum-pinned files. Normal and optimized Python generation produced byte-identical assets without network access. [Device records](validation/unicode-emoji/runs.json) · [Host checks](validation/unicode-emoji/host.json) · [Source hashes](validation/unicode-emoji/sources.json) · [Capture and log bindings](validation/unicode-emoji/bindings.json)

## Skin-tone picker verification

On the API 36 emulator, the earlier app's Actions search for “skin tone” had [no matching action](screenshots/emoji-tones/earlier-action-search.png). The current app offered [Medium + Light on tablet](screenshots/emoji-tones/tablet-mixed-choice.png) and [on phone](screenshots/emoji-tones/phone-mixed-choice.png). Both layouts used 100% font. Text replacement set up the filter and the test scrolled the list before tapping the row. The clipboard contained 🫱🏽‍🫲🏻 and Recent recorded that exact ID. The original 🤝 remained selected and favorited. [Tablet after copy](screenshots/emoji-tones/tablet-mixed-copy.png) · [Phone after copy](screenshots/emoji-tones/phone-mixed-copy.png)

Starting from ✌🏽, Escape preserved the query and clipboard. Reopening the chooser, filtering to [Default](screenshots/emoji-tones/phone-default.png) and pressing Enter copied ✌️, including U+FE0F. A country flag offered no tone action and still copied normally. The JVM check covers every catalog entry with a tone modifier, both directions of a mixed handshake, the neutral kiss family and standalone components.

Build `e58b60dd` passed eight tablet cases and three phone cases. Two earlier-app controls failed at the missing tone action with the same final instrumentation. These three batches recorded 33 successful restoration checks. The initial `de8feab1` tablet run passed seven of eight tests. Its mixed-tone test required a row to be displayed before revealing it in a scrollable list. The initial earlier-app controls both failed, and these two batches recorded 22 restoration checks. The initial phone stage did not run. The final instrumentation added the explicit scroll. The app and test binaries from the two builds have different payloads. [Build comparison](validation/emoji-tones/rebuild.json)

Debug and benchmark app and instrumentation builds passed. The debug JVM task ran 352 tests. Python ran 146 tests in each mode. Each lint variant reported zero errors and 52 warnings. Normal and optimized generator runs reproduced the committed asset from the pinned archive. Clipboard and navigation checks used the emulator. Physical keyboard delivery, OEM rendering and comprehensive accessibility remain open. [Device records](validation/emoji-tones/runs.json) · [Host checks](validation/emoji-tones/host.json) · [Source hashes](validation/emoji-tones/sources.json) · [Capture and log bindings](validation/emoji-tones/bindings.json)

### Delivering an emoji to another app

The picker currently writes an explicit copy to the clipboard. Droidcast already receives Android selection requests through `ACTION_PROCESS_TEXT` and checks whether the caller marked its text editable. Android defines a result extra for returning replacement text to that caller. A future **Replace selection with emoji** action could use that contract for an editable selection. It would need a verified return to the requesting editor, separate from the current copy action. A plain launcher invocation has no such selection request. Delivery to an external editor remains open under #44. [Android intent contract](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)
