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
- The bottom-right capsule offers Copy and Actions (Ctrl+K). The attached action
  panel searches Copy, Favorite and Copy Unicode actions; Unicode copies each
  codepoint as `U+XXXX`. Escape closes it and restores search focus.
- The footer names the selected symbol and confirms copying. The star toggles a
  favorite. Favorites lead the All view; Recent holds the last 32 distinct copies.
  Both are private on-device preferences and survive app restart. They are not
  included in the current portable backup schema.

The licensed snapshot contains 1,870 emoji and 70 common text symbols. It is not
the complete/current Unicode repertoire. Android's font determines appearance
and support; no emoji artwork is shipped. See [source provenance](../third_party/gemoji/UPSTREAM.md).
Skin-tone selection, multilingual/custom keywords, adjustable density, ordering
and portable favorites are tracked in [#44](https://github.com/moikdev/droidcast/issues/44).
Automatic cross-app paste is not claimed. This is partial Tinycast/Raycast parity.

References: [Tinycast baseline picker](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/docs/features/emoji.md)
and [Raycast Emoji & Symbols](https://manual.raycast.com/emoji-symbols).
