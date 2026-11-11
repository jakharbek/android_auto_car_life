package com.jakharbek.monzaauto

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** User settings. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Where Android Auto sound plays: decided by the car, always over USB, or always on the phone (Bluetooth). */
    enum class Audio { AUTO, USB, PHONE }

    var audio: Audio
        get() = runCatching { Audio.valueOf(p.getString("audio", null) ?: "") }.getOrDefault(Audio.AUTO)
        set(v) = p.edit().putString("audio", v.name).apply()

    /** Android Auto screen density: smaller = more on screen, bigger = larger buttons. */
    var dpi: Int
        get() = p.getInt("dpi", 160)
        set(v) = p.edit().putInt("dpi", v.coerceIn(DPI_MIN, DPI_MAX)).apply()

    var bitrateKbps: Int
        get() = p.getInt("bitrate", 4000)
        set(v) = p.edit().putInt("bitrate", v.coerceIn(1000, 12000)).apply()

    companion object {
        const val DPI_MIN = 100
        const val DPI_MAX = 320
    }
}

/** Current state shown on screen and in the notification. */
object Status {
    @Volatile var usb = "не подключено"
    @Volatile var car = "—"
    @Volatile var aa = "—"
    @Volatile var running = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    fun changed() {
        for (l in listeners) l()
    }
}
