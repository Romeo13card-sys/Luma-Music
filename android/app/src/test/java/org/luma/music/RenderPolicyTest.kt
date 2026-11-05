package org.luma.music

import org.junit.Assert.*
import org.junit.Test

class RenderPolicyTest {
    @Test
    fun twoGbDeviceUsesBoundedEconomyProfile() {
        val profile = RenderPolicy.choose(false, 2L * 1024 * 1024 * 1024, 256)
        assertTrue(profile.economy)
        assertEquals(20, profile.pageSize)
        assertEquals(1000L, profile.uiIntervalMs)
    }

    @Test
    fun enormousRamDoesNotExpandCaps() {
        val regular = RenderPolicy.choose(false, 8L * 1024 * 1024 * 1024, 256)
        val enormous = RenderPolicy.choose(false, 2L * 1024 * 1024 * 1024 * 1024, 1024)
        assertEquals(regular, enormous)
        assertEquals(40, enormous.pageSize)
        assertTrue(RenderPolicy.choose(true, 16L * 1024 * 1024 * 1024, 256).economy)
    }
}
