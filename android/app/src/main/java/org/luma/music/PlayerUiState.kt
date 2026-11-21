package org.luma.music

/**
 * Holds transient player presentation state owned by MainActivity.
 *
 * Playback itself remains in PlaybackService; this class only prevents redundant
 * control updates and keeps periodic progress refreshes from fighting a user seek.
 */
internal class PlayerUiState {
    private var lastPlaying: Boolean? = null

    var isSeeking: Boolean = false
        private set

    fun playbackStateChanged(isPlaying: Boolean): Boolean {
        if (lastPlaying == isPlaying) return false
        lastPlaying = isPlaying
        return true
    }

    fun beginSeeking() {
        isSeeking = true
    }

    fun finishSeeking() {
        isSeeking = false
    }

    fun resetPlaybackObservation() {
        lastPlaying = null
    }
}
