package org.luma.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthStateValidatorTest {
    private val createdAt = 1_000_000L
    private val validState = "one-time-random-state"

    @Test
    fun acceptsMatchingStateWithinTenMinuteWindow() {
        assertTrue(
            OAuthStateValidator.isValid(
                expected = validState,
                actual = validState,
                createdAtMs = createdAt,
                nowMs = createdAt + OAuthStateValidator.DEFAULT_MAX_AGE_MS,
            ),
        )
    }

    @Test
    fun rejectsMissingExpectedOrReturnedState() {
        assertFalse(OAuthStateValidator.isValid(null, validState, createdAt, createdAt))
        assertFalse(OAuthStateValidator.isValid("", "", createdAt, createdAt))
        assertFalse(OAuthStateValidator.isValid(validState, null, createdAt, createdAt))
    }

    @Test
    fun rejectsMismatchedState() {
        assertFalse(
            OAuthStateValidator.isValid(
                expected = validState,
                actual = "attacker-controlled-state",
                createdAtMs = createdAt,
                nowMs = createdAt + 1,
            ),
        )
    }

    @Test
    fun rejectsExpiredState() {
        assertFalse(
            OAuthStateValidator.isValid(
                expected = validState,
                actual = validState,
                createdAtMs = createdAt,
                nowMs = createdAt + OAuthStateValidator.DEFAULT_MAX_AGE_MS + 1,
            ),
        )
    }

    @Test
    fun rejectsStateCreatedInTheFuture() {
        assertFalse(
            OAuthStateValidator.isValid(
                expected = validState,
                actual = validState,
                createdAtMs = createdAt + 1,
                nowMs = createdAt,
            ),
        )
    }

    @Test
    fun rejectsNegativeMaximumAge() {
        assertFalse(
            OAuthStateValidator.isValid(
                expected = validState,
                actual = validState,
                createdAtMs = createdAt,
                nowMs = createdAt,
                maxAgeMs = -1,
            ),
        )
    }
}
