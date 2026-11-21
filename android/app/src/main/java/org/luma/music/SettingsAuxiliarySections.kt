package org.luma.music

import android.widget.LinearLayout

/** Data-management shortcuts backed by the existing settings schema and host destinations. */
internal class DataSettingsSection(
    private val target: LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val renderControls: () -> Unit,
    private val openHistory: () -> Unit,
    private val openBookmarks: () -> Unit,
    private val openBackup: () -> Unit,
) {
    fun render() {
        renderControls()
        target.addView(action("История прослушивания", false, openHistory))
        target.addView(action("Закладки в песнях", false, openBookmarks))
        target.addView(action("Резервная копия", false, openBackup))
    }
}

/** Audio/equalizer entry point; session lookup and Android effect intent stay in the host. */
internal class AudioSettingsSection(
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val renderControls: () -> Unit,
    private val openSystemEqualizer: () -> Unit,
) {
    fun render() {
        renderControls()
        val audio = group("Эквалайзер")
        settingRow(
            audio,
            "Системный эквалайзер",
            "Настройки звука Android для текущего аудиосеанса",
            openSystemEqualizer,
        )
    }
}

/** Downloads settings shortcut; download management remains in OfflineActivity. */
internal class DownloadsSettingsSection(
    private val target: LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val renderControls: () -> Unit,
    private val openDownloads: () -> Unit,
) {
    fun render() {
        renderControls()
        target.addView(action("Открыть загрузки", true, openDownloads))
    }
}
