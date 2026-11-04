# Calculator Actions

This slice of [#67](https://github.com/moikdev/droidcast/issues/67) adds two actions to the calculator's existing attached Actions panel. The floating palette, Copy Answer, menu search and native IME submission remain in use.

| Action | Result | Default accelerator |
| --- | --- | --- |
| Copy Answer | Copies the selected formatted answer, such as `36`. | Enter |
| Put Answer in Search Bar | Replaces the launcher query with `= 36`, focuses Search and places the caret after the answer. | Ctrl+Enter |
| Copy Calculation | Copies the displayed expression and selected answer, such as `12 × 3 = 36`. | Ctrl+Shift+Enter |

Both Enter and numeric-keypad Enter support the new accelerators. Existing configured shortcuts retain precedence at the launcher; a saved Ctrl+Shift+Enter binding is still valid. Within Actions, the named action owns its accelerator even if menu search highlights another row. A disabled chaining accelerator cannot fall through to Copy Answer. No cross-app paste-back is implemented.

## Formatting and chaining

The pinned Tinycast [calculator coordinator](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/Tinycast/Features/Calculator/UI/CalculatorCoordinator.swift) copies `expression = answer` and rewrites Search with the answer for chaining. Its [result model](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/Tinycast/Features/Calculator/Model/CalcEngine.swift) excludes answers that do not retain their meaning when entered again. Android keeps its deliberate calculator prefix when rewriting Search, so ordinary number searches remain available.

Copy Calculation uses the selected command's existing displayed expression and payload. It removes the leading explicit `=` mode marker and surrounding whitespace, then joins the expression and answer with ` = `. Multiplication retains the displayed `×`: `= sqrt(81)+12x3` copies `sqrt(81)+12 × 3 = 45`. Conversion expressions retain their units; `1 m to in` copies `1 m to in = 39.3700787402 in`. This action does not silently reevaluate or add precision to the selected value.

Chaining also retains units. A supported explicit quantity such as `= 100 cm` evaluates to `100 cm`; appending ` to m` converts it to `1 m`. Bare `100 cm` remains a search. This narrow quantity-literal support does not implement general unit arithmetic. The existing on-device Double arithmetic, US decimal formatting, ten fractional digits and 256-character expression limit remain. Reusing a rounded answer can accumulate rounding: the displayed inch answer above converts back to `100.0000000001 cm`.

Put Answer is enabled only when the formatted answer can be read back unchanged by the supported parser. For example, the 309-digit answer to `10^308` remains copyable, while chaining is disabled because it exceeds the input limit. Calculator history/pinning, currencies, broader date/time calculation, locale preferences and Raycast's cross-app paste workflows remain separate parity gaps.

## Input ownership

Every calculation shares the ID `result`. Calculator Actions therefore bind to the opening's exact query, category, selection, command value, route and focus epoch. Query/category/selection revisions also reject away-and-back transitions before a Compose frame. A stale choice is rejected before it can dismiss another route. Window focus, recording and busy-state guards remain active.

Root accelerators block actions during IME composition. The Actions search retains its separate composition guard and native IME action. Held repeats and releases remain consumed after a successful accelerator; an unmatched modified Enter repeat cannot become a plain Enter copy. Pointer activation is an explicit choice of the displayed action.

Validation and the final review gates are still in progress. The original failed probe and all historical APK/test identities are retained separately; they do not earn final-runtime pass credit. Physical/OEM keyboards, TalkBack, whole-Store parity and production signing/migration remain unverified.

## Reproducing the owned calculator cohort

Build debug application and instrumentation APKs, then use the existing initialized, idle `Tinycast_Tablet_API_36` emulator. With `ANDROID_HOME` set, the guarded runner can execute Calculator Actions without an extension archive:

```sh
python3 scripts/test-reviewed-text-input.py \
  --serial emulator-5580 --avd Tinycast_Tablet_API_36 --layout phone \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --classes dev.tinycast.android.CalculatorActionsDeviceTest --expected 14 \
  --output artifacts/calculator-actions-owned
```

Use a fresh output directory and retain any recovery ownership after failure. The runner restores and independently verifies the original clipboard and preferences; private recovery snapshots stay outside published evidence. Extension and shared regression cohorts still require their frozen formatter archive. Short viewports use existing palette scrolling and, when needed, the action-search keyboard toggle; these paths are verified separately from the production calculator changes.
