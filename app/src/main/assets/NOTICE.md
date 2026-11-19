# Source and license notices

Droidcast is an unofficial Android adaptation of [Tinycast](https://github.com/abue-ammar/tinycast). It is not affiliated with Tinycast's maintainers or Raycast.

Tinycast Copyright (C) 2026 Abue Ammar. Android implementation and modifications: Droidcast contributors, 2026. The Android implementation, host adapter and publication branding were developed/modified on 2026-10-30. Distributed under the GNU Affero General Public License, version 3 or later; see [LICENSE](LICENSE). There is no warranty.

- The upstream generated Raycast runtime is copied unchanged into `app/src/main/assets/RaycastRuntime.generated.js`.
- Its complete upstream runtime sources, package lock, build instructions, license and exact revision are in [`third_party/tinycast-runtime`](third_party/tinycast-runtime).
- Original Swift visual-reference files and provenance are in [`third_party/tinycast-palette`](third_party/tinycast-palette). The Android palette adapts their design tokens and view structure into Compose; the Swift references are not compiled into the APK.
- The runtime includes React under the MIT license. The React license is retained in `third_party/tinycast-runtime/REACT-LICENSE` and bundled as `app/src/main/assets/REACT-LICENSE`.
- The translucent Android activity interaction was informed by [Launchpad Search](https://github.com/jaxparrow07/launchpad-search); its implementation was not copied.
- Store extensions are downloaded at runtime under their respective licenses. No Store extension bundles are shipped in the APK or this repository.
- The offline emoji data is derived from GitHub's gemoji under its MIT license. The exact source and revision are in `third_party/gemoji`; the full license is bundled as `GEMOJI-LICENSE`. Common text-symbol annotations are authored for Droidcast. Glyph artwork is rendered by the device font.

Android/Compose dependencies and the Gradle wrapper retain their own upstream licenses. No upstream copyright or license notice is replaced by the Droidcast branding.

The Android emoji picker also includes Unicode Emoji 18.0 and CLDR 49 data under Unicode-3.0. The full notice is bundled as `UNICODE-LICENSE`.
