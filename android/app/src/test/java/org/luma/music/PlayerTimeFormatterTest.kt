package org.luma.music

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeFormatterTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00  /  3:07", PlayerTimeFormatter.format(0, 187_000))
        assertEquals("12:34  /  59:59", PlayerTimeFormatter.format(754_000, 3_599_000))
    }

    @Test
    fun keepsMinutesBeyondOneHour() {
        assertEquals("60:00  /  125:09", PlayerTimeFormatter.format(3_600_000, 7_509_000))
    }

    @Test
    fun clampsNegativeTimesToZero() {
        assertEquals("0:00  /  0:00", PlayerTimeFormatter.format(-1, -10_000))
    }

    @Test
    fun truncatesSubsecondRemainders() {
        assertEquals("1:02  /  1:03", PlayerTimeFormatter.format(62_999, 63_999))
    }
}
