package org.luma.music

/**
 * Validates the one-time state value returned by the Yandex browser OAuth flow.
 *
 * Keep this policy free of Android dependencies so expiry and replay protection
 * can be covered by local JVM tests.
 */
internal object OAuthStateValidator {
    const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L

    fun isValid(
        expected: String?,
        actual: String?,
        createdAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): Boolean {
        if (expected.isNullOrBlank() || actual != expected || maxAgeMs < 0) return false
        val ageMs = nowMs - createdAtMs
        return ageMs in 0..maxAgeMs
    }
}
