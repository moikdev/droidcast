package dev.tinycast.android

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmojiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val palettePrefs get() = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
    private fun storedFavorites(): Set<String> {
        val order = org.json.JSONArray(palettePrefs.getString(EmojiStore.FAVORITES_KEY, "[]"))
        return (0 until order.length()).map(order::getString).toSet()
    }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var originalEmoji = emptyMap<String, Any?>()
    private var originalClip: android.content.ClipData? = null
    private val store get() = compose.activity.store.emoji
    @Before fun setup() {
        device.executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalEmoji = context.getSharedPreferences("emoji", Context.MODE_PRIVATE).all.toMap()
        compose.activityRule.scenario.onActivity {
            val clipboard = it.getSystemService(ClipboardManager::class.java)
            originalClip = clipboard.primaryClip
            clipboard.resetClipboardForTest()
        }
        // The current ViewModel already loaded preferences; clear through the public operations.
        compose.waitUntil(10000) { !compose.activity.store.loading && !store.loading }
        compose.activityRule.scenario.onActivity {
            store.entries.filter { entry -> entry.id in store.favorites }.forEach(store::toggleFavorite)
            it.store.reset()
        }
        compose.onNodeWithTag("search").performTextReplacement("emoji")
        compose.onNodeWithTag("result-emoji").assertIsDisplayed().performClick()
        compose.onNodeWithTag("emoji-palette").assertIsDisplayed()
        if (compose.activity.resources.configuration.screenWidthDp >= 700) {
            compose.waitUntil(5000) {
                !androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
    }
    @After fun restore() {
        restorePalettePreferences(context, original)
        val editor = context.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit().clear()
        originalEmoji.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
        compose.activityRule.scenario.onActivity {
            val clipboard = it.getSystemService(ClipboardManager::class.java)
            originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.resetClipboardForTest()
        }
    }
    private fun search(value: String) { compose.onNodeWithTag("emoji-search").performTextReplacement(value); compose.waitForIdle() }
    private fun key(code: Int, meta: Int = 0) {
        val down = android.os.SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            assertTrue(testAutomation().injectInputEvent(
                KeyEvent(down, android.os.SystemClock.uptimeMillis(), action, code, 0, meta,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD), true))
        }
        compose.waitForIdle()
    }
    private fun clipboard(): String {
        var value = ""
        compose.activityRule.scenario.onActivity { value = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        return value
    }
    private fun filter(value: String) {
        compose.onNodeWithTag("emoji-filter").performClick()
        if(compose.onAllNodesWithTag("inline-menu-choices").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("emoji-filter-$value"))
        compose.onNodeWithTag("emoji-filter-$value").performScrollTo().performClick()
    }
    private fun evidence(name: String, marker: String, expectedColumns: Int? = null, expectedFavoriteIds: List<String>? = null) {
        compose.waitForIdle()
        assertTrue("Screenshot marker: $marker", device.wait(Until.hasObject(By.textContains(marker)), 5000))
        dismissClipboardForEvidence()
        assertTrue("System clipboard overlay must settle before capture", device.wait(Until.gone(By.res("com.android.systemui", "clipboard_ui")), 5000))
        val dir = File(context.filesDir, "emoji-evidence").apply { mkdirs() }
        val expectedCount = "${store.results().size} ${if (store.results().size == 1) "result" else "results"} · offline"
        val expectedName = store.results().getOrNull(store.selected)?.name ?: "Emoji & Symbols"
        val expectedEnabled = store.results().isNotEmpty().toString()
        val xml = File(dir, "$name.xml")
        // Validate the exact native hierarchy that will be paired with the PNG.
        // A Compose assertion alone cannot certify a cached accessibility snapshot.
        compose.waitUntil(10000) {
            if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(xml)
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
            val nodes = document.getElementsByTagName("node")
            val elements = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("package") == context.packageName }
            fun tagged(tag: String) = elements.singleOrNull { it.getAttribute("resource-id").substringAfterLast('/') == tag }
            val gridMatches = expectedColumns == null || elements.filter { it.getAttribute("resource-id").startsWith("emoji-cell-") }
                .map { android.graphics.Rect.unflattenFromString(it.getAttribute("bounds").replace(Regex("[^0-9-]+"), " ").trim()) }
                .filterNotNull().filter { !it.isEmpty }.groupBy { it.top }.toSortedMap().values.firstOrNull()?.size == expectedColumns
            val orderedIdsMatch = expectedFavoriteIds == null || elements.filter { it.getAttribute("resource-id").startsWith("emoji-cell-") }
                .map { it.getAttribute("resource-id").removePrefix("emoji-cell-") }.let { it == expectedFavoriteIds }
            orderedIdsMatch && gridMatches && tagged("emoji-count")?.getAttribute("text") == expectedCount &&
                tagged("emoji-selected-name")?.getAttribute("text") == expectedName &&
                tagged("emoji-copy")?.getAttribute("enabled") == expectedEnabled &&
                tagged("emoji-favorite")?.getAttribute("enabled") == expectedEnabled
        }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun searchStaysOnTheLauncherRowBesideBackAndCategory() {
        search("face")
        evidence("single-row-search", "results · offline")
        val emoji = compose.onNodeWithTag("emoji-search").fetchSemanticsNode().boundsInWindow
        val filter = compose.onNodeWithTag("emoji-filter").fetchSemanticsNode().boundsInWindow
        val back = compose.onNodeWithTag("emoji-back").fetchSemanticsNode().boundsInWindow
        fun bounds(r: androidx.compose.ui.geometry.Rect) = org.json.JSONArray(listOf(r.left, r.top, r.right, r.bottom))
        val geometry = org.json.JSONObject().put("emojiSearch", bounds(emoji)).put("category", bounds(filter)).put("back", bounds(back))
        val geometryFile = File(context.filesDir, "emoji-evidence/single-row-geometry.json")
        geometryFile.writeText(geometry.toString(2))
        assertEquals("Category shares the search baseline", emoji.center.y, filter.center.y, 1f)
        assertEquals("Back shares the search baseline", emoji.center.y, back.center.y, 1f)
        assertTrue("Back retains a full Android touch target", back.width >= 48 * context.resources.displayMetrics.density - 1)
        assertTrue("Back does not cover editable search", back.right <= emoji.left)
        assertTrue("Category shares the search row without overlap", emoji.right <= filter.left)
        for (query in listOf("happy", "", "face")) {
            search(query)
            assertEquals(emoji, compose.onNodeWithTag("emoji-search").fetchSemanticsNode().boundsInWindow)
        }
        val longestCategory = store.entries.map { it.category }.distinct().maxBy { it.length }
        filter(longestCategory)
        search("")
        val narrowSearch = compose.onNodeWithTag("emoji-search").fetchSemanticsNode().boundsInWindow
        val longFilter = compose.onNodeWithTag("emoji-filter").fetchSemanticsNode().boundsInWindow
        assertEquals(emoji.top, narrowSearch.top, 1f)
        assertTrue("Long categories retain a usable search field", narrowSearch.width >= 80 * context.resources.displayMetrics.density)
        assertTrue(narrowSearch.right <= longFilter.left)
        assertEquals(narrowSearch.center.y, longFilter.center.y, 1f)
        evidence("single-row-long-category", "results · offline")
        filter("All")
        search("face")
        compose.onNodeWithTag("emoji-back").performClick()
        val launcher = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInWindow
        assertEquals("Search baseline is stable between screens", launcher.top, emoji.top, 1f)
        assertEquals("Search starts at the same column", launcher.left, emoji.left, 1f)
        assertEquals(launcher.height, emoji.height, 1f)
        geometry.put("launcherSearch", bounds(launcher)).put("longCategorySearch", bounds(narrowSearch)).put("longCategory", bounds(longFilter))
        geometryFile.writeText(geometry.toString(2))
        compose.onNodeWithTag("result-emoji").performClick()
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val selected = store.results()[store.selected]
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(selected.glyph, clipboard())
    }
    @Test fun commandOpensOfflineGridWithinTheFloatingPalette() {
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("emoji-count").assertTextEquals("4042 results · offline")
        val bounds = compose.onNodeWithTag("emoji-palette").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width < device.displayWidth)
        evidence("grid", "4042 results")
        filter("Text symbols")
        compose.onNodeWithTag("emoji-count").assertTextEquals("70 results · offline")
        search("math")
        assertTrue(store.results().all { it.category == "Text symbols" })
        evidence("symbols", "results · offline")
    }
    @Test fun searchesNamesAliasesAndKeywordsAndShowsHonestEmptyState() {
        search(":+1:")
        compose.onNodeWithTag("emoji-selected-name").assertTextEquals("thumbs up")
        evidence("alias-search", "thumbs up")
        search("happy")
        assertTrue(store.results().any { it.glyph == "😀" })
        search("no-such-emoji-314159")
        compose.onNodeWithTag("emoji-empty").assertIsDisplayed()
        compose.onNodeWithTag("emoji-copy").assertIsNotEnabled()
        compose.onNodeWithTag("emoji-favorite").assertIsNotEnabled()
        evidence("no-results", "No matching emoji or symbols")
    }
    @Test fun japaneseDeviceSearchKeepsEnglishAndCopiesExactResult() {
        val locales = compose.activity.resources.configuration.locales
        assertTrue("Run with Japanese among the Android app languages", (0 until locales.size()).any { locales[it].language == "ja" })
        search("ネコの顔")
        if (store.results().isEmpty()) evidence("japanese-empty", "No matching emoji")
        assertEquals("Japanese short name must select the cat face", "🐱", store.results().firstOrNull()?.glyph)
        compose.onNodeWithTag("emoji-selected-name").assertTextEquals("cat face")
        evidence("japanese-cat", "cat face")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("🐱", clipboard())
        search(":+1:")
        compose.onNodeWithTag("emoji-selected-name").assertTextEquals("thumbs up")
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun changingAppLanguageRefreshesTheOpenPickerAndDropsOldKeywords() {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val original = manager.applicationLocales
        try {
            search("ネコの顔")
            compose.onNodeWithTag("emoji-selected-name").assertTextEquals("cat face")
            manager.applicationLocales = android.os.LocaleList.forLanguageTags("de-DE")
            compose.waitUntil(10000) {
                compose.activity.resources.configuration.locales[0].language == "de" &&
                    !store.loading && store.query == "ネコの顔" && store.results().isEmpty()
            }
            search("Katzengesicht")
            compose.onNodeWithTag("emoji-selected-name").assertTextEquals("cat face")
            evidence("german-cat", "cat face")
            search("cat face")
            compose.onNodeWithTag("emoji-selected-name").assertTextEquals("cat face")
        } finally {
            manager.applicationLocales = original
        }
    }

    @Test fun unicodeSequenceLookupCopiesExactMixedTones() {
        search("cracking face")
        if (store.results().isEmpty()) evidence("unicode-empty", "No matching emoji")
        assertEquals("The Unicode 18 name must resolve", "🫫", store.results().firstOrNull()?.glyph)
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("🫫", clipboard())
        search("handshake: medium skin tone, light skin tone")
        assertEquals("🫱🏽‍🫲🏻", store.results().first().glyph)
        evidence("mixed-tone", "handshake: medium skin tone, light skin tone")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("🫱🏽‍🫲🏻", clipboard())
    }

    private fun toneMenuFrame(name: String, tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val dir = File(context.filesDir, "emoji-evidence").apply { mkdirs() }
        val xml = File(dir, "$name.xml")
        compose.waitUntil(10000) {
            if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(xml)
            val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("node")
            (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.any {
                it.getAttribute("resource-id") == tag && it.getAttribute("package") == context.packageName &&
                    it.getAttribute("bounds").isNotEmpty()
            }
        }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }

    private fun openToneMenu() {
        compose.onNodeWithTag("emoji-actions").performScrollTo().performTouchInput { click(center) }
        val menuSearch = compose.onNodeWithTag("inline-menu-search")
        menuSearch.performTextReplacement("skin tone")
        if (compose.onAllNodesWithTag("inline-menu-empty").fetchSemanticsNodes().isNotEmpty())
            toneMenuFrame("tone-before", "inline-menu-empty")
        compose.onNodeWithTag("emoji-action-tone").assertIsDisplayed().performTouchInput { click(center) }
        menuSearch.assertIsFocused()
    }

    @Test fun pointerCopiesMixedToneWithoutChangingTheCallerOrOriginalFavorite() {
        search("🤝")
        compose.onNodeWithTag("emoji-favorite").performClick()
        compose.activityRule.scenario.onActivity { store.rememberRecent(true) }
        openToneMenu()
        val glyph = "🫱🏽‍🫲🏻"
        val id = "1faf1-1f3fd-200d-1faf2-1f3fb"
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Medium + Light")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("emoji-tone-$id"))
        toneMenuFrame("mixed-choice", "emoji-tone-$id")
        compose.onNodeWithTag("emoji-tone-$id").performTouchInput { click(center) }
        assertEquals(glyph, clipboard())
        assertEquals("🤝", store.query)
        assertEquals("🤝", store.results()[store.selected].glyph)
        assertEquals(setOf("1f91d"), storedFavorites())
        assertEquals(id, store.recent.first())
        evidence("mixed-copied", "Copied handshake")
    }

    @Test fun keyboardCanCancelAndCopyTheExactDefaultFromAnExistingTone() {
        search("✌🏽")
        val before = clipboard()
        openToneMenu()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        assertEquals(before, clipboard())
        assertEquals("✌🏽", store.query)
        openToneMenu()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Default")
        toneMenuFrame("default-choice", "emoji-tone-270c-fe0f")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("✌️", clipboard())
        assertEquals("✌🏽", store.query)
    }

    @Test fun symbolsWithoutTonesHaveNoToneAction() {
        search("🇯🇵")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("skin tone")
        compose.onNodeWithTag("inline-menu-empty").assertIsDisplayed()
        compose.onNodeWithTag("emoji-action-tone").assertDoesNotExist()
        key(KeyEvent.KEYCODE_ESCAPE)
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("🇯🇵", clipboard())
    }

    @Test fun typingAndFourWayNavigationWorkImmediatelyWithoutTab() {
        for (code in listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_E)) key(code)
        compose.onNodeWithTag("emoji-search").assertTextContains("face")
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        assertEquals(0, store.selected)
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(1, store.selected)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val row = store.selected
        assertTrue(row > 1)
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(row - 1, store.selected)
        key(KeyEvent.KEYCODE_DPAD_UP); assertEquals(0, store.selected)
        key(KeyEvent.KEYCODE_MOVE_END); assertEquals(store.results().lastIndex, store.selected)
        key(KeyEvent.KEYCODE_MOVE_HOME); assertEquals(0, store.selected)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        key(KeyEvent.KEYCODE_DEL)
        compose.onNodeWithTag("emoji-search").assertTextContains("fac")
        assertEquals(0, store.selected)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val chosen = store.results()[store.selected]
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals(chosen.glyph, clipboard())
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        evidence("typing-and-navigation", "Copied ${chosen.name}")
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_E)
        // Input injection acknowledgement and Compose idleness do not certify
        // that the IME has committed an edit. Await the actual editable value;
        // missing/dropped input still fails the bounded condition.
        val beforeCommit = compose.onNodeWithTag("emoji-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val commitStart = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(5000) {
            compose.onNodeWithTag("emoji-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "faec"
        }
        File(context.filesDir, "emoji-evidence/typing-caret-commit.json").writeText(org.json.JSONObject()
            .put("initialObservedText", beforeCommit).put("finalObservedText", "faec")
            .put("waitMs", android.os.SystemClock.elapsedRealtime() - commitStart).toString(2))
        compose.onNodeWithTag("emoji-search").assertTextContains("faec")
        val beforeCaret = store.selected
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Editing remains active after typing", beforeCaret, store.selected)
        compose.onNodeWithTag("emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        search("face")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Down returns from query editing to result browsing", 1, store.selected)
    }
    @Test fun rapidRightThenEnterUsesCurrentSelectionBeforeRedraw() {
        val expected = store.results()[1].glyph
        compose.activityRule.scenario.onActivity {
            val now = android.os.SystemClock.uptimeMillis()
            // Both pairs in one main-thread turn: no recomposition between them.
            for (code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER)) {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                    it.window.decorView.dispatchKeyEvent(KeyEvent(now, now, action, code, 0))
            }
            assertEquals(expected, it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
        }
    }
    @Test fun compositionStartedBeforeRedrawKeepsNavigationWithIme() {
        search("face")
        compose.activityRule.scenario.onActivity {
            val connection = it.window.decorView.findFocus()!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            assertTrue(connection.setComposingRegion(0, 4))
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                it.window.decorView.dispatchKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            assertEquals("Composition must be read at event time, before the next frame", 0, store.selected)
            connection.finishComposingText()
        }
    }
    @Test fun favoriteOrderingMigratesAndFollowsTheSelectedSymbol() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        val (a, b, c) = store.entries.take(3)
        compose.activityRule.scenario.onActivity {
            prefs.edit().putStringSet("favorites", setOf(c.id, b.id, a.id)).remove("favoriteOrder").apply()
            palettePrefs.edit().remove(EmojiStore.FAVORITES_KEY).apply()
        }
        filter("Favorites")
        fun displayed() = store.results().map { it.id }
        val initial = listOf(a.id, b.id, c.id)
        assertEquals(initial, displayed())
        evidence("favorites-before", "3 results", expectedFavoriteIds = initial)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("emoji-cell-${b.id}").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        val moved = listOf(b.id, a.id, c.id)
        assertEquals("Move the second favorite to the first displayed position", moved, displayed())
        compose.onNodeWithTag("emoji-cell-${b.id}").assertIsSelected().assertIsDisplayed()
        evidence("favorites-moved", "3 results", expectedFavoriteIds = moved)
        compose.onNodeWithTag("emoji-actions").performScrollTo().performClick()
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("emoji-favorite-earlier"))
        compose.onNodeWithTag("emoji-favorite-earlier").assertIsNotEnabled()
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("emoji-favorite-later"))
        compose.onNodeWithTag("emoji-favorite-later").performClick()
        assertEquals(initial, displayed())
        compose.onNodeWithTag("emoji-cell-${b.id}").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertEquals(listOf(a.id, c.id, b.id), displayed())
        filter("All")
        assertEquals(listOf(a.id, c.id, b.id), displayed().take(3))
        filter("Favorites")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("emoji-palette").assertIsDisplayed()
        assertEquals(listOf(a.id, c.id, b.id), displayed())
        evidence("favorites-reopened", "3 results", expectedFavoriteIds = listOf(a.id, c.id, b.id))
    }

    @Test fun openStoresPreserveNewerMembershipAndInvisibleFavoriteSlots() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            compose.activityRule.scenario.onActivity {
                val (a, b, c) = store.entries.take(3)
                val old = listOf(a.id, "future-entry", b.id)
                prefs.edit().putStringSet("favorites", old.toSet())
                    .putString("favoriteOrder", org.json.JSONArray(old).toString()).apply()
                palettePrefs.edit().remove(EmojiStore.FAVORITES_KEY).apply()
                val peer = EmojiStore(context, scope)
                store.chooseFilter("Favorites")
                peer.toggleFavorite(c)
                assertEquals(listOf(a.id, b.id, c.id), store.results().map { it.id })
                store.moveFavorite(a.id, b.id, 1)
                assertEquals(org.json.JSONArray(listOf(b.id, "future-entry", a.id, c.id)).toString(), palettePrefs.getString(EmojiStore.FAVORITES_KEY, null))
                store.moveFavorite(a.id, b.id, 1)
                assertEquals(listOf(b.id, a.id, c.id), store.results().map { it.id })
                peer.toggleFavorite(a)
                peer.toggleFavorite(a)
                assertEquals(listOf(b.id, c.id, a.id), store.results().map { it.id })
                assertEquals(setOf(a.id, b.id, c.id, "future-entry"), store.favorites)
                assertEquals(store.favorites, peer.favorites)
            }
        } finally { scope.cancel() }
    }

    @Test fun heldZoomRepeatsStayOutOfActionsSearchAfterFocusTransfer() {
        search("face")
        val down = android.os.SystemClock.uptimeMillis()
        fun emit(action: Int, repeat: Int, modifiers: Int) {
            assertTrue(testAutomation().injectInputEvent(KeyEvent(down, android.os.SystemClock.uptimeMillis(),
                action, KeyEvent.KEYCODE_EQUALS, repeat, modifiers, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                0, 0, android.view.InputDevice.SOURCE_KEYBOARD), true))
            compose.waitForIdle()
        }
        val menu = compose.onNodeWithTag("inline-menu-search")
        val exactText = { value: String -> SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)) }
        fun captureMenu(name: String) {
            val observed = menu.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertTrue(observed.length <= 3 && observed.all { it == '=' })
            val dir = File(context.filesDir, "emoji-evidence").apply { mkdirs() }
            val xml = File(dir, "$name.xml")
            compose.waitUntil(10000) {
                if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
                device.dumpWindowHierarchy(xml)
                val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(xml).getElementsByTagName("node")
                (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.any {
                    it.getAttribute("resource-id") == "inline-menu-search" && it.getAttribute("focused") == "true" &&
                        (it.getAttribute("text") == observed || (observed.isEmpty() && it.getAttribute("text") == "Search for actions…"))
                }
            }
            assertTrue(device.takeScreenshot(File(dir, "$name.png")))
        }
        try {
            emit(KeyEvent.ACTION_DOWN, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            compose.onNodeWithTag("emoji-actions").performScrollTo().performTouchInput { click(center) }
            menu.assertIsFocused().assert(exactText(""))
            for (repeat in 1..3) emit(KeyEvent.ACTION_DOWN, repeat, 0)
            captureMenu("handoff-menu")
            menu.assert(exactText(""))
        } finally {
            emit(KeyEvent.ACTION_UP, 0, 0)
        }
        // A genuinely new unmodified press must still reach the new editor.
        key(KeyEvent.KEYCODE_EQUALS)
        menu.assert(exactText("="))
        captureMenu("handoff-next")
        assertEquals("face", store.query)
    }

    @Test fun gridZoomFollowsSelectionAndOnlySavesAnExplicitDefault() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        compose.activityRule.scenario.onActivity {
            prefs.edit().remove("gridColumns").apply()
            palettePrefs.edit().remove(EmojiStore.GRID_KEY).apply()
        }
        search("face")
        fun firstRowColumns(): Int {
            val cells = compose.onAllNodes(SemanticsMatcher("emoji cells") {
                SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith("emoji-cell-")
            }).fetchSemanticsNodes().map { it.boundsInWindow }.filter { it.height > 0 && it.width > 0 }
            val top = cells.minOf { it.top }
            return cells.count { kotlin.math.abs(it.top - top) < 1 }
        }
        fun action(tag: String) {
            compose.onNodeWithTag("emoji-actions").performScrollTo().performClick()
            compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
        }
        val original = firstRowColumns()
        evidence("grid-default", "results · offline", original)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val selectedId = store.results()[store.selected].id
        key(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals("Zoom in removes a rendered column", original - 1, firstRowColumns())
        compose.onNodeWithTag("emoji-cell-$selectedId").assertIsSelected().assertIsDisplayed()
        assertEquals("face", store.query)
        assertEquals(0, palettePrefs.getInt(EmojiStore.GRID_KEY, 0))
        evidence("grid-zoomed", "results · offline", original - 1)
        val bounds = compose.onNodeWithTag("emoji-cell-$selectedId").fetchSemanticsNode().boundsInWindow
        val minimum = 48 * context.resources.displayMetrics.density - 1
        assertTrue("Zoomed target", bounds.width >= minimum && bounds.height >= minimum)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(original, store.selected)
        key(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON)
        assertEquals(original, firstRowColumns())
        action("emoji-zoom-in")
        assertEquals(original - 1, firstRowColumns())
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        key(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        assertEquals(original, firstRowColumns())
        action("emoji-zoom-in")
        action("emoji-save-size")
        assertEquals(original - 1, palettePrefs.getInt(EmojiStore.GRID_KEY, 0))
        action("emoji-zoom-out")
        assertEquals(original, firstRowColumns())
        assertEquals(original - 1, palettePrefs.getInt(EmojiStore.GRID_KEY, 0))
        compose.onNodeWithTag("emoji-back").performClick()
        compose.onNodeWithTag("search").performTextReplacement("emoji")
        compose.onNodeWithTag("result-emoji").performClick()
        search("face")
        assertEquals("A fresh picker uses its saved default", original - 1, firstRowColumns())
        evidence("grid-reopened", "results · offline", original - 1)
        action("emoji-auto-size")
        assertEquals(0, palettePrefs.getInt(EmojiStore.GRID_KEY, 0))
        assertEquals(original, firstRowColumns())
    }

    @Test fun copyingWithoutConsentLeavesHistoryEmpty() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        compose.activityRule.scenario.onActivity { prefs.edit().remove("rememberRecent").remove("recent").apply() }
        search("🇯🇵")
        compose.onNodeWithTag("emoji-copy").performClick()
        assertEquals("🇯🇵", clipboard())
        filter("Recent"); search("")
        evidence("recent-default", "Recent")
        assertFalse("Copy without consent must leave the recent preference absent", prefs.contains("recent"))
        assertTrue(store.results().isEmpty())
        compose.onNodeWithTag("emoji-enable-history").assertIsDisplayed()
    }

    @Test fun legacyHistoryStaysHiddenUntilEnabledAndCanBePausedAndCleared() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        val originalEntry = store.entries.first { it.glyph == "🇯🇵" }
        val next = store.entries.first { it.glyph == "❤️" }
        val legacy = org.json.JSONArray(listOf(originalEntry.id)).toString()
        compose.activityRule.scenario.onActivity {
            prefs.edit().remove("rememberRecent").putString("recent", legacy).apply()
            store.toggleFavorite(originalEntry)
        }
        filter("Recent")
        assertTrue(store.results().isEmpty())
        assertEquals(legacy, prefs.getString("recent", null))
        evidence("recent-legacy-off", "Recent history is off")
        compose.onNodeWithTag("emoji-enable-history").performScrollTo().performClick()
        assertEquals(listOf(originalEntry), store.results())
        filter("All"); search(next.glyph)
        compose.onNodeWithTag("emoji-copy").performClick()
        assertEquals(next.glyph, clipboard())
        filter("Recent"); search("")
        assertEquals(listOf(next, originalEntry), store.results())
        evidence("recent-enabled", "2 results")
        fun historyAction(tag: String, keyboard: Boolean = false) {
            if (keyboard) {
                compose.onNodeWithTag("emoji-search").performSemanticsAction(SemanticsActions.RequestFocus)
                key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
            } else compose.onNodeWithTag("emoji-actions").performScrollTo().performClick()
            compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
        }
        historyAction("emoji-history-pause")
        assertTrue(store.results().isEmpty())
        val paused = prefs.getString("recent", null)
        assertEquals(org.json.JSONArray(listOf(next.id, originalEntry.id)).toString(), paused)
        filter("All"); search("π"); compose.onNodeWithTag("emoji-copy").performClick()
        assertEquals("π", clipboard()); assertEquals(paused, prefs.getString("recent", null))
        filter("Recent"); search("")
        historyAction("emoji-history-clear", keyboard = true)
        assertFalse(prefs.contains("recent")); assertFalse(prefs.getBoolean("rememberRecent", true))
        assertTrue(originalEntry.id in store.favorites)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("emoji-palette").assertIsDisplayed()
        assertTrue(store.recent.isEmpty())
        assertTrue(originalEntry.id in store.favorites)
    }

    @Test fun freshAndOpenStoresRespectConsentAndIgnorePausedHistoryForRanking() {
        val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            compose.activityRule.scenario.onActivity {
                val entries = store.entries
                val chosen = entries.last()
                val saved = org.json.JSONArray(listOf(chosen.id)).toString()
                prefs.edit().remove("rememberRecent").putString("recent", saved).apply()
                val peer = EmojiStore(context, job)
                assertFalse(peer.historyEnabled)
                assertTrue(peer.recent.isEmpty())
                assertEquals(entries.first().id, store.results().first().id)
                store.rememberRecent(true)
                assertEquals(listOf(chosen.id), peer.recent)
                assertEquals(chosen.id, store.results().first().id)
                val reopened = EmojiStore(context, job)
                assertTrue(reopened.historyEnabled)
                assertEquals(listOf(chosen.id), reopened.recent)
                peer.rememberRecent(false)
                assertFalse(store.historyEnabled)
                assertTrue(reopened.recent.isEmpty())
                reopened.copied(entries.first())
                assertEquals(saved, prefs.getString("recent", null))
                assertEquals(entries.first().id, store.results().first().id)
                peer.clearHistory()
                assertFalse(store.hasSavedHistory)
                reopened.rememberRecent(true)
                store.copied(chosen)
                peer.clearHistory()
                assertTrue(reopened.historyEnabled)
                assertTrue(reopened.recent.isEmpty())
                reopened.copied(entries.first())
                assertEquals(listOf(entries.first().id), peer.recent)
            }
        } finally {
            job.cancel()
        }
    }

    @Test fun clipboardPreservesJoinersFlagsVariationSelectorsAndKeycaps() {
        compose.activityRule.scenario.onActivity { store.rememberRecent(true) }
        listOf("👨‍👩‍👧‍👦", "🇯🇵", "❤️", "1️⃣", "π").forEach { glyph ->
            search(glyph)
            if (glyph == "π") compose.onNodeWithTag("emoji-search").performImeAction()
            else compose.onNodeWithTag("emoji-copy").performClick()
            assertEquals(glyph, clipboard())
            compose.onNodeWithTag("emoji-copy-status").assertTextContains("Copied", substring = true)
            if (glyph == "👨‍👩‍👧‍👦") evidence("family-copied", "Copied family")
        }
        filter("Recent"); search("")
        assertEquals(listOf("π", "1️⃣", "❤️", "🇯🇵", "👨‍👩‍👧‍👦"), store.results().take(5).map { it.glyph })
        evidence("recent", "Recent")
    }
    @Test fun favoriteCanBeClearedAndReusedAndSurvivesActivityRecreation() {
        filter("Favorites")
        compose.onNodeWithTag("emoji-empty").assertIsDisplayed()
        evidence("empty-favorites", "Favorite a symbol")
        filter("All"); search("family man woman girl boy")
        key(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        val entry = store.results().first()
        assertTrue(entry.id in store.favorites)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("emoji-palette").assertIsDisplayed()
        filter("Favorites"); search("")
        compose.onNodeWithTag("emoji-count").assertTextEquals("1 result · offline")
        compose.onNodeWithTag("emoji-cell-${entry.id}").assertContentDescriptionContains("favorite", substring = true)
        evidence("favorites", "family: man, woman, girl, boy")
        compose.onNodeWithTag("emoji-favorite").performClick()
        compose.onNodeWithTag("emoji-empty").assertIsDisplayed()
        assertFalse(entry.id in store.favorites)
        filter("All"); search(entry.glyph)
        compose.onNodeWithTag("emoji-favorite").performClick()
        assertTrue(entry.id in store.favorites)
    }
    @Test fun keyboardNavigationAndActivationKeepSelectionVisibleWithoutAnimation() {
        compose.activityRule.scenario.onActivity { store.rememberRecent(true) }
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle(); device.waitForIdle()
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        assertTrue(store.selected >= 3)
        val row = store.selected
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, store.selected)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(row, store.selected)
        var entry = store.results()[store.selected]
        compose.onNodeWithTag("emoji-cell-${entry.id}").assertIsDisplayed().assertIsSelected()
        key(KeyEvent.KEYCODE_NUMPAD_ENTER)
        compose.waitUntil(5000) { store.recent.firstOrNull() == entry.id }
        assertEquals(entry.glyph, clipboard())
        var tabs = 0
        while (runCatching { compose.onNodeWithTag("emoji-grid").assertIsFocused() }.isFailure && tabs++ < 8) key(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("emoji-grid").assertIsFocused()
        key(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(store.results().lastIndex, store.selected)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(store.results().lastIndex - 1, store.selected)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(store.results().lastIndex, store.selected)
        key(KeyEvent.KEYCODE_MOVE_HOME)
        assertEquals(0, store.selected)
        key(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(store.results().lastIndex, store.selected)
        entry = store.results()[store.selected]
        compose.onNodeWithTag("emoji-cell-${entry.id}").assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(entry.glyph, clipboard())
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        filter("Text symbols")
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        search("infinity")
        listOf(KeyEvent.KEYCODE_ENTER to 0, KeyEvent.KEYCODE_ENTER to KeyEvent.META_CTRL_ON,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyEvent.META_CTRL_ON).forEach { (code, meta) ->
            compose.activityRule.scenario.onActivity { it.getSystemService(ClipboardManager::class.java).resetClipboardForTest() }
            key(code, meta)
            assertEquals("∞", clipboard())
        }
        evidence("keyboard-copy", "Copied infinity")
        // Settle the intentional keyboard/focus changes before testing typing geometry.
        device.waitForIdle()
        val bounds = compose.onNodeWithTag("emoji-palette").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            listOf("math", "no-such-emoji-314159", "").forEach { query ->
                compose.activityRule.scenario.onActivity { store.search(query) }
                repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
                assertEquals(bounds, compose.onNodeWithTag("emoji-palette").fetchSemanticsNode().boundsInRoot)
                if (store.results().isNotEmpty()) {
                    compose.activityRule.scenario.onActivity { store.selected = store.results().lastIndex }
                    repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
                    compose.onNodeWithTag("emoji-cell-${store.results().last().id}").assertIsDisplayed().assertIsSelected()
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun escapeClearsSearchThenReturnsToCommandsWithKeyboardFocus() {
        search("heart")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("emoji-palette").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsFocused()
        compose.onNodeWithTag("result-emoji").assertIsDisplayed()
        compose.onNodeWithTag("result-emoji").performClick()
        compose.onNodeWithTag("emoji-back").performClick()
        compose.onNodeWithTag("search").assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun caretAndSelectionChangesPreserveNavigatedResultAndCopyFeedback() {
        search("face")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val index = store.selected
        val entry = store.results()[index]
        assertTrue(index > 0)
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        compose.onNodeWithTag("emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        assertEquals("Caret movement must preserve the grid selection", index, store.selected)
        compose.onNodeWithTag("emoji-search").performTextInputSelection(TextRange(0, 2))
        assertEquals("Selecting text must preserve the grid selection", index, store.selected)
        var connection: android.view.inputmethod.InputConnection? = null
        compose.activityRule.scenario.onActivity {
            connection = it.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            assertNotNull("Use the actual focused Android input connection", connection)
            assertTrue(connection!!.setComposingRegion(0, 4))
        }
        compose.waitForIdle()
        assertEquals("Composition-only updates must preserve the grid selection", index, store.selected)
        compose.activityRule.scenario.onActivity { assertTrue(connection!!.finishComposingText()) }
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON)
        assertEquals(entry.glyph, clipboard())
        val status = "Copied ${entry.name}"
        compose.onNodeWithTag("emoji-copy-status").assertTextEquals(status)
        compose.onNodeWithTag("emoji-search").performTextInputSelection(TextRange(0, 4))
        compose.onNodeWithTag("emoji-copy-status").assertTextEquals(status)
        evidence("caret-preserved", status)
    }

    @Test fun tabletKeyboardShowControlOpensImeWithoutLosingSearch() {
        if (compose.activity.resources.configuration.screenWidthDp < 700) {
            compose.onNodeWithContentDescription("Show on-screen keyboard").assertDoesNotExist()
            return
        }
        compose.onNodeWithContentDescription("Show on-screen keyboard").performClick()
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        search("heart")
        compose.onNodeWithTag("emoji-count").assertIsDisplayed()
        compose.onNodeWithTag("emoji-copy").assertIsDisplayed().assertIsEnabled()
        evidence("tablet-keyboard", "heart")
    }

    @Test fun heldFavoriteChordTogglesOnceAndConsumesReleaseAfterModifierIsReleased() {
        val entries = store.entries.take(3)
        // Ctrl+D is the advertised Android chord; Meta+D delivery remains unverified (#26).
        listOf(KeyEvent.META_CTRL_ON).forEachIndexed { iteration, modifier ->
            compose.activityRule.scenario.onActivity {
                store.entries.filter { it.id in store.favorites }.forEach(store::toggleFavorite)
                entries.forEach(store::toggleFavorite)
                store.search(""); store.chooseFilter("Favorites")
            }
            compose.waitForIdle()
            key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
            val first = store.results().first()
            val expected = entries.map { it.id }.toSet() - first.id
            var downTime = android.os.SystemClock.uptimeMillis()
            fun emit(action: Int, repeat: Int = 0, meta: Int = modifier) {
                assertTrue(testAutomation().injectInputEvent(
                    KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_D, repeat, meta,
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD), true))
                compose.waitForIdle()
            }
            try {
                emit(KeyEvent.ACTION_DOWN)
                assertEquals("Initial favorite down: modifier=$modifier package=${device.currentPackageName}", expected, store.favorites)
                for (repeat in 1..5) {
                    // Release the modifier before the final D repeats and D-up.
                    emit(KeyEvent.ACTION_DOWN, repeat, if (repeat >= 3) 0 else modifier)
                    assertEquals("A held chord must not remove successive favorites", expected, store.favorites)
                    assertEquals("Consumed repeats must not type into search", "", store.query)
                }
                if (iteration == 0) evidence("held-favorite", "2 results")
            } finally { emit(KeyEvent.ACTION_UP, meta = 0) }
            assertEquals(expected, store.favorites)
            assertEquals("", store.query)
            key(KeyEvent.KEYCODE_D, modifier)
            assertEquals("A genuine next press must toggle the next selected favorite", 1, store.favorites.size)
            assertEquals(store.favorites, storedFavorites())
            compose.activityRule.scenario.onActivity { store.chooseFilter("All"); store.search("grinning face") }
            compose.waitForIdle()
            val allEntry = store.results().first()
            val allExpected = store.favorites.toMutableSet().apply { if (!add(allEntry.id)) remove(allEntry.id) }.toSet()
            downTime = android.os.SystemClock.uptimeMillis()
            try {
                emit(KeyEvent.ACTION_DOWN)
                assertEquals(allExpected, store.favorites)
                for (repeat in 1..5) {
                    emit(KeyEvent.ACTION_DOWN, repeat, if (repeat >= 3) 0 else modifier)
                    assertEquals("Repeats must not toggle the same favorite in All", allExpected, store.favorites)
                    assertEquals("grinning face", store.query)
                }
            } finally { emit(KeyEvent.ACTION_UP, meta = 0) }
            assertEquals(allExpected, storedFavorites())
        }
    }
}
