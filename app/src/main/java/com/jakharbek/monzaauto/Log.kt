package com.jakharbek.monzaauto

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** App log: logcat + a file per day + the last lines in memory for the screen. */
object Log {
    private const val TAG = "MonzaAuto"
    private const val MEMORY_LINES = 400

    private val memory = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    var dir: File? = null

    private var writer: FileWriter? = null
    private var writerDay: String? = null

    fun i(msg: String) = write("I", msg, null)

    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)

    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val now = Date()
        val line = "${timeFmt.format(now)} $level $msg" + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        when (level) {
            "E" -> android.util.Log.e(TAG, msg, t)
            "W" -> android.util.Log.w(TAG, msg, t)
            else -> android.util.Log.i(TAG, msg)
        }
        memory.addLast(line)
        while (memory.size > MEMORY_LINES) memory.removeFirst()
        try {
            val d = dir
            if (d != null) {
                val day = dayFmt.format(now)
                if (day != writerDay) {
                    writer?.close()
                    d.mkdirs()
                    writer = FileWriter(File(d, "monza-$day.log"), true)
                    writerDay = day
                }
                writer?.apply {
                    write(line)
                    write("\n")
                    if (t != null) write(android.util.Log.getStackTraceString(t) + "\n")
                    flush()
                }
            }
        } catch (_: Exception) {
            writer = null
            writerDay = null
        }
        for (l in listeners) l()
    }

    @Synchronized
    fun lines(): List<String> = memory.toList()

    fun listen(l: () -> Unit) = listeners.add(l)

    fun unlisten(l: () -> Unit) = listeners.remove(l)

    fun files(): List<File> = dir?.listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.name }.orEmpty()

    fun hex(b: ByteArray, max: Int = 32): String =
        b.take(max).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } + if (b.size > max) " …(${b.size})" else ""
}
