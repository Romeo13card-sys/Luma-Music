package org.luma.music

import android.widget.LinearLayout

/** Account settings presentation. OAuth and credential lifecycle stay in the host activities. */
internal class AccountSettingsScreen(
    private val target: LinearLayout,
    private val group: (String) -> LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val gap: (LinearLayout, Int) -> Unit,
) {
    data class Data(
        val yandexConnected: Boolean,
        val mutedColor: Int,
    )

    data class Actions(
        val connectSpotify: () -> Unit,
        val connectYandex: () -> Unit,
        val configureOAuth: () -> Unit,
        val logout: () -> Unit,
    )

    fun render(data: Data, actions: Actions) {
        val spotify = group("Spotify")
        spotify.addView(action("Подключить Spotify", false, actions.connectSpotify))
        spotify.addView(text(
            "Аккаунты Spotify и Яндекса подключаются независимо.",
            13f,
            data.mutedColor,
            false,
            false,
        ))

        val account = group("Яндекс Музыка")
        account.addView(text(
            if (data.yandexConnected) "Вход сохранён на устройстве" else "Вход не выполнен",
            15f,
            data.mutedColor,
            false,
            false,
        ))
        account.addView(action(
            if (data.yandexConnected) "Сменить аккаунт" else "Войти через Яндекс",
            true,
            actions.connectYandex,
        ))
        gap(account, 8)
        settingRow(account, "Дополнительный вход", "Браузер со своим OAuth-клиентом", actions.configureOAuth)
        if (data.yandexConnected) {
            account.addView(action("Выйти из аккаунта", false, actions.logout))
        }
        account.addView(text(
            "Токен хранится зашифрованным на этом устройстве. Для каталога и прослушивания нужна действующая подписка.",
            13f,
            data.mutedColor,
            false,
            false,
        ))
    }
}
