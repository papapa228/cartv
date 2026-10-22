package com.bmwflow.launcher

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Часы лаунчера, независимые от системных.
 * После NTP-синхронизации время считается от монотонного таймера
 * (elapsedRealtime), поэтому сброс или скачок системных часов на него не влияет.
 * Системные часы Android не изменяются.
 */
object TrustedClock {
    private const val TAG = "TrustedClock"
    private const val PREFS = "flow_clock"
    private val SERVERS = listOf("time.google.com", "pool.ntp.org", "time.cloudflare.com", "ru.pool.ntp.org")

    @Volatile
    var synced = false
        private set

    @Volatile
    private var baseTime = 0L

    @Volatile
    private var baseElapsed = 0L

    fun now(): Long =
        if (synced) baseTime + (SystemClock.elapsedRealtime() - baseElapsed)
        else System.currentTimeMillis()

    /** Восстанавливает синхронизацию, сделанную в этом же сеансе питания (если процесс перезапускался). */
    fun restore(ctx: Context) {
        if (synced) return
        val p = ctx.getSharedPreferences(PREFS, 0)
        val boot = bootCount(ctx)
        if (boot >= 0 && p.getInt("boot", -2) == boot) {
            val t = p.getLong("base_time", 0)
            val e = p.getLong("base_elapsed", 0)
            if (t > 0 && SystemClock.elapsedRealtime() >= e) {
                baseTime = t
                baseElapsed = e
                synced = true
            }
        }
    }

    /** Время последней успешной синхронизации (по точным часам), 0 — не было. */
    fun lastSync(ctx: Context): Long = ctx.getSharedPreferences(PREFS, 0).getLong("last_sync", 0)

    /** Вызывать только из фонового потока. */
    @Synchronized
    fun sync(ctx: Context): Boolean {
        for (host in SERVERS) {
            val r = Sntp.request(host, 3000) ?: continue
            baseTime = r.time
            baseElapsed = r.elapsed
            synced = true
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putLong("base_time", r.time)
                .putLong("base_elapsed", r.elapsed)
                .putInt("boot", bootCount(ctx))
                .putLong("last_sync", r.time)
                .apply()
            Log.i(TAG, "synced with $host, system offset ${r.time - System.currentTimeMillis()} ms")
            return true
        }
        Log.w(TAG, "NTP sync failed")
        return false
    }

    private fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }
}

/** Минимальный SNTP-клиент (RFC 4330). */
object Sntp {
    class Result(val time: Long, val elapsed: Long)

    private const val OFFSET_1900_TO_1970 = (365L * 70L + 17L) * 24L * 60L * 60L

    fun request(host: String, timeoutMs: Int): Result? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI = 0, VN = 3, Mode = 3 (клиент)

            val requestTime = System.currentTimeMillis()
            val requestElapsed = SystemClock.elapsedRealtime()
            writeTimestamp(buf, 40, requestTime)
            socket.send(DatagramPacket(buf, buf.size, address, 123))

            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            val responseElapsed = SystemClock.elapsedRealtime()
            val responseTime = requestTime + (responseElapsed - requestElapsed)

            val mode = buf[0].toInt() and 0x7
            val stratum = buf[1].toInt() and 0xFF
            val originate = readTimestamp(buf, 24)
            val receive = readTimestamp(buf, 32)
            val transmit = readTimestamp(buf, 40)

            if ((mode != 4 && mode != 5) || stratum == 0 || stratum > 15 || transmit == 0L ||
                kotlin.math.abs(originate - requestTime) > 1000
            ) {
                null
            } else {
                val offset = ((receive - originate) + (transmit - responseTime)) / 2
                Result(responseTime + offset, responseElapsed)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun read32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun readTimestamp(b: ByteArray, o: Int): Long {
        val seconds = read32(b, o)
        val fraction = read32(b, o + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        return (seconds - OFFSET_1900_TO_1970) * 1000 + fraction * 1000L / 0x100000000L
    }

    private fun writeTimestamp(b: ByteArray, o: Int, time: Long) {
        val seconds = time / 1000L + OFFSET_1900_TO_1970
        val fraction = (time % 1000L) * 0x100000000L / 1000L
        for (i in 0..3) b[o + i] = (seconds shr (24 - 8 * i)).toByte()
        for (i in 0..3) b[o + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
    }
}
