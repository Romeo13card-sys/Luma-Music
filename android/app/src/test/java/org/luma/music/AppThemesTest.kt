package org.luma.music

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class AppThemesTest {
    @Test
    fun systemThemeFollowsAndroidAndOledRemainsBlack() {
        assertEquals(AppThemes.light, AppThemes.resolve("system", false))
        assertEquals(AppThemes.dark, AppThemes.resolve("system", true))
        assertEquals(AppThemes.dark, AppThemes.resolve("dark", false))
        assertEquals(0xFF000000.toInt(), AppThemes.resolve("oled", false).background)
    }

    @Test
    fun textAndSecondaryLabelsHaveReadableContrast() {
        fun luminance(color: Int): Double {
            fun c(shift: Int): Double {
                val v = ((color shr shift) and 255) / 255.0
                return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return .2126 * c(16) + .7152 * c(8) + .0722 * c(0)
        }
        for (palette in listOf(AppThemes.dark, AppThemes.light, AppThemes.oled)) {
            for (text in listOf(palette.text, palette.muted, palette.accent)) {
                val bg = luminance(palette.background)
                val fg = luminance(text)
                assertTrue((maxOf(bg, fg) + .05) / (minOf(bg, fg) + .05) >= 4.5)
            }
        }
    }
}
