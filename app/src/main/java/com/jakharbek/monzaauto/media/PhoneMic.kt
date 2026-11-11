package com.jakharbek.monzaauto.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.jakharbek.monzaauto.Log

/** Phone microphone for Android Auto's voice assistant: PCM16 mono 16 kHz in 128 ms pieces. */
class PhoneMic(private val sink: (ByteArray, Long) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rate = 16000
            val chunk = 4096
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
            } catch (e: Exception) {
                Log.e("MIC: cannot open the microphone", e)
                running = false
                return@Thread
            }
            try {
                rec.startRecording()
                Log.i("MIC: recording")
                val buf = ByteArray(chunk)
                while (running) {
                    var got = 0
                    val t = SystemClock.elapsedRealtimeNanos() / 1000
                    while (got < chunk && running) {
                        val n = rec.read(buf, got, chunk - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got == chunk) sink(buf.copyOf(), t)
                    else if (running) Thread.sleep(10)
                }
            } catch (e: Exception) {
                Log.e("MIC: recording failed", e)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                Log.i("MIC: stopped")
            }
        }, "phone-mic").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread = null
    }
}
