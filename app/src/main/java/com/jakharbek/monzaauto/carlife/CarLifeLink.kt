package com.jakharbek.monzaauto.carlife

import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.proto.PbIn
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the head unit link reports to the rest of the app. Called on the link's threads. */
interface CarLifeEvents {
    fun onCarState(state: String)

    /** Head unit is ready for video: the next frame must be a key frame with SPS/PPS. */
    fun onVideoWanted()

    fun onVideoPaused()

    fun onTouch(t: Touch)

    /** Android key code for a head unit button. */
    fun onKey(androidKeyCode: Int)

    /** Feature list from the head unit (once per session). */
    fun onFeatures(features: Map<String, Int>)

    fun onCarClosed(reason: String)
}

/**
 * Phone side of the CarLife protocol over the USB accessory streams.
 * One instance per USB connection.
 */
class CarLifeLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val phone: PhoneInfo,
    private val events: CarLifeEvents,
) {
    companion object {
        const val VIDEO_W = 1280
        const val VIDEO_H = 720

        /** Largest piece written to the accessory in one call. */
        private const val CHUNK = 16384

        /** Video frames waiting for USB above this count are dropped until the next key frame. */
        private const val MAX_QUEUED_VIDEO = 30

        /** Without VIDEO_ENCODER_START this long after INIT_DONE, video starts anyway. */
        private const val START_FALLBACK_MS = 1500L

        /** Empty VIDEO_DATA when no frame went out for this long (no picture yet, or paused). */
        private const val VIDEO_KEEPALIVE_MS = 2000L

        private const val TTS_STOP_DELAY_MS = 1500L
    }

    private val closed = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<Packet>()
    private val queuedVideo = AtomicInteger(0)
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "carlife-timer").apply { isDaemon = true }
    }
    private val touch = TouchMapper(VIDEO_W, VIDEO_H)

    @Volatile private var huMajor = 1
    @Volatile private var versionSeen = false
    @Volatile private var initDone = false
    @Volatile private var started = false

    /** True between START and PAUSE: frames are wanted. */
    @Volatile var streaming = false
        private set

    @Volatile private var dropUntilKey = false
    @Volatile private var lastVideoTx = 0L
    @Volatile private var mediaInited = false
    @Volatile private var ttsActive = false
    private var ttsStopTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var videoFrames = 0L
    private var videoBytes = 0L

    fun start() {
        Thread(::readLoop, "carlife-rx").apply { isDaemon = true }.start()
        Thread(::writeLoop, "carlife-tx").apply { isDaemon = true }.start()
        sched.scheduleWithFixedDelay(::tick, 1000, 500, TimeUnit.MILLISECONDS)
        events.onCarState("USB подключён, жду магнитолу")
        Log.i("CL: link started")
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        Log.i("CL: closing ($reason)")
        try {
            writePacket(Packet(Chan.CMD, Msg.MD_EXIT, ByteArray(0)))
        } catch (_: Exception) {
        }
        sched.shutdownNow()
        queue.clear()
        queue.offer(Packet(0, 0, ByteArray(0))) // wakes the writer
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        events.onCarClosed(reason)
    }

    val isClosed get() = closed.get()

    // ---------- sending ----------

    private fun cmd(type: Int, data: ByteArray = ByteArray(0)) {
        Log.i("CL TX ${Msg.name(type)} ${if (data.isNotEmpty()) Log.hex(data, 24) else ""}")
        queue.offer(Packet(Chan.CMD, type, data))
    }

    /** One encoded H.264 access unit (Annex-B). */
    fun sendVideo(frame: ByteArray, key: Boolean) {
        if (closed.get() || !streaming) return
        if (dropUntilKey) {
            if (!key) return
            dropUntilKey = false
            Log.i("CL: video resumed on key frame")
        }
        if (queuedVideo.get() > MAX_QUEUED_VIDEO) {
            dropUntilKey = true
            Log.w("CL: USB too slow, dropping video until next key frame")
            events.onVideoWanted()
            return
        }
        queuedVideo.incrementAndGet()
        queue.offer(Packet(Chan.VIDEO, Msg.VIDEO_DATA, frame, (System.currentTimeMillis() / 1000).toInt()))
    }

    fun sendMedia(pcm: ByteArray) {
        if (closed.get()) return
        if (!mediaInited) {
            mediaInited = true
            queue.offer(Packet(Chan.MEDIA, Msg.MEDIA_INIT, Bodies.audioInit(48000, 2, 16)))
            cmd(Msg.MODULE_STATUS, Bodies.moduleStatus(3, 1))
        }
        queue.offer(Packet(Chan.MEDIA, Msg.MEDIA_DATA, pcm))
    }

    fun stopMedia() {
        if (!mediaInited || closed.get()) return
        mediaInited = false
        queue.offer(Packet(Chan.MEDIA, Msg.MEDIA_STOP, ByteArray(0)))
        cmd(Msg.MODULE_STATUS, Bodies.moduleStatus(3, 0))
    }

    @Synchronized
    fun sendSpeech(pcm: ByteArray) {
        if (closed.get()) return
        ttsStopTask?.cancel(false)
        ttsStopTask = null
        if (!ttsActive) {
            ttsActive = true
            cmd(Msg.MODULE_STATUS, Bodies.moduleStatus(2, 1))
            queue.offer(Packet(Chan.TTS, Msg.TTS_INIT, Bodies.audioInit(16000, 1, 16)))
        }
        queue.offer(Packet(Chan.TTS, Msg.TTS_DATA, pcm))
    }

    @Synchronized
    fun stopSpeech() {
        if (!ttsActive || closed.get()) return
        ttsStopTask?.cancel(false)
        ttsStopTask = sched.schedule({
            synchronized(this) {
                if (ttsActive) {
                    ttsActive = false
                    queue.offer(Packet(Chan.TTS, Msg.TTS_STOP, ByteArray(0)))
                    cmd(Msg.MODULE_STATUS, Bodies.moduleStatus(2, 0))
                }
            }
        }, TTS_STOP_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val p = queue.take()
                if (closed.get()) break
                if (p.chan == Chan.VIDEO) queuedVideo.decrementAndGet()
                writePacket(p)
                if (p.chan == Chan.VIDEO) {
                    lastVideoTx = System.currentTimeMillis()
                    if (p.data.isNotEmpty()) {
                        videoFrames++
                        videoBytes += p.data.size
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.e("CL: write failed", e)
                close("ошибка записи USB")
            }
        }
    }

    /** Outer header in its own write, then the inner message in pieces of at most [CHUNK] bytes. */
    private fun writePacket(p: Packet) {
        val inner = Frames.inner(p)
        synchronized(output) {
            output.write(Frames.outerHeader(p.chan, inner.size))
            var off = 0
            while (off < inner.size) {
                val n = minOf(CHUNK, inner.size - off)
                output.write(inner, off, n)
                off += n
            }
            output.flush()
        }
    }

    // ---------- receiving ----------

    private fun readLoop() {
        val deframer = Deframer(::onPacket)
        val buf = ByteArray(CHUNK)
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) deframer.feed(buf, 0, n)
            }
            if (!closed.get()) close("магнитола закрыла соединение")
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.w("CL: read stopped", e)
                close("USB отключён")
            }
        }
    }

    private fun onPacket(p: Packet) {
        try {
            when (p.chan) {
                Chan.CMD -> onCmd(p)
                Chan.TOUCH -> onTouchPacket(p)
                Chan.VR -> Unit // car microphone is not used: the phone microphone feeds Android Auto
                else -> Log.i("CL RX $p")
            }
        } catch (e: Exception) {
            Log.e("CL: error handling $p", e)
        }
    }

    private fun onCmd(p: Packet) {
        val body = PbIn.parse(p.data)
        Log.i("CL RX ${Msg.name(p.type)} $body")
        when (p.type) {
            Msg.HU_PROTOCOL_VERSION -> {
                if (versionSeen) return
                versionSeen = true
                huMajor = body.int(1, 1)
                events.onCarState("Магнитола на связи (протокол ${body.int(1)}.${body.int(2)})")
                cmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Bodies.matchStatus(true))
                cmd(Msg.FOREGROUND)
                cmd(Msg.SCREEN_ON)
                cmd(Msg.MD_INFO, Bodies.phoneInfo(phone))
                sched.schedule({ cmd(Msg.MODULE_STATUS, Bodies.moduleStatusAllIdle(huMajor)) }, 500, TimeUnit.MILLISECONDS)
            }
            Msg.CARLIFE_DATA_SUBSCRIBE -> cmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, Bodies.subscribeDone(p.data))
            Msg.HU_INFO -> Log.i("CL: head unit ${body.string(13)} / ${body.string(14)} / ${body.string(15)}, android ${body.string(19)}")
            Msg.STATISTIC_INFO -> {
                Log.i("CL: head unit channel ${body.string(4)}, version ${body.string(2)}")
                cmd(Msg.MD_AUTHEN_RESULT, Bodies.authenResult(true))
                cmd(Msg.MD_FEATURE_CONFIG_REQUEST)
                cmd(Msg.CAR_DATA_SUBSCRIBE)
            }
            Msg.HU_AUTHEN_REQUEST -> {
                val random = body.string(1) ?: ""
                val answer = Bodies.challengeAnswer(random, phone)
                if (answer != null) {
                    cmd(Msg.MD_AUTHEN_RESPONSE, Bodies.authenResponse(answer))
                } else {
                    Log.w("CL: head unit asks for an unsupported authentication form ($random)")
                    events.onCarState("Магнитола требует неподдерживаемую авторизацию")
                }
            }
            Msg.HU_AUTHEN_RESULT -> Log.i("CL: authentication result ${body.bool(1)}")
            Msg.HU_FEATURE_CONFIG_RESPONSE -> events.onFeatures(Bodies.featureConfig(p.data))
            Msg.VIDEO_ENCODER_INIT -> {
                val fps = body.int(3).let { if (it in 1..60) it else 30 }
                Log.i("CL: head unit screen ${body.int(1)}x${body.int(2)}, fps ${body.int(3)}; sending ${VIDEO_W}x$VIDEO_H@$fps")
                cmd(Msg.VIDEO_ENCODER_INIT_DONE, Bodies.videoInfo(VIDEO_W, VIDEO_H, fps))
                cmd(Msg.FOREGROUND)
                initDone = true
                sched.schedule({
                    if (!started && !closed.get()) {
                        Log.i("CL: no VIDEO_ENCODER_START, starting video anyway")
                        startVideo()
                    }
                }, START_FALLBACK_MS, TimeUnit.MILLISECONDS)
            }
            Msg.VIDEO_ENCODER_START, Msg.VIDEO_ENCODER_RESET -> startVideo()
            Msg.VIDEO_ENCODER_PAUSE -> {
                streaming = false
                events.onVideoPaused()
                events.onCarState("Магнитола: видео на паузе")
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> cmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Bodies.frameRate(body.int(1, 30)))
            Msg.VIDEO_ENCODER_JPEG -> cmd(Msg.VIDEO_ENCODER_JPEG_ACK)
            else -> Unit
        }
    }

    private fun startVideo() {
        started = true
        initDone = true
        streaming = true
        dropUntilKey = false
        events.onCarState("Магнитола показывает экран")
        events.onVideoWanted()
    }

    private fun onTouchPacket(p: Packet) {
        val b = PbIn.parse(p.data)
        val touches: List<Touch> = when (p.type) {
            Msg.TOUCH_ACTION -> touch.action(b.int(1), b.int(2), b.int(3), b.int(4), b.int(5))
            Msg.TOUCH_DOWN -> touch.gesture(1, b.int(1), b.int(2))
            Msg.TOUCH_MOVE -> touch.gesture(2, b.int(1), b.int(2))
            Msg.TOUCH_UP -> touch.gesture(3, b.int(1), b.int(2))
            Msg.TOUCH_SINGLE_CLICK -> touch.gesture(4, b.int(1), b.int(2))
            Msg.TOUCH_HARD_KEY -> {
                val key = b.int(1)
                val android = hardKeyToAndroid(key)
                Log.i("CL RX hard key $key -> android $android")
                if (android != 0) events.onKey(android)
                emptyList()
            }
            else -> {
                Log.i("CL RX touch ${Msg.name(p.type)} $b")
                emptyList()
            }
        }
        for (t in touches) events.onTouch(t)
    }

    private fun hardKeyToAndroid(k: Int): Int = when (k) {
        HardKey.HOME -> 3
        HardKey.BACK -> 4
        HardKey.PHONE_CALL -> 5
        HardKey.PHONE_END -> 6
        HardKey.MOVE_UP -> 19
        HardKey.MOVE_DOWN -> 20
        HardKey.MOVE_LEFT -> 21
        HardKey.MOVE_RIGHT -> 22
        HardKey.OK -> 23
        HardKey.VR_START -> 84
        HardKey.MEDIA_START -> 126
        HardKey.MEDIA_STOP -> 127
        HardKey.SEEK_ADD, HardKey.SELECTOR_NEXT -> 87
        HardKey.SEEK_SUB, HardKey.SELECTOR_PREVIOUS -> 88
        else -> 0
    }

    private var lastStatsAt = 0L

    private fun tick() {
        if (closed.get()) return
        val now = System.currentTimeMillis()
        if (started && now - lastVideoTx >= VIDEO_KEEPALIVE_MS && queuedVideo.get() == 0) {
            queuedVideo.incrementAndGet()
            queue.offer(Packet(Chan.VIDEO, Msg.VIDEO_DATA, ByteArray(0), (now / 1000).toInt()))
        }
        if (now - lastStatsAt >= 10_000) {
            lastStatsAt = now
            if (videoFrames > 0) Log.i("CL: video sent $videoFrames frames, ${videoBytes / 1024} KiB")
        }
    }
}
