package org.luma.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {
    @Test
    fun playbackControlsUpdateOnlyWhenPlayingStateChanges() {
        val state = PlayerUiState()

        assertTrue(state.playbackStateChanged(false))
        assertFalse(state.playbackStateChanged(false))
        assertTrue(state.playbackStateChanged(true))
        assertFalse(state.playbackStateChanged(true))
        assertTrue(state.playbackStateChanged(false))
    }

    @Test
    fun resetAllowsCurrentPlaybackStateToBeObservedAgain() {
        val state = PlayerUiState()
        state.playbackStateChanged(true)

        state.resetPlaybackObservation()

        assertTrue(state.playbackStateChanged(true))
    }

    @Test
    fun seekingSuppressesPeriodicProgressSynchronizationUntilFinished() {
        val state = PlayerUiState()

        assertFalse(state.isSeeking)
        state.beginSeeking()
        assertTrue(state.isSeeking)

        state.finishSeeking()

        assertFalse(state.isSeeking)
    }
}
