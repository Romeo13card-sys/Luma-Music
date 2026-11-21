package org.luma.music

import org.junit.Assert.*
import org.junit.Test

class AccountLoginCoordinatorTest {
    @Test
    fun successfulAuthenticationPersistsBeforeNavigating() {
        var token = ""
        var saved = ""
        var generation = 0
        var demo = true
        var finishedCount = 0
        var authenticatedCount = 0
        var homeCount = 0
        var requestFinished: (() -> Unit)? = null
        var requestDone: ((Any) -> Unit)? = null
        var status = ""

        val coordinator = AccountLoginCoordinator(
            updateCredential = { token = it },
            invalidateRequests = { generation++ },
            setDemo = { demo = it },
            saveCredential = { saved = it },
            requestAccount = { finished, done ->
                requestFinished = finished
                requestDone = done
            },
            showStatus = { status = it },
            openHome = { homeCount++ },
        )

        coordinator.authenticate(
            credential = "fixture-token",
            finished = { finishedCount++ },
            onAuthenticated = { authenticatedCount++ },
            failureMessage = "save failed",
        )

        assertEquals("fixture-token", token)
        assertEquals(1, generation)
        assertNotNull(requestFinished)
        assertNotNull(requestDone)
        requestFinished?.invoke()
        assertEquals(1, finishedCount)
        requestDone?.invoke(Unit)

        assertEquals("fixture-token", saved)
        assertFalse(demo)
        assertEquals(1, authenticatedCount)
        assertEquals(0, homeCount)
        assertEquals("", status)
    }

    @Test
    fun oauthSaveFailureClearsCredentialAndDoesNotNavigate() {
        var token = ""
        var demo = true
        var homeCount = 0
        var status = ""
        var requestDone: ((Any) -> Unit)? = null

        val coordinator = AccountLoginCoordinator(
            updateCredential = { token = it },
            invalidateRequests = {},
            setDemo = { demo = it },
            saveCredential = { throw IllegalStateException("keystore unavailable") },
            requestAccount = { _, done -> requestDone = done },
            showStatus = { status = it },
            openHome = { homeCount++ },
        )

        coordinator.authenticate(
            credential = "oauth-token",
            clearCredentialOnFailure = true,
            failureMessage = "Не удалось сохранить токен",
        )
        requestDone?.invoke(Unit)

        assertEquals("", token)
        assertTrue(demo)
        assertEquals(0, homeCount)
        assertEquals("Не удалось сохранить токен", status)
    }

    @Test
    fun manualTokenSaveFailureKeepsEnteredCredentialForRetry() {
        var token = ""
        var status = ""
        var requestDone: ((Any) -> Unit)? = null

        val coordinator = AccountLoginCoordinator(
            updateCredential = { token = it },
            invalidateRequests = {},
            setDemo = {},
            saveCredential = { throw IllegalStateException("keystore unavailable") },
            requestAccount = { _, done -> requestDone = done },
            showStatus = { status = it },
            openHome = {},
        )

        coordinator.authenticate(
            credential = "manual-token",
            failureMessage = "Не удалось защитить токен. Попробуйте ещё раз.",
        )
        requestDone?.invoke(Unit)

        assertEquals("manual-token", token)
        assertEquals("Не удалось защитить токен. Попробуйте ещё раз.", status)
    }
}
