package org.luma.music

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import java.util.concurrent.Executors
import org.json.*

class MainActivity : MaterialActivity(),LibraryActions.Observer {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var mini: TextView? = null
    private var miniArtist: TextView? = null
    private var miniToggle: LumaButton? = null
    private var miniProgress: ProgressBar? = null
    private var miniCover: AlbumArt? = null
    private var playerCover: AlbumArt? = null
    private val trackMarkers = mutableListOf<Triple<List<Song>, Int, TextView>>()
    private var playerTitle: TextView? = null
    private var playerSubtitle: TextView? = null
    private var playButton: LumaButton? = null
    private var heartButton:LumaButton?=null
    private var shuffleButton:LumaButton?=null
    private var repeatButton:LumaButton?=null
    private var heartTrackId=""
    private var seenLibraryRevision=-1L
    private var pendingReactionId:String?=null
    private var lastPlaying:Boolean?=null
    private var playerScreen=false
    private var playerAccent:Int?=null
    private var time: TextView? = null
    private var seek: SeekBar? = null
    private var dragging = false
    private var page = "Главная"
    private var creditScreen = false
    private var settingsSection = ""
    private var pendingUpdate: java.io.File? = null
    private var checkingUpdates = false
    private val hardwarePolicy by lazy {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        RenderPolicy.choose(manager.isLowRamDevice, memory.totalMem, manager.memoryClass)
    }
    private val economyPolicy = RenderPolicy(true)
    private val renderPolicy
        get() =
            if (preferences.getBoolean("reduce_effects", false)) economyPolicy else hardwarePolicy

    private val palette get() =
        AppThemes.resolve(preferences.getString("theme_mode","system").orEmpty(),
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES).let {
            if(Build.VERSION.SDK_INT>=31)it.copy(accent=getColor(
                if(it.light)android.R.color.system_accent1_600 else android.R.color.system_accent1_200))
            else it
        }

    private var token = ""
    private var demo = false
    @Volatile private var generation = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val apiRequests = LatestRequests()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var visible = false
    private val tick =
        object : Runnable {
            override fun run() {
                if (!visible) return
                refreshPlayer()
                handler.postDelayed(this, renderPolicy.uiIntervalMs)
            }
        }

    private val preferences by lazy { SettingsStore.get(this) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val white
        get() = palette.text

    private val muted
        get() = palette.muted

    private val mint
        get() = palette.accent

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(L10n.wrap(base))
    }
    private fun t(source: String, vararg args: Any) = L10n.text(this, source, *args)

    override fun onCreate(state: Bundle?) {
        setTheme(
            if (palette.light) R.style.Theme_Luma_Light
            else R.style.Theme_Luma_Dark
        )
        super.onCreate(state)
        // Public update feed no longer uses stored GitHub credentials.
        TokenStore(this, "github_auth").clear()
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){
            override fun handleOnBackPressed(){navigateBack()}
        })
        PlaybackService.shuffle = preferences.getBoolean("shuffle", false)
        PlaybackService.repeat = preferences.getBoolean("repeat", false)
        PlaybackService.restore(this)
        token = TokenStore(this).load().orEmpty()
        demo = state?.getBoolean("demo", false) ?: false
        page = state?.getString("page") ?: "Главная"
        if (token.isEmpty() && !demo) login() else shell(page,state?.getBoolean("settings",false)!=true)
        if (state?.getBoolean("settings", false) == true) settings(state.getString("settings_section").orEmpty())
        if(state?.getBoolean("player",false)==true && PlaybackService.queue.isNotEmpty())fullPlayer()
        handleOAuth(intent)
        intent.getStringExtra("settings_section")?.let {settings(it)}
        if(intent.getBooleanExtra("open_player",false) && PlaybackService.queue.isNotEmpty())fullPlayer()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun text(value: String, size: Float = 16f, color: Int = white, bold: Boolean = false, raw: Boolean = false) =
        LumaScreenComponents.text(this, preferences, value, size, color, bold, raw)

    private fun column() = LumaScreenComponents.column(this)

    private fun row() = LumaScreenComponents.row(this)

    private fun gap(target: LinearLayout, height: Int = 12) {
        LumaScreenComponents.gap(this, target, height)
    }

    private fun action(label: String, accent: Boolean = false, click: () -> Unit) =
        LumaButton(this).apply {
            text = t(label)
            textSize = 14f * preferences.getInt("font_size", 100) / 100f
            isAllCaps = false
            minHeight = dp(LumaDesign.TOUCH_TARGET_DP)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(if (accent) LumaDesign.CONTROL_RADIUS_DP else 16)
            setTextColor(LumaDesign.contentColor(accent))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (accent) mint else if (palette.light) 0xFFE9EDF3.toInt()
                else if (palette.oled) 0xFF12151B.toInt() else 0xFF202630.toInt()
            )
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(
                if (accent) mint else if (palette.light) 0xFFDDE2EA.toInt() else 0xFF303846.toInt()
            )
            setPadding(dp(16), dp(9), dp(16), dp(9))
            contentDescription = t(label)
            setOnClickListener {
                if (preferences.getBoolean("haptics", true)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                click()
            }
        }

    private fun panel(target: LinearLayout): LinearLayout =
        LumaScreenComponents.panel(this, target)

    private fun backdrop() {
        AppThemes.active = palette
        creditScreen = false
        mini = null
        miniArtist = null
        miniToggle = null
        miniProgress=null;heartButton=null;shuffleButton=null;repeatButton=null
        heartTrackId="";seenLibraryRevision=-1;lastPlaying=null;playerScreen=false;playerAccent=null
        miniCover = null
        playerCover = null
        trackMarkers.clear()
        body = column()
        status = text("")
        generation++
        apiRequests.invalidate()
        playerTitle = null
        playerSubtitle = null
        playButton = null
        seek = null
        time = null
        val frame = FrameLayout(this)
        frame.setBackgroundColor(palette.background)
        root = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        // PhoneWindow.getInsetsController() can throw until setContentView creates DecorView.
        val bars =
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.light) bars else 0, bars)
        root.setOnApplyWindowInsetsListener { v, inset ->
            val bars = inset.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(
                dp(20) + bars.left,
                bars.top + dp(8),
                dp(20) + bars.right,
                bars.bottom + dp(8),
            )
            inset
        }
        root.requestApplyInsets()
    }

    private fun request(
        op: String,
        arg: String = "",
        finished: (() -> Unit)? = null,
        done: (Any) -> Unit,
    ) {
        val version = generation
        val credential = token
        status.text = "Загрузка…"
        apiRequests.submit {ticket ->
            if(destroyed || version!=generation || !ticket.valid) return@submit
            val result =
                try {
                    JSONObject(Native.request(credential, op, arg))
                } catch (_: Exception) {
                    JSONObject().put("ok", false).put("error", "Не удалось обработать ответ")
                }
            runOnUiThread {
                if (destroyed || version != generation || !ticket.valid) return@runOnUiThread
                finished?.invoke()
                if (result.optBoolean("ok")) {
                    status.text = ""
                    done(result.get("data"))
                } else status.text = result.optString("error", "Ошибка сервиса")
            }
        }
    }

    private fun login() {
        backdrop()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column()
        scroll.addView(body)
        gap(body, 24)
        body.addView(text(getString(R.string.app_name), 18f, mint, true))
        body.addView(text("Ваша музыка.\nВаш момент.", 36f, white, true))
        body.addView(
            text("Войдите в Яндекс Музыку, чтобы открыть свою коллекцию и Мою волну.", 15f, muted)
        )
        gap(body, 18)
        status = text("", 13f, muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(status)
        body.addView(action("Открыть окно входа Яндекса", true) {
            startActivityForResult(Intent(this, YandexLoginActivity::class.java), 12)
        })
        gap(body, 8)
        body.addView(action("Spotify") {startActivity(Intent(this,SpotifyActivity::class.java))})
        body.addView(action("Загрузки") {startActivity(Intent(this,OfflineActivity::class.java))})
        body.addView(action("Браузер · свой OAuth-клиент") { browserLogin() })
        body.addView(
            text(
                "Вход как в YAYMA: защищённая страница Яндекса в отдельном окне. Пароль обрабатывает Яндекс.",
                13f,
                muted,
            )
        )
        val alternative = action("Другой способ: OAuth-токен") {}
        body.addView(alternative)
        val card = panel(body)
        card.visibility = View.GONE
        alternative.setOnClickListener {
            card.visibility = if (card.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        card.addView(text("Вход в аккаунт", 24f, white, true))
        card.addView(text("Личный OAuth-токен", 13f, mint, true))
        val input =
            EditText(this).apply {
                filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
                hint = t("Вставьте токен")
                setTextColor(white)
                setHintTextColor(muted)
                setSingleLine()
                background = surface(dp(16).toFloat())
                setPadding(dp(14), dp(14), dp(14), dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
        card.addView(input)
        val show =
            CheckBox(this).apply {
                text = t("Показать токен")
                setTextColor(muted)
                setOnCheckedChangeListener { _, checked ->
                    val cursor = input.selectionStart
                    input.transformationMethod =
                        if (checked) null
                        else android.text.method.PasswordTransformationMethod.getInstance()
                    input.setSelection(cursor.coerceAtLeast(0).coerceAtMost(input.length()))
                }
            }
        card.addView(show)
        val tokenNotice =
            text(
                "Токен шифруется на устройстве через Android Keystore. Пароль Яндекса не нужен.",
                13f,
                muted,
            )
        card.addView(tokenNotice)
        lateinit var submit: TextView
        fun connect() {
            if (!submit.isEnabled) return
            val candidate = input.text.toString().trim()
            if (candidate.isEmpty()) {
                status.text = "Сначала вставьте OAuth-токен"
                input.requestFocus()
                return
            }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            submit.isEnabled = false
            submit.alpha = .55f
            submit.text = "Проверяем аккаунт…"
            token = candidate
            generation++
            request(
                "account",
                finished = {
                    submit.isEnabled = true
                    submit.alpha = 1f
                    submit.text = "Войти"
                },
            ) {
                try {
                    TokenStore(this).save(token)
                    input.text.clear()
                    demo = false
                    shell("Главная")
                } catch (_: Exception) {
                    status.text = "Не удалось защитить токен. Попробуйте ещё раз."
                }
            }
        }
        submit = action("Войти", true) { connect() }
        card.addView(submit)
        input.setOnEditorActionListener { _, id, _ ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                connect()
                true
            } else false
        }

        gap(body)
        body.addView(
            action("Попробовать без аккаунта") {
                demo = true
                shell("Главная")
            }
        )
        body.addView(
            text(
                "Неофициальный клиент. Для каталога и прослушивания нужна подписка Яндекс Музыки.",
                12f,
                muted,
            )
        )
    }

    private fun browserLogin() {
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val client =
            prefs.getString("client", null).orEmpty().ifBlank { BuildConfig.YANDEX_CLIENT_ID }
        if (client.isNotBlank()) {
            launchOAuth(client)
            return
        }
        configureOAuth()
    }

    private fun configureOAuth() {
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val input =
            EditText(this).apply {
                hint = t("Client ID OAuth-приложения")
                setSingleLine()
                setText(prefs.getString("client", ""))
            }
        LumaDialog(this)
            .setTitle("Вход через Яндекс")
            .setMessage(
                "Введите Client ID вашего OAuth-приложения с доступом к Яндекс Музыке. В нём должен быть зарегистрирован Redirect URI: yamusicrust://oauth. Client ID не является паролем."
            )
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть браузер") { _, _ ->
                val client = input.text.toString().trim()
                if (!client.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
                    status.text = "Некорректный Client ID"
                    return@setPositiveButton
                }
                prefs.edit().putString("client", client).apply()
                launchOAuth(client)
            }
            .show()
    }

    private fun launchOAuth(client: String) {
        val state = java.util.UUID.randomUUID().toString()
        getSharedPreferences("oauth", MODE_PRIVATE)
            .edit()
            .putString("state", state)
            .putLong("created", System.currentTimeMillis())
            .apply()
        val uri =
            android.net.Uri.parse("https://oauth.yandex.ru/authorize")
                .buildUpon()
                .appendQueryParameter("response_type", "token")
                .appendQueryParameter("client_id", client)
                .appendQueryParameter("redirect_uri", "yamusicrust://oauth")
                .appendQueryParameter("state", state)
                .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            status.text = "Не найден браузер"
        }
    }

    private fun handleOAuth(incoming: Intent?) {
        val uri = incoming?.data ?: return
        if (uri.scheme != "yamusicrust" || uri.host != "oauth") return
        // Do not leave credentials attached to the activity intent.
        intent = Intent(this, MainActivity::class.java)
        val prefs = getSharedPreferences("oauth", MODE_PRIVATE)
        val params =
            android.net.Uri.parse(
                "https://callback.invalid/?" + (uri.fragment ?: uri.query.orEmpty())
            )
        val expected = prefs.getString("state", null)
        val age = System.currentTimeMillis() - prefs.getLong("created", 0)
        prefs.edit().remove("state").remove("created").apply()
        if (
            expected == null || params.getQueryParameter("state") != expected || age !in 0..600000
        ) {
            status.text = "Вход отклонён: неверный или просроченный ответ. Начните вход заново."
            return
        }
        val credential = params.getQueryParameter("access_token")
        if (credential.isNullOrBlank()) {
            status.text = "Яндекс не предоставил токен. Проверьте права OAuth-приложения."
            return
        }
        token = credential
        generation++
        request("account") {
            try {
                TokenStore(this).save(token)
                demo = false
                shell("Главная")
            } catch (_: Exception) {
                token = ""
                status.text = "Не удалось сохранить токен"
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        handleOAuth(newIntent)
        newIntent.getStringExtra("settings_section")?.let {settings(it)}
        if(newIntent.getBooleanExtra("open_player",false) && PlaybackService.queue.isNotEmpty())fullPlayer()
    }

    private fun shell(target: String, loadContent: Boolean = true) {
        page = target
        backdrop()

        val header = row().apply {
            setPadding(0, dp(8), 0, dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        val identity = row().apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(
            text("L", 26f, mint, true, raw = true).apply {
                gravity = Gravity.CENTER
                background = surface(dp(14).toFloat(), true)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) },
        )
        val brand = column()
        brand.addView(text("LUMA MUSIC", 15f, white, true, raw = true))
        brand.addView(text("ВАША МУЗЫКА · ВАШ РИТМ", 9f, muted, true, raw = true))
        identity.addView(brand)
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            MusicUi.icon(this, android.R.drawable.ic_menu_manage, "Настройки", muted) { settings() },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(header)

        status = text(
            if (demo) "ДЕМО · ЛОКАЛЬНЫЙ РЕЖИМ" else "",
            10f,
            mint,
            true,
            raw = true,
        ).apply {
            visibility = if (demo) View.VISIBLE else View.GONE
            background = surface(dp(14).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(10), 0, dp(10))
        }
        body = column()
        body.clipChildren = false
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val miniPanel = column().apply {
            background = surface(dp(22).toFloat())
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        val miniBar = row()
        miniCover = AlbumArt(this).apply {
            prominent = true
            swipeTracks(this) { fullPlayer() }
        }
        miniBar.addView(
            miniCover,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) },
        )
        val miniLabels = column().apply {
            isFocusable = true
            swipeTracks(this) { fullPlayer() }
        }
        mini = text(t("Выберите трек"), 14f, white, true, raw = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        miniArtist = text(t("Открыть плеер"), 11f, muted, raw = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        miniLabels.addView(mini)
        miniLabels.addView(miniArtist)
        miniBar.addView(miniLabels, LinearLayout.LayoutParams(0, -2, 1f))
        miniToggle = MusicUi.icon(this, R.drawable.luma_play, "Воспроизвести", mint) {
            PlaybackService.command(this, "toggle")
            refreshPlayer()
        }
        miniBar.addView(miniToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniBar.addView(
            MusicUi.icon(this, R.drawable.luma_next, "Следующий трек", muted) {
                PlaybackService.command(this, "next")
            },
            LinearLayout.LayoutParams(dp(44), dp(48)),
        )
        miniPanel.addView(miniBar)
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
        }
        miniPanel.addView(
            miniProgress,
            LinearLayout.LayoutParams(-1, dp(2)).apply {
                leftMargin = dp(4)
                rightMargin = dp(8)
                topMargin = dp(5)
            },
        )
        root.addView(miniPanel)
        gap(root, 8)

        val nav = com.google.android.material.bottomnavigation.BottomNavigationView(this).apply {
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(palette.background)
        }
        val destinations = listOf("Главная", "Поиск", "Медиатека", "Настройки")
        val icons = listOf(
            R.drawable.luma_wave,
            android.R.drawable.ic_menu_search,
            android.R.drawable.ic_menu_agenda,
            android.R.drawable.ic_menu_manage,
        )
        val labels = listOf("Волна", "Поиск", "Коллекция", "Настройки")
        destinations.forEachIndexed { index, _ ->
            nav.menu.add(0, index + 1, index, t(labels[index])).setIcon(icons[index])
        }
        nav.selectedItemId = destinations.indexOf(page).coerceAtLeast(0) + 1
        nav.setOnItemSelectedListener { item ->
            val destination = destinations[item.itemId - 1]
            when (destination) {
                "Настройки" -> settings()
                else -> if (destination != page) shell(destination)
            }
            true
        }
        root.addView(nav)

        if (loadContent) {
            when (target) {
                "Поиск" -> search()
                "Медиатека" -> library()
                else -> home()
            }
        }
        refreshPlayer()
    }

    private fun home() {
        val stations = NativeCore.call("wave_stations", "{}") as JSONArray
        val chosen = preferences.getString("wave_station", "user:onyourwave")
        val station = (0 until stations.length())
            .map { stations.getJSONObject(it) }
            .firstOrNull { it.getString("id") == chosen }
        val currentSong = PlaybackService.queue.getOrNull(PlaybackService.index)

        HomeScreen(
            context = this,
            target = body,
            text = ::text,
            row = ::row,
            gap = ::gap,
            panel = ::panel,
            action = ::action,
            dp = ::dp,
            surface = { radius -> surface(radius) },
            collectionCards = ::collectionCards,
        ).render(
            data = HomeScreen.Data(
                demo = demo,
                stationDescription = if (demo) {
                    "Локальный режим: можно послушать тестовый звук без аккаунта."
                } else {
                    t(station?.getString("title") ?: "Музыка, подобранная для вас")
                },
                currentSong = currentSong,
                textColor = white,
                mutedColor = muted,
                accentColor = mint,
            ),
            actions = HomeScreen.Actions(
                startWave = ::startWave,
                playDemo = ::playDemo,
                configureWave = { WaveSheet.show(this) { startWave() } },
                openPlayer = ::fullPlayer,
                openTrackMenu = { song ->
                    TrackSheet.show(this, song) {
                        heartTrackId = ""
                        refreshPlayer()
                    }
                },
                openFile = ::openFile,
            ),
        )
    }

    private fun startWave(){
        if(demo){playDemo();return}
        if(token.isBlank()){notice("Войдите в Яндекс Музыку");return}
        generation++
        val station=preferences.getString("wave_station","user:onyourwave").orEmpty()
        request("wave",JSONObject().put("station",station).put("previous","").toString()){data->
            val songs=(data as JSONArray).songs()
            if(songs.isEmpty()){notice("Волна пока недоступна");return@request}
            PlaybackService.select(this,songs,0,true);fullPlayer()
        }
    }

    private fun collectionCards(target: LinearLayout) {
        CollectionCardsRenderer(
            context = this,
            text = ::text,
            row = ::row,
            column = ::column,
            dp = ::dp,
            surface = { radius -> surface(radius) },
            textColor = white,
            mutedColor = muted,
            accentColor = mint,
            openCollection = { kind -> showCollection(kind) },
            openOffline = { startActivity(Intent(this, OfflineActivity::class.java)) },
            openSpotify = { startActivity(Intent(this, SpotifyActivity::class.java)) },
        ).render(target)
    }

    private fun search() {
        SearchScreen(
            context = this,
            target = body,
            text = ::text,
            row = ::row,
            column = ::column,
            gap = ::gap,
            action = ::action,
            dp = ::dp,
            surface = { radius -> surface(radius) },
            textColor = white,
            mutedColor = muted,
            translate = { value -> t(value) },
            canSearch = { token.isNotBlank() },
            beginSearch = { generation++ },
            searchCatalog = { query, kind, page, onResult ->
                val args = JSONObject()
                    .put("query", query)
                    .put("kind", kind)
                    .put("page", page)
                    .toString()
                request("catalog_search", args) { raw -> onResult(raw as JSONObject) }
            },
            renderResults = ::renderCatalogResults,
            notify = ::notice,
        ).render()
    }

    private fun renderCatalogResults(target: LinearLayout, items: JSONArray, kind: String) {
        CatalogResultsRenderer(
            context = this,
            text = ::text,
            row = ::row,
            column = ::column,
            dp = ::dp,
            surface = { radius -> surface(radius) },
            renderTracks = ::renderTracks,
            openPlaylist = { title, id -> showTracks(title, "playlist", id) },
            notify = ::notice,
            textColor = white,
            mutedColor = muted,
        ).render(target, items, kind)
    }

    private fun library() {
        LibraryScreen(
            target = body,
            text = ::text,
            action = ::action,
            gap = ::gap,
            collectionCards = ::collectionCards,
            hasAccount = token.isNotBlank(),
            loadPlaylists = { onResult ->
                request("library") { data -> onResult(data as JSONArray) }
            },
            openPlaylist = { title, id -> showTracks(title, "playlist", id) },
            openFile = ::openFile,
            textColor = white,
            mutedColor = muted,
        ).render()
    }

    private fun showCollection(kind:String,offset:Int=0,refresh:Boolean=false){
        shell("Медиатека",false)
        body.addView(text(if(kind=="likes")"Избранное" else "Не нравится",30f,white,true))
        body.addView(action("Обновить"){showCollection(kind,0,true)})
        if(token.isBlank()){body.addView(text("Войдите в Яндекс Музыку",15f,muted));return}
        val args=JSONObject().put("kind",kind).put("offset",offset).put("limit",renderPolicy.pageSize).put("refresh",refresh)
        request("collection_page",args.toString()){raw->
            val data=raw as JSONObject
            renderTracks(body,data.getJSONArray("tracks").songs())
            val navigation=row()
            if(offset>0)navigation.addView(action("‹ Назад"){showCollection(kind,maxOf(0,offset-renderPolicy.pageSize))},LinearLayout.LayoutParams(0,-2,1f))
            if(data.optBoolean("hasMore"))navigation.addView(action("Ещё ›"){showCollection(kind,data.getInt("nextOffset"))},LinearLayout.LayoutParams(0,-2,1f))
            body.addView(navigation)
            body.addView(text(t("Всего: %1\$d · доступно до %2\$d",data.optInt("total"),data.optInt("limit",5000)),12f,muted))
        }
    }

    private fun JSONArray.songs() = (0 until length()).map { Song.from(getJSONObject(it)) }

    private fun showTracks(title: String, op: String, arg: String = "") {
        shell("Медиатека", false)
        generation++
        body.removeAllViews()
        body.addView(text(title, 30f, white, true,raw=true))
        request(op, arg) { renderTracks(body, (it as JSONArray).songs()) }
    }

    private fun renderTracks(target: LinearLayout, songs: List<Song>) {
        TrackListRenderer(
            context = this,
            text = ::text,
            column = ::column,
            row = ::row,
            action = ::action,
            dp = ::dp,
            pageSize = renderPolicy.pageSize,
            trackMarkers = trackMarkers,
            initialIndex = if (songs === PlaybackService.queue) PlaybackService.index else 0,
            mutedColor = muted,
            onSelect = { queue, index ->
                PlaybackService.select(this, queue, index)
                refreshPlayer()
            },
            onUnavailable = { notice("Трек недоступен") },
            onTrackMenu = { song ->
                TrackSheet.show(this, song) {
                    heartTrackId = ""
                    refreshPlayer()
                }
            },
            formatRange = { first, last, total -> t("%1\$d–%2\$d из %3\$d", first, last, total) },
        ).render(target, songs)
    }

    private fun fullPlayer() {
        if (PlaybackService.queue.isEmpty()) {
            notice("Сначала выберите трек")
            return
        }

        backdrop()
        playerScreen = true
        val views = FullPlayerScreen(
            context = this,
            root = root,
            text = ::text,
            row = ::row,
            column = ::column,
            panel = ::panel,
            action = ::action,
            dp = ::dp,
            textColor = white,
            mutedColor = muted,
            accentColor = mint,
            translate = { value -> t(value) },
            swipeTracks = { view, onClick -> swipeTracks(view, onClick) },
            isCurrentCover = { cover -> playerCover === cover },
            actions = FullPlayerScreen.Actions(
                collapse = { shell(page) },
                openQueue = { startActivity(Intent(this, QueueActivity::class.java)) },
                openCurrentTrack = ::currentTrackSheet,
                likeCurrent = ::likeCurrent,
                seekStarted = { dragging = true },
                seekStopped = { progress ->
                    PlaybackService.instance?.seek(progress)
                    dragging = false
                },
                jump = ::jump,
                toggleShuffle = {
                    PlaybackService.shuffle = !PlaybackService.shuffle
                    preferences.edit().putBoolean("shuffle", PlaybackService.shuffle).apply()
                    refreshPlayer()
                },
                previous = { PlaybackService.command(this, "previous") },
                togglePlayback = {
                    PlaybackService.command(this, "toggle")
                    refreshPlayer()
                },
                next = { PlaybackService.command(this, "next") },
                toggleRepeat = {
                    PlaybackService.repeat = !PlaybackService.repeat
                    preferences.edit().putBoolean("repeat", PlaybackService.repeat).apply()
                    refreshPlayer()
                },
                showLyrics = ::showLyrics,
                downloadCurrent = {
                    PlaybackService.queue.getOrNull(PlaybackService.index)?.let { song ->
                        runCatching { OfflineDownloads.enqueue(this, song) }
                            .onSuccess { notice("Загрузка добавлена в очередь") }
                            .onFailure { notice(it.message ?: "Не удалось скачать трек") }
                    }
                },
                openSleepTimer = { settings("player") },
                onCoverImage = ::applyCoverTone,
            ),
        ).render()

        body = views.body
        playerCover = views.cover
        playerTitle = views.title
        playerSubtitle = views.subtitle
        heartButton = views.heart
        status = views.status
        seek = views.seek
        time = views.time
        shuffleButton = views.shuffle
        playButton = views.play
        repeatButton = views.repeat
        refreshPlayer()
        applyCoverTone(views.cover.image)
    }

    private fun currentTrackSheet(){
        PlaybackService.queue.getOrNull(PlaybackService.index)?.let {song->TrackSheet.show(this,song){heartTrackId="";refreshPlayer()}}
    }
    internal fun openMusicSettings(section:String){settings(section)}
    private fun jump(delta:Int){
        val service=PlaybackService.instance ?: return
        val duration=service.duration().toLong()
        if(duration<=0){notice("Перемотка для этого потока пока недоступна");return}
        service.seek((service.position().toLong()+delta).coerceIn(0,duration).toInt());refreshPlayer()
    }
    private fun notice(message:String){if(!destroyed)Toast.makeText(this,t(message),Toast.LENGTH_SHORT).show()}
    private fun likeCurrent(){
        val song=PlaybackService.queue.getOrNull(PlaybackService.index) ?: return
        if(token.isBlank() || song.uri!=null){notice("Войдите в Яндекс Музыку");return}
        val state=LibraryActions.cached(token,song.id)
        if(LibraryActions.react(this,token,song,"likes",!state.optBoolean("liked",false),this)){
            pendingReactionId=song.id;refreshPlayer()
        }else notice("Дождитесь завершения предыдущего действия")
    }
    override fun reactionFinished(id:String,result:JSONObject){
        if(pendingReactionId==id)pendingReactionId=null
        heartTrackId=""
        if(!destroyed && visible){
            refreshPlayer();notice(if(result.optBoolean("ok"))"Отметка сохранена" else result.optString("error","Не удалось изменить отметку"))
        }
    }

    private fun showLyrics() {
        if (PlaybackService.queue.getOrNull(PlaybackService.index) == null) return
        LumaDialog(this).setTitle("Текст песни")
            .setMessage("Найти текст в LrcLib? Пока экран текста открыт, сервис получит названия треков и исполнителей.")
            .setNegativeButton("Отмена", null)
            .setNeutralButton("LRC / TXT") { _, _ ->
                startActivity(Intent(this,LyricsActivity::class.java).putExtra("localOnly",true))
            }
            .setPositiveButton("Найти") { _, _ -> startActivity(Intent(this, LyricsActivity::class.java)) }
            .show()
    }

    private fun applyCoverTone(image:CoverImages.Image?) {
        val tone=if(preferences.getBoolean("cover_colors",true) && image!=null)
            if(palette.oled)image.oled else if(palette.light)image.light else image.dark else null
        val accent=tone?.accent ?: mint
        playerAccent=accent;heartTrackId=""
        root.setBackgroundColor(tone?.surface ?: palette.background)
        seek?.progressTintList=android.content.res.ColorStateList.valueOf(accent)
        seek?.thumbTintList=android.content.res.ColorStateList.valueOf(accent)
        playButton?.backgroundTintList=android.content.res.ColorStateList.valueOf(accent)
        playButton?.iconTint=android.content.res.ColorStateList.valueOf(tone?.onAccent ?: if(com.google.android.material.color.MaterialColors.isColorLight(accent))Color.BLACK else Color.WHITE)
    }

    private fun refreshPlayer() {
        val song=PlaybackService.queue.getOrNull(PlaybackService.index)
        miniCover?.bind(song?.cover);playerCover?.bind(song?.cover,true)
        mini?.text=song?.title ?: t("Выберите трек");miniArtist?.text=song?.artist ?: t("Музыка появится здесь")
        miniToggle?.isEnabled=song!=null
        if(lastPlaying!=PlaybackService.playing){
            lastPlaying=PlaybackService.playing
            val icon=if(PlaybackService.playing)R.drawable.luma_pause else R.drawable.luma_play
            val label=t(if(PlaybackService.playing)"Приостановить" else "Воспроизвести")
            miniToggle?.setIconResource(icon);playButton?.setIconResource(icon)
            miniToggle?.contentDescription=label;playButton?.contentDescription=label
        }
        trackMarkers.forEach {(songs,index,view)->
            val current=index==PlaybackService.index && songs.getOrNull(index)?.id==song?.id && song!=null
            view.text=if(current)"●" else "";view.visibility=if(current)View.VISIBLE else View.GONE
            view.setTextColor(Color.BLACK)
        }
        playerTitle?.text=song?.title ?: t("Выберите трек");playerSubtitle?.text=song?.artist.orEmpty()
        if(playerScreen)status.text=if(PlaybackService.message in listOf("Сейчас играет","Пауза","Воспроизведение остановлено"))"" else t(PlaybackService.message)
        val accent=playerAccent ?: mint
        shuffleButton?.iconTint=android.content.res.ColorStateList.valueOf(if(PlaybackService.shuffle)accent else muted)
        repeatButton?.iconTint=android.content.res.ColorStateList.valueOf(if(PlaybackService.repeat)accent else muted)
        heartButton?.let {heart->
            val remote=song!=null && song.uri==null && song.id.isNotEmpty() && song.id.all {it.isDigit()} && token.isNotBlank()
            heart.isEnabled=remote && pendingReactionId!=song?.id
            if(heartTrackId!=song?.id || seenLibraryRevision!=LibraryActions.revision){
                heartTrackId=song?.id.orEmpty();seenLibraryRevision=LibraryActions.revision
                val liked=if(remote)LibraryActions.cached(token,song!!.id).optBoolean("liked",false) else false
                heart.setIconResource(if(liked)R.drawable.luma_heart else R.drawable.luma_heart_outline)
                heart.iconTint=android.content.res.ColorStateList.valueOf(if(liked)accent else muted)
                heart.contentDescription=t(if(liked)"Убрать из избранного" else "Добавить в избранное")
            }
        }
        val service=PlaybackService.instance
        val duration=service?.duration() ?: 0;val position=service?.position() ?: 0
        miniProgress?.max=duration;miniProgress?.progress=position
        if(!dragging){seek?.max=duration;seek?.progress=position}
        fun clock(ms:Int)=(ms/60000).toString()+":"+String.format(java.util.Locale.ROOT,"%02d",(ms/1000)%60)
        time?.text=clock(position)+"  /  "+clock(duration)
    }

    private fun settingRow(parent: LinearLayout, title: String, summary: String, onClick: () -> Unit) {
        val item = row().apply {
            minimumHeight = dp(68)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = t(title)+". "+t(summary)
            setOnClickListener { onClick() }
        }
        val labels = column()
        labels.addView(text(title, 17f, white, true))
        labels.addView(text(summary, 13f, muted))
        item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        item.addView(text("›", 24f, muted), LinearLayout.LayoutParams(dp(24), -2))
        parent.addView(item)
    }

    private fun settingSwitch(parent: LinearLayout, title: String, summary: String, key: String, default: Boolean,
                              changed: (Boolean) -> Unit = {}) {
        parent.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = t(title)
            textSize = 16f * preferences.getInt("font_size", 100) / 100f
            setTextColor(white)
            minHeight = dp(52)
            isChecked = preferences.getBoolean(key, default)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean(key, enabled).apply()
                changed(enabled)
                if(key in setOf("unmetered_only","preload_next","preload_wifi_only","shuffle"))PlaybackService.instance?.refreshBufferPolicy()
            }
        })
        parent.addView(text(summary, 13f, muted))
        gap(parent, 8)
    }

    private fun settings(section: String = "") {
        backdrop()
        creditScreen = true
        settingsSection = section
        val schema=NativeCore.call("settings_schema","{}") as JSONObject
        val entries=schema.getJSONArray("categories")
        val categories=(0 until entries.length()).map {entries.getJSONObject(it)}
        val titles=categories.associate {it.getString("id") to it.getString("title")}
        root.addView(action(if (section.isBlank()) "‹ К музыке" else "‹ Настройки") { navigateBack() })
        root.addView(text(titles[section] ?: "Настройки", 30f, white, true))
        val scroll = ScrollView(this)
        body = column()
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("", 13f, muted)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(status)
        val mode = preferences.getString("theme_mode", "system")
        val modes = arrayOf("system", "dark", "light", "oled")
        val labels = arrayOf("Как в системе", "Тёмная", "Светлая", "OLED · Чёрная")
        val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        fun themed() {
            setTheme(if (palette.light) R.style.Theme_Luma_Light else R.style.Theme_Luma_Dark)
            settings(section)
        }
        fun group(title: String): LinearLayout = panel(body).also { it.addView(text(title, 21f, white, true)) }
        val schemaControls = SettingsSchemaRenderer(
            context = this,
            schema = schema,
            preferences = preferences,
            group = { title -> group(title) },
            settingSwitch = { parent, title, summary, key, default ->
                settingSwitch(parent, title, summary, key, default)
            },
            settingRow = ::settingRow,
            translate = { value -> t(value) },
            onChanged = { id ->
                PlaybackService.instance?.refreshAudioSettings()
                PlaybackService.instance?.refreshBufferPolicy()
                settings(id)
            },
        )
        when (section) {
            "network","lyrics" -> schemaControls.render(section)
            "data" -> {
                DataSettingsSection(
                    target = body,
                    action = ::action,
                    renderControls = { schemaControls.render(section) },
                    openHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                    openBookmarks = {
                        startActivity(Intent(this, HistoryActivity::class.java).putExtra("bookmarks", true))
                    },
                    openBackup = { startActivity(Intent(this, BackupActivity::class.java)) },
                ).render()
            }
            "audio" -> {
                AudioSettingsSection(
                    group = { title -> group(title) },
                    settingRow = ::settingRow,
                    renderControls = { schemaControls.render(section) },
                    openSystemEqualizer = {
                        val session = PlaybackService.instance?.audioSessionId() ?: 0
                        if (session == 0) {
                            status.text = "Сначала запустите трек"
                        } else {
                            val intent = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, session)
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                            runCatching { startActivity(intent) }
                                .onFailure { status.text = "На устройстве нет системного эквалайзера" }
                        }
                    },
                ).render()
            }
            "downloads" -> {
                DownloadsSettingsSection(
                    target = body,
                    action = ::action,
                    renderControls = { schemaControls.render(section) },
                    openDownloads = { startActivity(Intent(this, OfflineActivity::class.java)) },
                ).render()
            }
            "wave" -> {
                val choices = NativeCore.call("wave_stations", "{}") as JSONArray
                val selected = preferences.getString("wave_station", "user:onyourwave")
                val chosen = (0 until choices.length())
                    .map { choices.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == selected }
                WaveSettingsScreen(
                    group = { title -> group(title) },
                    settingRow = ::settingRow,
                    action = ::action,
                ).render(
                    data = WaveSettingsScreen.Data(
                        stationTitle = chosen?.getString("title") ?: "Моя волна",
                    ),
                    actions = WaveSettingsScreen.Actions(
                        configure = { WaveSheet.show(this) { settings("wave") } },
                        start = ::startWave,
                    ),
                )
            }
            "sound" -> {
                val network = getSystemService(android.net.ConnectivityManager::class.java)
                val metered = network.isActiveNetworkMetered
                val qualityKey = if (metered) "quality_mobile" else "quality_wifi"
                val selected = preferences.getInt(qualityKey, if (metered) 128 else 320)
                val networkState = if (network.activeNetwork == null) {
                    "Нет подключённой сети"
                } else {
                    t(
                        "%1\$s · до %2\$d кбит/с",
                        t(if (metered) "Сеть с лимитом" else "Безлимитная сеть"),
                        selected,
                    )
                }
                AudioQualitySettingsSection(
                    target = body,
                    group = { title -> group(title) },
                    text = ::text,
                    settingRow = ::settingRow,
                    settingSwitch = { parent, title, summary, key, default ->
                        settingSwitch(parent, title, summary, key, default)
                    },
                    action = ::action,
                ).render(
                    data = AudioQualitySettingsSection.Data(
                        networkState = networkState,
                        wifiQuality = preferences.getInt("quality_wifi", 320),
                        mobileQuality = preferences.getInt("quality_mobile", 128),
                        mutedColor = muted,
                        accentColor = mint,
                    ),
                    actions = AudioQualitySettingsSection.Actions(
                        chooseQuality = { title, key ->
                            val values = intArrayOf(128, 192, 320)
                            val currentValue = preferences.getInt(
                                key,
                                if (key == "quality_mobile") 128 else 320,
                            )
                            LumaDialog(this).setTitle(title)
                                .setSingleChoiceItems(
                                    arrayOf("Экономия · 128 кбит/с", "Баланс · 192 кбит/с", "Высокое · 320 кбит/с"),
                                    values.indexOf(currentValue).coerceAtLeast(0),
                                ) { dialog, index ->
                                    preferences.edit().putInt(key, values[index]).apply()
                                    PlaybackService.instance?.refreshBufferPolicy()
                                    dialog.dismiss()
                                    settings("sound")
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        },
                        replayCurrent = {
                            if (PlaybackService.index !in PlaybackService.queue.indices) {
                                status.text = "Сначала выберите трек"
                            } else {
                                PlaybackService.command(this, "play", PlaybackService.index)
                                fullPlayer()
                            }
                        },
                    ),
                )
            }
            "appearance" -> {
                AppearanceSettingsSection(
                    group = { title -> group(title) },
                    settingRow = ::settingRow,
                    settingSwitch = { parent, title, summary, key, default, changed ->
                        settingSwitch(parent, title, summary, key, default, changed)
                    },
                    text = ::text,
                ).render(
                    data = AppearanceSettingsSection.Data(
                        themeLabel = labels[modes.indexOf(mode).coerceAtLeast(0)],
                        languageName = L10n.name(this),
                        fontSizeSummary = t(
                            "%1\$d%% · вместе с системным размером текста",
                            preferences.getInt("font_size", 100),
                        ),
                        performanceSummary = t(
                            "Сейчас: %1\$s профиль · %2\$d треков на странице. Анимации фона и размытие не используются.",
                            t(if (renderPolicy.economy) "экономичный" else "обычный"),
                            renderPolicy.pageSize,
                        ),
                        mutedColor = muted,
                    ),
                    actions = AppearanceSettingsSection.Actions(
                        chooseTheme = {
                            LumaDialog(this).setTitle("Тема оформления")
                                .setSingleChoiceItems(labels, modes.indexOf(mode).coerceAtLeast(0)) { dialog, index ->
                                    preferences.edit().putString("theme_mode", modes[index]).apply()
                                    dialog.dismiss()
                                    themed()
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        },
                        chooseLanguage = {
                            val languages = NativeCore.call("languages", "{}") as JSONArray
                            val codes = listOf("system") + (0 until languages.length()).map {
                                languages.getJSONObject(it).getString("code")
                            }
                            val names = arrayOf(t("Как в системе")) + (0 until languages.length()).map {
                                val item = languages.getJSONObject(it)
                                item.getString("name") + if (item.optBoolean("preview")) {
                                    " · " + t("Предварительный перевод")
                                } else {
                                    ""
                                }
                            }
                            LumaDialog(this).setTitle("Язык приложения")
                                .setSingleChoiceItems(
                                    names,
                                    codes.indexOf(preferences.getString("language", "system")).coerceAtLeast(0),
                                ) { dialog, index ->
                                    preferences.edit().putString("language", codes[index]).apply()
                                    dialog.dismiss()
                                    recreate()
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        },
                        chooseFontSize = {
                            val sizes = intArrayOf(90, 100, 115, 130)
                            LumaDialog(this).setTitle("Размер текста")
                                .setSingleChoiceItems(
                                    arrayOf(
                                        "Компактный · 90%",
                                        "Обычный · 100%",
                                        "Крупный · 115%",
                                        "Очень крупный · 130%",
                                    ),
                                    sizes.indexOf(preferences.getInt("font_size", 100)).coerceAtLeast(0),
                                ) { dialog, index ->
                                    preferences.edit().putInt("font_size", sizes[index]).apply()
                                    dialog.dismiss()
                                    settings("appearance")
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        },
                        onEconomyChanged = { settings("appearance") },
                    ),
                )
            }
            "player" -> {
                PlayerSettingsSection(
                    group = { title -> group(title) },
                    settingRow = ::settingRow,
                    settingSwitch = { parent, title, summary, key, default, changed ->
                        settingSwitch(parent, title, summary, key, default, changed)
                    },
                ).render(
                    data = PlayerSettingsSection.Data(
                        sleepMinutesRemaining = PlaybackService.instance?.sleepMinutesRemaining() ?: 0,
                    ),
                    actions = PlayerSettingsSection.Actions(
                        onShuffleChanged = { PlaybackService.shuffle = it },
                        onRepeatChanged = { PlaybackService.repeat = it },
                        onPreloadChanged = { PlaybackService.instance?.refreshBufferPolicy() },
                        onSleepEndChanged = { if (it) PlaybackService.instance?.setSleepTimer(0) },
                        openSleepTimer = {
                            val service = PlaybackService.instance
                            if (service == null) {
                                status.text = "Сначала запустите трек"
                            } else {
                                val minutes = intArrayOf(0, 15, 30, 60, 90)
                                LumaDialog(this).setTitle("Таймер сна")
                                    .setItems(arrayOf("Выключить", "15 минут", "30 минут", "60 минут", "90 минут")) { _, index ->
                                        service.setSleepTimer(minutes[index])
                                        settings("player")
                                    }
                                    .show()
                            }
                        },
                        openNotifications = {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                            )
                        },
                    ),
                )
            }
            "storage" -> {
                val updateFiles = java.io.File(filesDir, "updates").listFiles()
                    ?.filter { it.isFile && it.name in setOf("update.apk", "update.part", "update.json", "update.json.tmp") }
                    .orEmpty()
                val bytes = updateFiles.sumOf { it.length() }
                StorageSettingsScreen(
                    group = { title -> group(title) },
                    text = ::text,
                    settingRow = ::settingRow,
                    action = ::action,
                ).render(
                    data = StorageSettingsScreen.Data(
                        downloadedUpdates = t(
                            "Скачанные обновления: %1\$s МиБ",
                            String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0),
                        ),
                        freeSpace = t(
                            "Свободно: %1\$s ГиБ",
                            String.format(
                                java.util.Locale.ROOT,
                                "%.1f",
                                android.os.StatFs(filesDir.path).availableBytes / 1073741824.0,
                            ),
                        ),
                        primaryColor = white,
                        mutedColor = muted,
                    ),
                    actions = StorageSettingsScreen.Actions(
                        clearArtworkCache = {
                            val version = generation
                            executor.execute {
                                val ok = runCatching { CoverImages.clearDisk(applicationContext) }.isSuccess
                                runOnUiThread {
                                    if (!destroyed && generation == version) {
                                        status.text = t(if (ok) "Кэш обложек очищен" else "Не удалось очистить кэш обложек")
                                    }
                                }
                            }
                        },
                        deleteUpdateFiles = {
                            LumaDialog(this).setTitle("Удалить скачанные обновления?")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить") { _, _ ->
                                    val version = generation
                                    executor.execute {
                                        val deleted = java.io.File(filesDir, "updates").listFiles()
                                            ?.filter {
                                                it.isFile && it.name in setOf(
                                                    "update.apk",
                                                    "update.part",
                                                    "update.json",
                                                    "update.json.tmp",
                                                )
                                            }
                                            ?.map { it.delete() }?.all { it } ?: true
                                        runOnUiThread {
                                            if (!destroyed && version == generation) {
                                                pendingUpdate = null
                                                settings("storage")
                                                status.text = if (deleted) "Файлы удалены" else "Не все файлы удалось удалить"
                                            }
                                        }
                                    }
                                }
                                .show()
                        },
                        openOfflineDownloads = { settings("downloads") },
                    ),
                )
            }
            "updates" -> {
                val updater = GitHubUpdater(this)
                val last = preferences.getLong("last_update_check", 0)
                UpdateSettingsSection(
                    context = this,
                    group = { title -> group(title) },
                    settingRow = ::settingRow,
                    settingSwitch = { parent, title, summary, key, default ->
                        settingSwitch(parent, title, summary, key, default)
                    },
                    text = ::text,
                    action = ::action,
                    gap = ::gap,
                ).render(
                    data = UpdateSettingsSection.Data(
                        version = current,
                        preview = preferences.getBoolean("test_updates", false),
                        lastAttempt = if (last <= 0) {
                            "Проверка ещё не запускалась"
                        } else {
                            t(
                                "Последняя попытка: %1\$s",
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT,
                                ).format(java.util.Date(last)),
                            )
                        },
                        partialDownloadInfo = updater.partialDownloadInfo(),
                        hasSavedUpdate = updater.hasSavedUpdate(),
                        mutedColor = muted,
                    ),
                    actions = UpdateSettingsSection.Actions(
                        changeChannel = { preview ->
                            preferences.edit().putBoolean("test_updates", preview).apply()
                            settings("updates")
                        },
                        checkUpdates = { checkUpdates() },
                        installSaved = {
                            installUpdate(java.io.File(filesDir, "updates/update.apk"))
                        },
                        installFromFile = {
                            startActivityForResult(
                                Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType("application/vnd.android.package-archive")
                                    .addCategory(Intent.CATEGORY_OPENABLE),
                                11,
                            )
                        },
                    ),
                )
            }
            "account" -> {
                AccountSettingsScreen(
                    target = body,
                    group = { title -> group(title) },
                    text = ::text,
                    action = ::action,
                    settingRow = ::settingRow,
                    gap = ::gap,
                ).render(
                    data = AccountSettingsScreen.Data(
                        yandexConnected = token.isNotBlank(),
                        mutedColor = muted,
                    ),
                    actions = AccountSettingsScreen.Actions(
                        connectSpotify = { startActivity(Intent(this, SpotifyActivity::class.java)) },
                        connectYandex = {
                            startActivityForResult(Intent(this, YandexLoginActivity::class.java), 12)
                        },
                        configureOAuth = ::configureOAuth,
                        logout = ::logout,
                    ),
                )
            }
            "about" -> showAbout(current)
            else -> {
                SettingsIndexScreen(
                    context = this,
                    target = body,
                    categories = categories,
                    text = ::text,
                    action = ::action,
                    panel = ::panel,
                    settingRow = ::settingRow,
                    dp = ::dp,
                    surface = { radius -> surface(radius) },
                    translate = { value -> t(value) },
                    hideKeyboard = { view ->
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                            .hideSoftInputFromWindow(view.windowToken, 0)
                    },
                    openSection = { id -> settings(id) },
                    resetSettings = {
                        LumaDialog(this).setTitle("Вернуть настройки по умолчанию?")
                            .setMessage("Тема, качество и поведение плеера будут сброшены. Аккаунт, очередь и ваши файлы сохранятся.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Сбросить") { _, _ ->
                                preferences.reset { saved ->
                                    if (!destroyed && creditScreen) {
                                        status.text = if (saved) "Настройки сброшены" else
                                            "Не удалось сохранить сброс. Проверьте свободное место."
                                    }
                                }
                                PlaybackService.shuffle = false
                                PlaybackService.repeat = false
                                PlaybackService.instance?.setSleepTimer(0)
                                recreate()
                            }
                            .show()
                    },
                    version = current,
                    textColor = white,
                    mutedColor = muted,
                ).render()
            }
        }
    }

    private fun showAbout(version: String) {
        val mode = preferences.getString("theme_mode", "system").orEmpty()
        val selectedTheme = when (mode) {
            "light" -> "Светлая"
            "dark" -> "Тёмная"
            "oled" -> "OLED · Чёрная"
            else -> "Как в системе"
        }
        val effectiveTheme = if (palette.oled) "OLED" else if (palette.light) "Светлая" else "Тёмная"
        val memory = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
        val channel = NativeCore.call(
            "version_channel",
            JSONObject().put("version", version).toString(),
        ) as String

        AboutScreen(
            target = body,
            text = ::text,
            panel = ::panel,
            action = ::action,
            settingRow = ::settingRow,
        ).render(
            data = AboutScreen.Data(
                version = version,
                channel = t(if (channel == "stable") "Стабильный" else "Предварительный"),
                build = BuildConfig.SOURCE_COMMIT.take(8),
                packageName = packageName,
                selectedTheme = t(selectedTheme),
                activeTheme = t(effectiveTheme),
                language = L10n.name(this),
                translationPreview = L10n.preview(this),
                model = Build.MANUFACTURER + " " + Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                abis = Build.SUPPORTED_ABIS.joinToString(),
                memory = String.format(java.util.Locale.ROOT, "%.1f GiB", memory.totalMem / 1073741824.0),
                renderProfile = t(if (renderPolicy.economy) "Экономичный" else "Обычный"),
                authorCredit = getString(R.string.author_credit),
                textColor = white,
                mutedColor = muted,
                accentColor = mint,
            ),
            actions = AboutScreen.Actions(
                openWhatsNew = { startActivity(Intent(this, WhatsNewActivity::class.java)) },
                openUrl = ::openAuthorLink,
                copySupportInfo = {
                    val reportData = JSONObject()
                        .put("version", version)
                        .put("build", BuildConfig.SOURCE_COMMIT)
                        .put("android", Build.VERSION.RELEASE)
                        .put("api", Build.VERSION.SDK_INT)
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                        .put("abis", Build.SUPPORTED_ABIS.joinToString())
                        .put("theme", mode)
                        .put("language", L10n.code(this))
                        .put("economy", renderPolicy.economy)
                        .put("channel", channel)
                        .put("quality_wifi", preferences.getInt("quality_wifi", 320))
                        .put("quality_mobile", preferences.getInt("quality_mobile", 128))
                        .put(
                            "metered",
                            getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered,
                        )
                    val report = NativeCore.call("support_report", reportData.toString()) as String
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Luma Music", report))
                    status.text = "Сведения скопированы. Токенов и адресов аудио в них нет."
                },
                openLicense = {
                    val license = assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
                    val scroll = ScrollView(this).apply {
                        addView(text(license, 13f, white, false, true))
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    }
                    LumaDialog(this).setTitle("GPL-3.0 · YAYMA / Luma")
                        .setView(scroll)
                        .setPositiveButton("Закрыть", null)
                        .show()
                },
            ),
        )
    }

    private fun openAuthorLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { status.text = "Не найдено приложение для открытия ссылки" }
    }

    private fun checkUpdates(manual: Boolean = true) {
        if (checkingUpdates) return
        val now = System.currentTimeMillis()
        if (
            !manual && !UpdatePolicy.dailyCheckDue(now, preferences.getLong("last_update_check", 0))
        )
            return
        checkingUpdates = true
        preferences.edit().putLong("last_update_check", now).apply()
        val version = generation
        if (manual) status.text = "Проверяем GitHub…"
        executor.execute {
            try {
                val updater = GitHubUpdater(this)
                val release = updater.check(preferences.getBoolean("test_updates", false))
                runOnUiThread {
                    checkingUpdates = false
                    if (destroyed || version != generation) return@runOnUiThread
                    if (release == null) {
                        if (manual) status.text = "Нового выпуска в выбранном канале нет"
                        return@runOnUiThread
                    }
                    status.text = t("Найден %1\$s",release.tag)
                    LumaDialog(this)
                        .setTitle(release.tag)
                        .setMessage(release.notes)
                        .setNegativeButton("Позже", null)
                        .setPositiveButton("Скачать") { _, _ ->
                            executor.execute {
                                try {
                                    val file =
                                        updater.download(release) { percent ->
                                            runOnUiThread {
                                                if (!destroyed && version == generation)
                                                    status.text = t("Загрузка: %1\$d%%",percent)
                                            }
                                        }
                                    runOnUiThread {
                                        if (!destroyed && version == generation) installUpdate(file)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        if (!destroyed && version == generation)
                                            status.text = e.message ?: "Ошибка обновления"
                                    }
                                }
                            }
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    checkingUpdates = false
                    if (manual && !destroyed && version == generation)
                        status.text = e.message ?: "Ошибка проверки"
                }
            }
        }
    }

    private fun installUpdate(file: java.io.File) {
        if (checkingUpdates) return
        checkingUpdates = true
        status.text = "Проверяем APK перед установкой…"
        val activityGeneration = generation
        executor.execute {
            val verification = runCatching {
                val updater = GitHubUpdater(this)
                val metadata = java.io.File(filesDir, "updates/update.json")
                val persistent = java.io.File(filesDir, "updates/update.apk")
                if (file.canonicalFile == persistent.canonicalFile && metadata.isFile) {
                    require(updater.savedUpdate() != null) {
                        "Сохранённый APK не прошёл повторную проверку SHA-256 или подписи"
                    }
                }
                updater.validate(file)
            }
            runOnUiThread {
                checkingUpdates = false
                if (destroyed || activityGeneration != generation) return@runOnUiThread
                verification.onFailure { error ->
                    status.text = error.message ?: "Проверка APK не пройдена"
                }.onSuccess {
                    if (!packageManager.canRequestPackageInstalls()) {
                        pendingUpdate = file
                        LumaDialog(this)
                            .setTitle("Разрешение установки")
                            .setMessage(
                                "Разрешите Luma Music установку обновлений в системных настройках, затем вернитесь в приложение."
                            )
                            .setNegativeButton("Отмена") { _, _ -> pendingUpdate = null }
                            .setPositiveButton("Открыть") { _, _ ->
                                startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:$packageName"),
                                    )
                                )
                            }
                            .show()
                        return@onSuccess
                    }
                    try {
                        val uri = android.net.Uri.parse("content://$packageName.updates/update.apk")
                        val intent =
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri("Luma update", uri) }
                        startActivity(intent)
                    } catch (error: Exception) {
                        status.text = error.message ?: "Установка недоступна"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val file = pendingUpdate
        if (file != null && packageManager.canRequestPackageInstalls()) {
            pendingUpdate = null
            installUpdate(file)
            return
        }
        if (preferences.getBoolean("auto_update_check", true)) checkUpdates(false)
    }

    private fun navigateBack() {
        if(playerScreen){shell(page);return}
        if (creditScreen) {
            if (settingsSection.isNotBlank()) settings() else {
                creditScreen = false
                shell(page)
            }
        } else finish()
    }

    private fun logout() {
        LumaDialog(this)
            .setTitle("Выйти из аккаунта?")
            .setMessage("Воспроизведение остановится, сохранённая очередь будет очищена.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ ->
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.forgetQueue(this)
                TokenStore(this).clear()
                OfflineDownloads.forgetAccount(this)
                token = ""
                demo = false
                login()
            }
            .show()
    }

    private fun openFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE),
            10,
        )
    }

    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12 && resultCode == RESULT_OK) {
            val credential = TokenStore(this).load().orEmpty()
            if (credential.isNotEmpty()) {
                stopService(Intent(this, PlaybackService::class.java))
                PlaybackService.forgetQueue(this)
                token = credential
                demo = false
                shell("Главная")
            }
            return
        }
        if (requestCode == 11 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val version = generation
                status.text = "Проверка APK…"
                executor.execute {
                    try {
                        val folder = java.io.File(filesDir, "updates").apply { mkdirs() }
                        // A manually selected APK is not a GitHub download; discard stale resume metadata.
                        listOf("update.json", "update.json.tmp", "update.part").forEach { java.io.File(folder, it).delete() }
                        val file = java.io.File(folder, "update.apk")
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output ->
                                val bytes = ByteArray(32768)
                                var count = 0L
                                while (true) {
                                    val n = input.read(bytes)
                                    if (n < 0) break
                                    count += n
                                    require(count <= UpdatePolicy.MAX_APK) { "APK слишком большой" }
                                    output.write(bytes, 0, n)
                                }
                            }
                        } ?: error("Не удалось открыть файл")
                        GitHubUpdater(this).validate(file)
                        runOnUiThread {
                            if (!destroyed && version == generation) installUpdate(file)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!destroyed && version == generation)
                                status.text = e.message ?: "Ошибка APK"
                        }
                    }
                }
            }
        if (requestCode == 10 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                var title = "Аудиофайл"
                contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) title = it.getString(0) }
                PlaybackService.queue =
                    listOf(Song("local", title, "На устройстве", uri = uri.toString()))
                PlaybackService.command(this, "play", 0)
                fullPlayer()
            }
    }

    private fun playDemo() {
        val file = java.io.File(cacheDir, "glass-demo.wav")
        if (!file.exists())
            assets.open("glass-demo.wav").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        PlaybackService.queue =
            listOf(
                Song(
                    "demo",
                    "Luma · тестовый звук",
                    "Локальный синтезатор · 8 секунд",
                    uri = android.net.Uri.fromFile(file).toString(),
                )
            )
        PlaybackService.command(this, "play", 0)
        fullPlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("demo", demo)
        outState.putBoolean("player",playerScreen)
        outState.putString("page", page)
        outState.putBoolean("settings", creditScreen)
        outState.putString("settings_section", settingsSection)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        PlaybackService.restore(this)
        heartTrackId=""
        handler.post(tick)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        apiRequests.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
