package org.luma.music

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Shared native building blocks for Android screens.
 *
 * Screen-specific navigation and business actions remain with their host/controller;
 * this object owns only predictable layout primitives and typography.
 */
internal object LumaScreenComponents {
    fun text(
        context: Context,
        preferences: SharedPreferences,
        value: String,
        size: Float = 16f,
        color: Int = AppThemes.active.text,
        bold: Boolean = false,
        raw: Boolean = false,
    ): LumaTextView =
        LumaTextView(context).apply {
            localize = !raw
            text = value
            textSize = size * preferences.getInt("font_size", 100) / 100f
            setTextColor(color)
            letterSpacing = if (bold && size >= 24f) -0.025f else if (size <= 12f) 0.035f else 0f
            val density = resources.displayMetrics.density
            setLineSpacing(density.toInt().toFloat(), 1f)
            val verticalPadding = (density * if (size >= 28f) 3 else 5).toInt()
            setPadding(0, verticalPadding, 0, verticalPadding)
            if (bold) typeface = LumaDesign.typeface("sans-serif-medium")
            includeFontPadding = false
        }

    fun column(context: Context): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun gap(context: Context, target: LinearLayout, heightDp: Int = 12) {
        val height = (heightDp * context.resources.displayMetrics.density).toInt()
        target.addView(View(context), LinearLayout.LayoutParams(1, height))
    }

    fun panel(context: Context, target: LinearLayout): LinearLayout {
        val density = context.resources.displayMetrics.density
        val panel = column(context).apply {
            val horizontal = (20 * density).toInt()
            val vertical = (18 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            background = surface((LumaDesign.CARD_RADIUS_DP * density).toInt().toFloat())
        }
        val bottomMargin = ((LumaDesign.ROW_GAP_DP + 4) * density).toInt()
        target.addView(
            panel,
            LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin = bottomMargin },
        )
        return panel
    }
}
