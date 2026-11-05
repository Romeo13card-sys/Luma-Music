package org.luma.music

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.http.SslError
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Native adaptation of YAYMA's login window. Never reads page fields or injects JavaScript. */
class YandexLoginActivity : Activity() {
    private var web: WebView? = null
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var nonce = UUID.randomUUID().toString()
    private var started = SystemClock.elapsedRealtime()
    private var authorizing = false
    private var checking = false
    private var destroyed = false
    private var sessionGeneration = 0
    private var authorizeUrl = ""
    private val colors get() = AppThemes.resolve(
        SettingsStore.get(this).getString("theme_mode", "system").orEmpty(),
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    )
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        setTheme(if (colors.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            fitsSystemWindows = true
        }
        val title = TextView(this).apply { text = "Вход через Яндекс"; textSize = 22f; setTextColor(colors.text) }
        content.addView(title)
        message = TextView(this).apply {
            text = "Пароль вводится только на сайте Яндекса"
            setTextColor(colors.muted)
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(message)
        val actions = LinearLayout(this)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
        }
        actions.addView(button("Закрыть") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Заново") { restart() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Другой аккаунт") {
            if (!checking) {
                CookieManager.getInstance().removeAllCookies {
                    if (!destroyed) { WebStorage.getInstance().deleteAllData(); restart() }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        setContentView(content)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { back() }
        restart()
    }

    @Suppress("DEPRECATION")
    private fun restart() {
        if (checking || destroyed) return
        web?.let { content.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        nonce = UUID.randomUUID().toString()
        started = SystemClock.elapsedRealtime()
        sessionGeneration++
        authorizing = false
        try {
            val urls = NativeCore.call("auth_start", JSONObject().put("state", nonce).toString()) as JSONObject
            authorizeUrl = urls.getString("authorizeUrl")
            val browser = WebView(this)
            web = browser
            browser.isSaveEnabled = false
            browser.setBackgroundColor(Color.WHITE)
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            // Suppress web-page console output; OAuth responses must never enter application logs.
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && inspect(request.url.toString())
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    inspect(url)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { inspect(url) }
                override fun onPageFinished(view: WebView, url: String) { inspect(url) }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    message.text = "Не удалось проверить защищённое соединение с Яндексом"
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !checking) message.text = "Страница не загрузилась. Проверьте сеть и нажмите «Заново»."
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    content.removeView(view); view.destroy(); web = null
                    message.text = "Окно входа было закрыто системой. Нажмите «Заново»."
                    return true
                }
            }
            content.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
            browser.loadUrl(urls.getString("loginUrl"))
        } catch (_: Exception) {
            message.text = "Окно входа недоступно. Обновите Android System WebView или используйте браузер на предыдущем экране."
        }
    }

    private fun inspect(url: String): Boolean {
        if (destroyed || checking) return true
        val result = try {
            NativeCore.call("auth_navigation", JSONObject().put("url", url).put("state", nonce)
                .put("ageMs", SystemClock.elapsedRealtime() - started).put("authorizing", authorizing).toString()) as JSONObject
        } catch (_: Exception) {
            web?.stopLoading(); message.text = "Не удалось обработать вход. Нажмите «Заново»."; return true
        }
        when (result.optString("action")) {
            "authorize" -> {
                authorizing = true
                web?.stopLoading()
                web?.loadUrl(authorizeUrl)
                return true
            }
            "token" -> {
                checking = true
                web?.stopLoading()
                message.text = "Проверяем доступ к Яндекс Музыке…"
                val token = result.getString("token")
                val generation = sessionGeneration
                worker.execute {
                    val verified = runCatching {
                        val response = JSONObject(Native.request(token, "account", ""))
                        check(response.optBoolean("ok")) { "Вход не подтверждён музыкальным API. Попробуйте другой аккаунт или способ входа." }
                    }
                    runOnUiThread {
                        if (destroyed || generation != sessionGeneration) return@runOnUiThread
                        if (verified.isSuccess) {
                            try {
                                TokenStore(this).save(token)
                                // Return only success: no credentials in activity intents or saved state.
                                setResult(RESULT_OK)
                                finish()
                            } catch (_: Exception) {
                                checking = false; message.text = "Не удалось защитить токен на устройстве. Повторите вход."
                            }
                        } else {
                            checking = false
                            message.text = verified.exceptionOrNull()?.message ?: "Ошибка входа"
                        }
                    }
                }
                return true
            }
            "deny" -> {
                web?.stopLoading()
                message.text = "Переход за пределы сайтов Яндекса заблокирован. Используйте другой способ входа, если он необходим."
                return true
            }
            "error" -> {
                web?.stopLoading()
                message.text = result.optString("message", "Начните вход заново")
                return true
            }
        }
        return false
    }

    private fun back() { if (!checking && web?.canGoBack() == true) web?.goBack() else finish() }
    // Android 11/12 fallback only; Android 13+ uses the native dispatcher above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Platform back navigation")
    override fun onBackPressed() { back() }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        destroyed = true
        sessionGeneration++
        worker.shutdownNow()
        web?.let { content.removeView(it); it.stopLoading(); it.webChromeClient = null; it.destroy() }
        web = null
        super.onDestroy()
    }
}
