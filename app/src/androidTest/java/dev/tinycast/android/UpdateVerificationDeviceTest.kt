package dev.tinycast.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.updates.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private fun updateHash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun updateCandidate(context: Context, file: File): UpdateCandidate {
    @Suppress("DEPRECATION") val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES))
    val tag = "nightly-controlled-update-fixture"
    val asset = UpdateAsset("Droidcast-fixture.apk", UpdatePolicy.assetUrl(tag, "Droidcast-fixture.apk"), file.length())
    val actualCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    val manifest = UpdateManifest(1, UpdatePolicy.PACKAGE, if (info.packageName == UpdatePolicy.PACKAGE) actualCode else 7,
        info.versionName.orEmpty().ifBlank { "fixture-test" }, "a".repeat(40), asset.name, updateHash(file), file.length())
    val release = NightlyRelease(tag, "Controlled update fixture", "Real signed APK fixture. Download transport is controlled for this test.", "2026-11-01T00:00:00Z",
        listOf(asset, UpdateAsset(UpdatePolicy.CHECKSUMS, UpdatePolicy.assetUrl(tag, UpdatePolicy.CHECKSUMS), 128)))
    return UpdatePolicy.candidate(release, manifest)
}

private class FixtureUpdateSource(var candidate: UpdateCandidate, var file: File) : UpdateSource {
    var offline = false
    var timeout = false
    var pause = false
    var truncate = false
    var corrupt = false
    var discoveries = 0
    var downloads = 0
    val gate = CompletableDeferred<Unit>()
    override suspend fun newest(): UpdateCandidate { discoveries++; if (timeout) withTimeout(1) { delay(1000) }; if (offline) throw IOException("Offline fixture: connect and try again."); return candidate }
    override suspend fun checksum(candidate: UpdateCandidate) = candidate.manifest.sha256
    override suspend fun download(candidate: UpdateCandidate, file: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        downloads++
        this@FixtureUpdateSource.file.inputStream().use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                if (corrupt && total == 0L) buffer[0] = (buffer[0].toInt() xor 1).toByte()
                output.write(buffer, 0, n); total += n; progress(total)
                if (pause && total >= 1024 * 1024) { delay(120); progress(total); gate.await() }
                if (truncate && total >= 1024 * 1024) throw IOException("Download interrupted by fixture")
            }
        } }
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateVerificationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val fixtures get() = File(context.filesDir, "updates-fixtures")
    private lateinit var directory: File
    private lateinit var source: FixtureUpdateSource
    private lateinit var updates: AppUpdates
    private var original = emptyMap<String, Any?>()
    @Before fun setup() {
        device.executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        val apk = File(fixtures, "newer.apk"); assertTrue("Guarded runner must supply signed newer APK", apk.isFile)
        directory = File(context.cacheDir, "updates-test-"+UUID.randomUUID()).apply { mkdirs() }
        source = FixtureUpdateSource(updateCandidate(context, apk), apk)
        updates = AppUpdates(context, source, directory)
        compose.waitUntil(15_000) { !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity {
            it.selectPage("updates")
            it.setContent { AdvancedSettings(it, updates) }
        }
        // The content's initial LaunchedEffect starts recovery; initial model IDLE
        // alone can be observed before that first composition has run.
        compose.waitForIdle()
        compose.waitUntil(10_000) { !updates.busy && updates.state.phase == UpdatePhase.IDLE }
        awaitControl("updates-check")
    }
    @After fun restore() {
        if (::updates.isInitialized) {
            compose.runOnUiThread { updates.cancel() }
            compose.waitUntil(10_000) { !updates.busy }
        }
        if (::directory.isInitialized) { directory.deleteRecursively(); context.deleteSharedPreferences(directory.name) }
        restorePalettePreferences(context, original)
    }
    private fun evidence(name: String, marker: String, tag: String = "updates-message") {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextContains(marker, substring = true)
        updateEvidence(name, marker)
    }
    private fun awaitControl(tag: String) {
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 && !updates.busy
            }
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        } catch (error: Throwable) {
            File(context.filesDir, "updates-evidence").apply { mkdirs() }.let {
                device.dumpWindowHierarchy(File(it, "failure-control-ready.xml"))
                device.takeScreenshot(File(it, "failure-control-ready.png"))
            }
            throw error
        }
    }
    private fun checkAvailable() {
        awaitControl("updates-check")
        compose.onNodeWithTag("updates-check").performClick()
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.AVAILABLE && !updates.busy }
        awaitControl("updates-download")
    }
    private fun downloadAndWait() {
        awaitControl("updates-download")
        compose.onNodeWithTag("updates-download").performClick()
        compose.waitUntil(20_000) { !updates.busy }
    }
    @Test fun offlineRetryReachesAvailableWithoutPopups() {
        source.offline = true
        compose.onNodeWithTag("updates-check").performScrollTo().performClick()
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.ERROR }
        evidence("offline-error", "Offline fixture")
        source.offline = false; checkAvailable()
        compose.onNodeWithTag("updates-download").assertIsEnabled()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        evidence("available", "A new nightly is available", "updates-status")
    }
    @Test fun cancelDownloadRemovesPartialAndCannotInstall() {
        source.pause = true; checkAvailable()
        compose.onNodeWithTag("updates-download").performScrollTo().performClick()
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.DOWNLOADING && updates.state.bytes >= 1024 * 1024 }
        evidence("download-progress", "Downloading update", "updates-status")
        compose.onNodeWithTag("updates-cancel").performScrollTo().performClick()
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.AVAILABLE }
        assertFalse(File(directory, "download.part").exists()); assertFalse(File(directory, "verified.apk").exists())
        assertFalse(updates.state.verified)
        compose.onNodeWithTag("updates-install").assertDoesNotExist()
        evidence("download-cancelled", "Cancelled.")
    }
    @Test fun signedNewerArchiveIsVerifiedAndRecoveredByReplacement() {
        checkAvailable(); downloadAndWait()
        assertEquals(UpdatePhase.READY, updates.state.phase); assertTrue(updates.state.verified)
        assertEquals(source.candidate.manifest.sha256, updateHash(File(directory, "verified.apk")))
        evidence("verified-ready", "signing certificate verified", "updates-verified")
        val replacement = AppUpdates(context, source, directory)
        val discoveries = source.discoveries
        compose.runOnUiThread { replacement.check() }
        compose.waitUntil(15_000) { replacement.state.phase == UpdatePhase.READY }
        assertTrue(replacement.state.verified)
        assertEquals("Recovery does not need another network discovery", discoveries, source.discoveries)
        updates = replacement
    }
    @Test fun corruptAndInterruptedDownloadsRejectBeforeRetrying() {
        checkAvailable()
        source.corrupt = true; downloadAndWait()
        assertEquals(UpdatePhase.ERROR, updates.state.phase); assertFalse(updates.state.verified)
        assertTrue(updates.state.message.contains("checksum", true))
        assertFalse(File(directory, "verified.apk").exists())
        evidence("checksum-error", "checksum verification failed")
        source.corrupt = false; source.truncate = true
        downloadAndWait()
        assertEquals(UpdatePhase.ERROR, updates.state.phase); assertFalse(File(directory, "download.part").exists())
        source.truncate = false; downloadAndWait()
        assertEquals(UpdatePhase.READY, updates.state.phase); assertTrue(updates.state.verified)
    }
    @Test fun wrongSignerAndPackageNeverCreateAnInstallerSession() {
        val sessions = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        for ((name, marker) in listOf("wrong-signer.apk" to "signing certificate", "wrong-package.apk" to "package")) {
            val file = File(fixtures, name); assertTrue(file.isFile)
            source.file = file; source.candidate = updateCandidate(context, file).let {
                // The wrong-package APK's actual version may be 1; offer it as newer
                // so archive validation, rather than feed filtering, rejects it.
                it.copy(manifest = it.manifest.copy(versionCode = 7))
            }
            compose.runOnUiThread { updates.check() }
            compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.AVAILABLE }
            downloadAndWait()
            assertEquals(UpdatePhase.ERROR, updates.state.phase); assertFalse(updates.state.verified)
            assertTrue(updates.state.message, updates.state.message.contains(marker, true))
            assertEquals(sessions, context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet())
            assertFalse(File(directory, "verified.apk").exists())
            evidence(if (name == "wrong-signer.apk") "wrong-signer-error" else "wrong-package-error", marker)
        }
    }
    @Test fun olderFeedCannotOfferDownloadOrInstall() {
        source.candidate = source.candidate.copy(manifest = source.candidate.manifest.copy(versionCode = 5))
        compose.runOnUiThread { updates.check() }
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.CURRENT }
        assertEquals(0, source.downloads); assertNull(updates.state.candidate)
        compose.onNodeWithTag("updates-download").assertDoesNotExist()
        compose.onNodeWithTag("updates-install").assertDoesNotExist()
    }
    @Test fun tamperedRecoveredFileIsRejectedAndDeleted() {
        checkAvailable(); downloadAndWait()
        val saved = File(directory, "verified.apk")
        java.io.RandomAccessFile(saved, "rw").use { it.seek(0); it.write(0) }
        val replacement = AppUpdates(context, source, directory)
        compose.runOnUiThread { replacement.refresh() }
        compose.waitUntil(15_000) { replacement.state.phase == UpdatePhase.ERROR }
        assertFalse(replacement.state.verified); assertFalse(saved.exists())
        assertFalse(File(directory, "staged.json").exists())
        updates = replacement
    }
    @Test fun changedStagedFileLosesVerificationBeforeInstall() {
        assertTrue("Guarded fixture runner supplies scoped install approval", context.packageManager.canRequestPackageInstalls())
        checkAvailable(); downloadAndWait()
        val sessions = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        java.io.RandomAccessFile(File(directory, "verified.apk"), "rw").use { it.seek(0); it.write(0) }
        compose.runOnUiThread { updates.install() }
        compose.waitUntil(15_000) { updates.state.phase == UpdatePhase.ERROR }
        assertFalse(updates.state.verified); assertFalse(File(directory, "verified.apk").exists())
        assertEquals(sessions, context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet())
        compose.onNodeWithTag("updates-verified").assertDoesNotExist()
        evidence("staged-tamper-error", "checksum verification failed")
    }
    @Test fun timeoutIsAnErrorRatherThanUserCancellation() {
        source.timeout = true
        compose.onNodeWithTag("updates-check").performScrollTo().performClick()
        compose.waitUntil(10_000) { updates.state.phase == UpdatePhase.ERROR }
        assertTrue(updates.state.message.contains("timed out"))
        assertFalse(updates.state.verified)
    }
    @Test fun checkingTheSameReleaseKeepsVerifiedDownload() {
        checkAvailable(); downloadAndWait()
        assertTrue(updates.state.verified)
        val downloaded = source.downloads
        compose.runOnUiThread { updates.check() }
        compose.waitUntil(15_000) { !updates.busy }
        assertEquals(UpdatePhase.READY, updates.state.phase)
        assertTrue(updates.state.verified)
        assertEquals(downloaded, source.downloads)
        assertEquals(source.candidate.manifest.sha256, updateHash(File(directory, "verified.apk")))
    }
    @Test fun forgedInstallerReceiptsCannotChangeReadyState() {
        checkAvailable(); downloadAndWait()
        val intent = Intent("dev.droidcast.UPDATE_STATUS").setData(android.net.Uri.parse("droidcast-update://result/forged"))
            .putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, 999)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_SUCCESS)
        runBlocking { updates.receive(intent).join() }
        assertEquals(UpdatePhase.READY, updates.state.phase); assertTrue(updates.state.verified)
        assertTrue(File(directory, "verified.apk").isFile)
    }
}
