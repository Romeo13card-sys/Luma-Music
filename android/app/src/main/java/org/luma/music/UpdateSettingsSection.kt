package org.luma.music

import android.content.Context
import android.widget.LinearLayout

/** OTA settings UI; checking, downloading, validation and installation remain host actions. */
internal class UpdateSettingsSection(
    private val context: Context,
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean) -> Unit,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val gap: (LinearLayout, Int) -> Unit,
) {
    data class Data(
        val version: String,
        val preview: Boolean,
        val lastAttempt: String,
        val partialDownloadInfo: String?,
        val hasSavedUpdate: Boolean,
        val mutedColor: Int,
    )

    data class Actions(
        val changeChannel: (Boolean) -> Unit,
        val checkUpdates: () -> Unit,
        val installSaved: () -> Unit,
        val installFromFile: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val update = group("Luma ${data.version}")
        settingRow(
            update,
            "Канал обновлений",
            if (data.preview) "Предварительные · включая RC" else "Только стабильные выпуски",
        ) {
            LumaDialog(context).setTitle("Канал обновлений")
                .setSingleChoiceItems(
                    arrayOf("Только стабильные", "Предварительные и стабильные"),
                    if (data.preview) 1 else 0,
                ) { dialog, index ->
                    dialog.dismiss()
                    actions.changeChannel(index == 1)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        settingSwitch(
            update,
            "Проверять при запуске",
            "Не чаще раза в сутки. Скачивание и установка требуют вашего действия.",
            "auto_update_check",
            true,
        )
        update.addView(text(data.lastAttempt, 13f, data.mutedColor, false, false))
        update.addView(action("Проверить обновления", true, actions.checkUpdates))
        data.partialDownloadInfo?.let { partial ->
            update.addView(
                text(
                    "Сохранена незавершённая загрузка: $partial. Продолжение возможно для того же выпуска и файла GitHub; другой APK будет загружен отдельно.",
                    13f,
                    data.mutedColor,
                    false,
                    false,
                ),
            )
        }
        if (data.hasSavedUpdate) {
            update.addView(action("Установить сохранённое обновление", true, actions.installSaved))
            update.addView(
                text(
                    "APK сохранён на устройстве. Перед установкой повторно проверяются SHA-256, версия и подпись.",
                    13f,
                    data.mutedColor,
                    false,
                    false,
                ),
            )
        }
        gap(update, 8)
        update.addView(action("Установить APK из файла", false, actions.installFromFile))
        update.addView(
            text(
                "Перед установкой проверяются версия и подпись. Источник: GitHub Releases Luma-Music.",
                13f,
                data.mutedColor,
                false,
                false,
            ),
        )
    }
}
