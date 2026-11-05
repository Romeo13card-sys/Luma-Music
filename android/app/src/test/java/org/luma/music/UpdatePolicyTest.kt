package org.luma.music

import org.json.JSONArray
import org.json.JSONObject
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
    fun selectsDeviceAbiBeforeUniversalAndRejectsOversizedApk() {
        fun asset(abi: String, size: Long = 100) =
            JSONObject().put("name", "Luma-Music-0.4.0-test-$abi.apk").put("size", size)
        val list =
            JSONArray().put(asset("universal")).put(asset("arm64-v8a")).put(asset("armeabi-v7a"))
        assertTrue(
            UpdatePolicy.apk(list, listOf("arm64-v8a", "armeabi-v7a"))
                .getString("name")
                .endsWith("arm64-v8a.apk")
        )
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.apk(JSONArray().put(asset("x86", UpdatePolicy.MAX_APK + 1)), listOf("x86"))
        }
    }

    @Test
    fun checksumRequiresExactUniqueFilenameAndDigest() {
        val hash = "a".repeat(64)
        assertEquals(hash, UpdatePolicy.checksum("$hash  ./test.apk\n", "test.apk"))
        for (text in
            listOf("$hash  other.apk", "bad  test.apk", "$hash  test.apk\n$hash  test.apk")) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdatePolicy.checksum(text, "test.apk")
            }
        }
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
    fun versionFilterDoesNotOfferOlderReleases() {
        assertTrue(UpdatePolicy.baseVersionComparison("v0.3.1-test.1", "0.4.0-test")!! < 0)
        assertTrue(UpdatePolicy.baseVersionComparison("v0.10.0-test.1", "0.4.0-test")!! > 0)
        assertEquals(0, UpdatePolicy.baseVersionComparison("v0.4.0-test.1", "0.4.0-test"))
        assertNull(UpdatePolicy.baseVersionComparison("unexpected", "0.4.0-test"))
    }
}
