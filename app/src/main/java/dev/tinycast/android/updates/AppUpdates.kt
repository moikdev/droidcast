package dev.tinycast.android.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, APPROVAL, INSTALLING, CONFIRM, CANCELLING, COMPLETE, ERROR }
internal data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val candidate: UpdateCandidate? = null,
    val bytes: Long = 0, val verified: Boolean = false, val message: String = "Check for a new nightly when you're ready.") {
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.CONFIRM, UpdatePhase.CANCELLING)
}

/** One process-wide owner across palette/settings/activity replacement. No automatic network checks. */
internal object Updates {
    private var instance: AppUpdates? = null
    @Synchronized fun get(context: Context): AppUpdates = instance ?: AppUpdates(context.applicationContext).also { instance = it }
}

internal class AppUpdates(
    private val context: Context,
    private val source: UpdateSource = GithubUpdates(),
    private val directory: File = File(context.filesDir, "app-updates")
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var operation: Job? = null
    private var epoch = 0L
    private var recovered = false
    private val prefs = context.getSharedPreferences(directory.name, Context.MODE_PRIVATE)
    private val apk get() = File(directory, "verified.apk")
    private val partial get() = File(directory, "download.part")
    private val record get() = File(directory, "staged.json")
    private val installer get() = context.packageManager.packageInstaller
    var state by mutableStateOf(UpdateState()); private set
    var installed by mutableStateOf(installedIdentity()); private set
    var approvalGranted by mutableStateOf(context.packageManager.canRequestPackageInstalls()); private set
    private var confirmation: Intent? = null
    var confirmEveryUpdate by mutableStateOf(prefs.getBoolean("confirm-every-update", true)); private set
    fun chooseConfirmation(value: Boolean) {
        if (state.busy || operation?.isActive == true) return
        if (prefs.edit().putBoolean("confirm-every-update", value).commit()) confirmEveryUpdate = value
        else state = state.copy(message = "Could not save update preferences.")
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ArchiveIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        return ArchiveIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.versionName.orEmpty(), certificates.map { digest(it.toByteArray()) }.toSet(), info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)
    }
    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    private fun installedIdentity() = identity(context.packageManager.getPackageInfo(context.packageName, signingFlags()))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun fileHash(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private suspend fun verify(candidate: UpdateCandidate, file: File) = withContext(Dispatchers.IO) {
        require(file.length() == candidate.manifest.bytes) { "Downloaded APK is incomplete" }
        require(fileHash(file) == candidate.manifest.sha256) { "APK checksum verification failed" }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signingFlags()) ?: error("Downloaded file is not a valid APK")
        UpdatePolicy.verifyArchive(candidate.manifest, identity(archive), installedIdentity(), Build.VERSION.SDK_INT)
    }
    private fun operate(phase: UpdatePhase, work: suspend () -> Unit) {
        if (operation?.isActive == true || state.busy) return
        epoch++
        operation = scope.launch {
            mutex.withLock {
                state = state.copy(phase = phase)
                try { work() }
                catch (_: TimeoutCancellationException) {
                    state = state.copy(phase = if (state.verified) UpdatePhase.READY else UpdatePhase.ERROR, message = "The update request timed out. Please try again.")
                }
                catch (_: CancellationException) {
                    if (state.phase != UpdatePhase.CANCELLING && !hasSession())
                        state = state.copy(phase = if (state.verified) UpdatePhase.READY else UpdatePhase.AVAILABLE, message = "Operation cancelled.")
                }
                catch (error: Exception) {
                    state = state.copy(phase = if (state.verified) UpdatePhase.READY else UpdatePhase.ERROR,
                        message = error.message?.take(300) ?: "Update failed. Please try again.")
                }
                finally { withContext(NonCancellable + Dispatchers.IO) { partial.delete() } }
            }
        }
    }
    fun check() = operate(UpdatePhase.CHECKING) {
        if (!recovered) {
            val saved = recoverState()
            recovered = true
            if (saved.phase in setOf(UpdatePhase.READY, UpdatePhase.COMPLETE)) { state = saved; return@operate }
        }
        val candidate = withTimeout(60_000) { source.newest() }
        installed = installedIdentity()
        state = if (candidate == null || candidate.manifest.versionCode <= installed.versionCode)
            UpdateState(UpdatePhase.CURRENT, message = "No newer updater-ready nightly is published.")
        else UpdateState(UpdatePhase.AVAILABLE, candidate, message = "A newer nightly is available.")
    }
    fun download() {
        val candidate = state.candidate ?: return
        operate(UpdatePhase.DOWNLOADING) {
            require(candidate.manifest.versionCode > installedIdentity().versionCode) { "This build is already installed or older" }
            val request = epoch
            withContext(Dispatchers.IO) { directory.mkdirs(); clearStage() }
            require(source.checksum(candidate) == candidate.manifest.sha256) { "Release checksum does not match the update manifest" }
            var last = 0L
            source.download(candidate, partial) { bytes ->
                val now = System.nanoTime()
                if (now - last >= 100_000_000 || bytes == candidate.manifest.bytes) {
                    last = now
                    scope.launch { if (request == epoch && state.phase == UpdatePhase.DOWNLOADING) state = state.copy(bytes = bytes) }
                }
            }
            state = state.copy(phase = UpdatePhase.VERIFYING, bytes = candidate.manifest.bytes)
            verify(candidate, partial)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                val json = candidate.manifest.json().put("tag", candidate.release.tag).put("title", candidate.release.title)
                    .put("notes", candidate.release.notes).put("published", candidate.release.published)
                val temp = File(directory, "staged.tmp")
                FileOutputStream(temp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(temp.toPath(), record.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            state = state.copy(phase = UpdatePhase.READY, verified = true, message = "Verified and ready to install.")
        }
    }
    private fun clearStage() { partial.delete(); apk.delete(); record.delete(); File(directory, "staged.tmp").delete() }
    private fun hasSession() = prefs.getInt("session", -1) >= 0
    private fun clearSession() { check(prefs.edit().remove("session").remove("token").commit()) { "Could not save installer state" }; confirmation = null }
    private fun abandon() {
        val id = prefs.getInt("session", -1)
        if (id >= 0 && installer.getSessionInfo(id) != null) installer.abandonSession(id)
        clearSession()
    }
    fun refresh() {
        approvalGranted = context.packageManager.canRequestPackageInstalls()
        installed = installedIdentity()
        if (state.phase == UpdatePhase.APPROVAL && approvalGranted) state = state.copy(phase = UpdatePhase.READY, message = "Android approval enabled. Choose Install update.")
        if (state.phase != UpdatePhase.IDLE || operation?.isActive == true) return
        operate(UpdatePhase.VERIFYING) { state = recoverState(); recovered = true }
    }
    private suspend fun recoverState(): UpdateState {
        try {
            val candidate = withContext(Dispatchers.IO) {
                partial.delete(); File(directory, "staged.tmp").delete()
                if (!record.isFile) { apk.delete(); null } else {
                    require(record.length() <= 64 * 1024) { "Saved update metadata is invalid" }
                    val json = JSONObject(record.readText())
                    val manifest = decodeManifest(json)
                    val release = NightlyRelease(json.getString("tag"), json.getString("title"), json.getString("notes"), json.getString("published"),
                        listOf(UpdateAsset(manifest.apk, UpdatePolicy.assetUrl(json.getString("tag"), manifest.apk), manifest.bytes),
                            UpdateAsset(UpdatePolicy.CHECKSUMS, UpdatePolicy.assetUrl(json.getString("tag"), UpdatePolicy.CHECKSUMS), 1)))
                    UpdatePolicy.candidate(release, manifest)
                }
            }
            return if (candidate == null) {
                withContext(Dispatchers.IO) { if (hasSession()) abandon() }
                UpdateState()
            } else if (installedIdentity().versionCode >= candidate.manifest.versionCode) {
                withContext(Dispatchers.IO) { clearStage(); clearSession() }
                UpdateState(UpdatePhase.COMPLETE, message = "Droidcast was updated. Your data stays on this device.")
            } else {
                withContext(Dispatchers.IO) { if (hasSession()) abandon() }
                verify(candidate, apk)
                UpdateState(UpdatePhase.READY, candidate, candidate.manifest.bytes, true, "Verified download recovered. Choose Install update to continue.")
            }
        } catch (error: Exception) {
            if (error !is CancellationException) withContext(NonCancellable + Dispatchers.IO) { clearStage() }
            throw error
        }
    }
    fun approvalIntent(): Intent {
        state = state.copy(phase = UpdatePhase.APPROVAL, message = "Allow updates from Droidcast in Android, then return here.")
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    }
    fun install() {
        val candidate = state.candidate ?: return
        if (!state.verified) return
        approvalGranted = context.packageManager.canRequestPackageInstalls()
        if (!approvalGranted) { state = state.copy(phase = UpdatePhase.APPROVAL, message = "Allow app updates from Droidcast in Android first."); return }
        operate(UpdatePhase.INSTALLING) {
            try { verify(candidate, apk) }
            catch (error: Exception) {
                if (error !is CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) { clearStage() }
                    state = state.copy(verified = false)
                }
                throw error
            }
            var sessionId = -1
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(UpdatePolicy.PACKAGE); setSize(candidate.manifest.bytes)
                        setInstallReason(PackageManager.INSTALL_REASON_USER)
                        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(if (confirmEveryUpdate) PackageInstaller.SessionParams.USER_ACTION_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                    }
                    sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("base.apk", 0, candidate.manifest.bytes).use { out ->
                            val hash = MessageDigest.getInstance("SHA-256"); var bytes = 0L
                            apk.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    bytes += n; require(bytes <= candidate.manifest.bytes) { "Staged APK changed" }
                                    hash.update(buffer, 0, n); out.write(buffer, 0, n)
                                }
                            }
                            require(bytes == candidate.manifest.bytes && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == candidate.manifest.sha256) { "Staged APK changed" }
                            session.fsync(out)
                        }
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            val token = UUID.randomUUID().toString()
                            check(prefs.edit().putInt("session", sessionId).putString("token", token).commit()) { "Could not save installer state" }
                            val intent = Intent(context, UpdateInstallReceiver::class.java)
                                .setAction("dev.droidcast.UPDATE_STATUS").setData(Uri.parse("droidcast-update://result/$token"))
                            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                            val result = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                            session.commit(result.intentSender)
                            committed = true
                        }
                    }
                }
                if (state.phase != UpdatePhase.CANCELLING) state = state.copy(phase = UpdatePhase.INSTALLING, message = "Waiting for Android's installer…")
            } catch (error: IllegalArgumentException) {
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) { clearStage() }
                    state = state.copy(verified = false)
                }
                throw error
            } finally {
                if (!committed && sessionId >= 0) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { installer.abandonSession(sessionId) }; clearSession()
                }
            }
        }
    }
    fun cancel() {
        if (state.phase == UpdatePhase.CANCELLING) return
        val previous = operation
        epoch++; state = state.copy(phase = UpdatePhase.CANCELLING, message = "Cancelling…")
        previous?.cancel()
        operation = scope.launch {
            previous?.join()
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) { abandon(); partial.delete() }
                    installed = installedIdentity()
                    state = if (state.candidate?.manifest?.versionCode?.let { installed.versionCode >= it } == true)
                        UpdateState(UpdatePhase.COMPLETE, message = "Android completed the update.")
                    else state.copy(phase = if (state.verified) UpdatePhase.READY else if (state.candidate != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE, message = "Cancelled. You can try again.")
                } catch (_: Exception) { state = state.copy(phase = UpdatePhase.ERROR, message = "Android is still handling this installation. Return after it finishes.") }
            }
        }
    }
    fun confirm(context: Context) {
        val intent = confirmation ?: run { state = state.copy(message = "Installer confirmation expired. Cancel and try installing again."); return }
        try { context.startActivity(Intent(intent)) }
        catch (_: Exception) { state = state.copy(message = "Android's installer could not be opened. Cancel and try again.") }
    }
    private fun matchesResult(intent: Intent): Boolean {
        val token = prefs.getString("token", null) ?: return false
        return intent.action == "dev.droidcast.UPDATE_STATUS" && intent.data?.toString() == "droidcast-update://result/$token" &&
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) == prefs.getInt("session", -1)
    }
    fun receive(intent: Intent): Job = scope.launch {
        if (!matchesResult(intent) || state.phase == UpdatePhase.CANCELLING) return@launch
        try { mutex.withLock {
            val token = prefs.getString("token", null) ?: return@withLock
            val id = prefs.getInt("session", -1)
            if (intent.action != "dev.droidcast.UPDATE_STATUS" || intent.data?.toString() != "droidcast-update://result/$token" ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != id || state.phase == UpdatePhase.CANCELLING) return@withLock
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val next = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (next != null) { confirmation = next; state = state.copy(phase = UpdatePhase.CONFIRM, message = "Android needs your confirmation. Continue with Android's installer.") }
                    else state = state.copy(phase = UpdatePhase.ERROR, message = "Android did not provide an installation prompt. Cancel and retry.")
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    withContext(Dispatchers.IO) { clearSession(); clearStage() }
                    installed = installedIdentity(); state = UpdateState(UpdatePhase.COMPLETE, message = "Update installed.")
                }
                else -> {
                    withContext(Dispatchers.IO) { clearSession() }
                    state = state.copy(phase = if (state.verified) UpdatePhase.READY else UpdatePhase.ERROR,
                        message = if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99) == PackageInstaller.STATUS_FAILURE_ABORTED)
                            "Installation cancelled. Verified download kept for retry." else "Android could not install this update. You can retry or check for a newer build.")
                }
            }
        } } catch (error: Exception) {
            if (error is CancellationException) throw error
            state = state.copy(phase = UpdatePhase.ERROR, message = "Could not save Android's installation result. Reopen Updates to recover.")
        }
    }
    fun report(message: String) { state = state.copy(message = message) }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        // The non-exported receiver accepts only the explicit mutable PendingIntent
        // created for the persisted session/token. No external action is registered.
        val updates = Updates.get(context)
        updates.receive(intent).invokeOnCompletion { pending.finish() }
    }
}
