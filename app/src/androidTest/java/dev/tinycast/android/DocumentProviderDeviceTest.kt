package dev.tinycast.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DocumentProviderDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val uri=Uri.parse("content://dev.droidcast.audit/document/slow-fixture")
    private var original=emptyMap<String,Any?>()
    private val providers=mutableListOf<SlowProvider>()
    @Before fun prepare() {
        original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15000) { !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.store.reset() }
    }
    @After fun restore() {
        providers.forEach { it.release.countDown() }
        restorePalettePreferences(context,original)
    }
    private class SlowProvider:ContentProvider() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val finished=CountDownLatch(1)
        val offMain=AtomicBoolean(false); val cancellation=CountDownLatch(1)
        private fun waitForRelease() {
            offMain.set(Looper.myLooper()!=Looper.getMainLooper()); entered.countDown()
            while(release.count>0) try { release.await(30,TimeUnit.MILLISECONDS) } catch (_:InterruptedException) { /* Model an uncooperative binder/provider. */ }
            finished.countDown()
        }
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
            waitForRelease()
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Audit provider document.txt")) }
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?,signal:CancellationSignal?):Cursor {
            signal?.setOnCancelListener { cancellation.countDown() }
            return query(uri,projection,selection,selectionArgs,sortOrder)
        }
        override fun getType(uri:Uri):String { waitForRelease(); return "text/plain" }
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    }
    private fun reader(timeout:Long=3000):Pair<SlowProvider,DocumentProviderQueries> {
        val provider=SlowProvider(); providers.add(provider)
        return provider to DocumentProviderQueries(ContentResolver.wrap(provider),timeout)
    }
    private fun await(latch:CountDownLatch) { assertTrue("Expected provider event",latch.await(5,TimeUnit.SECONDS)) }
    private fun capture(name:String) {
        compose.waitForIdle()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val dir=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(dir,"$name.xml")); assertTrue(device.takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun slowDisplayNameLeavesSearchResponsiveAndUpdatesTheTrackedReference() {
        val (provider,queries)=reader()
        compose.activityRule.scenario.onActivity { it.rememberDocument(uri,queries) }
        await(provider.entered); assertTrue(provider.offMain.get())
        compose.onNodeWithTag("search").performTextInput("provider")
        compose.onNodeWithTag("search").assertTextEquals("provider")
        assertTrue(compose.activity.store.files.any { it.body==uri.toString() && it.title=="Document" })
        capture("provider-name-pending")
        provider.release.countDown()
        compose.waitUntil(5000) { compose.activity.store.files.any { it.body==uri.toString() && it.title=="Audit provider document.txt" } }
    }
    @Test fun providerTimeoutLeavesTheDocumentTrackedAndSearchUsable() {
        val (provider,queries)=reader(350)
        compose.activityRule.scenario.onActivity { it.rememberDocument(uri,queries) }
        await(provider.entered)
        compose.waitUntil(5000) { compose.activity.store.message?.contains("name could not be read")==true }
        assertTrue(provider.offMain.get()); await(provider.cancellation)
        assertTrue(compose.activity.store.files.any { it.body==uri.toString() })
        compose.onNodeWithTag("search").performTextInput("still responsive")
        compose.onNodeWithTag("search").assertTextEquals("still responsive")
        capture("provider-name-timeout")
    }
    @Test fun timedOutMimeLookupDoesNotLaunchAndAllowsRetry() {
        val (provider,queries)=reader(350)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val launches=CopyOnWriteArrayList<Intent>()
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_VIEW)return null
                launches.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        var savedId=""
        try {
            compose.activityRule.scenario.onActivity {
                savedId=it.store.save("files","Audit retry fixture",uri.toString())
                it.openRecentDocument(requireNotNull(it.store.command(savedId)),queries)
            }
            await(provider.entered)
            compose.waitUntil(5000) { compose.activity.store.message?.contains("Storage provider timed out")==true }
            assertTrue(provider.offMain.get());assertEquals(0,monitor.hits);assertTrue(launches.isEmpty())
            assertTrue(compose.activity.store.files.any { it.id==savedId && it.body==uri.toString() })
            // Let the abandoned provider finish before changing query/route:
            // another navigation guard must not mask a stale first launch.
            provider.release.countDown();await(provider.finished);compose.waitForIdle()
            assertEquals("",compose.activity.store.query)
            assertEquals("The timed-out request must never launch after release",0,monitor.hits)
            assertTrue(launches.isEmpty())
            compose.onNodeWithTag("search").performTextInput("retry")
            compose.onNodeWithTag("search").assertTextEquals("retry")
            capture("provider-mime-timeout")
            compose.activityRule.scenario.onActivity {
                val retained=it.store.results().single { command -> command.id==savedId }
                assertEquals(uri.toString(),retained.payload)
                it.openRecentDocument(retained,queries)
            }
            compose.waitUntil(5000) { launches.isNotEmpty() }
            instrumentation.waitForIdleSync()
            assertEquals("Only the successful retry may launch",1,launches.size)
            assertEquals(1,monitor.hits)
            val launched=launches.single()
            assertEquals(Intent.ACTION_VIEW,launched.action)
            assertEquals(uri,launched.data);assertEquals("text/plain",launched.type)
            assertTrue(launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val evidence=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
            File(evidence,"provider-mime-retry.json").writeText(org.json.JSONObject()
                .put("timed_out_request_launches_after_release",0).put("retry_launches",launches.size)
                .put("retained_document_reselected",true).put("action",launched.action)
                .put("uri",launched.dataString).put("mime",launched.type)
                .put("read_grant",true).put("destination","Instrumentation monitor; real external viewer not launched by this controlled provider test").toString(2))
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun newerCommandCancelsPendingFileLaunch() {
        val (provider,queries)=reader()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW),Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true)
        try {
            compose.activityRule.scenario.onActivity { it.openRecentDocument(Command("audit-file","Audit document","",Kind.FILE,"file",payload=uri.toString()),queries) }
            await(provider.entered)
            compose.activityRule.scenario.onActivity { it.execute(Command("emoji","Emoji","")) }
            provider.release.countDown(); await(provider.finished); compose.waitForIdle()
            compose.onNodeWithTag("emoji-search").assertIsDisplayed(); assertEquals(0,monitor.hits)
            capture("provider-cancelled-open")
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
