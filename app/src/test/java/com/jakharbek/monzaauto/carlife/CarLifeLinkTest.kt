package com.jakharbek.monzaauto.carlife

import com.jakharbek.monzaauto.proto.PbIn
import com.jakharbek.monzaauto.proto.PbOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the phone side against a scripted head unit over in-memory pipes. */
class CarLifeLinkTest {
    private val phone = PhoneInfo("b", "bl", "Brand", "arm64-v8a", "", "dev", "disp", "fp", "hw", "host", "id",
        "Maker", "Model", "prod", "REL", "inc", "14", 34)

    private class Recorder : CarLifeEvents {
        val events = LinkedBlockingQueue<String>()
        override fun onCarState(state: String) = Unit
        override fun onVideoWanted() { events += "video" }
        override fun onVideoPaused() { events += "pause" }
        override fun onTouch(t: Touch) { events += "touch ${t.action} ${t.fingers}" }
        override fun onKey(androidKeyCode: Int) { events += "key $androidKeyCode" }
        override fun onFeatures(features: Map<String, Int>) { events += "features $features" }
        override fun onCarClosed(reason: String) { events += "closed" }
    }

    @Test
    fun handshakeVideoAndTouch() {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 20)
        val fromPhone = PipedInputStream(1 shl 20)
        val phoneOut = PipedOutputStream(fromPhone)
        val rec = Recorder()
        val link = CarLifeLink(phoneIn, phoneOut, phone, rec)
        val got = LinkedBlockingQueue<Packet>()
        val deframer = Deframer { got += it }
        Thread {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = fromPhone.read(buf)
                    if (n < 0) break
                    deframer.feed(buf, 0, n)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        link.start()

        fun hu(chan: Int, type: Int, body: ByteArray = ByteArray(0)) {
            toPhone.write(Frames.encode(Packet(chan, type, body)))
            toPhone.flush()
        }

        fun next(): Packet = got.poll(3, TimeUnit.SECONDS) ?: error("phone sent nothing")

        hu(Chan.CMD, Msg.HU_PROTOCOL_VERSION, PbOut().int(1, 2).int(2, 0).toBytes())
        assertEquals(Msg.PROTOCOL_VERSION_MATCH_STATUS, next().type)
        assertEquals(Msg.FOREGROUND, next().type)
        assertEquals(Msg.SCREEN_ON, next().type)
        val info = next()
        assertEquals(Msg.MD_INFO, info.type)
        assertEquals("Model", PbIn.parse(info.data).string(14))
        assertEquals(Msg.MODULE_STATUS, next().type)

        hu(Chan.CMD, Msg.STATISTIC_INFO, PbOut().string(1, "cuid").string(4, "1234").toBytes())
        assertEquals(Msg.MD_AUTHEN_RESULT, next().type)
        assertEquals(Msg.MD_FEATURE_CONFIG_REQUEST, next().type)
        assertEquals(Msg.CAR_DATA_SUBSCRIBE, next().type)

        val features = PbOut().int(1, 1).msg(2, PbOut().string(1, "AUDIO_TRANSMISSION_MODE").int(2, 1)).toBytes()
        hu(Chan.CMD, Msg.HU_FEATURE_CONFIG_RESPONSE, features)
        assertEquals("features {AUDIO_TRANSMISSION_MODE=1}", rec.events.poll(3, TimeUnit.SECONDS))

        hu(Chan.CMD, Msg.VIDEO_ENCODER_INIT, Bodies.videoInfo(1024, 600, 0))
        val done = next()
        assertEquals(Msg.VIDEO_ENCODER_INIT_DONE, done.type)
        assertEquals(1280, PbIn.parse(done.data).int(1))
        assertEquals(30, PbIn.parse(done.data).int(3))
        assertEquals(Msg.FOREGROUND, next().type)

        hu(Chan.CMD, Msg.VIDEO_ENCODER_START)
        assertEquals("video", rec.events.poll(3, TimeUnit.SECONDS))
        assertTrue(link.streaming)

        link.sendVideo(byteArrayOf(0, 0, 0, 1, 0x67, 0x42), true)
        val v = next()
        assertEquals(Chan.VIDEO, v.chan)
        assertEquals(Msg.VIDEO_DATA, v.type)
        assertEquals(6, v.data.size)

        hu(Chan.TOUCH, Msg.TOUCH_ACTION, PbOut().int(1, 0).int(2, 640).int(3, 360).toBytes())
        assertEquals("touch 0 [Finger(id=0, x=640, y=360)]", rec.events.poll(3, TimeUnit.SECONDS))

        hu(Chan.TOUCH, Msg.TOUCH_HARD_KEY, PbOut().int(1, HardKey.SEEK_ADD).toBytes())
        assertEquals("key 87", rec.events.poll(3, TimeUnit.SECONDS))

        link.close("test")
    }
}
