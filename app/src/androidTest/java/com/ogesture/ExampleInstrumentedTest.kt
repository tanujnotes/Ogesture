package com.ogesture

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test. The debug build adds a `.debug` applicationId
        // suffix so it installs alongside the release app, so accept either form.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = appContext.packageName
        assertTrue(
            "package should be com.ogesture or its debug variant, was $pkg",
            pkg == "com.ogesture" || pkg == "com.ogesture.debug",
        )
    }
}