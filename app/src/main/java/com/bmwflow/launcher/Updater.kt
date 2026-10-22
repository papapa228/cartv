package com.bmwflow.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ExecutorService

class UpdateInfo(val versionCode: Long, val versionName: String, val notes: String, val apkUrl: String)

/**
 * Обновление «как у ЧимаТВ»: лаунчер сам проверяет последний релиз на GitHub,
 * показывает окно «Обновить / Позже», скачивает APK с прогрессом и запускает установку.
 * Без root Android всегда спрашивает подтверждение установки — это системное окно.
 */
object Updater {
    private const val TAG = "Updater"
    const val INFO_URL = "https://github.com/papapa228/cartv/releases/latest/download/version.json"
    const val ACTION_STATUS = "com.bmwflow.launcher.INSTALL_STATUS"
    const val CHECK_INTERVAL = 6 * 60 * 60_000L
    private const val PREFS = "flow_update"

    @Suppress("DEPRECATION")
    fun currentVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Блокирующий запрос — только из фонового потока. */
    fun fetchInfo(): UpdateInfo? = try {
        Http.client.newCall(Request.Builder().url(INFO_URL).header("Cache-Control", "no-cache").build())
            .execute().use { r ->
                if (!r.isSuccessful) null else {
                    val o = JSONObject(r.body?.string() ?: "")
                    UpdateInfo(o.getLong("versionCode"), o.optString("versionName"),
                        o.optString("notes"), o.getString("apkUrl"))
                }
            }
    } catch (e: Exception) {
        Log.w(TAG, "check failed", e)
        null
    }

    fun isNewer(ctx: Context, info: UpdateInfo) = info.versionCode > currentVersionCode(ctx)

    fun dueForCheck(ctx: Context): Boolean =
        kotlin.math.abs(TrustedClock.now() - prefs(ctx).getLong("last_check", 0)) > CHECK_INTERVAL

    fun markChecked(ctx: Context) = prefs(ctx).edit().putLong("last_check", TrustedClock.now()).apply()

    fun isSnoozed(ctx: Context, info: UpdateInfo): Boolean {
        val p = prefs(ctx)
        return p.getLong("snooze_code", -1) == info.versionCode &&
                kotlin.math.abs(TrustedClock.now() - p.getLong("snooze_at", 0)) < CHECK_INTERVAL
    }

    fun snooze(ctx: Context, info: UpdateInfo) = prefs(ctx).edit()
        .putLong("snooze_code", info.versionCode).putLong("snooze_at", TrustedClock.now()).apply()

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /**
     * Скачивает APK прямо в сеанс PackageInstaller и отправляет его на установку.
     * Возвращает текст ошибки или null при успехе. Только из фонового потока.
     */
    fun downloadAndInstall(ctx: Context, info: UpdateInfo, progress: (Int) -> Unit): String? {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return "не удалось начать установку"
        }
        return try {
            installer.openSession(sessionId).use { session ->
                Http.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("сервер ответил ${r.code}")
                    val body = r.body ?: throw IllegalStateException("пустой ответ")
                    val total = body.contentLength()
                    session.openWrite("base.apk", 0, total).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct; progress(pct)
                                    }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    ctx, sessionId,
                    Intent(ctx, InstallReceiver::class.java).setAction(ACTION_STATUS), flags
                )
                session.commit(pi.intentSender)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            e.message ?: "ошибка загрузки"
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, 0)
}

/** Получает результат установки; для подтверждения открывает системное окно Android. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(confirm)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Не удалось открыть окно установки", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Лаунчер будет перезапущен системой уже в новой версии.
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                Toast.makeText(ctx, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Окна обновления; используется на главном экране и в скрытых настройках. */
class UpdateUi(private val activity: Activity, private val io: ExecutorService) {
    private val main = Handler(Looper.getMainLooper())
    private var dialogShown = false
    private var waitingPermission: UpdateInfo? = null

    /** force — проверить сразу; reportResult — сообщить, если обновлений нет. */
    fun check(force: Boolean, reportResult: Boolean) {
        if (dialogShown) return
        if (!force && !Updater.dueForCheck(activity)) return
        io.execute {
            val info = Updater.fetchInfo()
            if (info != null) Updater.markChecked(activity)
            main.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                when {
                    info == null -> if (reportResult) toast("Не удалось проверить обновления")
                    !Updater.isNewer(activity, info) ->
                        if (reportResult) toast("Установлена последняя версия ${Updater.currentVersionName(activity)}")
                    !force && Updater.isSnoozed(activity, info) -> Unit
                    else -> prompt(info)
                }
            }
        }
    }

    /** Вызывать из onResume: продолжает обновление после выдачи разрешения на установку. */
    fun onResume() {
        val info = waitingPermission ?: return
        if (Updater.canInstall(activity)) {
            waitingPermission = null
            start(info)
        }
    }

    private fun prompt(info: UpdateInfo) {
        dialogShown = true
        val text = buildString {
            append("Доступна версия ${info.versionName}")
            append("\nУстановлена: ${Updater.currentVersionName(activity)}")
            if (info.notes.isNotBlank()) append("\n\n${info.notes}")
        }
        AlertDialog.Builder(activity)
            .setTitle("Обновление BMW Flow")
            .setMessage(text)
            .setPositiveButton("Обновить") { _, _ -> start(info) }
            .setNegativeButton("Позже") { _, _ -> Updater.snooze(activity, info) }
            .setOnDismissListener { dialogShown = false }
            .show()
    }

    private fun start(info: UpdateInfo) {
        if (!Updater.canInstall(activity)) {
            waitingPermission = info
            toast("Разрешите BMW Flow устанавливать приложения и вернитесь назад")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val label = TextView(activity).apply { text = "Загрузка… 0%"; textSize = 18f }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            })
        }
        dialogShown = true
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Обновление ${info.versionName}")
            .setView(box)
            .setCancelable(false)
            .show()
        io.execute {
            val error = Updater.downloadAndInstall(activity, info) { pct ->
                main.post { label.text = "Загрузка… $pct%"; bar.progress = pct }
            }
            main.post {
                dialogShown = false
                try {
                    dlg.dismiss()
                } catch (_: Exception) {
                }
                if (error != null) toast("Обновление не загружено: $error")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
