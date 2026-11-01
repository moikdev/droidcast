package dev.tinycast.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import dev.tinycast.android.extensions.*
import dev.tinycast.android.updates.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Separate prepare/verify/cleanup calls intentionally cross a real PackageInstaller replacement. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateSelfInstallDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val directory get() = File(context.filesDir, "updates-self-test")
    private val stateFile get() = File(directory, "private-fixture-state.json")
    private val rootName = "DroidcastUpdaterFixture"
    private val extensionId = "updater-retention-fixture"
    private val noteTitle = "Updater retention note"
    private val noteBody = "Droidcast real self-update retention fixture 628318"
    private val fileBody = "Droidcast retained document grant 271828"
    @Before fun ready() {
        device.executeShellCommand("cmd statusbar collapse")
        compose.waitUntil(15_000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading && !compose.activity.store.emoji.loading }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(file: File) = digest(file.readBytes())
    // Invoking Check for Updates legitimately increments command usage between prepare/install.
    // Compare every other preference without publishing private values or their key names.
    private fun palettePreferencesHash(): String {
        val values = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.filterKeys { it != "usage" }
        val json = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            json.put(key, if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value)
        }
        return digest(json.toString().toByteArray())
    }
    private fun grantHashes() = context.contentResolver.persistedUriPermissions.map {
        digest((it.uri.toString()+"|"+it.isReadPermission+"|"+it.isWritePermission).toByteArray())
    }.toSet()
    private fun fixtureGrant(uri: Uri) = uri.authority == "com.android.externalstorage.documents" &&
        runCatching { DocumentsContract.getTreeDocumentId(uri) == "primary:Download/$rootName" }.getOrDefault(false)
    private fun writeState(json: JSONObject) {
        directory.mkdirs()
        FileOutputStream(stateFile).use { it.write(json.toString(2).toByteArray()); it.fd.sync() }
    }
    private fun publicRecord(name: String, json: JSONObject) {
        File(context.filesDir, "updates-evidence").apply { mkdirs() }.resolve("$name.json").writeText(json.toString(2))
    }
    private fun clickNative(text: String? = null, description: String? = null, resourceId: String? = null) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.permissioncontroller", "com.google.android.permissioncontroller") &&
                (text == null || node.text?.toString() == text) && (description == null || node.contentDescription?.toString() == description) && (resourceId == null || node.viewIdResourceName == resourceId)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { found -> return found } } }
            return null
        }
        try {
            compose.waitUntil(7000) {
                automation.clearCache()
                var node = automation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::find)
                while (node != null && !node.isClickable) node = node.parent
                node?.takeIf { it.isEnabled && it.isVisibleToUser }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } catch (error: Throwable) {
            File(context.filesDir, "updates-evidence").apply { mkdirs() }.let {
                device.dumpWindowHierarchy(File(it, "failure-picker-action.xml"))
                device.takeScreenshot(File(it, "failure-picker-action.png"))
            }
            throw error
        }
    }
    @Test fun preparePublicDataAndVerifiedUpdate() {
        assertFalse("Fixture directory is unused", stateFile.exists())
        assertTrue("Public document fixture is unused", device.executeShellCommand("ls -d /sdcard/Download/$rootName 2>/dev/null").trim().isEmpty())
        assertFalse(compose.activity.store.folderSearch.folders.any { it.title == rootName })
        val extDirectory = File(context.filesDir, "extensions/$extensionId")
        assertFalse("Extension fixture is unused", extDirectory.exists())
        val mode = InstrumentationRegistry.getArguments().getString("confirmationMode", "required")
        require(mode in setOf("required", "best-effort"))
        val saved = JSONObject().put("rootCreated", false).put("grantPreexisting", context.contentResolver.persistedUriPermissions.any { fixtureGrant(it.uri) })
            .put("grantHashes", JSONArray(grantHashes().toList())).put("confirmationRequired", mode == "required")
        writeState(saved)
        device.executeShellCommand("mkdir -p /sdcard/Download/$rootName")
        saved.put("rootCreated", true); writeState(saved)
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("tee /sdcard/Download/$rootName/Retention-note.txt")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(fileBody.toByteArray()) }
        assertEquals(fileBody, android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() })
        compose.activityRule.scenario.onActivity { it.selectPage("folders") }
        compose.onNodeWithTag("folder-add").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 7000))
        device.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        if (!device.hasObject(By.text(rootName))) {
            // API36 tree selection can expose only the primary-storage root, without a drawer.
            if (device.hasObject(By.res("android:id/title").text("Download"))) {
                clickNative(text = "Download", resourceId = "android:id/title")
            } else {
                clickNative(description = "Show roots")
                clickNative(text = "Downloads", resourceId = "android:id/title")
            }
        }
        if (!device.hasObject(By.text(rootName))) UiScrollable(UiSelector().packageName("com.google.android.documentsui").scrollable(true))
            .setMaxSearchSwipes(15).scrollIntoView(UiSelector().text(rootName))
        clickNative(text = rootName)
        assertTrue(device.wait(Until.hasObject(By.text("USE THIS FOLDER")), 7000))
        updateSystemEvidence("grant-picker", rootName)
        clickNative(text = "USE THIS FOLDER")
        if (device.wait(Until.hasObject(By.text("ALLOW")), 3000)) clickNative(text = "ALLOW")
        compose.waitUntil(20_000) {
            val search = compose.activity.store.folderSearch
            !search.busy && !search.changing && search.folders.any { it.title == rootName } &&
                search.configurationCommands().any { it.title == "Retention-note.txt" }
        }
        val document = compose.activity.store.folderSearch.configurationCommands().single { it.title == "Retention-note.txt" && it.payload.contains(rootName) }
        saved.put("documentUri", document.payload).put("documentId", document.id)
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8)
        compose.activityRule.scenario.onActivity {
            val store = it.store
            val note = store.save("notes", noteTitle, noteBody)
            val link = store.save("links", "Updater retention link", "https://example.com/droidcast-update", "retlink$nonce")
            val clip = store.save("clips", "Updater retention clip", "Saved clip survives a real APK update.")
            store.setAlias(note, "retnote$nonce")
            if (note !in store.pins) store.togglePin(note)
            if (document.id !in store.pins) store.togglePin(document.id)
            store.setShortcut(ShortcutRules.PALETTE, KeyChord(KeyEvent.KEYCODE_Q, ShortcutRules.ALT))
            store.setShortcut(note, KeyChord(KeyEvent.KEYCODE_J, ShortcutRules.CTRL or ShortcutRules.ALT))
            val emoji = store.emoji.entries.first()
            if (emoji.id !in store.emoji.favorites) store.emoji.toggleFavorite(emoji)
            store.emoji.copied(emoji)
            saved.put("note", note).put("link", link).put("clip", clip).put("alias", "retnote$nonce").put("emoji", emoji.id)
        }
        extDirectory.mkdirs()
        val listing = StoreExtension(extensionId, extensionId, "Updater retention extension", "Controlled preservation fixture", "Droidcast tests", "tests",
            "https://example.com/fixture.zip", "https://example.com", "a".repeat(40), 1)
        File(extDirectory, ".tinycast-store.json").writeText(listing.json().toString())
        File(extDirectory, "package.json").writeText(JSONObject().put("name", extensionId).put("title", listing.title)
            .put("preferences", JSONArray().put(JSONObject().put("name", "sample").put("title", "Sample").put("type", "textfield").put("default", "MISSING")))
            .put("commands", JSONArray().put(JSONObject().put("name", "retention").put("title", "Retention fixture").put("mode", "view"))).toString())
        File(extDirectory, "retention.js").writeText("""
            const React=require('react');const {List,LocalStorage,getPreferenceValues}=require('@raycast/api');
            module.exports.default=function(){const [value,setValue]=React.useState('Loading...');
            React.useEffect(()=>{LocalStorage.getItem('retention-value').then(v=>setValue(v||'MISSING'))},[]);
            return React.createElement(List,null,React.createElement(List.Item,{id:'retained',title:'Extension data retained',
            subtitle:value+' · '+getPreferenceValues().sample}));};
        """.trimIndent())
        val support = File(context.filesDir, "extension-data/$extensionId").apply { mkdirs() }
        File(support, "retention.txt").writeText("Extension support file survives update.")
        assertTrue(context.getSharedPreferences("extension-$extensionId", Context.MODE_PRIVATE).edit()
            .putString("local", JSONObject().put("retention-value", "Extension data survives update").toString())
            .putString("preferences", JSONObject().put("sample", "Saved preference survives").toString()).commit())
        assertTrue(ExtensionCatalog(context).installed.any { it.listing.id == extensionId })
        for (name in listOf("palette", "emoji")) assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
        for (name in listOf("emoji", "extension-$extensionId"))
            saved.put("hash-$name", hash(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")))
        saved.put("palettePreferencesHash", palettePreferencesHash())
        saved.put("extensionCodeHash", hash(File(extDirectory, "retention.js")))
        writeState(saved)
        val input = File(context.filesDir, "updates-self-input/newer.apk")
        assertTrue(input.isFile)
        @Suppress("DEPRECATION") val info = requireNotNull(context.packageManager.getPackageArchiveInfo(input.path, android.content.pm.PackageManager.GET_SIGNATURES or android.content.pm.PackageManager.GET_META_DATA))
        val tag = "nightly-controlled-self-update"
        val manifest = UpdateManifest(1, UpdatePolicy.PACKAGE, info.longVersionCode, requireNotNull(info.versionName),
            requireNotNull(info.applicationInfo?.metaData?.getString("dev.droidcast.BUILD_COMMIT")), "Droidcast-controlled.apk", hash(input), input.length())
        val stage = File(context.filesDir, "app-updates").apply { mkdirs() }
        assertFalse("No user update is pending", File(stage, "staged.json").exists())
        input.copyTo(File(stage, "verified.apk"), overwrite = false)
        File(stage, "staged.json").writeText(manifest.json().put("tag", tag).put("title", "Controlled self-update fixture")
            .put("notes", "Real Android self-update. APK delivery was staged by the test harness; production recovery revalidates it.")
            .put("published", "2026-11-01T00:00:00Z").toString())
        compose.activityRule.scenario.onActivity { it.store.updates.chooseConfirmation(mode == "required"); it.selectPage("updates") }
        compose.waitUntil(20_000) { !compose.activity.store.updates.busy && compose.activity.store.updates.state.phase == UpdatePhase.READY }
        compose.onNodeWithTag("updates-verified").performScrollTo().assertIsDisplayed()
        updateEvidence("self-before-ready", "signing certificate verified")
        publicRecord("retention-before", JSONObject().put("prepared", true).put("sourceVersionCode", 6).put("targetVersionCode", info.longVersionCode)
            .put("confirmationRequired", mode == "required").put("appUid", android.os.Process.myUid()).put("keyboardServiceApproved", KeyboardServiceConsent.read(context) == KeyboardConsent.APPROVED)
            .put("scope", "Public fixture collections, persistent document grant, extension code/support/preferences/storage. Private originals are handled only by the opaque restoration helper."))
    }
    @Test fun verifyRetainedDataAfterInstall() {
        val saved = JSONObject(stateFile.readText())
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(7L, info.longVersionCode)
        val store = compose.activity.store
        assertTrue("Note body survived", store.notes.any { it.id == saved.getString("note") && it.title == noteTitle && it.body == noteBody })
        assertTrue("Quicklink survived", store.links.any { it.id == saved.getString("link") && it.body == "https://example.com/droidcast-update" })
        assertTrue("Clip survived", store.clips.any { it.id == saved.getString("clip") && it.body == "Saved clip survives a real APK update." })
        assertTrue(store.pins.containsAll(listOf(saved.getString("note"), saved.getString("documentId"))))
        assertEquals(saved.getString("alias"), store.aliases[saved.getString("note")])
        assertEquals(KeyChord(KeyEvent.KEYCODE_Q, ShortcutRules.ALT), store.shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        assertEquals(KeyChord(KeyEvent.KEYCODE_J, ShortcutRules.CTRL or ShortcutRules.ALT), store.shortcutConfiguration.bindings[saved.getString("note")])
        assertTrue(saved.getString("emoji") in store.emoji.favorites); assertTrue(saved.getString("emoji") in store.emoji.recent)
        assertEquals("Palette preferences survive, apart from updater command usage", saved.getString("palettePreferencesHash"), palettePreferencesHash())
        for (name in listOf("emoji", "extension-$extensionId"))
            assertTrue("Preference bytes survived: $name", saved.getString("hash-$name") == hash(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")))
        val beforeGrants = saved.getJSONArray("grantHashes").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        assertTrue("Existing document grants survived", grantHashes().containsAll(beforeGrants))
        val documentUri = Uri.parse(saved.getString("documentUri"))
        assertTrue("Fixture document grant survived", context.contentResolver.persistedUriPermissions.any { fixtureGrant(it.uri) && it.isReadPermission })
        assertEquals(fileBody, context.contentResolver.openInputStream(documentUri)!!.bufferedReader().use { it.readText() })
        assertTrue(store.folderSearch.folders.any { it.title == rootName })
        val ext = store.extensions.installed.single { it.listing.id == extensionId }
        assertEquals(saved.getString("extensionCodeHash"), hash(File(ext.directory, "retention.js")))
        assertEquals("Extension support file survives update.", File(context.filesDir, "extension-data/$extensionId/retention.txt").readText())
        assertEquals(saved.getBoolean("confirmationRequired"), store.updates.confirmEveryUpdate)
        publicRecord("retention-after", JSONObject().put("versionCode", 7).put("appUid", android.os.Process.myUid())
            .put("notesLinksClips", true).put("pinsAliasesShortcuts", true).put("emojiFavoritesRecents", true).put("emojiAndExtensionPreferenceBytesUnchanged", true).put("palettePreferencesExceptCommandUsageUnchanged", true)
            .put("existingGrants", true).put("documentGrantAndContent", true).put("extensionCodeSupportPreferencesStorage", true)
            .put("confirmationPreference", true).put("scope", "Public fixtures plus opaque preference-byte equality; no private preference or grant contents are published."))
        compose.activityRule.scenario.onActivity { it.selectPage("updates") }
        compose.waitUntil(10_000) { !store.updates.busy }
        compose.onNodeWithTag("updates-installed").performScrollTo().assertTextEquals("Droidcast ${info.versionName}")
        updateEvidence("self-after-version", "Droidcast ${info.versionName}")
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java)) }
        var resumed: MainActivity? = null
        compose.waitUntil(15_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            }
            resumed != null
        }
        val main = requireNotNull(resumed)
        compose.waitUntil(15_000) { !main.store.loading }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            main.execute(Command("extension:$extensionId/retention", "Retention fixture", "", Kind.EXTENSION, payload = "$extensionId/retention"))
        }
        compose.waitUntil(25_000) {
            main.store.extensions.runtimeError != null ||
                main.store.extensions.runtimeTree?.toString()?.contains("Extension data survives update · Saved preference survives") == true
        }
        assertNull(main.store.extensions.runtimeError, main.store.extensions.runtimeError)
        compose.onNodeWithText("Extension data retained").assertIsDisplayed()
        compose.onNodeWithText("Extension data survives update · Saved preference survives").assertIsDisplayed()
        updateEvidence("self-after-extension", "Extension data survives update")
        compose.onNodeWithTag("extension-back").performClick()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { main.execute(requireNotNull(main.store.command(saved.getString("note")))) }
        compose.onNodeWithTag("editor-body").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(noteBody)))
        updateEvidence("self-after-note", noteBody)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { main.finish() }
    }
    @Test fun releasePublicFixtureGrant() {
        if (!stateFile.isFile) return
        val saved = JSONObject(stateFile.readText())
        compose.activityRule.scenario.onActivity { it.store.updates.cancel() }
        compose.waitUntil(15_000) { !compose.activity.store.updates.busy }
        for (session in context.packageManager.packageInstaller.mySessions.filter { it.appPackageName == UpdatePolicy.PACKAGE })
            context.packageManager.packageInstaller.abandonSession(session.sessionId)
        if (!saved.optBoolean("grantPreexisting")) context.contentResolver.persistedUriPermissions.filter { fixtureGrant(it.uri) }.forEach {
            context.contentResolver.releasePersistableUriPermission(it.uri,
                (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
        if (saved.optBoolean("rootCreated")) device.executeShellCommand("rm -rf /sdcard/Download/$rootName")
        publicRecord("fixture-grant-cleanup", JSONObject().put("restored", true))
    }
    private fun updateSystemEvidence(name: String, marker: String) {
        assertTrue(device.wait(Until.hasObject(By.textContains(marker)), 7000))
        File(context.filesDir, "updates-evidence").apply { mkdirs() }.let {
            device.dumpWindowHierarchy(File(it, "$name.xml")); assertTrue(device.takeScreenshot(File(it, "$name.png")))
        }
    }
}
