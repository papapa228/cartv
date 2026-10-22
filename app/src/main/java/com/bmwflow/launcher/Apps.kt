package com.bmwflow.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

enum class TileKind { APP, SETTINGS, ALL }

class TileDef(
    val id: Int,
    val title: String,
    val color: Int,
    /** Известные имена пакетов — проверяются первыми. */
    val defaults: List<String>,
    /** Запасной поиск по названию приложения на приставке (нижний регистр). */
    val pattern: Regex?,
    val kind: TileKind,
)

object Tiles {
    val all = listOf(
        TileDef(0, "Яндекс Карты", 0xFF2F80FF.toInt(), listOf("ru.yandex.yandexmaps"),
            Regex("яндекс\\s*карты|yandex\\s*maps"), TileKind.APP),
        TileDef(1, "Яндекс Музыка", 0xFFFFB020.toInt(), listOf("ru.yandex.music"),
            Regex("яндекс\\s*музыка|yandex\\s*music"), TileKind.APP),
        TileDef(2, "YouTube", 0xFFFF3030.toInt(), listOf("com.google.android.youtube", "app.revanced.android.youtube"),
            Regex("^youtube$"), TileKind.APP),
        TileDef(3, "ЧимаТВ", 0xFF2E8BFF.toInt(), emptyList(),
            Regex("^чима|chima"), TileKind.APP),
        TileDef(4, "HAPP", 0xFF8B5CF6.toInt(), listOf("com.happproxy"),
            Regex("^happ\\b"), TileKind.APP),
        TileDef(5, "Google Play", 0xFF1DBF73.toInt(), listOf("com.android.vending"),
            Regex("play\\s*(маркет|market|store)|google play"), TileKind.APP),
        TileDef(6, "Настройки", 0xFF8A93A6.toInt(), emptyList(), null, TileKind.SETTINGS),
        TileDef(7, "Все остальные", 0xFF2E7BFF.toInt(), emptyList(), null, TileKind.ALL),
    )
}

class AppEntry(val pkg: String, val activity: String, val label: String) {
    val component get() = ComponentName(pkg, activity)
}

object Apps {
    private const val PREFS = "flow_tiles"

    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { AppEntry(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
    }

    fun override(ctx: Context, tileId: Int): String? =
        ctx.getSharedPreferences(PREFS, 0).getString("tile_pkg_$tileId", null)

    fun setOverride(ctx: Context, tileId: Int, pkg: String?) {
        val e = ctx.getSharedPreferences(PREFS, 0).edit()
        if (pkg == null) e.remove("tile_pkg_$tileId") else e.putString("tile_pkg_$tileId", pkg)
        e.apply()
    }

    fun settingsPackage(ctx: Context): String? =
        ctx.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName

    /** Пакет, который запускает плитка, или null, если подходящего приложения нет. */
    fun resolve(ctx: Context, tile: TileDef, apps: List<AppEntry>): String? {
        if (tile.kind == TileKind.ALL) return null
        val installed = apps.map { it.pkg }.toHashSet()
        override(ctx, tile.id)?.let { if (it in installed) return it }
        if (tile.kind == TileKind.SETTINGS) return settingsPackage(ctx)
        tile.defaults.firstOrNull { it in installed }?.let { return it }
        tile.pattern?.let { p ->
            apps.firstOrNull { p.containsMatchIn(it.label.lowercase()) }?.let { return it.pkg }
        }
        return null
    }

    fun launchPackage(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(i); true
        } catch (e: Exception) {
            false
        }
    }

    fun launchComponent(ctx: Context, entry: AppEntry): Boolean = try {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ); true
    } catch (e: Exception) {
        false
    }

    fun openSystemSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        false
    }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    fun label(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }
}
