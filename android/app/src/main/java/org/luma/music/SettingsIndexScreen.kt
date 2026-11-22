package org.luma.music

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/** Settings category search and reset entry point. Mutations are delegated to the host. */
internal class SettingsIndexScreen(
    private val context: Context,
    private val target: LinearLayout,
    private val categories: List<JSONObject>,
    private val text: (String, Float, Int, Boolean, Boolean) -> LumaTextView,
    private val action: (String, Boolean, () -> Unit) -> LumaButton,
    private val panel: (LinearLayout) -> LinearLayout,
    private val settingRow: (LinearLayout, String, String, () -> Unit) -> Unit,
    private val dp: (Int) -> Int,
    private val surface: (Float) -> android.graphics.drawable.GradientDrawable,
    private val translate: (String) -> String,
    private val hideKeyboard: (View) -> Unit,
    private val openSection: (String) -> Unit,
    private val resetSettings: () -> Unit,
    private val version: String,
    private val textColor: Int,
    private val mutedColor: Int,
) {
    fun render() {
        target.addView(text("MAKE IT YOURS  /  LUMA SETTINGS", 10f, AppThemes.active.accent, true, true))
        target.addView(text("Настрой Luma", 30f, textColor, true, false))
        target.addView(
            text(
                "Звук, внешний вид, загрузки и обновления — всё в одном месте.",
                13f,
                mutedColor,
                false,
                false,
            ),
        )
        val search = EditText(context).apply {
            hint = translate("Найти настройку")
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            setSingleLine()
            background = surface(dp(14).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        target.addView(search)
        val rows = panel(target)

        fun filter(query: String) {
            rows.removeAllViews()
            val normalized = query.trim()
            val found = categories.filter { item ->
                listOf(
                    translate(item.getString("title")),
                    translate(item.getString("summary")),
                    item.getString("title"),
                    item.getString("summary"),
                    item.optString("keywords"),
                ).joinToString(" ").contains(normalized, ignoreCase = true)
            }
            found.forEach { item ->
                settingRow(rows, item.getString("title"), item.getString("summary")) {
                    hideKeyboard(search)
                    openSection(item.getString("id"))
                }
            }
            if (found.isEmpty()) rows.addView(text("Настройка не найдена", 15f, mutedColor, false, false))
        }

        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filter(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )
        filter("")
        target.addView(action("Сбросить настройки", false, resetSettings))
        target.addView(text("Luma Music · $version", 12f, mutedColor, false, false))
    }
}
