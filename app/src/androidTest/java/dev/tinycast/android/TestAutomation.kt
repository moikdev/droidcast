package dev.tinycast.android

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator

/** Match UiDevice's connection mode instead of implicitly reconnecting with flags=0. */
internal fun testAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation()
    .getUiAutomation(Configurator.getInstance().uiAutomationFlags)
