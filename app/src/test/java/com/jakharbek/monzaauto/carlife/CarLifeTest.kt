package com.jakharbek.monzaauto.carlife

import com.jakharbek.monzaauto.proto.PbIn
import com.jakharbek.monzaauto.proto.PbOut
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeTest {
    private fun hex(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesCmdMessage() {
        val p = Packet(Chan.CMD, Msg.PROTOCOL_VERSION_MATCH_STATUS, Bodies.matchStatus(true))
        assertArrayEquals(hex("00 00 00 01 00 00 00 0a 00 02 00 00 00 01 00 02 08 01"), Frames.encode(p))
    }

    @Test
    fun encodesVideoMessage() {
        val p = Packet(Chan.VIDEO, Msg.VIDEO_DATA, byteArrayOf(0, 0, 0, 1, 0x65), 7)
        assertArrayEquals(hex("00 00 00 02 00 00 00 11 00 00 00 05 00 00 00 07 00 02 00 01 00 00 00 01 65"), Frames.encode(p))
    }

    @Test
    fun videoInitBody() {
        assertArrayEquals(hex("08 80 0a 10 d0 05 18 1e"), Bodies.videoInfo(1280, 720, 30))
    }

    @Test
    fun decodesSplitAcrossReads() {
        val got = ArrayList<Packet>()
        val d = Deframer { got += it }
        val bytes = hex("00 00 00 01 00 00 00 0c 00 04 00 00 00 01 80 01 08 02 10 00") +
            Frames.encode(Packet(Chan.TOUCH, Msg.TOUCH_ACTION, PbOut().int(1, 0).int(2, 640).int(3, 360).toBytes()))
        for (b in bytes) d.feed(byteArrayOf(b), 0, 1)
        assertEquals(2, got.size)
        assertEquals(Msg.HU_PROTOCOL_VERSION, got[0].type)
        assertEquals(2, PbIn.parse(got[0].data).int(1))
        assertEquals(640, PbIn.parse(got[1].data).int(2))
    }

    @Test
    fun decodesInnerSplitAcrossOuterFrames() {
        // inner header in one outer frame, its body in the next one
        val got = ArrayList<Packet>()
        val d = Deframer { got += it }
        val a = hex("00 00 00 01 00 00 00 08 00 04 00 64 00 01 80 01")
        val b = hex("00 00 00 01 00 00 00 04 08 02 10 01")
        d.feed(a, 0, a.size)
        assertEquals(0, got.size)
        d.feed(b, 0, b.size)
        assertEquals(1, got.size)
        assertEquals(Msg.HU_PROTOCOL_VERSION, got[0].type)
        assertArrayEquals(hex("08 02 10 01"), got[0].data)
    }

    @Test
    fun subscribeDoneEchoesModules() {
        assertArrayEquals(hex("08 01 12 04 08 00 10 01"), Bodies.subscribeDone(hex("08 01 12 02 08 00")))
    }

    @Test
    fun moduleStatusForNewHeadUnits() {
        val b = PbIn.parse(Bodies.moduleStatusAllIdle(2))
        assertEquals(7, b.int(1))
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 9), b.msgs(2).map { it.int(1) })
        assertEquals(44, Bodies.moduleStatusAllIdle(2).size)
    }

    private val phone = PhoneInfo("b", "bl", "Xiaomi", "arm64-v8a", "", "dev", "disp", "fp", "hw", "host", "id",
        "Xiaomi", "2201", "prod", "REL", "inc", "16", 36)

    @Test
    fun challengeAnswer() {
        assertNull(Bodies.challengeAnswer("abc_0", phone))
        val expected = java.security.MessageDigest.getInstance("MD5").digest("CARLIFEXiaomi220129rnd".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, Bodies.challengeAnswer("2.0;rnd", phone))
    }

    @Test
    fun phoneInfoReportsCappedSdk() {
        val b = PbIn.parse(Bodies.phoneInfo(phone))
        assertEquals("Android", b.string(1))
        assertEquals("29", b.string(20))
        assertEquals(29, b.int(21))
        assertEquals(1, b.int(25))
    }

    @Test
    fun touchSingleFinger() {
        val m = TouchMapper(1280, 720)
        assertEquals(listOf(Touch(0, 0, listOf(Finger(0, 10, 20)))), m.action(0, 10, 20, 0, 0))
        assertEquals(listOf(Touch(2, 0, listOf(Finger(0, 15, 25)))), m.action(2, 15, 25, 0, 0))
        assertEquals(listOf(Touch(1, 0, listOf(Finger(0, 15, 25)))), m.action(1, 15, 25, 0, 0))
    }

    @Test
    fun touchTwoFingers() {
        val m = TouchMapper(1280, 720)
        m.action(0, 100, 100, 0, 0)
        val down2 = m.action(5 or (1 shl 8), 100, 100, 300, 300)
        assertEquals(listOf(Touch(5, 1, listOf(Finger(0, 100, 100), Finger(1, 300, 300)))), down2)
        val move = m.action(2, 110, 110, 310, 310)
        assertEquals(listOf(Touch(2, 0, listOf(Finger(0, 110, 110), Finger(1, 310, 310)))), move)
        val up2 = m.action(6 or (1 shl 8), 110, 110, 310, 310)
        assertEquals(listOf(Touch(6, 1, listOf(Finger(0, 110, 110), Finger(1, 310, 310)))), up2)
        assertEquals(listOf(Touch(1, 0, listOf(Finger(0, 110, 110)))), m.action(1, 110, 110, 0, 0))
    }

    @Test
    fun firstFingerLiftsThenNewFingerLands() {
        val m = TouchMapper(1280, 720)
        m.action(0, 100, 100, 0, 0)
        m.action(5 or (1 shl 8), 100, 100, 500, 500)
        // finger in slot 0 (id 0) lifts; the other one (id 1) moves to slot 0
        assertEquals(listOf(Touch(6, 0, listOf(Finger(0, 100, 100), Finger(1, 500, 500)))), m.action(6, 100, 100, 500, 500))
        assertEquals(listOf(Touch(2, 0, listOf(Finger(1, 520, 520)))), m.action(2, 520, 520, 0, 0))
        // a new finger lands: only POINTER_DOWN, the old finger keeps id 1, the new one gets the free id 0
        val down = m.action(5 or (1 shl 8), 530, 530, 50, 60)
        assertEquals(listOf(Touch(5, 0, listOf(Finger(0, 50, 60), Finger(1, 530, 530)))), down)
        // moves keep each id on its own finger
        assertEquals(listOf(Touch(2, 0, listOf(Finger(0, 55, 65), Finger(1, 540, 540)))), m.action(2, 540, 540, 55, 65))
        assertEquals(listOf(Touch(6, 0, listOf(Finger(0, 55, 65), Finger(1, 540, 540)))), m.action(6 or (1 shl 8), 540, 540, 55, 65))
        assertEquals(listOf(Touch(1, 0, listOf(Finger(1, 545, 545)))), m.action(1, 545, 545, 0, 0))
    }

    @Test
    fun clickGesture() {
        val m = TouchMapper(1280, 720)
        val t = m.gesture(4, 2000, 50)
        assertEquals(listOf(Touch(0, 0, listOf(Finger(0, 1280, 50))), Touch(1, 0, listOf(Finger(0, 1280, 50)))), t)
    }
}
