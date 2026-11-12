package com.jakharbek.monzaauto.carlife

import com.jakharbek.monzaauto.Diag
import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.media.H264
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

    /** The head unit wants at most this many frames per second. */
    fun onFrameRate(fps: Int)

    fun onTouch(t: Touch)

    /** Android key code for a head unit button. */
    fun onKey(androidKeyCode: Int)

    /** Feature list from the head unit (once per session). */
    fun onFeatures(features: Map<String, Int>)

    fun onCarClosed(link: CarLifeLink, reason: String)
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

        /** Audio packets waiting for USB above this count are dropped (about 1.5 s of music). */
        private const val MAX_QUEUED_AUDIO = 80
    }

    private val closed = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<Packet>()
    private val queuedVideo = AtomicInteger(0)
    private val queuedAudio = AtomicInteger(0)
    private val exitPacket = Packet(Chan.CMD, Msg.MD_EXIT, ByteArray(0))
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

    /** Until a frame starting with SPS goes out, nothing else may: the head unit decoder starts from SPS+PPS+IDR. */
    @Volatile private var needSps = true
    @Volatile private var lastKeyAsk = 0L
    @Volatile private var versionAt = 0L

    private fun askKeyFrame() {
        lastKeyAsk = System.currentTimeMillis()
        events.onVideoWanted()
    }
    private var audioDropLogged = false
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
        Diag.carReset()
        events.onCarState("USB подключён, жду магнитолу")
        Log.i("CL: link started")
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        Log.i("CL: closing ($reason)")
        Diag.carStage = Diag.CarStage.CLOSED
        Diag.carClosedReason = reason
        sched.shutdownNow()
        // The writer sends MD_EXIT and stops; the streams are closed shortly after on a separate thread,
        // so a head unit that stopped reading can never block the caller (often the main thread).
        queue.clear()
        queue.offer(exitPacket)
        Thread({
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }, "carlife-close").apply { isDaemon = true }.start()
        events.onCarClosed(this, reason)
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
        if (needSps) {
            if (!H264.startsWithSps(frame)) {
                // backstop: keep asking while frames without SPS arrive
                if (System.currentTimeMillis() - lastKeyAsk > 1500) askKeyFrame()
                return
            }
            needSps = false
            Log.i("CL: video starts with SPS/PPS + key frame")
        }
        if (dropUntilKey) {
            if (!key) return
            dropUntilKey = false
            Log.i("CL: video resumed on key frame")
        }
        if (queuedVideo.get() > MAX_QUEUED_VIDEO) {
            dropUntilKey = true
            Log.w("CL: USB too slow, dropping video until next key frame")
            askKeyFrame()
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
        offerAudio(Packet(Chan.MEDIA, Msg.MEDIA_DATA, pcm))
    }

    /** Audio is dropped rather than queued without limit when USB cannot keep up. */
    private fun offerAudio(p: Packet) {
        if (queuedAudio.get() >= MAX_QUEUED_AUDIO) {
            if (!audioDropLogged) {
                audioDropLogged = true
                Log.w("CL: USB too slow for audio, dropping sound")
            }
            return
        }
        audioDropLogged = false
        queuedAudio.incrementAndGet()
        queue.offer(p)
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
        offerAudio(Packet(Chan.TTS, Msg.TTS_DATA, pcm))
    }

    @Synchronized
    fun stopSpeech() {
        if (!ttsActive || closed.get()) return
        ttsStopTask?.cancel(false)
        ttsStopTask = try { sched.schedule({
            synchronized(this) {
                if (ttsActive) {
                    ttsActive = false
                    queue.offer(Packet(Chan.TTS, Msg.TTS_STOP, ByteArray(0)))
                    cmd(Msg.MODULE_STATUS, Bodies.moduleStatus(2, 0))
                }
            }
        }, TTS_STOP_DELAY_MS, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { null }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val p = queue.take()
                if (p === exitPacket) {
                    try { writePacket(p) } catch (_: Exception) {}
                    break
                }
                if (closed.get()) continue
                if (p.chan == Chan.VIDEO) queuedVideo.decrementAndGet()
                if (p.type == Msg.MEDIA_DATA || p.type == Msg.TTS_DATA) queuedAudio.decrementAndGet()
                writePacket(p)
                if (p.chan == Chan.VIDEO) {
                    lastVideoTx = System.currentTimeMillis()
                    if (p.data.isNotEmpty()) {
                        videoFrames++
                        videoBytes += p.data.size
                        Diag.carFramesSent = videoFrames
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
                if (versionSeen) {
                    // Some head units send it twice in a row: ignore that. Later, it means the head unit restarted
                    // its CarLife session on the same USB connection: start over.
                    if (!initDone && System.currentTimeMillis() - versionAt < 5000) return
                    Log.i("CL: head unit started a new session")
                    restartSession()
                }
                versionSeen = true
                versionAt = System.currentTimeMillis()
                huMajor = body.int(1, 1)
                Diag.huProtocol = "${body.int(1)}.${body.int(2)}"
                Diag.carStage = Diag.CarStage.TALKING
                events.onCarState("Магнитола на связи (протокол ${body.int(1)}.${body.int(2)})")
                cmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Bodies.matchStatus(true))
                cmd(Msg.FOREGROUND)
                cmd(Msg.SCREEN_ON)
                cmd(Msg.MD_INFO, Bodies.phoneInfo(phone))
                sched.schedule({ cmd(Msg.MODULE_STATUS, Bodies.moduleStatusAllIdle(huMajor)) }, 500, TimeUnit.MILLISECONDS)
            }
            Msg.CARLIFE_DATA_SUBSCRIBE -> cmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, Bodies.subscribeDone(p.data))
            Msg.HU_INFO -> {
                Log.i("CL: head unit ${body.string(13)} / ${body.string(14)} / ${body.string(15)}, android ${body.string(19)}")
                Diag.huModel = listOfNotNull(body.string(13), body.string(14)).joinToString(" ").ifBlank { null }
            }
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
                    Diag.authUnsupported = true
                    events.onCarState("Магнитола требует неподдерживаемую авторизацию")
                }
            }
            Msg.HU_AUTHEN_RESULT -> Log.i("CL: authentication result ${body.bool(1)}")
            Msg.HU_FEATURE_CONFIG_RESPONSE -> {
                val f = Bodies.featureConfig(p.data)
                Diag.huFeatures = f
                events.onFeatures(f)
            }
            Msg.VIDEO_ENCODER_INIT -> {
                val fps = body.int(3).let { if (it in 1..60) it else 30 }
                Log.i("CL: head unit screen ${body.int(1)}x${body.int(2)}, fps ${body.int(3)}; sending ${VIDEO_W}x$VIDEO_H@$fps")
                Diag.huScreen = "${body.int(1)}×${body.int(2)}"
                events.onFrameRate(fps)
                Diag.carStage = Diag.CarStage.VIDEO_SETUP
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
                Diag.carStage = Diag.CarStage.PAUSED
                events.onVideoPaused()
                events.onCarState("Магнитола: видео на паузе")
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val fps = body.int(1, 30).coerceIn(1, 60)
                events.onFrameRate(fps)
                cmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Bodies.frameRate(fps))
            }
            Msg.REQUEST_THEME -> cmd(Msg.RESPONSE_THEME, Bodies.themeAuto())
            Msg.VIDEO_ENCODER_JPEG -> cmd(Msg.VIDEO_ENCODER_JPEG_ACK)
            else -> Unit
        }
    }

    /** Forgets everything about the previous session on this USB connection. */
    private fun restartSession() {
        initDone = false
        started = false
        streaming = false
        needSps = true
        dropUntilKey = false
        mediaInited = false
        synchronized(this) {
            ttsStopTask?.cancel(false)
            ttsStopTask = null
            ttsActive = false
        }
        touch.reset()
        Diag.carStage = Diag.CarStage.TALKING
    }

    private fun startVideo() {
        started = true
        initDone = true
        // Gate first and open the stream, then ask for the key frame: a key frame with SPS that comes out
        // right away must not find the stream still closed (it would be dropped and nothing asked again).
        needSps = true
        dropUntilKey = false
        streaming = true
        askKeyFrame()
        Diag.carStage = Diag.CarStage.STREAMING
        events.onCarState("Магнитола показывает экран")
    }

    private fun onTouchPacket(p: Packet) {
        val b = PbIn.parse(p.data)
        val touches: List<Touch> = when (p.type) {
            Msg.TOUCH_ACTION -> touch.action(b.int(1), b.int(2), b.int(3), b.int(4), b.int(5))
            Msg.TOUCH_DOWN -> touch.gesture(1, b.int(1), b.int(2))
            Msg.TOUCH_MOVE -> touch.gesture(2, b.int(1), b.int(2))
            Msg.TOUCH_UP -> touch.gesture(3, b.int(1), b.int(2))
            Msg.TOUCH_SINGLE_CLICK, Msg.TOUCH_DOUBLE_CLICK -> touch.gesture(4, b.int(1), b.int(2))
            // long press comes at press time; the head unit sends TOUCH_UP when the finger lifts
            Msg.TOUCH_LONG_PRESS -> touch.gesture(1, b.int(1), b.int(2))
            Msg.TOUCH_BEGIN -> emptyList()
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
