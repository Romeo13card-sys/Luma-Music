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
        AppPalette(0xFF0B0E12.toInt(), 0xFFF2F4F7.toInt(), 0xFF9AA4B2.toInt(), 0xFFFFD84D.toInt())
    val light =
        AppPalette(
            0xFFF4F6FA.toInt(),
            0xFF171B22.toInt(),
            0xFF687180.toInt(),
            0xFF8A6200.toInt(),
            light = true,
        )
    val oled =
        AppPalette(
            0xFF000000.toInt(),
            0xFFF2F4F7.toInt(),
            0xFF9AA4B2.toInt(),
            0xFFFFD84D.toInt(),
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
