package com.jakharbek.monzaauto.aa

import com.jakharbek.monzaauto.proto.PbIn
import com.jakharbek.monzaauto.proto.PbOut
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Runs the head unit side against a scripted phone (TLS server) on a local socket:
 * version exchange, TLS handshake inside frames, service discovery, channel open,
 * fragmented video with acknowledgements, touch input.
 */
class AapLinkTest {

    /** Throwaway self-signed key pair made with the JDK's keytool for this test run only. */
    private fun testKeyStore(): KeyStore {
        val dir = createTempDir()
        val file = File(dir, "t.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "testpass",
            "-keypass", "testpass", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test", "-validity", "2",
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor() == 0) { "keytool failed" }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "testpass".toCharArray()) } }
            .also { dir.deleteRecursively() }
    }

    private class Phone(val engine: SSLEngine, val input: DataInputStream, val output: OutputStream) {
        var netIn: ByteBuffer = ByteBuffer.allocate(1 shl 17).apply { flip() }

        fun writeFrame(ch: Int, flags: Int, body: ByteArray, total: Int? = null) {
            val h = ByteArrayOutputStream()
            h.write(ch); h.write(flags); h.write(body.size shr 8); h.write(body.size and 0xFF)
            if (total != null) h.write(ByteBuffer.allocate(4).putInt(total).array())
            output.write(h.toByteArray() + body)
            output.flush()
        }

        fun readFrame(): Triple<Int, Int, ByteArray> {
            val ch = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val len = input.readUnsignedShort()
            val b = ByteArray(len)
            input.readFully(b)
            return Triple(ch, flags, b)
        }

        fun encrypt(plain: ByteArray): ByteArray {
            val out = ByteBuffer.allocate(1 shl 17)
            val r = engine.wrap(ByteBuffer.wrap(plain), out)
            check(r.status == SSLEngineResult.Status.OK)
            out.flip()
            return ByteArray(out.remaining()).also { out.get(it) }
        }

        fun decrypt(body: ByteArray): ByteArray {
            netIn.compact(); netIn.put(body); netIn.flip()
            val out = ByteBuffer.allocate(1 shl 17)
            engine.unwrap(netIn, out)
            out.flip()
            return ByteArray(out.remaining()).also { out.get(it) }
        }

        fun send(ch: Int, type: Int, payload: ByteArray, control: Boolean = false) {
            val plain = byteArrayOf((type shr 8).toByte(), type.toByte()) + payload
            writeFrame(ch, Flag.ENC or (if (control) Flag.CONTROL else 0), encrypt(plain))
        }

        /** Next message from the head unit: channel, flags, type, payload. */
        fun receive(): List<Any> {
            val (ch, flags, body) = readFrame()
            val plain = decrypt(body)
            val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
            return listOf(ch, flags, type, plain.copyOfRange(2, plain.size))
        }
    }

    private fun plainFrame(type: Int, payload: ByteArray) = byteArrayOf((type shr 8).toByte(), type.toByte()) + payload

    @Test
    fun fullSession() {
        val ks = testKeyStore()
        val cert = ks.getCertificate("t") as X509Certificate
        val key = ks.getKey("t", "testpass".toCharArray()) as PrivateKey

        val server = ServerSocket(0)
        val events = LinkedBlockingQueue<String>()
        val acks = LinkedBlockingQueue<() -> Unit>()
        val link = AapLink(HuCredentials(cert, key), HeadUnitConfig(usbAudio = true, mic = true), object : AaEvents {
            override fun onAaState(state: String) = Unit
            override fun onVideoConfig(data: ByteArray) { events += "config ${data.size}" }
            override fun onVideoFrame(data: ByteArray, ptsUs: Long, done: () -> Unit) {
                events += "frame ${data.size} $ptsUs"
                acks += done
            }
            override fun onVideoStopped() = Unit
            override fun onAudio(channel: Int, pcm: ByteArray) { events += "audio $channel ${pcm.size}" }
            override fun onAudioStopped(channel: Int) = Unit
            override fun onMicRequest(open: Boolean) { events += "mic $open" }
            override fun onAaClosed(link: AapLink, reason: String, refused: Boolean) { events += "closed" }
        }, port = server.localPort)
        link.start()

        val sock = server.accept()
        sock.soTimeout = 5000
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }
        val engine = ctx.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
        }
        val phone = Phone(engine, DataInputStream(sock.getInputStream()), sock.getOutputStream())

        // version
        val (vch, vflags, vbody) = phone.readFrame()
        assertEquals(0, vch)
        assertEquals(Flag.PLAIN, vflags)
        assertArrayEquals(byteArrayOf(0, 1, 0, 1, 0, 2), vbody)
        phone.writeFrame(0, Flag.PLAIN, plainFrame(2, byteArrayOf(0, 1, 0, 7, 0, 0)))

        // TLS handshake carried in type-3 frames
        engine.beginHandshake()
        val app = ByteBuffer.allocate(1 shl 17)
        loop@ while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = ByteBuffer.allocate(1 shl 17)
                    engine.wrap(ByteBuffer.allocate(0), out)
                    out.flip()
                    val b = ByteArray(out.remaining()).also { out.get(it) }
                    if (b.isNotEmpty()) phone.writeFrame(0, Flag.PLAIN, plainFrame(3, b))
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!phone.netIn.hasRemaining()) {
                        val (_, _, body) = phone.readFrame()
                        assertEquals(3, ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF))
                        phone.netIn.compact(); phone.netIn.put(body, 2, body.size - 2); phone.netIn.flip()
                    }
                    app.clear()
                    val r = engine.unwrap(phone.netIn, app)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        val (_, _, body) = phone.readFrame()
                        phone.netIn.compact(); phone.netIn.put(body, 2, body.size - 2); phone.netIn.flip()
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> while (true) (engine.delegatedTask ?: break).run()
                else -> break@loop
            }
        }
        assertEquals(cert, engine.session.peerCertificates[0])

        // auth complete (plain)
        val (_, aflags, abody) = phone.readFrame()
        assertEquals(Flag.PLAIN, aflags)
        assertArrayEquals(byteArrayOf(0, 4, 8, 0), abody)

        // service discovery
        phone.send(0, T.DISCOVERY_REQUEST, PbOut().string(4, "Pixel").string(5, "Google").toBytes())
        val sdr = phone.receive()
        assertEquals(T.DISCOVERY_RESPONSE, sdr[2])
        val services = PbIn.parse(sdr[3] as ByteArray).msgs(1).map { it.int(1) }
        assertTrue(Ch.VIDEO in services && Ch.INPUT in services && Ch.MIC in services)

        // open the video channel
        phone.send(Ch.VIDEO, T.CHANNEL_OPEN_REQUEST, PbOut().sint(1, 0).int(2, Ch.VIDEO).toBytes(), control = true)
        val open = phone.receive()
        assertEquals(Ch.VIDEO, open[0])
        assertEquals(Flag.ENC or Flag.CONTROL, open[1])
        assertEquals(T.CHANNEL_OPEN_RESPONSE, open[2])

        // ping
        phone.send(0, T.PING_REQUEST, PbOut().int(1, 12345L).toBytes())
        val pong = phone.receive()
        assertEquals(T.PING_RESPONSE, pong[2])
        assertEquals(12345L, PbIn.parse(pong[3] as ByteArray).long(1))

        // video setup -> config + focus
        phone.send(Ch.VIDEO, T.MEDIA_SETUP, PbOut().int(1, 3).toBytes())
        assertEquals(T.MEDIA_CONFIG, phone.receive()[2])
        val focus = phone.receive()
        assertEquals(T.VIDEO_FOCUS_NOTIFICATION, focus[2])
        assertEquals(1, PbIn.parse(focus[3] as ByteArray).int(1))
        phone.send(Ch.VIDEO, T.MEDIA_START, PbOut().int(1, 9).int(2, 0).toBytes())

        // codec config, acknowledged at once
        phone.send(Ch.VIDEO, T.MEDIA_CODEC_CONFIG, byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3))
        assertEquals("config 8", events.poll(3, TimeUnit.SECONDS))
        val ack1 = phone.receive()
        assertEquals(T.MEDIA_ACK, ack1[2])
        assertEquals(9, PbIn.parse(ack1[3] as ByteArray).int(1))

        // a 40 KB frame in three encrypted fragments
        val frame = ByteArray(40_000) { (it % 251).toByte() }
        frame[0] = 0; frame[1] = 0; frame[2] = 0; frame[3] = 1; frame[4] = 0x65
        val msg = plainFrame(T.MEDIA_DATA, ByteBuffer.allocate(8).putLong(777L).array() + frame)
        val parts = listOf(msg.copyOfRange(0, 15000), msg.copyOfRange(15000, 30000), msg.copyOfRange(30000, msg.size))
        phone.writeFrame(Ch.VIDEO, Flag.FIRST or Flag.ENCRYPTED, phone.encrypt(parts[0]), total = msg.size)
        phone.writeFrame(Ch.VIDEO, Flag.ENCRYPTED, phone.encrypt(parts[1]))
        phone.writeFrame(Ch.VIDEO, Flag.LAST or Flag.ENCRYPTED, phone.encrypt(parts[2]))
        assertEquals("frame 40000 777", events.poll(3, TimeUnit.SECONDS))
        acks.poll(3, TimeUnit.SECONDS)!!.invoke()
        assertEquals(T.MEDIA_ACK, phone.receive()[2])

        // touch from the car
        link.sendTouch(0, 0, listOf(Triple(0, 640, 360)))
        val touch = phone.receive()
        assertEquals(Ch.INPUT, touch[0])
        assertEquals(T.INPUT_EVENT, touch[2])
        val te = PbIn.parse(touch[3] as ByteArray).msg(3)!!
        assertEquals(640, te.msg(1)!!.int(1))
        assertEquals(360, te.msg(1)!!.int(2))

        // microphone
        phone.send(Ch.MIC, T.MIC_REQUEST, PbOut().bool(1, true).int(4, 2).toBytes())
        val micResp = phone.receive()
        assertEquals(T.MIC_RESPONSE, micResp[2])
        assertEquals(0, PbIn.parse(micResp[3] as ByteArray).int(1))
        assertEquals("mic true", events.poll(3, TimeUnit.SECONDS))

        // goodbye from the phone
        phone.send(0, T.BYEBYE_REQUEST, PbOut().int(1, 1).toBytes())
        assertEquals(T.BYEBYE_RESPONSE, phone.receive()[2])
        assertEquals("closed", events.poll(3, TimeUnit.SECONDS))
        sock.close()
        server.close()
    }
}
