package org.luma.music

/** Immutable palettes; no Context, images or per-frame allocations. */
data class AppPalette(
    val background: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val light: Boolean = false,
    val oled: Boolean = false,
)

object AppThemes {
    val dark =
        AppPalette(0xFF09131A.toInt(), 0xFFEFF7F8.toInt(), 0xFFACC1C9.toInt(), 0xFFB6F4DD.toInt())
    val light =
        AppPalette(
            0xFFF2F7F6.toInt(),
            0xFF16262D.toInt(),
            0xFF4E626A.toInt(),
            0xFF165C50.toInt(),
            light = true,
        )
    val oled =
        AppPalette(
            0xFF000000.toInt(),
            0xFFEFF7F8.toInt(),
            0xFFACC1C9.toInt(),
            0xFFB6F4DD.toInt(),
            oled = true,
        )

    fun resolve(mode: String, night: Boolean) =
        when (mode) {
            "light" -> light
            "dark" -> dark
            "oled" -> oled
            else -> if (night) dark else light
        }

    var active = dark
}
