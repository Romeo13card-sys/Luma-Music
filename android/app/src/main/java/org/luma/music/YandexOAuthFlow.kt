package org.luma.music

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.EditText

/**
 * Browser OAuth mechanics for Yandex Music.
 * The Activity owns credential persistence and authenticated account loading.
 */
internal class YandexOAuthFlow(
    private val activity: Activity,
    private val showStatus: (String) -> Unit,
    private val onCredential: (String) -> Unit,
) {
    fun browserLogin() {
        val prefs = activity.getSharedPreferences("oauth", Activity.MODE_PRIVATE)
        val client = prefs.getString("client", null).orEmpty()
            .ifBlank { BuildConfig.YANDEX_CLIENT_ID }
        if (client.isNotBlank()) {
            launch(client)
        } else {
            configureOAuth()
        }
    }

    fun configureOAuth() {
        val prefs = activity.getSharedPreferences("oauth", Activity.MODE_PRIVATE)
        val input = EditText(activity).apply {
            hint = L10n.text(activity, "Client ID OAuth-приложения")
            setSingleLine()
            setText(prefs.getString("client", ""))
        }
        LumaDialog(activity)
            .setTitle("Вход через Яндекс")
            .setMessage(
                "Введите Client ID вашего OAuth-приложения с доступом к Яндекс Музыке. В нём должен быть зарегистрирован Redirect URI: yamusicrust://oauth. Client ID не является паролем.",
            )
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть браузер") { _, _ ->
                val client = input.text.toString().trim()
                if (!client.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
                    showStatus("Некорректный Client ID")
                    return@setPositiveButton
                }
                prefs.edit().putString("client", client).apply()
                launch(client)
            }
            .show()
    }

    private fun launch(client: String) {
        val state = java.util.UUID.randomUUID().toString()
        activity.getSharedPreferences("oauth", Activity.MODE_PRIVATE)
            .edit()
            .putString("state", state)
            .putLong("created", System.currentTimeMillis())
            .apply()
        val uri = Uri.parse("https://oauth.yandex.ru/authorize")
            .buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", client)
            .appendQueryParameter("redirect_uri", "yamusicrust://oauth")
            .appendQueryParameter("state", state)
            .build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            showStatus("Не найден браузер")
        }
    }

    fun handleCallback(incoming: Intent?) {
        val uri = incoming?.data ?: return
        if (uri.scheme != "yamusicrust" || uri.host != "oauth") return
        val prefs = activity.getSharedPreferences("oauth", Activity.MODE_PRIVATE)
        val params = Uri.parse("https://callback.invalid/?" + (uri.fragment ?: uri.query.orEmpty()))
        val expected = prefs.getString("state", null)
        val createdAt = prefs.getLong("created", 0)
        // Consume the state before validation so every callback attempt is one-shot,
        // including malformed, expired, or attacker-controlled callbacks.
        prefs.edit().remove("state").remove("created").apply()
        if (
            !OAuthStateValidator.isValid(
                expected = expected,
                actual = params.getQueryParameter("state"),
                createdAtMs = createdAt,
                nowMs = System.currentTimeMillis(),
            )
        ) {
            showStatus("Вход отклонён: неверный или просроченный ответ. Начните вход заново.")
            return
        }
        val credential = params.getQueryParameter("access_token")
        if (credential.isNullOrBlank()) {
            showStatus("Яндекс не предоставил токен. Проверьте права OAuth-приложения.")
            return
        }
        onCredential(credential)
    }
}
