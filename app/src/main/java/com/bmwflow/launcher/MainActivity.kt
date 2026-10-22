package com.bmwflow.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class WeatherCol(
        val root: LinearLayout, val label: TextView, val icon: ImageView,
        val max: TextView, val min: TextView,
    )

    private class TileView(val def: TileDef, val root: FrameLayout, val icon: ImageView, val label: TextView) {
        var pkg: String? = null
    }

    private val ru = Locale("ru")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var st: Stage
    private lateinit var weather: WeatherRepository
    private lateinit var location: LocationHelper
    private lateinit var updates: UpdateUi

    private lateinit var clockBox: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherRow: LinearLayout
    private lateinit var weatherMsg: TextView
    private val weatherCols = ArrayList<WeatherCol>()
    private lateinit var icWifi: ImageView
    private lateinit var icSync: ImageView
    private lateinit var icLoc: ImageView
    private val tiles = ArrayList<TileView>()

    private var lastMinute = -1L
    private var lastDay: LocalDate? = null
    private var weatherBusy = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock(false)
            val now = TrustedClock.now()
            main.postDelayed(this, 1000 - now % 1000 + 20)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather(false)
            main.postDelayed(this, WEATHER_INTERVAL)
        }
    }

    private val openHiddenSettings = Runnable {
        clockBox.alpha = 1f
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrustedClock.restore(this)
        weather = WeatherRepository(this)
        location = LocationHelper(this)
        updates = UpdateUi(this, io)
        st = Stage.forActivity(this)

        val root = FrameLayout(this).apply { setBackgroundColor(FLOW_BG) }
        val stage = FrameLayout(this).apply { clipChildren = false }
        root.addView(stage, FrameLayout.LayoutParams(st.px(Stage.W), st.px(Stage.H), Gravity.CENTER))

        buildBackground(stage)
        buildClockAndWeather(stage)
        buildBrand(stage)
        buildStatus(stage)
        buildTiles(stage)
        setContentView(root)

        val cached = weather.cached()
        if (cached != null) renderWeather(cached) else renderWeatherMessage("Загрузка погоды…")

        registerNetwork()
        askLocationOnce()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        updateClock(true)
        updateStatus()
        refreshTiles()
        main.removeCallbacks(tick)
        main.post(tick)
        main.removeCallbacks(weatherTick)
        main.post(weatherTick)
        if (!TrustedClock.synced && isOnline()) syncClock()
        updates.onResume()
        main.removeCallbacks(updateCheck)
        main.postDelayed(updateCheck, 8000)
    }

    /** Проверка обновлений при запуске и не чаще раза в 6 часов. */
    private val updateCheck = Runnable {
        if (!isDestroyed && isOnline()) updates.check(force = false, reportResult = false)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        main.removeCallbacks(weatherTick)
        main.removeCallbacks(openHiddenSettings)
        main.removeCallbacks(updateCheck)
        clockBox.alpha = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        io.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Это домашний экран — «Назад» никуда не ведёт.
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        weather.markDirty()
        refreshWeather(false)
    }

    // ---------------------------------------------------------------- building

    private fun buildBackground(stage: FrameLayout) {
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.background)
        }
        // Фото 1600×600 на весь экран; справа затемнение под плитки, сверху — под часы и погоду.
        stage.place(bg, st, 0f, 0f, 1600f, 600f)

        val fadeRight = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00070A12, 0x33070A12, 0xA6070A12.toInt(), 0xCC070A12.toInt())
            )
        }
        stage.place(fadeRight, st, 0f, 0f, 1600f, 600f)

        val fadeTop = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3070A12.toInt(), 0x66070A12, 0x00070A12)
            )
        }
        stage.place(fadeTop, st, 0f, 0f, 1600f, 260f)
    }

    private fun buildClockAndWeather(stage: FrameLayout) {
        clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Fonts.light
            sizeU(st, 68f)
            includeFontPadding = false
            setSingleLine()
        }
        date = TextView(this).apply {
            setTextColor(0xFF8FB0E8.toInt())
            typeface = Fonts.regular
            sizeU(st, 22f)
            setSingleLine()
            setPadding(st.px(3f), st.px(10f), 0, 0)
        }
        clockBox.addView(clock)
        clockBox.addView(date)
        // Скрытые настройки: удерживать часы 3 секунды.
        clockBox.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.65f
                    main.postDelayed(openHiddenSettings, 3000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    main.removeCallbacks(openHiddenSettings)
                }
            }
            true
        }
        stage.place(clockBox, st, 30f, 40f, 206f, 140f)

        val card = FrameLayout(this).apply {
            background = roundedFill(st, 0xA6141B2A.toInt(), 18f, 0x26FFFFFF)
        }
        weatherRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 3) {
            if (i > 0) {
                val div = View(this).apply { setBackgroundColor(0x26FFFFFF) }
                weatherRow.addView(div, LinearLayout.LayoutParams(maxOf(1, st.px(1f)), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    topMargin = st.px(18f); bottomMargin = st.px(18f)
                })
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, st.px(14f), 0, 0)
            }
            val label = TextView(this).apply {
                setTextColor(0xFFD5DBE7.toInt()); typeface = Fonts.regular; sizeU(st, 18f); setSingleLine()
            }
            val icon = ImageView(this)
            val max = TextView(this).apply {
                setTextColor(Color.WHITE); typeface = Fonts.regular; sizeU(st, 29f); setSingleLine()
                includeFontPadding = false
            }
            val min = TextView(this).apply {
                setTextColor(0xFF8A93A6.toInt()); typeface = Fonts.regular; sizeU(st, 19f); setSingleLine()
            }
            col.addView(label)
            col.addView(icon, LinearLayout.LayoutParams(st.px(50f), st.px(50f)).apply {
                topMargin = st.px(6f); bottomMargin = st.px(8f)
            })
            col.addView(max)
            col.addView(min)
            weatherRow.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            weatherCols.add(WeatherCol(col, label, icon, max, min))
        }
        card.addView(weatherRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        weatherMsg = TextView(this).apply {
            setTextColor(0xFFB8C0D0.toInt()); typeface = Fonts.regular; sizeU(st, 19f)
            gravity = Gravity.CENTER
            setPadding(st.px(16f), 0, st.px(16f), 0)
            visibility = View.GONE
        }
        card.addView(weatherMsg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage.place(card, st, 246f, 34f, 324f, 184f)
    }

    private fun buildBrand(stage: FrameLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val stripe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in intArrayOf(0xFF4FA3E0.toInt(), 0xFF2350B8.toInt(), 0xFFE8322A.toInt())) {
            stripe.addView(View(this).apply { setBackgroundColor(c) },
                LinearLayout.LayoutParams(st.px(22f), st.px(4f)).apply { leftMargin = st.px(3f) })
        }
        val text = TextView(this).apply {
            text = "BMW FLOW"
            setTextColor(Color.WHITE)
            typeface = Fonts.bold
            sizeU(st, 20f)
            letterSpacing = 0.22f
            setShadowLayer(st.pxf(8f), 0f, 0f, 0xCC000000.toInt())
            setSingleLine()
        }
        box.addView(stripe)
        box.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = st.px(6f)
        })
        stage.place(box, st, 360f, 530f, 214f, 50f)
    }

    private fun buildStatus(stage: FrameLayout) {
        icWifi = ImageView(this).apply { setImageResource(R.drawable.ic_wifi) }
        icSync = ImageView(this).apply { setImageResource(R.drawable.ic_sync) }
        icLoc = ImageView(this).apply { setImageResource(R.drawable.ic_location) }
        stage.place(icWifi, st, 1446f, 20f, 32f, 32f)
        stage.place(icSync, st, 1498f, 20f, 32f, 32f)
        stage.place(icLoc, st, 1550f, 20f, 32f, 32f)
    }

    private fun buildTiles(stage: FrameLayout) {
        val x0 = 600f
        val y0 = 70f
        val gap = 16f
        val tw = (1584f - x0 - gap * 3) / 4f
        val th = (584f - y0 - gap) / 2f
        Tiles.all.forEachIndexed { i, def ->
            val col = i % 4
            val row = i / 4
            val root = FrameLayout(this).apply {
                background = tileBackground(st, def.color)
                isClickable = true
            }
            val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            root.addView(icon, FrameLayout.LayoutParams(st.px(128f), st.px(128f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = st.px(34f)
            })
            val label = TextView(this).apply {
                text = def.title
                setTextColor(Color.WHITE)
                typeface = Fonts.medium
                sizeU(st, 25f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(st.px(10f), 0, st.px(10f), 0)
            }
            root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM).apply { bottomMargin = st.px(26f) })
            val tv = TileView(def, root, icon, label)
            root.setOnClickListener { onTile(tv) }
            root.pressAnimation()
            stage.place(root, st, x0 + col * (tw + gap), y0 + row * (th + gap), tw, th)
            tiles.add(tv)
        }
    }

    // ---------------------------------------------------------------- tiles

    private fun refreshTiles() {
        io.execute {
            val apps = Apps.launchable(this)
            val resolved = tiles.map { t ->
                val pkg = Apps.resolve(this, t.def, apps)
                val icon: Drawable? = pkg?.let { Apps.icon(this, it) }
                val overridden = Apps.override(this, t.def.id) != null
                val label = if (overridden && pkg != null) Apps.label(this, pkg) ?: t.def.title else t.def.title
                Triple(pkg, icon, label)
            }
            main.post {
                if (isDestroyed) return@post
                tiles.forEachIndexed { i, t ->
                    val (pkg, icon, label) = resolved[i]
                    t.pkg = pkg
                    t.label.text = label
                    when {
                        t.def.kind == TileKind.ALL -> {
                            t.icon.setImageResource(R.drawable.ic_all_apps); t.icon.alpha = 1f
                        }
                        icon != null -> {
                            t.icon.setImageDrawable(icon); t.icon.alpha = 1f
                        }
                        else -> {
                            t.icon.setImageResource(R.drawable.ic_missing); t.icon.alpha = 0.6f
                        }
                    }
                }
            }
        }
    }

    private fun onTile(t: TileView) {
        when (t.def.kind) {
            TileKind.ALL -> startActivity(Intent(this, AllAppsActivity::class.java))
            TileKind.SETTINGS -> {
                val pkg = t.pkg
                val ok = if (Apps.override(this, t.def.id) != null && pkg != null) Apps.launchPackage(this, pkg)
                else Apps.openSystemSettings(this)
                if (!ok) toast("Не удалось открыть настройки Android")
            }
            TileKind.APP -> {
                val pkg = t.pkg
                if (pkg == null || !Apps.launchPackage(this, pkg)) {
                    toast("«${t.def.title}» не установлено.\nУдерживайте часы 3 секунды, чтобы назначить приложение.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- clock

    private fun updateClock(force: Boolean) {
        val now = TrustedClock.now()
        val minute = now / 60000L
        if (!force && minute == lastMinute) return
        lastMinute = minute
        val tz = TimeZone.getDefault()
        clock.text = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(now))
        val d = SimpleDateFormat("EEE, d MMMM", ru).apply { timeZone = tz }.format(Date(now))
        date.text = d.replaceFirstChar { it.titlecase(ru) }

        val today = trustedToday()
        if (lastDay != null && lastDay != today) weather.cached()?.let { renderWeather(it) }
        lastDay = today
    }

    private fun syncClock() {
        io.execute {
            TrustedClock.sync(this)
            main.post {
                if (isDestroyed) return@post
                updateClock(true)
                updateStatus()
                weather.cached()?.let { renderWeather(it) }
            }
        }
    }

    // ---------------------------------------------------------------- weather

    private fun refreshWeather(force: Boolean) {
        if (weatherBusy) return
        if (!force && !weather.isStale(WEATHER_INTERVAL)) return
        if (!isOnline()) {
            if (weather.cached() == null) renderWeatherMessage("Нет интернета")
            return
        }
        weatherBusy = true
        obtainCoords { coords ->
            if (coords == null) {
                weatherBusy = false
                if (weather.cached() == null) {
                    renderWeatherMessage("Местоположение неизвестно.\nУдерживайте часы, чтобы выбрать город")
                }
                updateStatus()
                return@obtainCoords
            }
            io.execute {
                if (!TrustedClock.synced) TrustedClock.sync(this)
                val f = weather.fetch(coords.first, coords.second)
                main.post {
                    weatherBusy = false
                    if (isDestroyed) return@post
                    if (f != null) renderWeather(f)
                    else if (weather.cached() == null) renderWeatherMessage("Погода временно недоступна")
                    updateClock(true)
                    updateStatus()
                }
            }
        }
    }

    /** Ручной город → последняя известная точка → однократный запрос → сохранённые координаты. */
    private fun obtainCoords(cb: (Pair<Double, Double>?) -> Unit) {
        weather.manualCity()?.let { cb(it.lat to it.lon); return }
        if (!location.hasPermission()) {
            cb(weather.savedCoords()); return
        }
        val last = location.lastKnown()
        if (last != null) {
            weather.saveCoords(last.latitude, last.longitude)
            cb(last.latitude to last.longitude)
            return
        }
        location.requestSingle(25_000) { l ->
            if (l != null) {
                weather.saveCoords(l.latitude, l.longitude)
                cb(l.latitude to l.longitude)
            } else {
                cb(weather.savedCoords())
            }
        }
    }

    private fun renderWeather(f: Forecast) {
        val today = trustedToday()
        val days = f.daysFrom(today).take(3)
        if (days.isEmpty()) {
            renderWeatherMessage("Прогноз устарел, обновляю…")
            return
        }
        weatherMsg.visibility = View.GONE
        weatherRow.visibility = View.VISIBLE
        weatherCols.forEachIndexed { i, c ->
            if (i < days.size) {
                val d = days[i]
                c.root.visibility = View.VISIBLE
                c.label.text = dayLabel(d.date, today)
                c.icon.setImageResource(WeatherIcons.forCode(d.code))
                c.max.text = formatTemp(d.tMax)
                c.min.text = formatTemp(d.tMin)
            } else {
                c.root.visibility = View.INVISIBLE
            }
        }
    }

    private fun renderWeatherMessage(msg: String) {
        weatherRow.visibility = View.INVISIBLE
        weatherMsg.visibility = View.VISIBLE
        weatherMsg.text = msg
    }

    private fun dayLabel(d: LocalDate, today: LocalDate): String {
        if (d == today) return "Сегодня"
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru).trimEnd('.')
            .replaceFirstChar { it.titlecase(ru) }
        return "$dow, ${d.dayOfMonth}"
    }

    // ---------------------------------------------------------------- status & network

    private fun updateStatus() {
        icWifi.alpha = if (isOnline()) 1f else 0.28f
        icSync.alpha = if (TrustedClock.synced) 1f else 0.28f
        val locOk = weather.manualCity() != null || (location.hasPermission() && location.anyProviderEnabled())
        icLoc.alpha = if (locOk) 1f else 0.28f
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean = try {
        getSystemService(ConnectivityManager::class.java).activeNetworkInfo?.isConnected == true
    } catch (e: Exception) {
        false
    }

    private fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({
                    if (isDestroyed) return@postDelayed
                    updateStatus()
                    io.execute {
                        TrustedClock.sync(this@MainActivity)
                        main.post {
                            if (isDestroyed) return@post
                            updateClock(true)
                            updateStatus()
                            refreshWeather(false)
                            if (hasWindowFocus()) updates.check(force = false, reportResult = false)
                        }
                    }
                }, 1500)
            }

            override fun onLost(network: Network) {
                main.post { if (!isDestroyed) updateStatus() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun askLocationOnce() {
        if (location.hasPermission()) return
        val p = getSharedPreferences("flow_main", 0)
        if (p.getBoolean("loc_asked", false)) return
        p.edit().putBoolean("loc_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val WEATHER_INTERVAL = 30 * 60_000L
    }
}
