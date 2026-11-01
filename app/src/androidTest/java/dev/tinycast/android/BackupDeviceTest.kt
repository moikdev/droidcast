package dev.tinycast.android

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

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
        compose.onNodeWithText("Portable collections · Droidcast JSON format v1").assertIsDisplayed()
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            File(folder,"picker-cancel-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"picker-cancel-failure.xml"))
            device.takeScreenshot(File(folder,"picker-cancel-failure.png"))
            throw failure
        }
    }
    private fun load(document: BackupDocument) {
        val file = File(context.cacheDir, "backup-test.json").apply { writeText(BackupCodec.encode(document.data, document.categories)) }
        compose.activityRule.scenario.onActivity { it.store.readBackup(android.net.Uri.fromFile(file)) }
        compose.waitUntil(10000) { compose.activity.store.dialog == "backup-preview" && !compose.activity.store.backupBusy }
    }
    private fun clickPickerNode(label: String, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if(node.packageName?.toString()=="com.google.android.documentsui" && node.isVisibleToUser && match(node)) return node
            for(index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        try {
            compose.waitUntil(7000) {
                automation.clearCache()
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
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        clickPickerNode("Show roots") { it.contentDescription?.toString()=="Show roots" }
        clickPickerNode("Downloads") { it.viewIdResourceName=="android:id/title" && it.text?.toString()=="Downloads" }
        automation.clearCache(); device.waitForIdle()
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
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(JSONObject(encoded).put("version", 2).toString()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded + " trailing") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded.replace("\"version\": 1", "\"version\": 1, \"version\": 1")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("[".repeat(20) + "0" + "]".repeat(20)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("not a backup") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read(ByteArrayInputStream(ByteArray(BackupCodec.MAX_BYTES + 1))) }
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        val badLink = data.copy(links = listOf(SavedItem("links:test", "Bad", "javascript:alert(1)")))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(badLink, all) }
        val duplicate = data.copy(notes = listOf(data.notes.first(), data.notes.first()))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(duplicate, all) }
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
        compose.onNodeWithText("Portable collections · Droidcast JSON format v1").assertIsDisplayed()
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"backup-evidence").apply { mkdirs() }
            File(folder,"picker-cancel-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"picker-cancel-failure.xml"))
            device.takeScreenshot(File(folder,"picker-cancel-failure.png"))
            throw failure
        }
        assertEquals(before, compose.activity.store.backupSnapshot())
        compose.onNodeWithTag("backup-export").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10000))
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
        load(BackupDocument(BackupData(usage = mapOf("calculator" to Int.MAX_VALUE)), setOf(BackupCategory.LEARNING)))
        compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
        compose.activityRule.scenario.onActivity { it.store.used("calculator") }
        assertEquals(Int.MAX_VALUE, compose.activity.store.usage["calculator"])
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
