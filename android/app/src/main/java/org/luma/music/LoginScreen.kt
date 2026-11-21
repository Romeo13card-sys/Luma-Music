package org.luma.music

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Login form presentation; OAuth, token validation, storage and navigation are host actions. */
internal class LoginScreen(
    private val context: Context,
    private val root: LinearLayout,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val column: () -> LinearLayout,
    private val gap: (LinearLayout, Int) -> Unit,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val panel: (LinearLayout) -> LinearLayout,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val textColor: Int,
    private val mutedColor: Int,
    private val accentColor: Int,
) {
    data class Actions(
        val openYandexLogin: () -> Unit,
        val openSpotify: () -> Unit,
        val openDownloads: () -> Unit,
        val browserLogin: () -> Unit,
        val tryDemo: () -> Unit,
        val connectToken: (String, EditText, LumaButton) -> Unit,
    )

    data class Views(val body: LinearLayout, val status: LumaTextView)

    fun render(actions: Actions): Views {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = column()
        scroll.addView(body)
        gap(body, 24)
        body.addView(text(context.getString(R.string.app_name), 18f, accentColor, true, false))
        body.addView(text("Ваша музыка.\nВаш момент.", 36f, textColor, true, false))
        body.addView(
            text(
                "Войдите в Яндекс Музыку, чтобы открыть свою коллекцию и Мою волну.",
                15f,
                mutedColor,
                false,
                false,
            ),
        )
        gap(body, 18)
        val status = text("", 13f, mutedColor, false, false)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(status)
        body.addView(action("Открыть окно входа Яндекса", true, actions.openYandexLogin))
        gap(body, 8)
        body.addView(action("Spotify", false, actions.openSpotify))
        body.addView(action("Загрузки", false, actions.openDownloads))
        body.addView(action("Браузер · свой OAuth-клиент", false, actions.browserLogin))
        body.addView(
            text(
                "Вход как в YAYMA: защищённая страница Яндекса в отдельном окне. Пароль обрабатывает Яндекс.",
                13f,
                mutedColor,
                false,
                false,
            ),
        )

        val alternative = action("Другой способ: OAuth-токен", false) {}
        body.addView(alternative)
        val card = panel(body)
        card.visibility = View.GONE
        alternative.setOnClickListener {
            card.visibility = if (card.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        card.addView(text("Вход в аккаунт", 24f, textColor, true, false))
        card.addView(text("Личный OAuth-токен", 13f, accentColor, true, false))
        val input = EditText(context).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
            hint = "Вставьте токен"
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine()
            background = surface(dp(16).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        card.addView(input)
        val show = CheckBox(context).apply {
            text = "Показать токен"
            setTextColor(mutedColor)
            setOnCheckedChangeListener { _, checked ->
                val cursor = input.selectionStart
                input.transformationMethod =
                    if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                input.setSelection(cursor.coerceAtLeast(0).coerceAtMost(input.length()))
            }
        }
        card.addView(show)
        card.addView(
            text(
                "Токен шифруется на устройстве через Android Keystore. Пароль Яндекса не нужен.",
                13f,
                mutedColor,
                false,
                false,
            ),
        )
        val submit = action("Войти", true) {
            actions.connectToken(input.text.toString().trim(), input, submitButton)
        }
        // The callback needs the same button instance to reflect the in-flight request state.
        submitButton = submit
        card.addView(submit)
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                actions.connectToken(input.text.toString().trim(), input, submit)
                true
            } else {
                false
            }
        }
        body.addView(action("Попробовать без аккаунта", false, actions.tryDemo))
        body.addView(
            text(
                "Неофициальный клиент. Для каталога и прослушивания нужна подписка Яндекс Музыки.",
                12f,
                mutedColor,
                false,
                false,
            ),
        )
        return Views(body, status)
    }

    private lateinit var submitButton: LumaButton
}
