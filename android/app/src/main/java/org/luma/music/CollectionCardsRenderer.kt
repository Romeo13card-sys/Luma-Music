package org.luma.music

import android.content.Context
import android.widget.LinearLayout

/** Shared collection shortcuts used by Home and Library screens. */
internal class CollectionCardsRenderer(
    private val context: Context,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val textColor: Int,
    private val mutedColor: Int,
    private val accentColor: Int,
    private val openCollection: (String) -> Unit,
    private val openOffline: () -> Unit,
    private val openSpotify: () -> Unit,
) {
    fun render(target: LinearLayout) {
        fun card(
            parent: LinearLayout,
            label: String,
            description: String,
            icon: Int,
            click: () -> Unit,
        ) {
            val item = column().apply {
                minimumHeight = dp(116)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = surface(dp(18).toFloat())
                isFocusable = true
                setOnClickListener { click() }
            }
            item.addView(
                MusicUi.icon(context, icon, label, accentColor) { click() },
                LinearLayout.LayoutParams(dp(40), dp(36)),
            )
            item.addView(text(label, 17f, textColor, true, false).apply { maxLines = 2 })
            item.addView(
                text(description, 12f, mutedColor, false, false).apply {
                    maxLines = 2
                    setPadding(0, 0, 0, 0)
                },
            )
            parent.addView(
                item,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                },
            )
        }

        val first = row()
        target.addView(first)
        card(first, "Избранное", "Любимые треки", R.drawable.luma_heart) {
            openCollection("likes")
        }
        card(first, "Не нравится", "Ваши отметки", R.drawable.luma_dislike) {
            openCollection("dislikes")
        }

        val second = row()
        target.addView(second)
        card(second, "Загрузки", "Слушайте без сети", R.drawable.luma_download, openOffline)
        card(second, "Spotify", "Отдельный аккаунт", android.R.drawable.ic_media_play, openSpotify)
    }
}
