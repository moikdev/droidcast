package dev.tinycast.android.extensions

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import dev.tinycast.android.TextInputProblem
import dev.tinycast.android.TextInputRejected

@SuppressLint("SetJavaScriptEnabled")
class ExtensionRuntime(private val context: Context, private val catalog: ExtensionCatalog,
    private val extension: InstalledExtension, private val command: ExtensionCommand,
    private val canLaunchCommand: () -> Boolean) {
    constructor(context: Context, catalog: ExtensionCatalog, extension: InstalledExtension, command: ExtensionCommand) :
        this(context, catalog, extension, command, { false })
    private val textContext = catalog.captureText(extension,command)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        fail(error.message ?: "Could not start this extension")
    })
    private val session = UUID.randomUUID().toString()
    private val storage = context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE)
    private val support = File(context.filesDir, "extension-data/${extension.listing.id}").apply { mkdirs() }
    @Volatile private var destroyed = false
    private var started = false
    private var watchdog: Job? = null
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private var pendingLaunchAcknowledgement: Any? = null
    private var acknowledgementTimeout: Runnable? = null
    private var engineDestroyed = false
    val webView = WebView(context).apply {
        // This is a headless JavaScript engine; visible native controls own input.
        isFocusable = false; isFocusableInTouchMode = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false; settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        addJavascriptInterface(Bridge(), "AndroidHost")
        webChromeClient = object : WebChromeClient() {
            // Extension output can contain reviewed input. Consume engine
            // console messages instead of forwarding them to Android logs.
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
            override fun onPageFinished(view: WebView?, url: String?) { if(started && active())boot() }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                fail("The JavaScript engine stopped. Close this command and try again.")
                destroyEngine(); return true
            }
        }
    }
    private val runtimeRegistration = catalog.attachRuntime(textContext.owner, extension, command) {
        scope.cancel()
        if (pendingLaunchAcknowledgement == null) webView.stopLoading()
    }
    init { if (runtimeRegistration == null) scope.cancel() }
    private fun active() = !destroyed && scope.isActive && catalog.ownsRuntime(textContext.owner,extension,command) && catalog.runtimeError == null
    internal fun isCurrentInvocation(): Boolean = active()
    internal fun formFileStorage(): FormFileStorage? = if (active()) FormFileStorage(context.filesDir, extension.listing.id) else null
    private fun fail(message: String) {
        if (!active()) return
        ready()
        catalog.runtimeError = message
        scope.cancel()
    }
    fun start() {
        if(started || !active())return
        started = true
        watchdog = scope.launch { delay(20000); if (active() && !catalog.runtimeReady) fail("This command did not respond. It may require unsupported desktop APIs.") }
        webView.loadDataWithBaseURL("https://tinycast.invalid/", "<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'\"></head><body></body></html>", "text/html", "UTF-8", null)
    }
    private fun evaluate(script: String, then: (() -> Unit)? = null) {
        if (active()) webView.evaluateJavascript(script) { if (active()) then?.invoke() }
    }
    private fun boot() {
        scope.launch {
            val (adapter, runtime, bundle) = withContext(Dispatchers.IO) {
                Triple(context.assets.open("android-runtime.js").bufferedReader().use { it.readText() },
                    context.assets.open("RaycastRuntime.generated.js").bufferedReader().use { it.readText() }, File(extension.directory, "${command.name}.js").readText())
            }
            evaluate(adapter) { evaluate(runtime) {
                val node = JSONObject().put("platform", "android").put("arch", "arm64").put("cwd", support.path).put("homedir", support.path).put("tmpdir", support.path).put("env", JSONObject())
                val env = JSONObject().put("extensionName", extension.listing.name).put("commandName", command.name).put("commandMode", command.mode)
                    .put("assetsPath", File(extension.directory, "assets").path).put("supportPath", support.path).put("raycastVersion", "2.0.3").put("appearance", "dark").put("textSize", "medium").put("isDevelopment", false)
                val configuration = JSONObject().put("node", node).put("environment", env).put("preferences", catalog.preferences(extension, command))
                    .put("caches", JSONObject(storage.getString("caches", "{}")!!)).put("launchProps", JSONObject(textContext.launchProperties))
                evaluate("try { __tinycast.boot(${quote(configuration.toString())}); if(globalThis.process) process.platform='android'; __tinycast.start(${quote(session)},${quote(bundle)},${quote(File(extension.directory,"${command.name}.js").path)},${quote(extension.directory.path)},${quote(command.mode)},${quote(configuration.toString())}); } catch(e) { AndroidHost.failed(String(e.stack || e)); }")
            } }
        }
    }
    fun dispatch(handle: String?, args: JSONArray = JSONArray()) {
        if (active() && !handle.isNullOrBlank()) evaluate("__tinycast.dispatch(${quote(session)},${quote(handle)},${quote(args.toString())})")
    }
    fun dispatchFormFocus(handle: String?, fieldId: String, focused: Boolean, value: Any) {
        if (active() && !handle.isNullOrBlank()) evaluate("__androidDispatchFormFocus(${quote(session)},${quote(handle)},${quote(fieldId)},${quote(if (focused) "focus" else "blur")},${quote(JSONArray().put(value).toString())})")
    }
    fun back() { evaluate("__tinycast.popNavigation(${quote(session)})") }
    fun close() {
        if (destroyed) return
        destroyed = true; catalog.detachRuntime(runtimeRegistration); textContext.input?.let(catalog.reviewedText::release); scope.cancel()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        // Only the acknowledgement may finish in this retired, detached engine.
        // Its authority was revoked before settlement; the timeout is independent
        // of the cancelled invocation scope and bounds retention after disposal.
        if (pendingLaunchAcknowledgement == null) destroyEngine()
    }
    private fun destroyEngine() {
        if (engineDestroyed) return
        engineDestroyed = true
        acknowledgementTimeout?.let(cleanupHandler::removeCallbacks); acknowledgementTimeout = null; pendingLaunchAcknowledgement = null
        webView.stopLoading(); webView.removeJavascriptInterface("AndroidHost")
        (webView.parent as? android.view.ViewGroup)?.removeView(webView); webView.destroy()
    }
    private fun acknowledgeCommandLaunch(token: Any) {
        if (pendingLaunchAcknowledgement !== token) return
        pendingLaunchAcknowledgement = null
        acknowledgementTimeout?.let(cleanupHandler::removeCallbacks); acknowledgementTimeout = null
        if (destroyed) destroyEngine()
    }
    private fun launchCommand(id: String, raw: String) {
        check(canLaunchCommand() && webView.isAttachedToWindow && webView.hasWindowFocus()) {
            "Command handoff needs the current command screen in the foreground"
        }
        val request = ExtensionCommandLaunch.parse(raw)
        val prepared = catalog.prepareCommandLaunch(textContext.owner, extension, command, request)
        check(canLaunchCommand() && webView.hasWindowFocus()) { "This command screen is no longer current" }
        val token = Any()
        check(pendingLaunchAcknowledgement == null) { "A command handoff is already pending" }
        pendingLaunchAcknowledgement = token
        val timeout = Runnable { acknowledgeCommandLaunch(token) }
        acknowledgementTimeout = timeout
        try {
            check(cleanupHandler.postDelayed(timeout, 1000)) { "Could not schedule command handoff" }
            check(catalog.commitCommandLaunch(prepared)) { "This command launch is no longer current" }
        } catch (failure: Throwable) {
            acknowledgeCommandLaunch(token)
            throw failure
        }
        // The target is selected and the parent scope/lease is already revoked.
        // Bypass active() solely for its final launch acknowledgement. Old host
        // calls, renders and completion notifications cannot affect the child.
        try {
            webView.evaluateJavascript("__tinycast.settle(${quote(id)},true,${quote("null")})") { acknowledgeCommandLaunch(token) }
        } catch (_: RuntimeException) { acknowledgeCommandLaunch(token) }
    }
    private fun ready() { catalog.runtimeReady = true; watchdog?.cancel() }
    private fun quote(s: String) = JSONObject.quote(s)
    private fun post(block: () -> Unit) { if (active()) scope.launch { if (active()) block() } }
    inner class Bridge {
        @JavascriptInterface fun render(tree: String) = post { ready(); catalog.runtimeTree = JSONObject(tree); catalog.runtimeStatus = "" }
        @JavascriptInterface fun failed(message: String) = post { fail(message.take(3000)) }
        @JavascriptInterface fun finished() = post { ready(); if(command.mode=="no-view")catalog.releaseText(textContext.owner); catalog.runtimeStatus = if (catalog.runtimeStatus.isBlank() || catalog.runtimeStatus == "Starting extension…") "Command completed" else catalog.runtimeStatus }
        @JavascriptInterface fun depth(value: String) = post { catalog.navigationDepth = value.toIntOrNull() ?: 1 }
        @JavascriptInterface fun log(message: String) = post { if (catalog.runtimeTree == null) catalog.runtimeStatus = message.take(300) }
        @JavascriptInterface fun invoke(id: String, api: String, method: String, raw: String) {
            scope.launch {
                if(!active())return@launch
                try {
                    if (api == "system" && method == "launchCommand") {
                        launchCommand(id, raw)
                        return@launch
                    }
                    val result = asyncCall(api, method, JSONArray(raw))
                    if(!active())return@launch
                    val encoded = JSONArray().put(result ?: JSONObject.NULL).toString().drop(1).dropLast(1)
                    evaluate("__tinycast.settle(${quote(id)},true,${quote(encoded)})")
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    catalog.releaseText(textContext.owner)
                    evaluate("__tinycast.settle(${quote(id)},false,${quote(e.message ?: "Unsupported Android API")})")
                }
            }
        }
        @JavascriptInterface fun invokeSync(api: String, method: String, raw: String): String = try {
            check(active()) { "This command session has ended" }
            JSONObject().put("ok", true).put("value", syncCall(api, method, JSONArray(raw)) ?: JSONObject.NULL).toString()
        } catch (e: Throwable) {
            post { catalog.releaseText(textContext.owner) }
            JSONObject().put("ok", false).put("error", e.message).put("code", if (e is java.io.FileNotFoundException) "ENOENT" else "ENOTSUP").toString()
        }
    }
    private suspend fun asyncCall(api: String, method: String, a: JSONArray): Any? = when (api) {
        "clipboard" -> {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            when (method) {
                "copy" -> { val o = a.optJSONObject(0); val text = o?.optString("text") ?: a.optString(0)
                    clipboard.setPrimaryClip(ClipData.newPlainText(extension.listing.title, text)); catalog.runtimeStatus = "Copied to clipboard"; null }
                "readText" -> clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                "read" -> JSONObject().put("text", clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                "clear" -> { clipboard.setPrimaryClip(ClipData.newPlainText("", "")); null }
                else -> error("Clipboard paste into another app is unavailable on Android. Use Copy instead.")
            }
        }
        "storage" -> withContext(Dispatchers.IO) { synchronized(storage) {
            val values = JSONObject(storage.getString("local", "{}")!!)
            val result: Any? = when (method) {
                "get" -> values.opt(a.getString(0)); "all" -> values
                "set" -> { values.put(a.getString(0), a.opt(1)); null }
                "remove" -> { values.remove(a.getString(0)); null }
                "clear" -> { values.keys().asSequence().toList().forEach { values.remove(it) }; null }
                else -> error("Unsupported LocalStorage operation")
            }
            require(values.toString().length <= 4 * 1024 * 1024) { "Extension storage limit reached" }
            storage.edit().putString("local", values.toString()).apply(); result
        } }
        "cache" -> { val caches = JSONObject(storage.getString("caches", "{}")!!); val key = a.optString(0, "default")
            val values = caches.optJSONObject(key) ?: JSONObject()
            when (method) { "set" -> if (a.isNull(2)) values.remove(a.getString(1)) else values.put(a.getString(1), a.getString(2)); "clear" -> values.keys().asSequence().toList().forEach { values.remove(it) }; else -> error("Unsupported cache operation") }
            caches.put(key, values); require(caches.toString().length <= 4 * 1024 * 1024); storage.edit().putString("caches", caches.toString()).apply(); null
        }
        "feedback" -> { when(method) {
            "showHUD" -> catalog.runtimeStatus = a.optString(0)
            "showToast", "updateToast" -> { val o = a.optJSONObject(if (method == "showToast") 0 else 1); catalog.runtimeStatus = listOfNotNull(o?.optString("title"), o?.optString("message")).filter { it.isNotEmpty() }.joinToString(" · ") }
            "hideToast" -> Unit
            else -> error("This extension alert is unsupported on Android")
        }; 1 }
        "window" -> { when (method) { "close", "popToRoot" -> catalog.runtimeStatus = catalog.runtimeStatus.ifBlank { "Command completed" }; "clearSearchBar" -> Unit; else -> error("Change extension preferences from the command's Settings button") }; null }
        "system" -> when (method) {
            "selectedText" -> textContext.input?.read(textContext.owner,textContext.target)
                ?: throw TextInputRejected(TextInputProblem.UNAVAILABLE)
            "open" -> { openExtensionLink(context, a.getString(0)); null }
            "frontmostApplication", "applications", "selectedFinderItems" -> error("$method requires a desktop API unavailable on Android")
            else -> error("$method is not supported on Android")
        }
        "fetch" -> withContext(Dispatchers.IO) {
            val request = a.getJSONObject(0); val headers = request.optJSONObject("headers") ?: JSONObject()
            val body = request.optString("bodyBase64").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }
            val response = ExtensionHttp.request(request.getString("url"), request.optString("method", "GET"), headers.keys().asSequence().associateWith { headers.getString(it) }, body, 8 * 1024 * 1024)
            val responseHeaders = JSONObject()
            response.headers.forEach { (key, values) -> responseHeaders.put(key, values.joinToString(", ")) }
            JSONObject().put("status", response.status).put("statusText", response.message).put("url", response.url)
                .put("headers", responseHeaders).put("bodyBase64", Base64.encodeToString(response.bytes, Base64.NO_WRAP))
        }
        else -> error("$api.$method is not supported on Android. Shell commands, AppleScript, OAuth and native desktop helpers are unavailable.")
    }
    private fun safeFile(path: String, write: Boolean = false): File {
        val file = File(path).canonicalFile
        val dataRoot = support.canonicalFile
        val packageRoot = extension.directory.canonicalFile
        val isData = file == dataRoot || file.path.startsWith(dataRoot.path + File.separator)
        val isPackage = file == packageRoot || file.path.startsWith(packageRoot.path + File.separator)
        require(isData || (!write && isPackage)) { "Extensions can only access their own package and data folder" }; return file
    }
    private fun syncCall(api: String, method: String, a: JSONArray): Any? = when(api) {
        "os" -> when (method) {
            "cpus" -> { require(a.length() == 0) { "os.cpus does not accept paths or options" }; NodeCpuInfo.read().json() }
            else -> error("os.$method is not supported on Android")
        }
        "crypto" -> when (method) {
            "uuid" -> UUID.randomUUID().toString()
            "random" -> { val n = a.getInt(0); require(n in 0..65536); val bytes = ByteArray(n); SecureRandom().nextBytes(bytes); Base64.encodeToString(bytes, Base64.NO_WRAP) }
            "hash" -> { val alg = when(a.getString(0)) { "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; "sha512" -> "SHA-512"; "md5" -> "MD5"; else -> error("Unsupported hash") }; Base64.encodeToString(MessageDigest.getInstance(alg).digest(Base64.decode(a.getString(1), Base64.DEFAULT)), Base64.NO_WRAP) }
            else -> error("This cryptographic operation is unsupported")
        }
        "fs" -> when (method) {
            "exists" -> safeFile(a.getString(0)).exists()
            "stat" -> { val f = safeFile(a.getString(0)); if (!f.exists()) throw java.io.FileNotFoundException(f.name)
                JSONObject().put("_isFile", f.isFile).put("_isDirectory", f.isDirectory).put("_isSymbolicLink", false).put("size", f.length()).put("mtimeMs", f.lastModified()).put("mode", if(f.isFile) 33188 else 16877) }
            "readFile" -> { val f = safeFile(a.getString(0)); require(f.length() <= 16 * 1024 * 1024); Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) }
            "readdir" -> JSONArray(safeFile(a.getString(0)).listFiles().orEmpty().map { JSONObject().put("name", it.name).put("parentPath", it.parent).put("_isFile", it.isFile).put("_isDirectory", it.isDirectory) })
            "mkdir" -> safeFile(a.getString(0), true).mkdirs().let { null }
            "writeFile" -> { val f = safeFile(a.getString(0), true); val bytes = Base64.decode(a.getString(1), Base64.DEFAULT); require(bytes.size <= 4 * 1024 * 1024); f.parentFile!!.mkdirs(); if(a.optBoolean(2)) f.appendBytes(bytes) else f.writeBytes(bytes); null }
            "remove" -> safeFile(a.getString(0), true).deleteRecursively().let { null }
            "realpath" -> safeFile(a.getString(0)).canonicalPath
            else -> error("fs.$method is not supported on Android")
        }
        else -> error("$api.$method requires an unsupported desktop capability. Android cannot run shell commands or native Mac helpers.")
    }
}
