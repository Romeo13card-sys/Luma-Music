package org.luma.music

import android.widget.LinearLayout

/** My Wave settings presentation; station selection and playback are host callbacks. */
internal class WaveSettingsSection(
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
) {
    data class Data(val stationTitle: String)

    data class Actions(
        val configure: () -> Unit,
        val play: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val wave = group("Подборка")
        settingRow(wave, "Настроить Мою волну", data.stationTitle, actions.configure)
        wave.addView(action("Слушать подборку", true, actions.play))
    }
}
