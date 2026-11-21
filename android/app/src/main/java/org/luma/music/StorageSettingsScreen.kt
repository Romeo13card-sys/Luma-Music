package org.luma.music

import android.widget.LinearLayout

/** Storage settings presentation; filesystem operations and lifecycle guards stay in the host. */
internal class StorageSettingsScreen(
    private val group: (String) -> LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
) {
    data class Data(
        val downloadedUpdates: String,
        val freeSpace: String,
        val primaryColor: Int,
        val mutedColor: Int,
    )

    data class Actions(
        val clearArtworkCache: () -> Unit,
        val deleteUpdateFiles: () -> Unit,
        val openOfflineDownloads: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val storage = group("Место на устройстве")
        storage.addView(text(data.downloadedUpdates, 16f, data.primaryColor, false, false))
        storage.addView(text(data.freeSpace, 14f, data.mutedColor, false, false))
        settingRow(
            storage,
            "Очистить кэш обложек",
            "До 24 МиБ на диске. Скачанная музыка и аккаунты сохранятся.",
            actions.clearArtworkCache,
        )
        settingRow(
            storage,
            "Удалить файлы обновлений",
            "Скачанные APK и незавершённая загрузка. Аккаунт и очередь сохранятся.",
            actions.deleteUpdateFiles,
        )
        storage.addView(action("Офлайн-загрузки", false, actions.openOfflineDownloads))
    }
}
