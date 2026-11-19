package dev.tinycast.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original: Map<String, Any?> = emptyMap()
    private val all = BackupCategory.entries.toSet()
    @Before fun preserve() { original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap() }
    @After fun restore() {
        val editor = context.getSharedPreferences("palette", Context.MODE_PRIVATE).edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        assertTrue(editor.commit())
    }
    private fun evidence(name: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "backup-evidence").apply { mkdirs() }
        val marker = when (name) {
            "reserved-alias-after-rollback" -> By.text("neighbor spring")
            "quicklink-after-rollback" -> By.text("pets spring")
            "peer-merge-results" -> By.text("Peer merge witness imported")
            "emoji-inspect" -> By.textContains("grinning face with smiling eyes")
            "emoji-import-rejected" -> By.text("Unsupported backup version")
            "emoji-merge-preview", "emoji-replace-confirm" -> By.textContains("emoji favorites,")
            "preview-cancel", "document-import-preview" -> By.text("Preview backup")
            "export-categories", "export-import-complete" -> By.text("Backup and restore")
            "document-export-picker" -> By.text("SAVE")
            "document-import-picker" -> By.res("com.google.android.documentsui", "breadcrumb_text").text("Downloads")
            "inspect-backup" -> By.text("Hide backup contents")
            "replace-confirmation" -> By.text("Apply this backup?")
            "restored-search" -> By.text("Restored tablet note")
            "alias-conflict", "document-alias-conflict" -> By.textStartsWith("Alias conflict:")
            "international-links" -> By.textContains("例え.テスト")
            "invalid-backup" -> By.text("Unexpected or missing backup fields")
            else -> error("Define evidence UI marker for $name")
        }
        assertTrue("Expected rendered state for $name", device.wait(Until.hasObject(marker), 7000))
        device.waitForIdle()
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    private fun openBackup() {
        // Shared backup component regression; SettingsDeviceTest covers its production settings host.
        compose.activityRule.scenario.onActivity { it.store.beginBackup() }
        try {
        compose.onNodeWithText("Portable collections and settings").performScrollTo().assertIsDisplayed()
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            File(folder,"picker-cancel-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"picker-cancel-failure.xml"))
            device.takeScreenshot(File(folder,"picker-cancel-failure.png"))
            throw failure
        }
    }
    private fun load(document: BackupDocument, file: File = File(context.cacheDir, "backup-test.json")) {
        file.writeText(BackupCodec.encode(document.data, document.categories))
        compose.activityRule.scenario.onActivity { it.store.readBackup(android.net.Uri.fromFile(file)) }
        compose.waitUntil(10000) { compose.activity.store.dialog == "backup-preview" && !compose.activity.store.backupBusy }
    }
    private fun clickPickerNode(label: String, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) {
        val automation=testAutomation()
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.packageName?.toString()=="com.google.android.documentsui" && node.isVisibleToUser && match(node)) return node
            for(index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        try {
            compose.waitUntil(7000) {
                automation.clearCacheForTest()
                var target=automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::find).firstOrNull()
                while(target!=null && !target.isClickable) target=target.parent
                target?.takeIf { it.packageName?.toString()=="com.google.android.documentsui" && it.isEnabled && it.isVisibleToUser }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
            }
        } catch(error: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(File(folder,"picker-action-failure.xml"));device.takeScreenshot(File(folder,"picker-action-failure.png"))
            File(folder,"picker-action-failure.json").writeText(JSONObject().put("target",label).toString(2));throw error
        }
    }
    private fun pickerDownloads() {
        val automation=testAutomation()
        clickPickerNode("Show roots") { it.contentDescription?.toString()=="Show roots" }
        clickPickerNode("Downloads") { it.viewIdResourceName=="android:id/title" && it.text?.toString()=="Downloads" }
        automation.clearCacheForTest(); device.waitForIdle()
        assertTrue("Downloads breadcrumb",device.wait(Until.hasObject(By.res("com.google.android.documentsui","breadcrumb_text").text("Downloads")),7000))
    }
    @Test fun codecRoundTripRejectsCapabilitiesMalformedVersionAndOversize() {
        val data = BackupData(notes = listOf(SavedItem("notes:test", "日本語", "Line one\nLine two")), links = listOf(SavedItem("links:test", "Docs", "https://example.com?q={query}", "docs-test")), pins = setOf("calculator", "files:private"), aliases = mapOf("calculator" to "math", "files:private" to "private"), usage = mapOf("calculator" to 3))
        val encoded = BackupCodec.encode(data, all)
        val decoded = BackupCodec.read(ByteArrayInputStream(encoded.toByteArray()))
        assertEquals(data.copy(pins = setOf("calculator"), aliases = mapOf("calculator" to "math")), decoded.data)
        assertFalse(encoded.contains("files:private"))
        val nestedActivity = "app:dev.example/dev.example.Main\$Nested"
        val appReferences = BackupData(pins = setOf(nestedActivity), aliases = mapOf(nestedActivity to "nested"))
        assertEquals(appReferences, BackupCodec.decode(BackupCodec.encode(appReferences, setOf(BackupCategory.PREFERENCES))).data)
        val partial = BackupCodec.decode(BackupCodec.encode(data, setOf(BackupCategory.NOTES)))
        assertEquals(setOf(BackupCategory.NOTES), partial.categories)
        assertEquals(data.notes, partial.data.notes); assertTrue(partial.data.links.isEmpty()); assertTrue(partial.data.aliases.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(JSONObject(encoded).put("accessibilityEnabled", true).toString()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(JSONObject(encoded).put("version", 3).toString()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded + " trailing") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded.replace("\"version\": 2", "\"version\": 2, \"version\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("[".repeat(20) + "0" + "]".repeat(20)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("not a backup") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read(ByteArrayInputStream(ByteArray(BackupCodec.MAX_BYTES + 1))) }
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertEquals(2, JSONObject(encoded).getInt("version"))
        assertEquals(1, JSONObject(BackupCodec.encode(data, all - BackupCategory.EMOJI)).getInt("version"))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(JSONObject(encoded).put("version", 1).toString()) }
        fun invalidEmoji(edit: (JSONObject) -> Unit) {
            val root = JSONObject(encoded)
            edit(root.getJSONObject("categories").getJSONObject("EMOJI"))
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
        }
        invalidEmoji { it.put("recent", org.json.JSONArray()) }
        invalidEmoji { it.put("rememberRecent", 1) }
        invalidEmoji { it.put("gridColumns", 11) }
        invalidEmoji { it.put("favorites", org.json.JSONArray(listOf("1f600", "1f600"))) }
        invalidEmoji { it.put("favorites", org.json.JSONArray(listOf("files:private"))) }
        val badLink = data.copy(links = listOf(SavedItem("links:test", "Bad", "javascript:alert(1)")))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(badLink, all) }
        val duplicate = data.copy(notes = listOf(data.notes.first(), data.notes.first()))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(duplicate, all) }
    }
    @Test fun emojiSettingsPreviewMergeReplaceAndExportPreserveLocalHistory() {
        val settings = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        val localHistory = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        val historyBefore = localHistory.all.filterKeys { it == "recent" || it == "rememberRecent" }
        val file = File.createTempFile("emoji-import-", ".json", context.cacheDir)
        val export = File.createTempFile("emoji-export-", ".json", context.cacheDir)
        val owner = compose.activity.store
        try {
            compose.activityRule.scenario.onActivity {
                settings.edit().putString("emojiFavoriteOrder", """["1f603","1f600"]""").putInt("emojiGridColumns", 4).apply()
                localHistory.edit().putString("recent", """["private-history-control"]""").putBoolean("rememberRecent", false).apply()
                owner.beginBackup()
            }
            file.writeText("""{"format":"dev.droidcast.backup","version":2,"categories":{"EMOJI":{"favorites":["1f600","1f604","future-entry"],"gridColumns":8}}}""")
            fun read() {
                compose.activityRule.scenario.onActivity { owner.readBackup(android.net.Uri.fromFile(file)) }
                compose.waitUntil(10000) { !owner.backupBusy }
            }
            fun apply() {
                compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
                compose.waitUntil(10000) { !owner.backupBusy && owner.backupDocument == null }
            }
            read()
            if (owner.backupDocument == null) {
                compose.onNodeWithTag("backup-error").performScrollTo()
                evidence("emoji-import-rejected")
            }
            assertNotNull("The version 2 emoji category must reach preview", owner.backupDocument)
            compose.onNodeWithTag("backup-plan-summary").performScrollTo()
                .assertTextContains("4 emoji favorites, 4 grid columns", substring = true)
            evidence("emoji-merge-preview")
            assertEquals(listOf("1f603", "1f600"), owner.emoji.portableSettings().favorites)
            compose.onNodeWithTag("backup-inspect").performScrollTo().performClick()
            val contents = compose.onNodeWithTag("backup-contents")
            contents.performScrollTo().performScrollToNode(hasText("😄 · grinning face with smiling eyes"))
            compose.onNodeWithText("😄 · grinning face with smiling eyes").assertIsDisplayed()
            evidence("emoji-inspect")
            contents.performScrollToNode(hasText("Unavailable emoji · future-entry"))
            compose.onNodeWithText("Unavailable emoji · future-entry").assertIsDisplayed()
            compose.onNodeWithTag("backup-inspect").performScrollTo().performClick()
            compose.onNodeWithTag("backup-review").performScrollTo().performClick()
            apply()
            val merged = PortableEmojiSettings(listOf("1f603", "1f600", "1f604", "future-entry"), 4)
            assertEquals(merged, owner.emoji.portableSettings())

            read()
            compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
            compose.onNodeWithTag("backup-review").performScrollTo().performClick()
            compose.onNodeWithText("Portable settings after restore · 3 emoji favorites, 8 grid columns").performScrollTo()
            evidence("emoji-replace-confirm")
            apply()
            val replaced = PortableEmojiSettings(listOf("1f600", "1f604", "future-entry"), 8)
            assertEquals(replaced, owner.emoji.portableSettings())
            assertEquals(8, settings.getInt("emojiGridColumns", 0))
            assertEquals(JSONArray(replaced.favorites).toString(), settings.getString("emojiFavoriteOrder", null))

            compose.activityRule.scenario.onActivity {
                owner.backupCategories = setOf(BackupCategory.EMOJI)
                owner.prepareExport { owner.writeExport(android.net.Uri.fromFile(export)) }
            }
            compose.waitUntil(10000) { owner.message == "Backup exported" }
            val encoded = export.readText()
            assertFalse(encoded.contains("private-history-control"))
            assertFalse(encoded.contains("rememberRecent"))
            assertEquals(replaced, BackupCodec.decode(encoded).data.emoji)
            assertEquals(setOf(BackupCategory.EMOJI), BackupCodec.decode(encoded).categories)
            assertEquals("""["private-history-control"]""", localHistory.getString("recent", null))
            assertFalse(localHistory.getBoolean("rememberRecent", true))
        } finally {
            file.delete()
            export.delete()
            localHistory.edit().apply {
                remove("recent").remove("rememberRecent")
                (historyBefore["recent"] as? String)?.let { putString("recent", it) }
                (historyBefore["rememberRecent"] as? Boolean)?.let { putBoolean("rememberRecent", it) }
            }.commit()
        }
    }

    @Test fun selectedBackupCategoriesPreserveNewerUnselectedNotesAndGrid() {
        val store = compose.activity.store
        val prefs = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        val categories = setOf(BackupCategory.NOTES, BackupCategory.EMOJI)
        val note = SavedItem("notes:portable", "Portable note", "Mixed category fixture")
        val incoming = PortableEmojiSettings(listOf("1f604", "1f603"), null)
        val document = BackupDocument(BackupData(notes = listOf(note), emoji = incoming), categories)
        val file = File.createTempFile("mixed-backup-", ".json", context.cacheDir)
        fun review(exclude: BackupCategory?) {
            load(document, file)
            exclude?.let { compose.onNodeWithTag("backup-category-${it.name}").performScrollTo().performClick() }
            compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
            compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        }
        fun apply() {
            compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
            compose.waitUntil(10000) { !store.backupBusy && store.backupDocument == null }
        }
        try {
            compose.activityRule.scenario.onActivity {
                prefs.edit().putString("emojiFavoriteOrder", """["1f600"]""").putInt("emojiGridColumns", 6).apply()
                store.beginBackup()
            }
            review(BackupCategory.EMOJI)
            apply()
            assertEquals(listOf(note), store.notes)
            assertEquals(PortableEmojiSettings(listOf("1f600"), 6), store.emoji.portableSettings())

            review(BackupCategory.NOTES)
            // A separate writer changed this unselected collection after the preview.
            val newer = """[{"id":"notes:newer","title":"Newer local edit","body":"Keep this note","alias":""}]"""
            compose.activityRule.scenario.onActivity { prefs.edit().putString("notes", newer).apply() }
            apply()
            assertEquals("An emoji-only restore must preserve newer notes", newer, prefs.getString("notes", null))
            assertEquals(incoming, store.emoji.portableSettings())
            review(null)
            apply()
            assertEquals(listOf(note), store.notes)
            assertEquals(incoming, store.emoji.portableSettings())
            assertEquals(0, prefs.getInt("emojiGridColumns", -1))
        } finally {
            file.delete()
        }
    }

    @Test fun mergePreservesNoteSavedBySettingsAfterConfirmationOpened() {
        val palette = compose.activity.store
        val incoming = SavedItem("notes:peer-import", "Peer merge witness imported", "Backup fixture")
        val peerNote = SavedItem("notes:peer-local", "Peer merge witness local", "New local text")
        val backup = BackupDocument(BackupData(notes = listOf(incoming)), setOf(BackupCategory.NOTES))
        val file = File.createTempFile("peer-merge-", ".json", context.cacheDir)
        try {
            load(backup, file)
            compose.onNodeWithTag("backup-review").performScrollTo().performClick()
            compose.onNodeWithText("Apply this backup?").assertIsDisplayed()
            ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
                settings.onActivity {
                    it.store.save("notes", peerNote.title, peerNote.body, id = peerNote.id)
                    assertTrue(it.moveTaskToBack(true))
                }
                compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
                compose.waitUntil(10000) { !palette.backupBusy && palette.backupDocument == null }
                compose.activityRule.scenario.onActivity {
                    palette.reset()
                    palette.search("Peer merge witness")
                }
                evidence("peer-merge-results")
                val prefs = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
                fun persistedIds(): Set<String> {
                    val items = JSONArray(prefs.getString("notes", "[]"))
                    return (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
                }
                assertTrue("Merge erased the note saved by Settings after review", peerNote.id in persistedIds())
                compose.onNodeWithText(peerNote.title).assertIsDisplayed()
                assertTrue(incoming in palette.notes && peerNote in palette.notes)
                settings.onActivity {
                    assertTrue("Settings missed the imported note", incoming in it.store.notes)
                    it.store.save("notes", "Later peer save", "After import", id = "notes:peer-after")
                }
                assertTrue(persistedIds().containsAll(setOf(incoming.id, peerNote.id, "notes:peer-after")))
                assertTrue(palette.notes.any { it.id == "notes:peer-after" })
            }
        } finally {
            file.delete()
        }
    }

    @Test fun paletteReflectsPeerCollectionsBindingsAndRankingUntilPeerCloses() {
        val palette = compose.activity.store
        lateinit var closedStore: PaletteStore
        val shortcut = KeyChord(android.view.KeyEvent.KEYCODE_8, ShortcutRules.CTRL or ShortcutRules.SHIFT)
        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            settings.onActivity {
                closedStore = it.store
                it.store.save("links", "Peer site", "https://example.org/", id = "links:peer")
                it.store.save("clips", "Peer clip", "Peer text", id = "clips:peer")
                it.store.save("files", "Peer document", "content://fixture/document", id = "files:peer")
                it.store.setAlias("links:peer", "peerfixture")
                it.store.togglePin("links:peer")
                it.store.used("links:peer")
                it.store.setShortcut("links:peer", shortcut)
                assertEquals(it.store.files, palette.files)
                assertEquals(it.store.backupSnapshot(), palette.backupSnapshot())
                assertEquals("peerfixture", palette.aliases["links:peer"])
                assertEquals(shortcut, palette.shortcutConfiguration.bindings["links:peer"])
            }
        }
        compose.activityRule.scenario.onActivity {
            val previous = closedStore.usage
            palette.used("links:peer")
            assertEquals("A cleared Settings ViewModel must stop observing preferences", previous, closedStore.usage)
        }
    }

    @Test fun failedRestoreKeepsPeerEditAtRollbackSnapshot() = failedRestoreWithPeerEdit(atSnapshot = true)

    @Test fun failedRestoreKeepsPeerWhoExplicitlySavedSameGrid() = failedRestoreWithPeerEdit(atSnapshot = false)

    @Test fun failedRestoreKeepsDeliberateQuicklinkAliasAndRestoresOtherOverrides() =
        failedRestoreWithPeerEdit(atSnapshot = false, quicklinkAlias = "pets")

    @Test fun failedRestoreReservesAliasForItsOriginalDestination() =
        failedRestoreWithPeerEdit(atSnapshot = false, quicklinkAlias = "neighbor")

    private fun failedRestoreWithPeerEdit(atSnapshot: Boolean, quicklinkAlias: String? = null) {
        val real = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        val enteredCommit = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val peerFinished = CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val intercepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val peer = compose.activity.store
        val injected = object : SharedPreferences by real {
            override fun getAll(): Map<String, *> {
                val snapshot = real.all
                if (atSnapshot && failed.get() && intercepted.compareAndSet(false, true)) {
                    main.post {
                        peer.emoji.saveGridColumns(7)
                        peerFinished.countDown()
                    }
                    // A peer can run between a worker's snapshot and rollback. On the main
                    // thread the edit runs on the next turn, after the indivisible rollback.
                    if (!main.looper.isCurrentThread) check(peerFinished.await(10, TimeUnit.SECONDS))
                }
                return snapshot
            }

            override fun edit(): SharedPreferences.Editor {
                val underlying = real.edit()
                return object : SharedPreferences.Editor by underlying {
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                        underlying.putBoolean(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        // Publish memory as Android does even when the durable commit fails.
                        underlying.apply()
                        enteredCommit.countDown()
                        check(finishCommit.await(10, TimeUnit.SECONDS))
                        failed.set(true)
                        return false
                    }
                }
            }
        }
        val application = object : android.app.Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "palette") injected else super.getSharedPreferences(name, mode)
        }
        val incoming = PortableEmojiSettings(listOf("1f604"), 8)
        val file = File.createTempFile("restore-failure-", ".json", context.cacheDir)
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var importing: PaletteStore
        try {
            val categories = if (quicklinkAlias != null) setOf(BackupCategory.EMOJI, BackupCategory.PREFERENCES) else setOf(BackupCategory.EMOJI)
            val linkId = "links:rollback-garden"
            val otherId = "links:rollback-neighbor"
            val reserved = quicklinkAlias == "neighbor"
            val incomingAliases = if (reserved) mapOf("calculator" to "importedcalc") else emptyMap()
            var rejection: String? = null
            var unchanged = false
            file.writeText(BackupCodec.encode(BackupData(emoji = incoming, aliases = incomingAliases), categories))
            compose.runOnIdle {
                real.edit().putInt("emojiGridColumns", 4).putString("emojiFavoriteOrder", """["1f600"]""").apply()
                if (quicklinkAlias != null) {
                    peer.save("links", "Quicklink rollback witness", "https://example.invalid/find?q={query}", "journal", linkId)
                    peer.save("links", "Other rollback link", "https://example.invalid/other?q={query}", id = otherId)
                    peer.setAlias(linkId, "garden")
                    peer.setAlias(otherId, "neighbor")
                }
                importing = androidx.lifecycle.ViewModelProvider(holder,
                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application))[PaletteStore::class.java]
                importing.readBackup(android.net.Uri.fromFile(file))
            }
            compose.waitUntil(10000) { importing.backupDocument != null && !importing.backupBusy }
            compose.runOnIdle {
                importing.restorePolicy = RestorePolicy.REPLACE
                importing.applyBackup()
            }
            assertTrue("The durable commit must be attempted", enteredCommit.await(10, TimeUnit.SECONDS))
            if (!atSnapshot) {
                compose.runOnIdle {
                    if (quicklinkAlias != null) {
                        assertFalse(peer.aliases.containsKey(linkId))
                        assertFalse(peer.aliases.containsKey(otherId))
                        peer.editing = peer.links.single { it.id == linkId }
                        peer.dialog = "link"
                        val draft = peer.editorDraft("link")
                        assertEquals("journal", draft.alias)
                        draft.alias = quicklinkAlias
                        if (reserved) {
                            val prior = peer.backupSnapshot()
                            draft.title = "Attempted takeover"
                            draft.body = "https://example.invalid/attempt?q={query}"
                            rejection = runCatching { peer.saveEditor(draft) }.exceptionOrNull()?.message
                            if (rejection != null) {
                                // The imported override also temporarily hides the built-in calculator alias.
                                assertEquals("importedcalc", peer.command("calculator")?.alias)
                                draft.alias = "calc"
                                val other = runCatching { peer.saveEditor(draft) }.exceptionOrNull()
                                assertEquals("That alias is in use until the backup finishes", other?.message)
                            }
                            val after = peer.backupSnapshot()
                            unchanged = prior.links == after.links && prior.aliases == after.aliases
                        } else {
                            peer.saveEditor(draft)
                            assertEquals("pets", peer.command(linkId)?.alias)
                        }
                        peer.reset()
                    }
                    peer.emoji.saveGridColumns(9)
                    peer.emoji.saveGridColumns(8)
                }
            }
            finishCommit.countDown()
            compose.waitUntil(10000) { !importing.backupBusy && importing.backupError != null }
            if (atSnapshot) assertTrue(peerFinished.await(10, TimeUnit.SECONDS))
            assertEquals("Failure recovery overwrote the peer's grid edit", if (atSnapshot) 7 else 8,
                real.getInt("emojiGridColumns", -1))
            assertEquals("An untouched key must roll back", """["1f600"]""", real.getString("emojiFavoriteOrder", null))
            assertNotNull("Keep the import available for retry", importing.backupDocument)
            if (quicklinkAlias != null) {
                compose.runOnIdle {
                    peer.search("$quicklinkAlias spring")
                    assertEquals("An unrelated override must roll back", "neighbor", peer.aliases[otherId])
                }
                evidence(if (reserved) "reserved-alias-after-rollback" else "quicklink-after-rollback")
                compose.runOnIdle {
                    if (reserved) {
                        assertEquals("A rollback-reserved alias was accepted", "That alias is in use until the backup finishes", rejection)
                        assertTrue("Rejected edits must preserve the entire links and overrides", unchanged)
                        assertEquals("garden", peer.command(linkId)?.alias)
                        assertEquals("journal", peer.links.single { it.id == linkId }.alias)
                        assertEquals("calc", peer.command("calculator")?.alias)
                        assertEquals(listOf(otherId), peer.commands().filter { it.alias == "neighbor" }.map { it.id })
                        assertEquals("https://example.invalid/other?q=spring", peer.results().first().payload)
                    } else {
                        assertEquals("pets", peer.backupSnapshot().links.single { it.id == linkId }.alias)
                        assertEquals("Rollback masked the deliberate Quicklink alias", "pets", peer.command(linkId)?.alias)
                        assertFalse(peer.backupSnapshot().aliases.containsKey(linkId))
                        assertEquals("https://example.invalid/find?q=spring", peer.results().first().payload)
                    }
                }
            }
        } finally {
            finishCommit.countDown()
            compose.runOnIdle { holder.clear() }
            file.delete()
        }
    }

    @Test fun previewCancelAndMalformedInputLeaveDataUntouched() {
        val before = compose.activity.store.backupSnapshot()
        load(BackupDocument(BackupData(notes = listOf(SavedItem("notes:import", "Imported note", "Do not apply on preview"))), setOf(BackupCategory.NOTES)))
        compose.onNodeWithText("Preview backup").assertIsDisplayed()
        evidence("preview-cancel")
        assertEquals(before, compose.activity.store.backupSnapshot())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Preview backup").assertIsDisplayed()
        assertEquals(before, compose.activity.store.backupSnapshot())
        compose.onNodeWithTag("backup-cancel-import").performScrollTo().performClick()
        assertEquals(before, compose.activity.store.backupSnapshot())
        assertNull(compose.activity.store.backupDocument)
        val invalid = File(context.cacheDir, "invalid-backup.json").apply { writeText("{\"version\":900}") }
        compose.activityRule.scenario.onActivity { it.store.readBackup(android.net.Uri.fromFile(invalid)) }
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.backupError != null }
        compose.onNodeWithTag("backup-error").performScrollTo().assertIsDisplayed()
        evidence("invalid-backup")
        assertEquals(before, compose.activity.store.backupSnapshot())
    }
    @Test fun replaceSelectedCategoryPersistsAndPreservesPermissionsAndOtherData() {
        lateinit var before: BackupData
        val secureBefore = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val grantsBefore = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        compose.activityRule.scenario.onActivity { before = it.store.backupSnapshot() }
        val imported = SavedItem("notes:replace-test", "Restored tablet note", "A category replacement verified after reopening.")
        load(BackupDocument(BackupData(notes = listOf(imported)), setOf(BackupCategory.NOTES)))
        compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithText("Apply this backup?").assertIsDisplayed()
        evidence("replace-confirmation")
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
        assertEquals(listOf(imported), compose.activity.store.notes)
        assertEquals(before.links, compose.activity.store.links); assertEquals(before.clips, compose.activity.store.clips)
        assertEquals(before.aliases, compose.activity.store.backupSnapshot().aliases); assertEquals(before.pins, compose.activity.store.backupSnapshot().pins)
        assertEquals(secureBefore, android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(grantsBefore, context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
        val reopened = PaletteStore(context.applicationContext as android.app.Application)
        assertEquals(listOf(imported), reopened.notes)
        compose.activityRule.scenario.onActivity { it.store.dialog = null; it.store.search("Restored tablet note") }
        compose.onNodeWithTag("result-${imported.id}").assertIsDisplayed()
        evidence("restored-search")
    }
    @Test fun startingImportClearsFeedbackBeforeItCanCoverPreviewControls() {
        val file=File(context.cacheDir,"backup-feedback-fixture.json").apply {
            writeText(BackupCodec.encode(BackupData(notes=listOf(SavedItem("notes:feedback","Feedback fixture","Public backup fixture"))),setOf(BackupCategory.NOTES)))
        }
        try {
            compose.activityRule.scenario.onActivity {
                it.store.message="Backup exported"
                it.store.readBackup(android.net.Uri.fromFile(file))
                assertNull("Starting import clears obsolete feedback synchronously",it.store.message)
            }
            compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog=="backup-preview" && compose.activity.window.decorView.hasWindowFocus() }
            compose.onNodeWithText("Backup exported").assertDoesNotExist()
            compose.onNodeWithTag("backup-inspect").performScrollTo().performClick()
            compose.onNodeWithTag("backup-contents").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Note · Feedback fixture").assertIsDisplayed()
        } finally { file.delete() }
    }
    @Test fun realDocumentPickerExportImportAndIdempotentMerge() {
        compose.activityRule.scenario.onActivity {
            val note = it.store.save("notes", "Tablet backup note", "Text survives the document picker round trip.")
            it.store.save("links", "Backup docs", "https://example.com/search?q={query}", "backupdocs")
            it.store.save("clips", "Backup saved text", "Portable saved clip")
            it.store.setAlias(note, "backupnote")
            if (note !in it.store.pins) it.store.togglePin(note)
        }
        openBackup()
        evidence("export-categories")
        compose.onNodeWithTag("backup-export").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000))
        pickerDownloads()
        val name = "droidcast-roundtrip-${System.currentTimeMillis()}.json"
        val nameField = device.wait(Until.findObject(By.res("android:id/title").clazz("android.widget.EditText")), 7000)
        assertNotNull("Document picker filename field", nameField)
        nameField.text = name
        evidence("document-export-picker")
        device.findObject(By.text("SAVE")).click()
        compose.waitUntil(15000) { !compose.activity.store.backupBusy && compose.activity.store.pendingExport == null }
        assertNull("Export completes without a write error",compose.activity.store.backupError)
        compose.waitUntil(10000) { compose.activity.window.decorView.hasWindowFocus() }
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Backup and restore")),10000))
        compose.onNodeWithTag("backup-import").performScrollTo().performClick()
        pickerDownloads()
        var document = device.wait(Until.findObject(By.pkg("com.google.android.documentsui").text(name)), 1500)
        if(document==null) {
            val files=UiScrollable(UiSelector().packageName("com.google.android.documentsui").scrollable(true)).setMaxSearchSwipes(20)
            files.scrollIntoView(UiSelector().packageName("com.google.android.documentsui").text(name))
            document=device.wait(Until.findObject(By.pkg("com.google.android.documentsui").text(name)),5000)
        }
        if(document==null) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(File(folder,"missing-exported-document.xml"));device.takeScreenshot(File(folder,"missing-exported-document.png"))
            File(folder,"missing-exported-document.json").writeText(JSONObject().put("expected_filename",name).toString(2))
        }
        assertNotNull("Exported document visible in real picker", document)
        evidence("document-import-picker")
        clickPickerNode(name) { it.text?.toString()==name }
        compose.waitUntil(15000) { compose.activity.store.dialog == "backup-preview" && !compose.activity.store.backupBusy }
        // Activity-result state can publish before DocumentsUI has finished closing.
        compose.waitUntil(10000) { compose.activity.window.decorView.hasWindowFocus() }
        if (!device.wait(Until.hasObject(By.pkg(context.packageName).text("Preview backup")),10000)) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            File(folder,"import-preview-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"import-preview-failure.xml"));device.takeScreenshot(File(folder,"import-preview-failure.png"))
            fail("Native preview heading not visible after document selection")
        }
        compose.onNodeWithText("Backup exported").assertDoesNotExist()
        val incoming = compose.activity.store.backupDocument!!
        assertTrue(incoming.data.notes.any { it.title == "Tablet backup note" })
        assertTrue(incoming.data.links.any { it.alias == "backupdocs" })
        assertTrue(incoming.data.clips.any { it.title == "Backup saved text" })
        compose.onNodeWithText("Preview backup").assertIsDisplayed()
        compose.onNodeWithTag("backup-inspect").performScrollTo()
        compose.onNodeWithTag("backup-inspect").performClick()
        compose.onNodeWithTag("backup-contents").performScrollTo().assertIsDisplayed()
        evidence("inspect-backup")
        compose.onNodeWithTag("backup-inspect").performScrollTo().performClick()
        evidence("document-import-preview")
        val before = compose.activity.store.backupSnapshot()
        val plan = compose.activity.store.restorePlan()
        assertEquals(0, plan.added)
        assertTrue(plan.skipped >= 3)
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
        assertEquals(before, compose.activity.store.backupSnapshot())
        val reopened = PaletteStore(context.applicationContext as android.app.Application)
        assertEquals(before, reopened.backupSnapshot())
        evidence("export-import-complete")
    }
    @Test fun aliasConflictAndEmptySelectionCannotApply() {
        val before = compose.activity.store.backupSnapshot()
        load(BackupDocument(BackupData(links = listOf(SavedItem("links:conflict", "Alias collision", "https://example.com", "calc"))), setOf(BackupCategory.LINKS)))
        compose.onNodeWithTag("backup-plan-error").performScrollTo().assertTextContains("Alias conflict", substring = true)
        compose.onNodeWithTag("backup-review").assertIsNotEnabled()
        evidence("alias-conflict")
        compose.onNodeWithTag("backup-category-LINKS").performScrollTo().performClick()
        compose.onNodeWithTag("backup-review").performScrollTo().assertIsNotEnabled()
        assertEquals(before, compose.activity.store.backupSnapshot())
    }
    @Test fun realDocumentPickerCancellationLeavesDataUntouched() {
        openBackup()
        val before = compose.activity.store.backupSnapshot()
        compose.onNodeWithTag("backup-import").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000))
        device.pressBack()
        try {
        compose.onNodeWithText("Portable collections and settings").performScrollTo().assertIsDisplayed()
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            File(folder,"picker-cancel-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"picker-cancel-failure.xml"))
            device.takeScreenshot(File(folder,"picker-cancel-failure.png"))
            throw failure
        }
        assertEquals(before, compose.activity.store.backupSnapshot())
        val exportAfterCancel=compose.onNodeWithTag("backup-export").performScrollTo()
        val exportNode=exportAfterCancel.fetchSemanticsNode()
        val feedbackNodes=compose.onAllNodesWithTag("palette-feedback").fetchSemanticsNodes()
        val frames=compose.onAllNodesWithTag("palette-screen").fetchSemanticsNodes()
        val routing=compose.runOnUiThread {
            fun bounds(rect:androidx.compose.ui.geometry.Rect)=org.json.JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom))
            fun full(node:androidx.compose.ui.semantics.SemanticsNode):androidx.compose.ui.geometry.Rect {
                val origin=node.positionInRoot
                return androidx.compose.ui.geometry.Rect(origin.x,origin.y,origin.x+node.size.width,origin.y+node.size.height)
            }
            JSONObject().put("phase","before-export-after-import-cancellation")
                .put("export_visible",bounds(exportNode.boundsInRoot)).put("export_full",bounds(full(exportNode)))
                .put("feedback",org.json.JSONArray(feedbackNodes.map { JSONObject()
                    .put("visible",bounds(it.boundsInRoot)).put("full",bounds(full(it))) }))
                .put("frames",org.json.JSONArray(frames.map { bounds(it.boundsInRoot) }))
                .put("message",compose.activity.store.message).put("backup_busy",compose.activity.store.backupBusy)
                .put("pending_export",compose.activity.store.pendingExport!=null)
                .put("ime_visible",androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
                .put("font_scale",context.resources.configuration.fontScale)
        }
        val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
        File(folder,"export-after-cancel-routing.json").writeText(routing.toString(2))
        exportAfterCancel.performClick()
        val exportOpened=device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000)
        routing.put("export_picker_opened",exportOpened).put("pending_export_after_click",compose.activity.store.pendingExport!=null)
            .put("backup_busy_after_click",compose.activity.store.backupBusy)
        File(folder,"export-after-cancel-routing.json").writeText(routing.toString(2))
        if(!exportOpened) {
            File(folder,"export-after-cancel-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"export-after-cancel-failure.xml"))
            device.takeScreenshot(File(folder,"export-after-cancel-failure.png"))
        }
        assertTrue("Export picker must open after cancelling import",exportOpened)
        device.dumpWindowHierarchy(File(folder,"export-after-cancel-destination.xml"))
        assertTrue(device.takeScreenshot(File(folder,"export-after-cancel-destination.png")))
        device.pressBack()
        try {
            compose.waitUntil(10000) { compose.activity.store.pendingExport == null && !compose.activity.store.backupBusy }
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(File(folder,"export-cancel-failure.xml"));device.takeScreenshot(File(folder,"export-cancel-failure.png"))
            throw failure
        }
        assertEquals(before, compose.activity.store.backupSnapshot())
        assertNull(compose.activity.store.backupDocument)
    }
    @Test fun importedRankingCannotOverflowOnNextCommand() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("backupRankingEvidence")
        val control = arguments.getString("backupRankingControl")
        require(control == null || (control == "omit-confirm" && runId != null))
        val folder = runId?.let { id ->
            require(Regex("[0-9a-f]{32}").matches(id))
            File(context.filesDir, "backup-ranking-$id").also {
                require(it.isDirectory && it.list()?.isEmpty() == true) { "Expected a fresh host-owned evidence directory" }
            }
        }
        val rounds = arguments.getString("backupRankingRounds")?.let {
            require(folder != null && control == null) { "Warm rounds require evidence and ordinary confirmation" }
            it.toInt().also { count -> require(count in 1..5) }
        } ?: 1
        val activity = compose.activity
        repeat(rounds) { round ->
            val trial = folder?.let { File(it, "trial-${round + 1}").apply { check(mkdir()) } }
            val fixture = trial?.let { File(it, "ranking.json") } ?: File.createTempFile("ranking-", ".json", context.cacheDir)
            try {
                fun prepare(rank: Int) {
                    load(BackupDocument(BackupData(usage = mapOf("calculator" to rank)), setOf(BackupCategory.LEARNING)), fixture)
                    compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
                    if (trial != null && rank == Int.MAX_VALUE) {
                        compose.onNodeWithTag("backup-plan-summary").performScrollTo().assertIsDisplayed()
                        captureRankingScreen(activity, trial, "preview", "Preview backup")
                    }
                    compose.onNodeWithTag("backup-review").performScrollTo().performClick()
                }
                if (round > 0) {
                    // Establish a fresh lower rank using the ordinary import path in this same activity.
                    prepare(0)
                    compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
                    compose.waitUntil(10000) { !activity.store.backupBusy && activity.store.dialog == "backup" }
                    compose.activityRule.scenario.onActivity {
                        assertSame(activity, it)
                        assertNull(it.store.backupError)
                        assertEquals(0, it.store.usage["calculator"])
                    }
                }
                prepare(Int.MAX_VALUE)
                checkRankingConfirmation(activity, trial, control == "omit-confirm")
            } finally {
                if (trial == null) fixture.delete()
            }
        }
    }
    private fun captureRankingScreen(activity: MainActivity, folder: File?, stage: String, marker: String? = null) {
        if (folder == null) return
        if (marker != null) {
            compose.waitForIdle()
            val frames = CountDownLatch(1)
            compose.activityRule.scenario.onActivity { live ->
                assertSame(activity, live)
                live.window.decorView.postOnAnimation { live.window.decorView.postOnAnimation { frames.countDown() } }
            }
            assertTrue(frames.await(5, TimeUnit.SECONDS))
            check(device.wait(Until.hasObject(By.pkg(context.packageName).text(marker)), 5000))
        }
        check(device.currentPackageName == context.packageName)
        device.dumpWindowHierarchy(File(folder, "$stage.xml"))
        check(device.currentPackageName == context.packageName)
        check(device.takeScreenshot(File(folder, "$stage.png")))
    }

    private fun checkRankingConfirmation(activity: MainActivity, folder: File?, omitConfirm: Boolean) {
        val prefs = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        val observations = JSONArray()
        val startedAt = SystemClock.elapsedRealtime()
        val usageNotification = AtomicLong(-1)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "usage") usageNotification.compareAndSet(-1, SystemClock.elapsedRealtime() - startedAt)
        }
        val store = activity.store
        fun observe(stage: String) {
            if (folder == null && stage != "failure") return
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val dialog = store.dialog
                observations.put(JSONObject()
                    .put("stage", stage)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
                    .put("usage_notification_ms", usageNotification.get())
                    .put("activity_state", activity.lifecycle.currentState.name)
                    .put("window_focused", activity.hasWindowFocus())
                    .put("activity_destroyed", activity.isDestroyed)
                    .put("dialog", dialog?.takeIf { it in setOf("backup-confirm", "backup-preview", "backup") } ?: "other")
                    .put("backup_busy", store.backupBusy)
                    .put("backup_error_present", store.backupError != null)
                    .put("incoming_document_present", store.backupDocument != null)
                    .put("model_at_limit", store.usage["calculator"] == Int.MAX_VALUE)
                    .put("preferences_at_limit", runCatching {
                        JSONObject(prefs.getString("usage", "{}") ?: "{}").optInt("calculator") == Int.MAX_VALUE
                    }.getOrNull() ?: JSONObject.NULL))
            }
            if (stage == "failure") {
                val status = Bundle().apply { putString("backup_ranking_state", observations.getJSONObject(observations.length() - 1).toString()) }
                InstrumentationRegistry.getInstrumentation().sendStatus(2, status)
            }
            if (folder == null) return
            val tmp = File(folder, "states.tmp")
            tmp.writeText(observations.toString(2))
            check(tmp.renameTo(File(folder, "states.json"))) { "Could not save fixture observations" }
        }
        if (folder != null) prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            val summary = "Portable settings after restore · 1 usage ranking entry"
            compose.onNodeWithText(summary).performScrollTo().assertIsDisplayed()
            captureRankingScreen(activity, folder, "confirmation", summary)
            val confirm = compose.onNodeWithTag("backup-confirm").performScrollTo().assertIsEnabled()
            observe("before_confirm")
            if (!omitConfirm) confirm.performClick()
            observe(if (omitConfirm) "confirm_omitted" else "after_confirm")
            compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
            observe("apply_complete")
            compose.activityRule.scenario.onActivity {
                assertSame(activity, it)
                assertNull(it.store.backupError)
                assertEquals("Backup restored · Usage ranking", it.store.message)
                assertEquals(Int.MAX_VALUE, it.store.usage["calculator"])
                it.store.used("calculator")
                assertEquals(Int.MAX_VALUE, it.store.usage["calculator"])
            }
            observe("after_next_command")
            if (folder != null) {
                compose.onNodeWithText("Notes").performScrollTo().assertIsDisplayed()
                captureRankingScreen(activity, folder, "complete", "Notes")
                compose.onNodeWithText("Backup and restore").assertIsDisplayed()
            }
        } catch (failure: Throwable) {
            runCatching { observe("failure") }.onFailure(failure::addSuppressed)
            runCatching {
                if (folder != null && device.currentPackageName == context.packageName) captureRankingScreen(activity, folder, "failure")
            }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            if (folder != null) prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    @Test fun preservedDocumentAliasBlocksQuicklinkCollisionsForBothPolicies() {
        lateinit var documentId: String
        compose.activityRule.scenario.onActivity {
            documentId = it.store.save("files", "Document alias fixture", "content://dev.droidcast.test/document")
            it.store.setAlias(documentId, "docs")
        }
        val before = compose.activity.store.backupSnapshot()
        assertFalse(documentId in before.aliases)
        val imported = BackupDocument(BackupData(links = listOf(SavedItem("links:document-conflict", "Conflicting docs link", "https://example.com", "docs"))), setOf(BackupCategory.LINKS, BackupCategory.PREFERENCES))
        load(imported)
        RestorePolicy.entries.forEach { policy ->
            compose.activityRule.scenario.onActivity { it.store.restorePolicy = policy }
            compose.onNodeWithTag("backup-plan-error").performScrollTo().assertTextContains("Alias conflict", substring = true)
            compose.onNodeWithTag("backup-review").performScrollTo().assertIsNotEnabled()
            assertTrue(runCatching { compose.activity.store.restorePlan() }.isFailure)
            assertEquals(before, compose.activity.store.backupSnapshot())
            assertEquals("docs", compose.activity.store.commands().first { it.id == documentId }.alias)
        }
        evidence("document-alias-conflict")
    }
    @Test fun editorAcceptedInternationalAndSpacedUrlsSurviveBackupRoundTrip() {
        val urls = listOf("https://例え.テスト/検索?q={query}", "https://example.com/with a space?q={query}")
        val ids = mutableListOf<String>()
        compose.activityRule.scenario.onActivity { activity ->
            urls.forEachIndexed { index, url -> ids += activity.store.save("links", "International backup link $index", url, "bkintl$index") }
        }
        val source = compose.activity.store.backupSnapshot()
        val encoded = BackupCodec.encode(source, setOf(BackupCategory.LINKS))
        val decoded = BackupCodec.decode(encoded)
        assertEquals(source.links, decoded.data.links)
        compose.activityRule.scenario.onActivity { activity -> ids.forEach { activity.store.delete(it) } }
        load(decoded)
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
        val reopened = PaletteStore(context.applicationContext as android.app.Application)
        assertEquals(source.links, reopened.links)
        compose.activityRule.scenario.onActivity { it.store.dialog = null; it.store.search("International backup link") }
        ids.forEach { id -> compose.onNodeWithTag("result-$id").assertIsDisplayed() }
        compose.onNodeWithTag("result-${ids.first()}").performTouchInput { longClick() }
        compose.onNodeWithTag("action-Edit quicklink").performScrollTo().performClick()
        compose.onNodeWithTag("editor-body").assertTextContains(urls.first())
        evidence("international-links")
    }
    @Test fun escapeCancelsPreviewWithoutApplying() {
        val before = compose.activity.store.backupSnapshot()
        load(BackupDocument(BackupData(notes = listOf(SavedItem("notes:keyboard", "Keyboard preview fixture", "Do not apply"))), setOf(BackupCategory.NOTES)))
        compose.onNodeWithText("Preview backup").assertIsDisplayed()
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5000) { compose.activity.store.dialog == null }
        assertNull(compose.activity.store.backupDocument)
        assertEquals(before, compose.activity.store.backupSnapshot())
    }
}
