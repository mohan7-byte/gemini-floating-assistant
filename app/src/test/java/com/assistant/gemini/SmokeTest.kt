package com.assistant.gemini
import org.junit.Assert.assertTrue
import org.junit.Test
class SmokeTest {
    @Test fun modelNamesUseGeminiPrefix() {
        assertTrue(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro").all { it.startsWith("gemini-") })
    }
}
