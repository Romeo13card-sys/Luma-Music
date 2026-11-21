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
    private val translate: (String) -> String,
) {
    data class Data(
        val networkState: String,
        val wifiQuality: Int,
        val mobileQuality: Int,
        val metered: Boolean,
        val accentColor: Int,
        val mutedColor: Int,
    )

    data class Actions(
        val selectQuality: (String, Int) -> Unit,
        val replayCurrent: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        targetState(data)
        val sound = group("Качество MP3")
        quality(
            sound,
            "Безлимитная сеть",
            "quality_wifi",
            data.wifiQuality,
            "Обычно Wi-Fi. Максимальное качество в пределах выбранного лимита.",
            actions.selectQuality,
        )
        quality(
            sound,
            "Сеть с лимитом",
            "quality_mobile",
            data.mobileQuality,
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

    private fun targetState(data: Data) {
        // The host-provided state reflects Android's active/metered network classification.
        // Add it as a standalone line above the quality controls.
        stateText = text(data.networkState, 15f, data.accentColor, true, false)
        stateTarget?.addView(stateText)
    }

    private var stateTarget: LinearLayout? = null
    private lateinit var stateText: LumaTextView

    private fun quality(
        parent: LinearLayout,
        title: String,
        key: String,
        selected: Int,
        detail: String,
        onSelected: (String, Int) -> Unit,
    ) {
        settingRow(parent, translate("%1\$s · до %2\$d кбит/с", title, selected), detail) {
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
