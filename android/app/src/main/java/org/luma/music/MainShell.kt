package org.luma.music

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView

/** Main navigation shell and persistent mini-player presentation. */
internal class MainShell(
    private val context: Context,
    private val root: LinearLayout,
    private val page: String,
    private val demo: Boolean,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val mutedColor: Int,
    private val accentColor: Int,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val gap: (LinearLayout, Int) -> Unit,
    private val dp: (Int) -> Int,
    private val surface: (Float, Boolean) -> android.graphics.drawable.GradientDrawable,
    private val translate: (String) -> String,
    private val swipeTracks: (View, () -> Unit) -> Unit,
) {
    data class Actions(
        val openSettings: () -> Unit,
        val navigate: (String) -> Unit,
        val openPlayer: () -> Unit,
        val togglePlayback: () -> Unit,
        val next: () -> Unit,
        val renderContent: (LinearLayout) -> Unit,
    )

    data class Bindings(
        val body: LinearLayout,
        val status: LumaTextView,
        val miniCover: AlbumArt,
        val title: LumaTextView,
        val subtitle: LumaTextView,
        val toggle: LumaButton,
        val progress: ProgressBar,
    )

    fun render(loadContent: Boolean, actions: Actions): Bindings {
        val header = row().apply {
            setPadding(0, dp(8), 0, dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = row().apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(
            text("L", 26f, accentColor, true, true).apply {
                gravity = Gravity.CENTER
                background = surface(dp(14).toFloat(), true)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) },
        )
        val brand = column()
        brand.addView(text("LUMA MUSIC", 15f, textColor, true, true))
        brand.addView(text("ВАША МУЗЫКА · ВАШ РИТМ", 9f, mutedColor, true, true))
        identity.addView(brand)
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            MusicUi.icon(context, android.R.drawable.ic_menu_manage, "Настройки", mutedColor, actions.openSettings),
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(header)

        val status = text(
            if (demo) "ДЕМО · ЛОКАЛЬНЫЙ РЕЖИМ" else "",
            10f,
            accentColor,
            true,
            true,
        ).apply {
            visibility = if (demo) View.VISIBLE else View.GONE
            background = surface(dp(14).toFloat(), false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(10), 0, dp(10))
        }
        val body = column().apply { clipChildren = false }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val miniPanel = column().apply {
            background = surface(dp(22).toFloat(), false)
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        val miniBar = row()
        val miniCover = AlbumArt(context).apply {
            prominent = true
            swipeTracks(this, actions.openPlayer)
        }
        miniBar.addView(
            miniCover,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) },
        )
        val miniLabels = column().apply {
            isFocusable = true
            swipeTracks(this, actions.openPlayer)
        }
        val title = text(translate("Выберите трек"), 14f, textColor, true, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        val subtitle = text(translate("Открыть плеер"), 11f, mutedColor, false, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        miniLabels.addView(title)
        miniLabels.addView(subtitle)
        miniBar.addView(miniLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = MusicUi.icon(
            context,
            R.drawable.luma_play,
            "Воспроизвести",
            accentColor,
            actions.togglePlayback,
        )
        miniBar.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniBar.addView(
            MusicUi.icon(context, R.drawable.luma_next, "Следующий трек", mutedColor, actions.next),
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        miniPanel.addView(miniBar)
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        miniPanel.addView(
            progress,
            LinearLayout.LayoutParams(-1, dp(2)).apply {
                leftMargin = dp(4)
                rightMargin = dp(8)
                topMargin = dp(5)
            },
        )
        root.addView(miniPanel)
        gap(root, 8)

        // Luma Flow navigation: compact, custom and visually distinct from the stock
        // Material bottom bar while retaining the same four destinations and callbacks.
        val destinations = listOf("Главная", "Поиск", "Медиатека", "Настройки")
        val labels = listOf("Главная", "Поиск", "Коллекция", "Настройки")
        val icons = listOf(
            R.drawable.luma_wave,
            android.R.drawable.ic_menu_search,
            android.R.drawable.ic_menu_agenda,
            android.R.drawable.ic_menu_manage,
        )
        val selectedIndex = destinations.indexOf(page).coerceAtLeast(0)
        val nav = row().apply {
            background = surface(dp(22).toFloat(), false)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            clipToPadding = false
        }
        destinations.forEachIndexed { index, destination ->
            val selected = index == selectedIndex
            val item = column().apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = if (selected) surface(dp(17).toFloat(), true) else null
                setPadding(dp(3), dp(7), dp(3), dp(7))
                contentDescription = translate(labels[index])
                setOnClickListener { if (!selected) actions.navigate(destination) }
            }
            val iconColor = if (selected) LumaDesign.contentColor(true) else mutedColor
            item.addView(
                MusicUi.icon(context, icons[index], translate(labels[index]), iconColor) {
                    if (!selected) actions.navigate(destination)
                },
                LinearLayout.LayoutParams(dp(25), dp(25)),
            )
            item.addView(
                text(translate(labels[index]), 10f, if (selected) LumaDesign.contentColor(true) else mutedColor, selected, true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
            )
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
            bottomMargin = dp(3)
        })

        if (loadContent) actions.renderContent(body)
        return Bindings(body, status, miniCover, title, subtitle, toggle, progress)
    }
}
