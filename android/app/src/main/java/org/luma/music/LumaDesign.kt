package org.luma.music

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/**
 * Shared visual tokens for the Luma 2.0 native interface.
 * Components stay opaque and cheap to draw on low-memory Android devices.
 */
object LumaDesign {
    const val PAGE_INSET_DP = 20
    const val CARD_RADIUS_DP = 22
    const val CONTROL_RADIUS_DP = 18
    const val TOUCH_TARGET_DP = 48
    const val SECTION_GAP_DP = 18
    const val ROW_GAP_DP = 10

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun typeface(weight: String): Typeface =
        Typeface.create(weight, Typeface.NORMAL)

    fun card(radius: Float = CARD_RADIUS_DP.toFloat(), accent: Boolean = false): GradientDrawable {
        val palette = AppThemes.active
        val fill = if (accent) palette.accent else when {
            palette.light -> 0xFFFFFFFF.toInt()
            palette.oled -> 0xFF090B0F.toInt()
            else -> 0xFF171C24.toInt()
        }
        val outline = when {
            accent -> if (palette.light) 0xFF8A6200.toInt() else 0xFFFFE58A.toInt()
            palette.light -> 0xFFE4E8EF.toInt()
            palette.oled -> 0xFF20242B.toInt()
            else -> 0xFF2A323F.toInt()
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(1, outline)
        }
    }

    fun trackCard(playing: Boolean, radius: Float = CARD_RADIUS_DP.toFloat()): GradientDrawable {
        val palette = AppThemes.active
        val fill = when {
            palette.light -> 0xFFFFFFFF.toInt()
            palette.oled -> 0xFF090B0F.toInt()
            else -> 0xFF171C24.toInt()
        }
        val outline = if (playing) palette.accent else when {
            palette.light -> 0xFFE4E8EF.toInt()
            palette.oled -> 0xFF20242B.toInt()
            else -> 0xFF2A323F.toInt()
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(if (playing) 2 else 1, outline)
        }
    }

    fun heroCard(radius: Float = CARD_RADIUS_DP.toFloat()): GradientDrawable {
        val palette = AppThemes.active
        val start = if (palette.light) 0xFFFFE9A6.toInt() else 0xFFFFD84D.toInt()
        val end = if (palette.light) 0xFFF3C65A.toInt() else 0xFFE7AE32.toInt()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(1, if (palette.light) 0xFFD9A93F.toInt() else 0xFFFFE58A.toInt())
        }
    }

    fun contentColor(accent: Boolean = false): Int {
        val palette = AppThemes.active
        return if (accent) {
            if (palette.light) 0xFF17120A.toInt() else 0xFF17130A.toInt()
        } else palette.text
    }

    fun secondaryColor(): Int = AppThemes.active.muted
}
