package org.luma.music

/** Formats elapsed player time without Android dependencies. */
internal object PlayerTimeFormatter {
    fun format(positionMs: Int, durationMs: Int): String =
        formatTime(positionMs) + "  /  " + formatTime(durationMs)

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
