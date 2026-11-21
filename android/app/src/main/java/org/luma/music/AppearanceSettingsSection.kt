package org.luma.music

import android.widget.LinearLayout

/** Appearance and performance settings presentation. Preference and theme mutations stay in the host. */
internal class AppearanceSettingsSection(
    private val group: (String) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val settingSwitch: (LinearLayout, String, String, String, Boolean, (Boolean) -> Unit) -> Unit,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
) {
    data class Data(
        val themeLabel: String,
        val languageName: String,
        val fontSizeSummary: String,
        val performanceSummary: String,
        val economy: Boolean,
        val mutedColor: Int,
    )

    data class Actions(
        val chooseTheme: () -> Unit,
        val chooseLanguage: () -> Unit,
        val chooseFontSize: () -> Unit,
        val onEconomyChanged: (Boolean) -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val appearance = group("Ваш интерфейс")
        settingRow(appearance, "Тема", data.themeLabel, actions.chooseTheme)
        settingRow(appearance, "Язык приложения", data.languageName, actions.chooseLanguage)
        settingRow(appearance, "Размер текста Luma", data.fontSizeSummary, actions.chooseFontSize)
        settingSwitch(
            appearance,
            "Вибрация кнопок",
            "Лёгкий тактильный отклик при нажатии.",
            "haptics",
            true,
        ) { }
        val performance = group("Быстродействие")
        settingSwitch(
            performance,
            "Экономичный интерфейс",
            "Меньше строк на странице и реже обновляется плеер. На малом объёме ОЗУ режим включается автоматически.",
            "reduce_effects",
            false,
            actions.onEconomyChanged,
        )
        performance.addView(text(data.performanceSummary, 13f, data.mutedColor, false, false))
    }
}
