package org.luma.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlPreloaderTest {
    @Test
    fun warmupKeysAreScopedByAccountAndQualityWithoutStoringTokens() {
        val first = streamWarmupKey("123", 320, "account-a")
        assertTrue(first == streamWarmupKey("123", 320, "account-a"))
        assertFalse(first == streamWarmupKey("123", 128, "account-a"))
        assertFalse(first == streamWarmupKey("123", 320, "account-b"))
        assertFalse(first.contains("account-a"))
    }

    @Test
    fun acceptsOnlyNonEmptyAsciiDecimalTrackIds() {
        assertTrue(isAsciiNumericTrackId("0"))
        assertTrue(isAsciiNumericTrackId("1234567890"))

        assertFalse(isAsciiNumericTrackId(""))
        assertFalse(isAsciiNumericTrackId("１２３"))
        assertFalse(isAsciiNumericTrackId("١٢٣"))
        assertFalse(isAsciiNumericTrackId("²"))
        assertFalse(isAsciiNumericTrackId("12/3"))
        assertFalse(isAsciiNumericTrackId(" 123"))
    }
}
