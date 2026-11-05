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
        AppPalette(0xFF121212.toInt(), 0xFFF5F5F5.toInt(), 0xFFB3B3B3.toInt(), 0xFFFFE04A.toInt())
    val light =
        AppPalette(
            0xFFFAFAFA.toInt(),
            0xFF191919.toInt(),
            0xFF626262.toInt(),
            0xFF7C5700.toInt(),
            light = true,
        )
    val oled =
        AppPalette(
            0xFF000000.toInt(),
            0xFFF5F5F5.toInt(),
            0xFFB3B3B3.toInt(),
            0xFFFFE04A.toInt(),
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
