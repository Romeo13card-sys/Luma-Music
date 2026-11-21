package org.luma.music

import android.content.Context
import android.widget.LinearLayout

/** Audio quality and traffic controls; network and playback operations are host callbacks. */
internal class SoundSettingsSection(
    private val context: Context,
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean, (Boolean) -> Unit) -> Unit,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
) {
    data class Data(
        val networkState: String,
        val wifiQuality: Int,
        val mobileQuality: Int,
        val wifiQualityLabel: String,
        val mobileQualityLabel: String,
        val accentColor: Int,
        val mutedColor: Int,
    )

    data class Actions(
        val selectQuality: (String, Int) -> Unit,
        val replayCurrent: () -> Unit,
    )

    fun render(target: LinearLayout, data: Data, actions: Actions) {
        target.addView(text(data.networkState, 15f, data.accentColor, true, false))
        val sound = group("Качество MP3")
        quality(
            sound,
            "Безлимитная сеть",
            "quality_wifi",
            data.wifiQuality,
            data.wifiQualityLabel,
            "Обычно Wi-Fi. Максимальное качество в пределах выбранного лимита.",
            actions.selectQuality,
        )
        quality(
            sound,
            "Сеть с лимитом",
            "quality_mobile",
            data.mobileQuality,
            data.mobileQualityLabel,
            "Обычно мобильная связь. Android может считать Wi-Fi тарифицируемым.",
            actions.selectQuality,
        )
        sound.addView(
            text(
                "Применяется к следующему запуску трека. Если Яндекс предлагает только более высокий битрейт, Luma сообщит об этом и не превысит лимит автоматически.",
                13f,
                data.mutedColor,
                false,
                false,
            ),
        )
        val traffic = group("Трафик")
        settingSwitch(
            traffic,
            "Только сеть без лимита",
            "В сети с лимитом сетевое аудио не загружается. Файлы с устройства доступны.",
            "unmetered_only",
            false,
        ) { }
        traffic.addView(action("Повторить текущий трек", true, actions.replayCurrent))
    }

    private fun quality(
        parent: LinearLayout,
        title: String,
        key: String,
        selected: Int,
        label: String,
        detail: String,
        onSelected: (String, Int) -> Unit,
    ) {
        settingRow(parent, label, detail) {
            val values = intArrayOf(128, 192, 320)
            LumaDialog(context).setTitle(title)
                .setSingleChoiceItems(
                    arrayOf("Экономия · 128 кбит/с", "Баланс · 192 кбит/с", "Высокое · 320 кбит/с"),
                    values.indexOf(selected).coerceAtLeast(0),
                ) { dialog, index ->
                    dialog.dismiss()
                    onSelected(key, values[index])
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }
}
