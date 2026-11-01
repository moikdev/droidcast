package dev.tinycast.android

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Diagnostic only: recording a rejected renderer is not extension-execution certification. */
@RunWith(AndroidJUnit4::class)
class WebViewCompatibilityDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var fixture:File?=null
    private var support:File?=null
    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { it.store.extensions.close();it.store.dialog=null }
        fixture?.deleteRecursively();support?.deleteRecursively()
    }
    @Test fun recordActualProviderRuntimeOutcomeAndCapturePaths() {
        compose.waitUntil(15000) { !compose.activity.store.loading }
        val id="compatibility-probe-"+UUID.randomUUID()
        fixture=File(context.cacheDir,id).apply { mkdirs() }
        support=File(context.filesDir,"extension-data/$id");assertFalse(support!!.exists())
        File(fixture,"fixture.js").writeText("""
            const React = require('react');const { List } = require('@raycast/api');
            module.exports.default = function() { return React.createElement(List, {},
              React.createElement(List.Item, {id:'compatible',title:'Compatibility fixture row'})); };
        """.trimIndent())
        val listing=StoreExtension(id,id,"WebView compatibility fixture","","Fixture","fixture","https://example.com","","",1)
        val installed=InstalledExtension(listing,fixture!!,JSONObject("""{"commands":[{"name":"fixture","title":"WebView compatibility fixture","mode":"view"}]}"""))
        val started=SystemClock.elapsedRealtime()
        compose.activityRule.scenario.onActivity { it.store.openExtension(installed,installed.commands.single()) }
        compose.waitUntil(30000) { compose.activity.store.extensions.runtimeTree!=null || compose.activity.store.extensions.runtimeError!=null }
        compose.waitForIdle()
        val provider=WebView.getCurrentWebViewPackage()
        val directory=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.dumpWindowHierarchy(File(directory,"webview-compatibility.xml"))
        val regular=device.takeScreenshot(File(directory,"webview-compatibility-uiautomation.png"))
        val screenshot=File(directory,"webview-compatibility-shell.png")
        ParcelFileDescriptor.AutoCloseInputStream(testAutomation().executeShellCommand("screencap -p")).use { input ->
            screenshot.outputStream().use { output ->
                val buffer=ByteArray(8192);var total=0
                while(true) { val count=input.read(buffer);if(count<0)break;total+=count;check(total<=16*1024*1024);output.write(buffer,0,count) }
            }
        }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(screenshot.path,bounds)
        assertTrue("A real shell screencap PNG is available",bounds.outWidth>0 && bounds.outHeight>0)
        File(directory,"webview-compatibility.json").writeText(JSONObject()
            .put("diagnostic_only",true).put("api",android.os.Build.VERSION.SDK_INT)
            .put("provider_package",provider?.packageName).put("provider_version",provider?.versionName)
            .put("runtime_error",compose.activity.store.extensions.runtimeError ?: JSONObject.NULL)
            .put("rendered_fixture_row",compose.activity.store.extensions.runtimeTree?.toString()?.contains("Compatibility fixture row")==true)
            .put("initialization_observation_ms",SystemClock.elapsedRealtime()-started)
            .put("uiautomation_screenshot",regular).put("shell_screenshot",true)
            .put("width",bounds.outWidth).put("height",bounds.outHeight).toString(2))
    }
}
