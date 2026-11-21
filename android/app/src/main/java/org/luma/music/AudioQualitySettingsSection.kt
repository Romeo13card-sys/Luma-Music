package org.luma.music

import android.widget.LinearLayout

/** Network-aware quality settings UI; preference writes and playback refresh stay in the host. */
internal class AudioQualitySettingsSection(
    private val target: LinearLayout,
    private val group: (String) -> LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean) -> Unit,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
) {
    data class Data(
        val networkState: String,
        val wifiQuality: Int,
        val mobileQuality: Int,
        val mutedColor: Int,
        val accentColor: Int,
    )

    data class Actions(
        val chooseQuality: (String, String) -> Unit,
        val replayCurrent: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        target.addView(text(data.networkState, 15f, data.accentColor, true, false))
        val sound = group("Качество MP3")
        settingRow(
            sound,
            "Безлимитная сеть · до ${data.wifiQuality} кбит/с",
            "Обычно Wi-Fi. Максимальное качество в пределах выбранного лимита.",
        ) {
            actions.chooseQuality("Безлимитная сеть", "quality_wifi")
        }
        settingRow(
            sound,
            "Сеть с лимитом · до ${data.mobileQuality} кбит/с",
            "Обычно мобильная связь. Android может считать Wi-Fi тарифицируемым.",
        ) {
            actions.chooseQuality("Сеть с лимитом", "quality_mobile")
        }
        sound.addView(text(
            "Применяется к следующему запуску трека. Если Яндекс предлагает только более высокий битрейт, Luma сообщит об этом и не превысит лимит автоматически.",
            13f,
            data.mutedColor,
            false,
            false,
        ))
        val traffic = group("Трафик")
        settingSwitch(
            traffic,
            "Только сеть без лимита",
            "В сети с лимитом сетевое аудио не загружается. Файлы с устройства доступны.",
            "unmetered_only",
            false,
        )
        target.addView(action("Повторить текущий трек", true, actions.replayCurrent))
    }
}
