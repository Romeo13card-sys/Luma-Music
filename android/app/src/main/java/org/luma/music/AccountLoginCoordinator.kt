/**
 * Coordinates credential validation and persistence without owning Android UI or token storage.
 * The host supplies account/session mutations and the existing generation-aware request pipeline.
 */
package org.luma.music

internal class AccountLoginCoordinator(
    private val updateCredential: (String) -> Unit,
    private val invalidateRequests: () -> Unit,
    private val setDemo: (Boolean) -> Unit,
    private val saveCredential: (String) -> Unit,
    private val requestAccount: ((() -> Unit)?, (Any) -> Unit) -> Unit,
    private val showStatus: (String) -> Unit,
    private val openHome: () -> Unit,
) {
    fun authenticate(
        credential: String,
        finished: (() -> Unit)? = null,
        clearCredentialOnFailure: Boolean = false,
        failureMessage: String,
        onAuthenticated: (() -> Unit)? = null,
    ) {
        updateCredential(credential)
        invalidateRequests()
        requestAccount(finished) {
            try {
                saveCredential(credential)
                setDemo(false)
                if (onAuthenticated != null) onAuthenticated() else openHome()
            } catch (_: Exception) {
                if (clearCredentialOnFailure) updateCredential("")
                showStatus(failureMessage)
            }
        }
    }
}
