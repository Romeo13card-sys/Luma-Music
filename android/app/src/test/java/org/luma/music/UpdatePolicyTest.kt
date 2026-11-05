package org.luma.music

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun rejectsUntrustedRedirectsAndCredentialUrls() {
        for (url in
            listOf(
                "http://github.com/a",
                "https://github.com.evil.test/a",
                "https://user@api.github.com/a",
                "https://api.github.com:8443/a",
                "file:///a.apk",
            )) assertFalse(url, UpdatePolicy.trusted(url))
        assertTrue(
            UpdatePolicy.trusted("https://release-assets.githubusercontent.com/a?sig=example")
        )
    }

    @Test
    fun upgradeRequiresSameIdentityNewerVersionAndIdenticalCertificate() {
        UpdatePolicy.upgrade(
            "org.luma.music",
            "org.luma.music",
            6,
            7,
            30,
            33,
            setOf("cert"),
            setOf("cert"),
        )
        for (test in 0..4) assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.upgrade(
                "org.luma.music",
                if (test == 0) "other.app" else "org.luma.music",
                6,
                if (test == 1) 6 else 7,
                if (test == 2) 36 else 30,
                33,
                if (test == 3) emptySet() else setOf("cert"),
                if (test == 4) setOf("other") else setOf("cert"),
            )
        }
    }

    @Test
    fun automaticCheckIsDailyAndRecoversFromClockChanges() {
        assertTrue(UpdatePolicy.dailyCheckDue(1000, 0))
        assertFalse(UpdatePolicy.dailyCheckDue(1001, 1000))
        assertTrue(UpdatePolicy.dailyCheckDue(1000 + 24L * 60 * 60 * 1000, 1000))
        assertTrue(UpdatePolicy.dailyCheckDue(900, 1000))
    }
}
