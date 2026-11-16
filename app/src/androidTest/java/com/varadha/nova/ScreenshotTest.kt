package com.varadha.nova

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Opens every screen and saves screenshots so the design can be checked without a phone. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val out get() = File(ctx.filesDir, "shots").apply { mkdirs() }

    @Before
    fun seed() {
        Prefs.prefs(ctx).edit().clear().commit()
        Prefs.setUserName(ctx, "Varadha")
        Prefs.remember(ctx, "Arun's UPI ID is arun@okaxis")
        Prefs.remember(ctx, "Amma is saved as 'Amma' in contacts")
    }

    @Test
    fun screens() {
        capture("light")
        inst.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        capture("dark")
        inst.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }

    private fun capture(theme: String) {
        // First run: no key yet
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            shot("${theme}_01_welcome")
            sc.onActivity { it.demo() }
            shot("${theme}_02_chat")
            sc.onActivity { it.demoListening("Amma ku call pannu") }
            shot("${theme}_05_listening")
        }
        Prefs.setApiKey(ctx, "test-key")
        Prefs.setModel(ctx, "gemini-flash-latest")
        ActivityScenario.launch(SettingsActivity::class.java).use { sc ->
            shot("${theme}_03_settings")
            sc.onActivity { (it.findViewById<View>(R.id.toolbar).parent as View).let { root ->
                (root as android.view.ViewGroup).getChildAt(1).scrollTo(0, 10_000)
            } }
            shot("${theme}_04_settings_bottom")
        }
        Prefs.setApiKey(ctx, "")
    }

    private fun shot(name: String) {
        Thread.sleep(1500)
        inst.waitForIdleSync()
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("unused")
    private fun unused(i: Intent) = i
}
