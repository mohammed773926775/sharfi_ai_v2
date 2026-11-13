package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sharafi.offlineassistant.executor.SystemActionExecutor
import com.sharafi.offlineassistant.nlu.NluEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Sharafi Assistant", appName)
    }

    @Test
    fun `execute compound command via SystemActionExecutor plugins`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nlu = NluEngine()
        val executor = SystemActionExecutor(context)

        val parsed = nlu.parse("Turn on the flashlight and tell me the battery")
        val report = executor.executeCompound(parsed.intents)

        assertEquals(2, report.results.size)
        assertTrue(report.results.all { it.success })
        assertTrue(executor.hardwareState.value.isFlashlightOn)
        assertTrue(report.combinedSpokenResponseEn.contains("Flashlight"))
        assertTrue(report.combinedSpokenResponseEn.contains("battery"))
    }
}
