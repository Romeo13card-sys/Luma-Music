package org.luma.music

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Home screen presentation. Wave requests, playback actions and navigation are supplied by the host.
 */
internal class HomeScreen(
    private val context: Context,
    private val target: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val row: () -> LinearLayout,
    private val column: () -> LinearLayout,
    private val gap: (LinearLayout, Int) -> Unit,
    private val panel: (LinearLayout) -> LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val collectionCards: (LinearLayout) -> Unit,
) {
    data class Data(
        val demo: Boolean,
        val stationDescription: String,
        val currentSong: Song?,
        val textColor: Int,
        val mutedColor: Int,
        val accentColor: Int,
    )

    data class Actions(
        val startWave: () -> Unit,
        val playDemo: () -> Unit,
        val configureWave: () -> Unit,
        val openPlayer: () -> Unit,
        val openTrackMenu: (Song) -> Unit,
        val openCollection: (String) -> Unit,
        val openOffline: () -> Unit,
        val openSpotify: () -> Unit,
        val openFile: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        gap(target, 8)
        target.addView(text("ВАША МУЗЫКАЛЬНАЯ СРЕДА", 10f, data.accentColor, true, false))
        target.addView(
            text(
                if (data.demo) "Знакомство с Luma" else "Что послушаем сегодня?",
                29f,
                data.textColor,
                true,
                false,
            ),
        )
        target.addView(
            text(
                "Откройте подборку, вернитесь к любимому или найдите что-то новое.",
                14f,
                data.mutedColor,
                false,
                false,
            ),
        )
        gap(target, 14)

        val wave = panel(target).apply {
            background = surface(dp(24).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        wave.addView(
            row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    MusicUi.icon(context, R.drawable.luma_wave, "Моя волна", data.accentColor) {
                        actions.startWave()
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) },
                )
                addView(text("ПОД ВАШЕ НАСТРОЕНИЕ", 10f, data.accentColor, true, true))
            },
        )
        wave.addView(
            text(
                if (data.demo) "Попробуйте Luma" else "Моя волна",
                30f,
                data.textColor,
                true,
                false,
            ),
        )
        wave.addView(text(data.stationDescription, 13f, data.mutedColor, false, false))
        gap(wave, 8)

        val controls = row()
        controls.addView(
            action(
                if (data.demo) "Слушать демо" else "Слушать волну",
                true,
            ) {
                if (data.demo) actions.playDemo() else actions.startWave()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        controls.addView(
            MusicUi.icon(context, android.R.drawable.ic_menu_manage, "Настроить Мою волну", data.mutedColor) {
                actions.configureWave()
            },
            LinearLayout.LayoutParams(dp(52), dp(52)),
        )
        wave.addView(controls)

        data.currentSong?.let { song ->
            gap(target, 8)
            target.addView(text("ПРОДОЛЖИТЬ", 10f, data.accentColor, true, false))
            val current = SongRow(context).apply {
                bind(song, true)
                setOnClickListener { actions.openPlayer() }
                more.setOnClickListener { actions.openTrackMenu(song) }
            }
            target.addView(current)
        }

        gap(target, LumaDesign.SECTION_GAP_DP)
        target.addView(text("БЫСТРЫЙ ДОСТУП", 10f, data.accentColor, true, false))
        target.addView(text("Ваша коллекция", 22f, data.textColor, true, false))
        collectionCards(target)
        gap(target, 4)
        target.addView(action("Музыка с устройства", false, actions.openFile))
    }
}
