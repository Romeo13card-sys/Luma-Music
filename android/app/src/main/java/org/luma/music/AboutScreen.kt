package org.luma.music

import android.widget.LinearLayout

/**
 * Renders the About screen without owning navigation, device state, or app services.
 * Those dependencies are supplied as immutable display data and explicit actions.
 */
internal class AboutScreen(
    private val target: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val panel: (LinearLayout) -> LinearLayout,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
) {
    data class Data(
        val version: String,
        val channel: String,
        val build: String,
        val packageName: String,
        val selectedTheme: String,
        val activeTheme: String,
        val language: String,
        val translationPreview: Boolean,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val abis: String,
        val memory: String,
        val renderProfile: String,
        val authorCredit: String,
        val textColor: Int,
        val mutedColor: Int,
        val accentColor: Int,
    )

    data class Actions(
        val openWhatsNew: () -> Unit,
        val openUrl: (String) -> Unit,
        val copySupportInfo: () -> Unit,
        val openLicense: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val hero = panel(target)
        hero.addView(text("Luma Music", 32f, data.textColor, true, false))
        hero.addView(text("Музыка в вашем ритме", 16f, data.mutedColor, false, false))
        hero.addView(text(data.authorCredit, 18f, data.accentColor, true, false))

        fun group(label: String) =
            panel(target).also { it.addView(text(label, 21f, data.textColor, true, false)) }

        fun info(parent: LinearLayout, label: String, value: String) {
            parent.addView(text(label, 12f, data.mutedColor, false, false))
            parent.addView(text(value, 16f, data.textColor, false, true))
        }

        val app = group("Приложение")
        info(app, "Версия", data.version)
        app.addView(action("Что нового", false, actions.openWhatsNew))
        info(app, "Выпуск", data.channel)
        info(app, "Сборка", data.build)
        info(app, "Пакет", data.packageName)

        val appearance = group("Оформление")
        info(appearance, "Выбранная тема", data.selectedTheme)
        info(appearance, "Активная тема", data.activeTheme)
        info(appearance, "Язык приложения", data.language)
        if (data.translationPreview) {
            appearance.addView(
                text(
                    "Предварительный перевод. Часть пояснений показана на английском.",
                    13f,
                    AppThemes.active.muted,
                    false,
                    false,
                ),
            )
        }

        val device = group("Устройство")
        info(device, "Модель", data.model)
        info(device, "Система", "Android ${data.androidVersion} · API ${data.apiLevel}")
        info(device, "Архитектуры", data.abis)
        info(device, "Оперативная память", data.memory)
        info(device, "Профиль интерфейса", data.renderProfile)

        val links = group("Связь и поддержка")
        settingRow(links, "Страница релизов", "GitHub Releases") {
            actions.openUrl("https://github.com/Romeo13card-sys/Luma-Music/releases")
        }
        settingRow(links, "Сообщить об ошибке", "GitHub Issues") {
            actions.openUrl("https://github.com/Romeo13card-sys/Luma-Music/issues")
        }
        settingRow(links, "Исходный код", "GPL-3.0 · Rust / Kotlin") {
            actions.openUrl("https://github.com/Romeo13card-sys/Luma-Music")
        }
        settingRow(links, "Telegram · Romeo13card", "t.me/romeo_13card") {
            actions.openUrl("https://t.me/romeo_13card")
        }
        settingRow(links, "Поддержать автора", "dalink.to/romeo_13card_fan") {
            actions.openUrl("https://dalink.to/romeo_13card_fan")
        }
        settingRow(links, "Поддерживаемые платформы", "Android · Windows · Linux · macOS") {
            actions.openUrl("https://github.com/Romeo13card-sys/Luma-Music/blob/main/docs/PLATFORMS.md")
        }
        links.addView(action("Скопировать сведения для поддержки", false, actions.copySupportInfo))

        val legal = group("Лицензии и авторство")
        legal.addView(
            text(
                "YAYMA — DarkPlayOff · YaMusic — Vyfor\nLuma — Romeo13card\nGPL-3.0",
                14f,
                AppThemes.active.muted,
                false,
                true,
            ),
        )
        legal.addView(action("Открыть лицензию", false, actions.openLicense))
    }
}
