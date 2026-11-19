package dev.tinycast.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.tinycast.android.extensions.ExtensionCatalog
import dev.tinycast.android.extensions.StoreExtension
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class HeldCatalogFixture(private val context: Context) : AutoCloseable {
    val id = "ext:startup-probe/fixture"
    val title = "Owned startup command"
    val uiAccesses = AtomicInteger()
    val workerAccesses = AtomicInteger()
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "catalog-startup-").toFile()
    private val preferences = ConcurrentHashMap.newKeySet<String>()
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val application = object : Application() {
        init { attachBaseContext(context.applicationContext) }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${directory.name}-$name"
            preferences.add(isolatedName)
            return super.getSharedPreferences(isolatedName, mode)
        }

        override fun getFilesDir(): File {
            if (Thread.currentThread().stackTrace.any { it.className.startsWith(ExtensionCatalog::class.java.name) }) {
                if (Looper.myLooper() == Looper.getMainLooper()) uiAccesses.incrementAndGet()
                else {
                    workerAccesses.incrementAndGet()
                    entered.countDown()
                    check(released.await(45, TimeUnit.SECONDS))
                }
            }
            return directory
        }
    }

    fun writeCommand() {
        val extension = File(directory, "extensions/startup-probe")
        check(extension.mkdirs())
        val listing = StoreExtension("startup-probe", "startup-probe", "Owned startup probe", "", "Tester", "tester", "", "", "", 1)
        File(extension, ".tinycast-store.json").writeText(listing.json().toString())
        File(extension, "package.json").writeText("""{"commands":[{"name":"fixture","title":${JSONObject.quote(title)},"mode":"view"}]}""")
    }

    fun createStore(holder: ViewModelStore): PaletteStore =
        ViewModelProvider(holder, ViewModelProvider.AndroidViewModelFactory(application))[PaletteStore::class.java]

    fun awaitWorker(): Boolean = entered.await(10, TimeUnit.SECONDS)
    fun release() = released.countDown()

    override fun close() {
        release()
        preferences.forEach(context::deleteSharedPreferences)
        check(directory.deleteRecursively())
    }
}
