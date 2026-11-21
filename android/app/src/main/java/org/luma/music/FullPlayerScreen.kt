package org.luma.music

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar

/**
 * Full player presentation. Playback commands and lifecycle-sensitive state are host callbacks.
 */
internal class FullPlayerScreen(
    private val context: Context,
    private val root: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val panel: (LinearLayout) -> LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val dp: (Int) -> Int,
    private val textColor: Int,
    private val mutedColor: Int,
    private val accentColor: Int,
    private val translate: (String) -> String,
    private val swipeTracks: (View, () -> Unit) -> Unit,
    private val actions: Actions,
) {
    data class Actions(
        val collapse: () -> Unit,
        val openQueue: () -> Unit,
        val openCurrentTrack: () -> Unit,
        val likeCurrent: () -> Unit,
        val seekStarted: () -> Unit,
        val seekStopped: (Int) -> Unit,
        val jump: (Int) -> Unit,
        val toggleShuffle: () -> Unit,
        val previous: () -> Unit,
        val togglePlayback: () -> Unit,
        val next: () -> Unit,
        val toggleRepeat: () -> Unit,
        val showLyrics: () -> Unit,
        val downloadCurrent: () -> Unit,
        val openSleepTimer: () -> Unit,
        val onCoverImage: (CoverImages.Image?) -> Unit,
    )

    data class Views(
        val body: LinearLayout,
        val cover: AlbumArt,
        val title: LumaTextView,
        val subtitle: LumaTextView,
        val heart: LumaButton,
        val status: LumaTextView,
        val seek: SeekBar,
        val time: LumaTextView,
        val shuffle: LumaButton,
        val play: LumaButton,
        val repeat: LumaButton,
    )

    fun render(): Views {
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        header.addView(
            MusicUi.icon(context, R.drawable.luma_down, "Свернуть плеер", mutedColor, actions.collapse),
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        val heading = column()
        heading.addView(text("LUMA PLAYER", 10f, accentColor, true, true))
        heading.addView(text("Сейчас играет", 14f, textColor, true, true))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            MusicUi.icon(context, R.drawable.luma_queue, "Открыть очередь", mutedColor, actions.openQueue),
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(header)

        val scroll = ScrollView(context).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        val body = column().apply {
            setPadding(dp(8), dp(4), dp(8), dp(24))
            clipChildren = false
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val artworkCard = panel(body).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val cover = AlbumArt(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = translate("Обложка текущего трека")
        }
        cover.onImage = actions.onCoverImage
        swipeTracks(cover, actions.openCurrentTrack)
        val side = minOf(
            context.resources.configuration.screenWidthDp - 64,
            360,
            maxOf(160, context.resources.configuration.screenHeightDp - 360),
        )
        artworkCard.addView(cover, LinearLayout.LayoutParams(dp(side), dp(side)))

        val infoCard = panel(body)
        infoCard.addView(text("NOW PLAYING", 10f, accentColor, true, true))
        val info = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = column()
        val title = text("", 27f, textColor, true, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitle = text("", 14f, mutedColor, false, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        labels.addView(title)
        labels.addView(subtitle)
        info.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val heart = MusicUi.icon(
            context,
            R.drawable.luma_heart_outline,
            "Добавить в избранное",
            mutedColor,
            actions.likeCurrent,
        )
        info.addView(heart, LinearLayout.LayoutParams(dp(48), dp(48)))
        infoCard.addView(info)
        val status = text("", 12f, mutedColor, false, false).apply {
            setPadding(0, dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        infoCard.addView(status)

        val progressCard = panel(body)
        val seek = SeekBar(context).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setPadding(dp(2), dp(6), dp(2), dp(6))
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(view: SeekBar) = actions.seekStarted()
                    override fun onStopTrackingTouch(view: SeekBar) = actions.seekStopped(view.progress)
                    override fun onProgressChanged(view: SeekBar, progress: Int, user: Boolean) = Unit
                },
            )
        }
        progressCard.addView(seek)
        val timing = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val time = text("0:00 / 0:00", 12f, mutedColor, false, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        timing.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        timing.addView(
            action("−10 с", false) { actions.jump(-10_000) },
            LinearLayout.LayoutParams(dp(68), dp(40)),
        )
        timing.addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
        timing.addView(
            action("+10 с", false) { actions.jump(10_000) },
            LinearLayout.LayoutParams(dp(68), dp(40)),
        )
        progressCard.addView(timing)

        val transportCard = panel(body)
        val controls = row().apply { gravity = Gravity.CENTER }
        val shuffle = MusicUi.icon(
            context,
            R.drawable.luma_shuffle,
            "Перемешивание",
            mutedColor,
            actions.toggleShuffle,
        )
        controls.addView(shuffle, LinearLayout.LayoutParams(0, dp(56), 1f))
        controls.addView(
            MusicUi.icon(context, R.drawable.luma_previous, "Предыдущий трек", textColor, actions.previous),
            LinearLayout.LayoutParams(0, dp(56), 1f),
        )
        val play = MusicUi.icon(
            context,
            R.drawable.luma_play,
            "Воспроизвести",
            Color.BLACK,
            actions.togglePlayback,
        ).apply {
            iconSize = dp(30)
            cornerRadius = dp(36)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(72), dp(72)))
        controls.addView(
            MusicUi.icon(context, R.drawable.luma_next, "Следующий трек", textColor, actions.next),
            LinearLayout.LayoutParams(0, dp(56), 1f),
        )
        val repeat = MusicUi.icon(
            context,
            R.drawable.luma_repeat,
            "Повтор трека",
            mutedColor,
            actions.toggleRepeat,
        )
        controls.addView(repeat, LinearLayout.LayoutParams(0, dp(56), 1f))
        transportCard.addView(controls)

        val toolsCard = panel(body)
        toolsCard.addView(text("БЫСТРЫЕ ДЕЙСТВИЯ", 10f, accentColor, true, true))
        val tools = row()
        tools.addView(
            MusicUi.icon(context, R.drawable.luma_lyrics, "Текст песни", mutedColor, actions.showLyrics),
            LinearLayout.LayoutParams(0, dp(52), 1f),
        )
        tools.addView(
            MusicUi.icon(context, R.drawable.luma_queue, "Очередь", mutedColor, actions.openQueue),
            LinearLayout.LayoutParams(0, dp(52), 1f),
        )
        tools.addView(
            MusicUi.icon(context, R.drawable.luma_download, "Скачать для офлайн", mutedColor, actions.downloadCurrent),
            LinearLayout.LayoutParams(0, dp(52), 1f),
        )
        tools.addView(
            MusicUi.icon(context, R.drawable.luma_clock, "Таймер сна", mutedColor, actions.openSleepTimer),
            LinearLayout.LayoutParams(0, dp(52), 1f),
        )
        toolsCard.addView(tools)

        return Views(body, cover, title, subtitle, heart, status, seek, time, shuffle, play, repeat)
    }
}
