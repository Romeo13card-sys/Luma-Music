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

        val heroText = LumaDesign.contentColor(true)
        val heroMuted = if (AppThemes.active.light) 0xFF51472B.toInt() else 0xFF403719.toInt()
        val wave = panel(target).apply {
            // The hero uses the Luma accent as its own canvas, not another generic card.
            background = LumaDesign.heroCard(dp(26).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        wave.addView(
            row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    MusicUi.icon(context, R.drawable.luma_wave, "Моя волна", heroText) {
                        actions.startWave()
                    },
                    LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) },
                )
                addView(text("LUMA FLOW  /  PERSONAL RADIO", 10f, heroText, true, true))
            },
        )
        gap(wave, 8)
        wave.addView(
            text(
                if (data.demo) "Почувствуйте Luma" else "Твоя музыка.\nТвой ритм.",
                31f,
                heroText,
                true,
                false,
            ),
        )
        gap(wave, 3)
        wave.addView(
            text(
                if (data.demo) "Попробуйте плеер и познакомьтесь с приложением." else data.stationDescription,
                13f,
                heroMuted,
                false,
                false,
            ),
        )
        gap(wave, 14)

        val controls = row()
        controls.addView(
            action(
                if (data.demo) "Слушать демо" else "Включить волну",
                false,
            ) {
                if (data.demo) actions.playDemo() else actions.startWave()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        controls.addView(
            MusicUi.icon(context, android.R.drawable.ic_menu_manage, "Настроить Мою волну", heroText) {
                actions.configureWave()
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        wave.addView(controls)


        data.currentSong?.let { song ->
            gap(target, 8)
            target.addView(text("СЕЙЧАС ИГРАЕТ", 10f, data.accentColor, true, false))
            val current = SongRow(context).apply {
                bind(song, true)
                setOnClickListener { actions.openPlayer() }
                more.setOnClickListener { actions.openTrackMenu(song) }
            }
            target.addView(current)
        }

        gap(target, LumaDesign.SECTION_GAP_DP)
        target.addView(text("ВАША МУЗЫКА", 10f, data.accentColor, true, false))
        target.addView(text("Ваша коллекция", 22f, data.textColor, true, false))
        collectionCards(target)
        gap(target, 4)
        target.addView(action("Музыка с устройства", false, actions.openFile))
    }
}
