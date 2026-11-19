# Emoji & Symbols

Run **Search Emoji & Symbols** (search for `emoji`) from the palette. It can also
receive a per-command hotkey through Settings → Shortcuts. No extra permission,
account or network connection is needed to use the bundled catalog.

- Search English names, keywords, shortcodes such as `:+1:` or a literal glyph.
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
  favorite. Favorites lead the All view.
- **Recent history is off by default**, including after an upgrade. To remember
  up to 32 distinct copied symbols on this device, choose **Recent** in the category
  menu and select **Enable recent history**. Enabling also shows any previously
  saved entries. While history is off, copying leaves the saved list untouched
  and the picker excludes that list from search ranking.
- In **Recent**, open **Actions** (Ctrl+K) to pause recording or clear the saved
  list. Pausing hides the list and preserves it for later. Clearing removes it
  without changing your recording preference or favorites. Favorites and recent
  history are local to this installation. Portable favorites remain part of #44.

The licensed snapshot contains 1,870 emoji and 70 common text symbols. It is not
the complete/current Unicode repertoire. Android's font determines appearance
and support; no emoji artwork is shipped. See [source provenance](../third_party/gemoji/UPSTREAM.md).
Skin-tone selection, multilingual/custom keywords, favorite ordering
and portable favorites are tracked in [#44](https://github.com/moikdev/droidcast/issues/44).
Automatic cross-app paste is not claimed. This is partial Tinycast/Raycast parity.

References: [Tinycast baseline picker](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/docs/features/emoji.md)
and [Raycast Emoji & Symbols](https://manual.raycast.com/emoji-symbols).
