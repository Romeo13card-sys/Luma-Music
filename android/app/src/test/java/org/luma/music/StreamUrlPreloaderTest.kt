package org.luma.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlPreloaderTest {
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
