package com.jakharbek.monzaauto.aa

import android.os.SystemClock
import com.jakharbek.monzaauto.Log
import com.jakharbek.monzaauto.proto.PbIn
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/** What the Android Auto connection reports. Called on the link's reader thread unless noted. */
interface AaEvents {
    fun onAaState(state: String)

    /** H.264 SPS/PPS (Annex-B). */
    fun onVideoConfig(data: ByteArray)

    /** One H.264 access unit (Annex-B). Call [done] once it is consumed, which acknowledges it to Android Auto. */
    fun onVideoFrame(data: ByteArray, ptsUs: Long, done: () -> Unit)

    fun onVideoStopped()

    fun onAudio(channel: Int, pcm: ByteArray)

    fun onAudioStopped(channel: Int)

    fun onMicRequest(open: Boolean)

    /** [refused]: nothing listens on the port (server not started), safe to try again later. */
    fun onAaClosed(reason: String, refused: Boolean)
}

/**
 * Head unit side of the Android Auto protocol, talking to Android Auto's head unit server on this phone.
 *
 * Frame: `[channel][flags][length u16 BE]` (+ `[total length u32 BE]` on the first of several fragments) + body.
 * After the TLS handshake every body is TLS-encrypted; plaintext is `[type u16 BE]` + payload.
 */
class AapLink(
    private val credentials: HuCredentials,
    private val config: HeadUnitConfig,
    private val events: AaEvents,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
) {
    private val closed = AtomicBoolean(false)
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private lateinit var engine: SSLEngine
    private val writeLock = Any()

    private var netIn: ByteBuffer = ByteBuffer.allocate(64 * 1024)
    private var appIn: ByteBuffer = ByteBuffer.allocate(64 * 1024)

    /** Message reassembly per channel. */
    private val partial = HashMap<Int, ByteArrayOutputStream>()

    private val sessions = HashMap<Int, Int>()
    private val seenTypes = HashSet<Long>()

    @Volatile private var micSession = 0
    @Volatile private var micUnacked = 0
    @Volatile private var micMaxUnacked = 4

    @Volatile var connected = false
        private set

    fun start() {
        Thread(::run, "aa-link").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            events.onAaState("Подключаюсь к Android Auto…")
            socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), 5000)
            input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
            output = socket.getOutputStream()
            handshake()
            connected = true
            events.onAaState("Android Auto подключён")
            socket.soTimeout = 20_000
            while (!closed.get()) readMessage()
        } catch (e: Exception) {
            if (!closed.get()) {
                val reason = when (e) {
                    is java.net.ConnectException -> "сервер головного устройства в Android Auto не запущен"
                    is java.net.SocketTimeoutException -> if (connected) "Android Auto перестал отвечать" else "Android Auto не ответил (перезапустите сервер головного устройства)"
                    else -> e.message ?: e.javaClass.simpleName
                }
                Log.w("AA: link ended: $reason", e)
                shutdown(reason, refused = e is java.net.ConnectException)
            }
        }
    }

    // ---------- handshake ----------

    private fun handshake() {
        socket.soTimeout = 10_000
        writeFrame(Ch.CONTROL, Flag.PLAIN, plain(T.VERSION_REQUEST, AaMessages.versionRequest()))
        val v = readPlain()
        if (v.first != T.VERSION_RESPONSE || v.second.size < 6) throw IOException("unexpected answer to version request: ${v.first}")
        val b = v.second
        val major = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        val minor = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val status = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        Log.i("AA: protocol $major.$minor status $status")
        if (status != 0) throw IOException("Android Auto rejected the protocol version")

        engine = credentials.sslContext().createSSLEngine("android-auto", port)
        engine.useClientMode = true
        engine.beginHandshake()
        netIn.clear()
        netIn.flip()
        val empty = ByteBuffer.allocate(0)
        var out = ByteBuffer.allocate(engine.session.packetBufferSize)
        loop@ while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    out.clear()
                    val r = engine.wrap(empty, out)
                    if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        out = ByteBuffer.allocate(out.capacity() * 2)
                        continue@loop
                    }
                    out.flip()
                    if (out.hasRemaining()) {
                        val tls = ByteArray(out.remaining()).also { out.get(it) }
                        writeFrame(Ch.CONTROL, Flag.PLAIN, plain(T.SSL, tls))
                    }
                    if (r.status == SSLEngineResult.Status.CLOSED) throw IOException("TLS closed during handshake")
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netIn.hasRemaining()) appendNet(readSslPlain())
                    appIn.clear()
                    val r = engine.unwrap(netIn, appIn)
                    when (r.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> appendNet(readSslPlain())
                        SSLEngineResult.Status.CLOSED -> throw IOException("TLS closed during handshake")
                        else -> Unit
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) (engine.delegatedTask ?: break).run()
                }
                else -> break@loop
            }
        }
        Log.i("AA: TLS ${engine.session.protocol} ${engine.session.cipherSuite}")
        writeFrame(Ch.CONTROL, Flag.PLAIN, plain(T.AUTH_COMPLETE, AaMessages.authComplete()))
    }

    private fun readSslPlain(): ByteArray {
        val m = readPlain()
        if (m.first != T.SSL) throw IOException("unexpected message ${m.first} during TLS handshake")
        return m.second
    }

    /** Reads one unencrypted message: (type, payload). */
    private fun readPlain(): Pair<Int, ByteArray> {
        val ch = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val len = input.readUnsignedShort()
        if (flags and Flag.ENCRYPTED != 0) throw IOException("encrypted frame during handshake")
        if (flags and (Flag.FIRST or Flag.LAST) == Flag.FIRST) input.readInt()
        val body = ByteArray(len)
        input.readFully(body)
        if (len < 2) throw IOException("short frame on channel $ch")
        val type = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return type to body.copyOfRange(2, body.size)
    }

    private fun appendNet(data: ByteArray) {
        netIn.compact()
        if (netIn.remaining() < data.size) {
            val bigger = ByteBuffer.allocate(netIn.position() + data.size + 16 * 1024)
            netIn.flip()
            bigger.put(netIn)
            netIn = bigger
        }
        netIn.put(data)
        netIn.flip()
    }

    private fun plain(type: Int, payload: ByteArray): ByteArray {
        val b = ByteArray(2 + payload.size)
        b[0] = (type shr 8).toByte()
        b[1] = type.toByte()
        System.arraycopy(payload, 0, b, 2, payload.size)
        return b
    }

    // ---------- framing ----------

    private fun writeFrame(ch: Int, flags: Int, body: ByteArray) {
        if (body.size > 0xFFFF) throw IOException("frame too large: ${body.size}")
        val h = byteArrayOf(ch.toByte(), flags.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        synchronized(writeLock) {
            output.write(h + body)
            output.flush()
        }
    }

    /** Encrypts and sends one message. Control-type messages on a service channel carry the CONTROL flag. */
    fun send(ch: Int, type: Int, payload: ByteArray, controlType: Boolean = ch == Ch.CONTROL) {
        if (closed.get()) return
        val flags = Flag.ENC or (if (controlType && ch != Ch.CONTROL) Flag.CONTROL else 0)
        try {
            synchronized(writeLock) {
                val src = ByteBuffer.wrap(plain(type, payload))
                var out = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)
                val acc = ByteArrayOutputStream()
                while (src.hasRemaining()) {
                    out.clear()
                    val r = engine.wrap(src, out)
                    if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        out = ByteBuffer.allocate(out.capacity() * 2)
                        continue
                    }
                    if (r.status == SSLEngineResult.Status.CLOSED) throw IOException("TLS closed")
                    out.flip()
                    acc.write(out.array(), 0, out.limit())
                }
                writeFrame(ch, flags, acc.toByteArray())
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.w("AA: send failed", e)
                shutdown("ошибка отправки в Android Auto")
            }
        }
    }

    private fun decrypt(body: ByteArray): ByteArray {
        appendNet(body)
        val out = ByteArrayOutputStream()
        while (netIn.hasRemaining()) {
            appIn.clear()
            val r = engine.unwrap(netIn, appIn)
            when (r.status) {
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    out.write(appIn.array(), 0, appIn.limit())
                    if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break // rest of the record comes with the next frame
                SSLEngineResult.Status.CLOSED -> throw IOException("TLS closed by Android Auto")
            }
        }
        return out.toByteArray()
    }

    private fun readMessage() {
        val ch = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val len = input.readUnsignedShort()
        val fragmentStart = flags and (Flag.FIRST or Flag.LAST) == Flag.FIRST
        if (fragmentStart) input.readInt()
        val body = ByteArray(len)
        input.readFully(body)
        val data = if (flags and Flag.ENCRYPTED != 0) decrypt(body) else body

        val first = flags and Flag.FIRST != 0
        val last = flags and Flag.LAST != 0
        val msg: ByteArray = when {
            first && last -> data
            first -> {
                partial[ch] = ByteArrayOutputStream(data.size * 4).apply { write(data) }
                return
            }
            else -> {
                val acc = partial[ch] ?: return // fragment without a start: drop
                acc.write(data)
                if (!last) return
                partial.remove(ch)
                acc.toByteArray()
            }
        }
        if (msg.size < 2) return
        val type = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        val isControl = ch == Ch.CONTROL || flags and Flag.CONTROL != 0
        dispatch(ch, type, msg, isControl)
    }

    // ---------- messages ----------

    private fun logOnce(ch: Int, type: Int, what: String) {
        if (seenTypes.add((ch.toLong() shl 32) or type.toLong())) Log.i("AA RX ${Ch.name(ch)} type 0x${Integer.toHexString(type)} $what")
    }

    private fun dispatch(ch: Int, type: Int, msg: ByteArray, isControl: Boolean) {
        if (isControl) {
            onControl(ch, type, PbIn.parse(msg, 2))
            return
        }
        when (ch) {
            Ch.VIDEO -> onVideo(type, msg)
            Ch.SPEECH, Ch.SYSTEM, Ch.MEDIA -> onAudio(ch, type, msg)
            Ch.SENSOR -> onSensor(type, PbIn.parse(msg, 2))
            Ch.INPUT -> onInput(type, PbIn.parse(msg, 2))
            Ch.MIC -> onMic(type, PbIn.parse(msg, 2))
            else -> logOnce(ch, type, "(ignored)")
        }
    }

    private fun onControl(ch: Int, type: Int, b: PbIn) {
        when (type) {
            T.DISCOVERY_REQUEST -> {
                Log.i("AA: phone ${b.string(4)} / ${b.string(5)}")
                Log.i("AA: declaring ${config.width}x${config.height} dpi ${config.dpi}, USB audio ${config.usbAudio}, mic ${config.mic}")
                send(Ch.CONTROL, T.DISCOVERY_RESPONSE, AaMessages.discoveryResponse(config))
            }
            T.CHANNEL_OPEN_REQUEST -> {
                Log.i("AA: open channel ${Ch.name(ch)}")
                send(ch, T.CHANNEL_OPEN_RESPONSE, AaMessages.channelOpenOk(), controlType = true)
                if (ch == Ch.SENSOR) send(Ch.SENSOR, T.SENSOR_EVENT, AaMessages.drivingStatusUnrestricted(), controlType = false)
            }
            T.PING_REQUEST -> send(Ch.CONTROL, T.PING_RESPONSE, AaMessages.pingResponse(b.long(1)))
            T.NAV_FOCUS_REQUEST -> send(Ch.CONTROL, T.NAV_FOCUS_NOTIFICATION, AaMessages.navFocus())
            T.AUDIO_FOCUS_REQUEST -> {
                Log.i("AA: audio focus request ${b.int(1)}")
                send(Ch.CONTROL, T.AUDIO_FOCUS_NOTIFICATION, AaMessages.audioFocus(b.int(1)))
            }
            T.BYEBYE_REQUEST -> {
                Log.i("AA: phone says goodbye (reason ${b.int(1)})")
                send(Ch.CONTROL, T.BYEBYE_RESPONSE, AaMessages.byeByeResponse())
                Thread.sleep(300)
                shutdown("Android Auto завершил сеанс")
            }
            T.BYEBYE_RESPONSE -> shutdown("сеанс закрыт")
            T.VOICE_SESSION -> Log.i("AA: voice session ${b.int(1)}")
            else -> logOnce(ch, type, b.toString())
        }
    }

    private fun mediaSetup(ch: Int, maxUnacked: Int) {
        send(ch, T.MEDIA_CONFIG, AaMessages.mediaConfig(maxUnacked), controlType = false)
    }

    private fun onVideo(type: Int, msg: ByteArray) {
        when (type) {
            T.MEDIA_DATA, T.MEDIA_CODEC_CONFIG -> {
                val session = sessions[Ch.VIDEO] ?: 0
                val ack = { send(Ch.VIDEO, T.MEDIA_ACK, AaMessages.mediaAck(session), controlType = false) }
                if (type == T.MEDIA_CODEC_CONFIG) {
                    events.onVideoConfig(msg.copyOfRange(2, msg.size))
                    ack()
                } else {
                    val withTs = msg.size >= 10 && !startsWithStartCode(msg, 2)
                    val off = if (withTs) 10 else 2
                    val pts = if (withTs) ByteBuffer.wrap(msg, 2, 8).long else SystemClock.elapsedRealtimeNanos() / 1000
                    val once = AtomicBoolean(false)
                    events.onVideoFrame(msg.copyOfRange(off, msg.size), pts) { if (once.compareAndSet(false, true)) ack() }
                }
            }
            T.MEDIA_SETUP -> {
                Log.i("AA: video setup")
                mediaSetup(Ch.VIDEO, 4)
                send(Ch.VIDEO, T.VIDEO_FOCUS_NOTIFICATION, AaMessages.videoFocus(projected = true, unsolicited = true), controlType = false)
            }
            T.MEDIA_START -> {
                val b = PbIn.parse(msg, 2)
                sessions[Ch.VIDEO] = b.int(1)
                Log.i("AA: video start, session ${b.int(1)}")
                events.onAaState("Android Auto передаёт картинку")
            }
            T.MEDIA_STOP -> {
                Log.i("AA: video stop")
                events.onVideoStopped()
            }
            T.VIDEO_FOCUS_REQUEST -> {
                val b = PbIn.parse(msg, 2)
                Log.i("AA: video focus request mode ${b.int(2)} reason ${b.int(3)}")
                // There is no native screen to switch to: keep showing Android Auto.
                send(Ch.VIDEO, T.VIDEO_FOCUS_NOTIFICATION, AaMessages.videoFocus(projected = true, unsolicited = false), controlType = false)
            }
            else -> logOnce(Ch.VIDEO, type, "")
        }
    }

    private fun onAudio(ch: Int, type: Int, msg: ByteArray) {
        when (type) {
            T.MEDIA_DATA -> {
                val session = sessions[ch] ?: 0
                val off = if (msg.size >= 10) 10 else 2
                events.onAudio(ch, msg.copyOfRange(off, msg.size))
                send(ch, T.MEDIA_ACK, AaMessages.mediaAck(session), controlType = false)
            }
            T.MEDIA_CODEC_CONFIG -> Unit
            T.MEDIA_SETUP -> {
                Log.i("AA: audio setup ${Ch.name(ch)}")
                mediaSetup(ch, 16)
            }
            T.MEDIA_START -> {
                sessions[ch] = PbIn.parse(msg, 2).int(1)
                Log.i("AA: audio start ${Ch.name(ch)}")
            }
            T.MEDIA_STOP -> {
                Log.i("AA: audio stop ${Ch.name(ch)}")
                events.onAudioStopped(ch)
            }
            else -> logOnce(ch, type, "")
        }
    }

    private fun onSensor(type: Int, b: PbIn) {
        if (type != T.SENSOR_REQUEST) {
            logOnce(Ch.SENSOR, type, b.toString())
            return
        }
        val sensor = b.int(1)
        Log.i("AA: sensor request $sensor")
        send(Ch.SENSOR, T.SENSOR_RESPONSE, AaMessages.sensorOk(), controlType = false)
        when (sensor) {
            13 -> send(Ch.SENSOR, T.SENSOR_EVENT, AaMessages.drivingStatusUnrestricted(), controlType = false)
            10 -> send(Ch.SENSOR, T.SENSOR_EVENT, AaMessages.nightMode(false), controlType = false)
        }
    }

    private fun onInput(type: Int, b: PbIn) {
        if (type == T.KEY_BINDING_REQUEST) {
            Log.i("AA: key binding request ${b.longs(1)}")
            send(Ch.INPUT, T.KEY_BINDING_RESPONSE, AaMessages.keyBindingOk(), controlType = false)
        } else {
            logOnce(Ch.INPUT, type, b.toString())
        }
    }

    private fun onMic(type: Int, b: PbIn) {
        when (type) {
            T.MIC_REQUEST -> {
                val open = b.bool(1)
                Log.i("AA: microphone ${if (open) "open" else "close"}")
                if (open) {
                    micSession++
                    micUnacked = 0
                    micMaxUnacked = b.int(4, 4).coerceIn(1, 32)
                }
                send(Ch.MIC, T.MIC_RESPONSE, AaMessages.micResponse(true, micSession), controlType = false)
                events.onMicRequest(open)
            }
            T.MEDIA_ACK -> {
                val n = b.longs(3).size.takeIf { it > 0 } ?: b.int(2, 1)
                micUnacked = (micUnacked - n).coerceAtLeast(0)
            }
            else -> logOnce(Ch.MIC, type, b.toString())
        }
    }

    // ---------- outgoing input and microphone ----------

    private fun nowNs() = SystemClock.elapsedRealtimeNanos()

    /** pointers: (id, x, y) in video pixels. */
    fun sendTouch(action: Int, index: Int, pointers: List<Triple<Int, Int, Int>>) {
        if (!connected) return
        send(Ch.INPUT, T.INPUT_EVENT, AaMessages.touch(nowNs(), action, index, pointers), controlType = false)
    }

    fun sendKey(keycode: Int) {
        if (!connected) return
        send(Ch.INPUT, T.INPUT_EVENT, AaMessages.key(nowNs(), keycode, true), controlType = false)
        send(Ch.INPUT, T.INPUT_EVENT, AaMessages.key(nowNs(), keycode, false), controlType = false)
    }

    /** PCM16 mono 16 kHz from the phone microphone. Dropped when Android Auto is behind. */
    fun sendMic(pcm: ByteArray, timestampUs: Long) {
        if (!connected) return
        if (micUnacked >= micMaxUnacked) return
        micUnacked++
        val payload = ByteBuffer.allocate(8 + pcm.size).putLong(timestampUs).put(pcm).array()
        send(Ch.MIC, T.MEDIA_DATA, payload, controlType = false)
    }

    private fun startsWithStartCode(b: ByteArray, off: Int): Boolean =
        b.size >= off + 4 && b[off].toInt() == 0 && b[off + 1].toInt() == 0 &&
            (b[off + 2].toInt() == 1 || (b[off + 2].toInt() == 0 && b[off + 3].toInt() == 1))

    // ---------- shutdown ----------

    /** Polite close: goodbye, short pause, then the socket. Leaves Android Auto's server usable. */
    fun close() {
        if (closed.get()) return
        if (connected) {
            send(Ch.CONTROL, T.BYEBYE_REQUEST, AaMessages.byeByeRequest())
            Thread.sleep(200)
        }
        shutdown("остановлено")
    }

    private fun shutdown(reason: String, refused: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
        events.onAaClosed(reason, refused)
    }
}
