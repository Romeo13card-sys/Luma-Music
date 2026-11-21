package org.luma.music

import android.widget.LinearLayout

/** Player preference controls; playback policy changes are explicit host callbacks. */
internal class PlayerSettingsSection(
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean, (Boolean) -> Unit) -> Unit,
) {
    data class Data(val sleepMinutesRemaining: Int)

    data class Actions(
        val onShuffleChanged: (Boolean) -> Unit,
        val onRepeatChanged: (Boolean) -> Unit,
        val onPreloadChanged: (Boolean) -> Unit,
        val onSleepEndChanged: (Boolean) -> Unit,
        val openSleepTimer: () -> Unit,
        val openNotifications: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val player = group("Поведение плеера")
        settingSwitch(
            player,
            "Перемешивание",
            "Выбор следующего доступного трека в случайном порядке.",
            "shuffle",
            false,
            actions.onShuffleChanged,
        )
        settingSwitch(
            player,
            "Повтор трека",
            "Повторять текущую композицию после окончания.",
            "repeat",
            false,
            actions.onRepeatChanged,
        )
        settingSwitch(
            player,
            "Пауза без наушников",
            "Останавливать звук при отключении проводных или Bluetooth-наушников.",
            "pause_on_unplug",
            true,
        ) { }
        settingSwitch(
            player,
            "Цвет плеера по обложке",
            "Акцентные кнопки и спокойный фон в цвет альбома. OLED сохраняет чёрный фон.",
            "cover_colors",
            true,
        ) { }

        val fast = group("Быстрое переключение")
        settingSwitch(
            fast,
            "Подготавливать следующий трек",
            "Один следующий трек готовится заранее. Использует дополнительный трафик и память.",
            "preload_next",
            true,
            actions.onPreloadChanged,
        )
        settingSwitch(
            fast,
            "Предзагрузка только без лимита",
            "Отключать предзагрузку в тарифицируемой сети.",
            "preload_wifi_only",
            false,
            actions.onPreloadChanged,
        )
        settingSwitch(
            player,
            "Остановиться после трека",
            "Одноразовая пауза в конце текущей композиции.",
            "sleep_end",
            false,
            actions.onSleepEndChanged,
        )
        settingRow(
            player,
            "Таймер сна",
            if (data.sleepMinutesRemaining > 0) {
                "Осталось около ${data.sleepMinutesRemaining} мин"
            } else {
                "Выключен"
            },
            actions.openSleepTimer,
        )
        settingRow(
            player,
            "Уведомления и экран блокировки",
            "Разрешения и категории уведомлений Android",
            actions.openNotifications,
        )
    }
}
