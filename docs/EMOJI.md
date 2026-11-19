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
that reached the clipboard. If a variant displaces the selected emoji from a full
Recent history, the picker keeps the caller visible for the current view.
An unfiltered grid can temporarily show 33 results. Saved history still holds
32. Changing the query or category, leaving the picker, clearing history or
turning history off releases the extra result.

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

### Keeping the caller when Recent is full

Both independent reviews of `cdf945a8` identified a case the mixed-handshake test missed. Starting with 32 saved IDs, copying a previously absent dark thumbs up from the oldest default thumbs up evicted the caller. The clipboard was correct. The [exact-glyph search lost its only result](screenshots/emoji-tones/recent/before-final-exact.png), and the [unfiltered grid selected a different face](screenshots/emoji-tones/recent/before-final-all.png).

The matched device tests seed the history, open Recent, select the oldest caller and tap the variant row. They verify the clipboard and the complete saved chronology before checking the caller. The unfiltered test waits for the category menu to return focus before injecting End. On the repair, the [exact-glyph view keeps its one result](screenshots/emoji-tones/recent/tablet-final-exact.png). The [unfiltered phone view](screenshots/emoji-tones/recent/phone-final-all.png) keeps the caller alongside the 32 saved entries. The tests also change the search or pause and resume history and verify the transient result disappears.

The first repair protocol on `43d2fb86` passed 8/9 tablet cases. On both comparison and repaired apps its unfiltered setup failed to select the oldest ID. The exact-search comparison reached the eviction assertion and failed; the repaired exact-search case passed. These two batches recorded 22 restoration checks. The first repair phone continuation did not run. The later instrumentation adds the focus precondition. All source and binary identities are kept separately. [Device logs and restoration outcomes](validation/emoji-tones/recent/runs.json) · [Repair sources](validation/emoji-tones/recent/sources.json) · [Host build and lint results](validation/emoji-tones/recent/host.json)

With final instrumentation on `c9ef042d`, nine tablet and five phone cases passed. Both matched comparison cases reached the caller-preservation assertion and failed on `e58b60dd`. These three batches completed 33 restoration checks. Both build variants passed, 352 JVM cases passed, and each lint variant reported zero errors and 52 warnings. Python receipts from the unchanged scripts record 146 passes in each mode. The results cover one API 36 emulator in two configurations at 100% font.

### Delivering an emoji to another app

In an Android text editor, select the text you want to replace. Open the selection menu (or its overflow) and choose **Replace with emoji**. Search for a symbol, choose it, then tap **Replace selection**. **Choose another emoji** returns to search. **Cancel** returns to the editor with the original text.

The Android editor receives the exact Unicode sequence through an `ACTION_PROCESS_TEXT` result and applies it to its selection. The replacement screen does not need the clipboard or keyboard accessibility service. Editors decide which text actions to offer and how to apply results. A read-only selection can return to the editor without changing the text. The regular **Droidcast** text action still opens the existing reviewed-text workflow. The launcher picker still offers **Copy to Clipboard**. [Android intent contract](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)

On the dedicated API 36 emulator, a long press in a stock Android EditText exposed the new action in the real selection toolbar. After confirmation the native editor replaced `sunshine` with 🫱🏽‍🫲🏻. A separate explicit request selected only `chosen words` in `Before chosen words after`. That caller checked the complete returned text and a fresh clipboard sentinel. The screenshot’s clipboard overlay comes from seeding that sentinel. The stock-toolbar case exercises the framework’s own result handling. [Earlier toolbar](screenshots/emoji-selection/earlier-menu.png) · [Current toolbar](screenshots/emoji-selection/tablet-system-menu.png) · [Phone confirmation](screenshots/emoji-selection/phone-confirm.png) · [Returned range](screenshots/emoji-selection/phone-replaced.png) · [Read-only selection](screenshots/emoji-selection/phone-read-only.png)

Build `adde55c9` passed six cases in each of two layouts. These covered the system menu, explicit range replacement, cancellation, read-only input, a malformed editability flag and reopening the same choice followed by activity recreation. The same instrumentation failed twice on the pre-feature app at the missing handler and menu entry. On the intermediate `afdd2150` app the captured stale confirmation callback dismissed the confirmation screen. The test failed when it expected the screen to remain, before reaching recreation. The intermediate control does not measure the caller’s resulting text. All four final batches passed eleven restoration checks each.

Earlier runs remain separate. Build `4460e039` passed three explicit-caller cases per layout. Its pre-feature comparison failed to observe the fixture’s unavailable message and supplies no matched missing-handler evidence. Build `afdd2150` passed four cases per layout, and both of its pre-feature comparisons failed at the missing route. These two collections each completed 33 restoration checks. Debug and benchmark app and test APKs built at the final source. The debug JVM run passed 354 cases. Lint recorded zero errors and 53 warnings per variant. Python passed 146 cases per mode at `afdd2150` on unchanged scripts. The two layouts share one API 36 emulator at 100% font. Keyboard search text in these tests used controlled Compose replacement. Physical typing, other editors and OEMs, process death and comprehensive accessibility remain unverified. [Device outcomes](validation/emoji-selection/runs.json) · [APK identities](validation/emoji-selection/builds.json) · [Build inputs](validation/emoji-selection/sources.json) · [Host checks](validation/emoji-selection/host.json) · [Evidence bindings](validation/emoji-selection/bindings.json)

### Custom keyword verification

The API 36 emulator ran both tablet and phone layouts at 100% font. The earlier app from `c9ef042d` returned [no matching action](screenshots/emoji-keywords/earlier-custom-action.png) for “custom keywords.” The same final instrumentation failed at that missing action in both comparison cases.

On build `27e2f3b9`, the user could save two phrases for 🐱 and find the same favorited symbol with either a decomposed spelling of “Café Club” or the Japanese phrase “夜の猫”. The tests tapped Save and verified that Enter copied exactly 🐱. Escape discarded a subsequent edit. Clearing both lines and saving removed the match while preserving the favorite. [Tablet editor](screenshots/emoji-keywords/tablet-editor.png) · [Phone search](screenshots/emoji-keywords/phone-result.png) · [Phone after removal](screenshots/emoji-keywords/phone-removed.png)

A separate test recreated the activity with an open draft and used a second live store to save a different value. Save preserved the draft and the peer's edit until the user chose **Load saved keywords**. After loading that value the user could make a new edit. In the held-key case, releasing Ctrl before S produced no extra text in the restored picker, and a new S press reached the query. [Tablet conflict](screenshots/emoji-keywords/tablet-conflict.png) · [Phone conflict](screenshots/emoji-keywords/phone-conflict.png)

Nine final tablet cases and four final phone cases passed. The final comparison failed both missing-action assertions. These three batches completed 33 restoration checks. The initial build `b5debc0c` passed six tablet and two phone cases, and its separate comparison failed one missing-action assertion. Those three batches recorded another 33 checks. Each comparison used the same instrumentation as its matching positive cases. Text setup used Compose replacement, and the concurrent edit used two stores in one process. Physical multilingual typing, a cold process restart and broad accessibility remain outside the recorded scenarios.

Both final build variants succeeded. The debug JVM task ran 354 tests. Each lint variant reported zero errors and 53 warnings, including a suggestion to use the KTX preference editor helper. Python ran 146 tests per mode on unchanged scripts. [Device records](validation/emoji-keywords/runs.json) · [Host results](validation/emoji-keywords/host.json) · [Source identities](validation/emoji-keywords/sources.json) · [Capture and log bindings](validation/emoji-keywords/bindings.json)

### Canceling with a held key and saving an invalid draft

Holding Escape in the keyword editor cancels the draft once. The returning picker keeps its search and selected emoji through further key repeats and release. A new Escape press then clears the search. If Ctrl+S arrives immediately after an invalid edit, the editor keeps the draft and displays its validation message. Correcting it allows Save to succeed.

On the API 36 emulator the earlier keyword app from `27e2f3b9` cleared the cat query on the first repeat. In the other control, setting a 65-character phrase and dispatching Ctrl+S in the same main-thread turn threw an IllegalArgumentException. The fixture caught the exception to retain the screen and fail the test. The screenshot shows the validation state. The exception is recorded in the [earlier dispatch log](validation/emoji-keywords/repair/runs/before/invalid.log).

Using identical instrumentation, the repaired `bc103d88` app passed nine tablet and five phone cases, including both regressions on each layout. Three batches completed 33 restoration checks. The keyboard-hold test injected a down, repeats and release. The immediate Save test combined a Compose text update with direct Activity key dispatch. The earlier keyword matrices above remain attributed to their own builds. Both repaired debug and benchmark app/test variants built, 354 debug JVM tests passed, and lint reported zero errors and 53 warnings per variant. Python results are reused from the unchanged scripts.

[Earlier Escape](screenshots/emoji-keywords/repair/before-escape.png) · [Repaired tablet](screenshots/emoji-keywords/repair/tablet-escape-repeat.png) · [Phone invalid draft](screenshots/emoji-keywords/repair/phone-invalid-save.png) · [Device results](validation/emoji-keywords/repair/runs.json) · [Host results](validation/emoji-keywords/repair/host.json) · [Source inventory](validation/emoji-keywords/repair/sources.json) · [Capture bindings](validation/emoji-keywords/repair/bindings.json)
