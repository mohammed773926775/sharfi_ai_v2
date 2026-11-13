package com.example

import com.sharafi.offlineassistant.nlu.IntentDomain
import com.sharafi.offlineassistant.nlu.NluEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    private val nluEngine = NluEngine()

    @Test
    fun compoundEnglishCommand_parsesFlashlightAndBattery() {
        val result = nluEngine.parse("Turn on the flashlight and tell me the battery")
        assertTrue(result.isCompound)
        assertEquals(2, result.intents.size)
        assertEquals(IntentDomain.FLASHLIGHT, result.intents[0].domain)
        assertEquals("ON", result.intents[0].parameters["state"])
        assertEquals(IntentDomain.BATTERY, result.intents[1].domain)
    }

    @Test
    fun compoundArabicCommand_parsesFlashlightAndBattery() {
        val result = nluEngine.parse("شغل الكشاف وكم نسبة البطارية")
        assertTrue(result.isCompound)
        assertTrue(result.isArabic)
        assertEquals(2, result.intents.size)
        assertEquals(IntentDomain.FLASHLIGHT, result.intents[0].domain)
        assertEquals("ON", result.intents[0].parameters["state"])
        assertEquals(IntentDomain.BATTERY, result.intents[1].domain)
    }

    @Test
    fun alarmAndTimerAndVolume_parseSlotsAccurately() {
        val compound = nluEngine.parse("Set alarm for 7:30 am and set a timer for 5 minutes and set volume to 80 percent")
        assertEquals(3, compound.intents.size)
        assertEquals(IntentDomain.ALARM, compound.intents[0].domain)
        assertEquals("7", compound.intents[0].parameters["hour"])
        assertEquals("30", compound.intents[0].parameters["minute"])

        assertEquals(IntentDomain.TIMER, compound.intents[1].domain)
        assertEquals("300", compound.intents[1].parameters["seconds"])

        assertEquals(IntentDomain.VOLUME, compound.intents[2].domain)
        assertEquals("80", compound.intents[2].parameters["level"])
    }
}
