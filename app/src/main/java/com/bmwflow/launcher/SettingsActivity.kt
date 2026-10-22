package com.bmwflow.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Скрытые настройки: открываются удержанием часов 3 секунды. */
class SettingsActivity : Activity() {

    private lateinit var st: Stage
    private lateinit var weather: WeatherRepository
    private lateinit var location: LocationHelper
    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private val updates by lazy { UpdateUi(this, io) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Stage.forActivity(this)
        weather = WeatherRepository(this)
        location = LocationHelper(this)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(st.px(40f), st.px(24f), st.px(40f), st.px(40f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(FLOW_BG)
            addView(list)
        }
        setContentView(scroll)
        io.execute {
            val a = Apps.launchable(this)
            main.post { apps = a; render() }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        render()
        updates.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        weather.markDirty()
        render()
    }

    // ---------------------------------------------------------------- content

    private fun render() {
        list.removeAllViews()

        header("Скрытые настройки BMW Flow", big = true)
        row("← Вернуться на главный экран", "") { finish() }

        header("Плитки")
        for (t in Tiles.all) {
            if (t.kind == TileKind.ALL) continue
            val pkg = Apps.resolve(this, t, apps)
            val name = pkg?.let { Apps.label(this, it) } ?: "не найдено"
            val mode = if (Apps.override(this, t.id) != null) "выбрано вручную" else "автоопределение"
            row("${t.id + 1}. ${t.title}", "$name · $mode${pkg?.let { "\n$it" } ?: ""}") { pickApp(t) }
        }

        header("Погода")
        val city = weather.manualCity()
        row("Город", city?.name ?: "Автоматически по геолокации") { chooseCity() }
        if (!location.hasPermission()) {
            row("Разрешить геолокацию", "Сейчас разрешения нет — погода только по выбранному городу") {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
        row("Обновить прогноз", "Загрузится при возврате на главный экран") {
            weather.markDirty(); toast("Прогноз обновится на главном экране")
        }

        header("Время")
        val last = TrustedClock.lastSync(this)
        val status = when {
            TrustedClock.synced && last > 0 -> "Синхронизировано по NTP: " + fmt(last)
            last > 0 -> "Ждём интернет. Последняя синхронизация: " + fmt(last)
            else -> "Ещё не выполнялась"
        }
        row("Синхронизировать сейчас", status) {
            toast("Синхронизация…")
            io.execute {
                val ok = TrustedClock.sync(this)
                main.post {
                    if (isDestroyed) return@post
                    toast(if (ok) "Время синхронизировано" else "Нет связи с NTP-серверами")
                    render()
                }
            }
        }

        header("Лаунчер")
        row("Выбрать домашний экран Android", "Здесь можно вернуть штатный лаунчер") { openHomeSettings() }
        row("Настройки Android", "") { Apps.openSystemSettings(this) }
        row("Проверить обновления", "Установлена версия " + versionName()) {
            toast("Проверяю…")
            updates.check(force = true, reportResult = true)
        }
    }

    private fun header(text: String, big: Boolean = false) {
        list.addView(TextView(this).apply {
            this.text = text
            setTextColor(if (big) Color.WHITE else 0xFF6FA8FF.toInt())
            typeface = if (big) Fonts.medium else Fonts.bold
            sizeU(st, if (big) 30f else 18f)
            letterSpacing = if (big) 0f else 0.08f
            setPadding(0, st.px(if (big) 8f else 28f), 0, st.px(10f))
        })
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(st, 0xFF121826.toInt(), 16f, 0x1FFFFFFF)
            setPadding(st.px(24f), st.px(16f), st.px(24f), st.px(16f))
            minimumHeight = st.px(72f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); typeface = Fonts.medium; sizeU(st, 22f)
        })
        if (subtitle.isNotEmpty()) box.addView(TextView(this).apply {
            text = subtitle; setTextColor(0xFF9AA3B5.toInt()); typeface = Fonts.regular; sizeU(st, 17f)
            setPadding(0, st.px(4f), 0, 0)
        })
        list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = st.px(10f)
        })
    }

    // ---------------------------------------------------------------- actions

    private fun pickApp(t: TileDef) {
        if (apps.isEmpty()) {
            toast("Список приложений ещё загружается"); return
        }
        val collator = Collator.getInstance(Locale("ru"))
        val sorted = apps.distinctBy { it.pkg }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        val labels = ArrayList<String>()
        labels.add(if (t.kind == TileKind.SETTINGS) "По умолчанию (настройки Android)" else "Автоопределение")
        sorted.forEach { labels.add("${it.label}  ·  ${it.pkg}") }
        AlertDialog.Builder(this)
            .setTitle("Плитка «${t.title}»")
            .setItems(labels.toTypedArray()) { _, which ->
                Apps.setOverride(this, t.id, if (which == 0) null else sorted[which - 1].pkg)
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseCity() {
        val input = EditText(this).apply {
            hint = "Например, Санкт-Петербург"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(weather.manualCity()?.name?.substringBefore(",") ?: "")
        }
        val pad = LinearLayout(this).apply {
            setPadding(st.px(24f), st.px(8f), st.px(24f), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Город для погоды")
            .setView(pad)
            .setPositiveButton("Найти") { _, _ -> searchCity(input.text.toString().trim()) }
            .setNeutralButton("По геолокации") { _, _ ->
                weather.setManualCity(null); render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun searchCity(q: String) {
        if (q.isEmpty()) return
        toast("Поиск…")
        io.execute {
            if (!TrustedClock.synced) TrustedClock.sync(this)
            val found = weather.searchCity(q)
            main.post {
                if (isDestroyed) return@post
                if (found.isEmpty()) {
                    toast("Ничего не найдено или нет интернета"); return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("Выберите город")
                    .setItems(found.map { it.name }.toTypedArray()) { _, which ->
                        weather.setManualCity(found[which]); render()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            Apps.openSystemSettings(this)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun fmt(t: Long) = SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(t))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
