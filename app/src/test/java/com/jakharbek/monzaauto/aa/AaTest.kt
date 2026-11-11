package com.jakharbek.monzaauto.aa

import com.jakharbek.monzaauto.media.H264
import com.jakharbek.monzaauto.proto.PbIn
import com.jakharbek.monzaauto.proto.PbOut
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaTest {
    @Test
    fun protobufRoundTrip() {
        val b = PbOut().int(1, 300).string(2, "abc").int(3, -7).msg(4, PbOut().bool(1, true)).toBytes()
        val p = PbIn.parse(b)
        assertEquals(300, p.int(1))
        assertEquals("abc", p.string(2))
        assertEquals(-7, p.int(3))
        assertTrue(p.msg(4)!!.bool(1))
    }

    @Test
    fun authCompleteBody() {
        assertArrayEquals(byteArrayOf(0x08, 0x00), AaMessages.authComplete())
    }

    @Test
    fun discoveryDeclaresServices() {
        val sdr = PbIn.parse(AaMessages.discoveryResponse(HeadUnitConfig(dpi = 170, usbAudio = true, mic = true)))
        val ids = sdr.msgs(1).map { it.int(1) }
        assertEquals(listOf(Ch.SENSOR, Ch.VIDEO, Ch.INPUT, Ch.SYSTEM, Ch.SPEECH, Ch.MEDIA, Ch.MIC, Ch.PLAYBACK, Ch.NAVIGATION), ids)
        val video = sdr.msgs(1).first { it.int(1) == Ch.VIDEO }.msg(3)!!.msg(4)!!
        assertEquals(2, video.int(1))
        assertEquals(170, video.int(5))
        val touch = sdr.msgs(1).first { it.int(1) == Ch.INPUT }.msg(4)!!.msg(2)!!
        assertEquals(1280, touch.int(1))
        assertEquals(720, touch.int(2))
        assertEquals("Desktop Head Unit", sdr.string(8))
        assertEquals(1, sdr.msg(17)!!.int(9))
    }

    @Test
    fun discoveryWithoutMicIsMotorcycle() {
        val sdr = PbIn.parse(AaMessages.discoveryResponse(HeadUnitConfig(usbAudio = false, mic = false)))
        val ids = sdr.msgs(1).map { it.int(1) }
        assertFalse(Ch.MIC in ids)
        assertFalse(Ch.MEDIA in ids)
        assertEquals(3, sdr.msg(17)!!.int(9))
    }

    @Test
    fun spsLevelIsLowered() {
        // start code, SPS header 0x67, profile 66, constraints 0x80, level 5.1
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0x80.toByte(), 51, 0x11)
        val fixed = H264.fixSps(sps, 1280, 720)
        assertEquals(31, fixed[7].toInt())
        assertEquals(0xC0, fixed[6].toInt() and 0xFF)
        assertTrue(H264.startsWithSps(fixed))
        val ok = byteArrayOf(0, 0, 0, 1, 0x67, 100, 0, 31)
        assertArrayEquals(ok, H264.fixSps(ok, 1280, 720))
    }

    @Test
    fun nalTypes() {
        val b = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3)
        assertEquals(listOf(7, 8, 5), H264.nalTypes(b))
    }

    @Test
    fun pkcs1Wrap() {
        val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
        // PKCS#8 of an RSA key = header + PKCS#1; unwrap the octet string and wrap it back
        val pkcs8 = key.encoded
        val pkcs1 = pkcs8.copyOfRange(pkcs8.size - pkcs1Length(pkcs8), pkcs8.size)
        val rebuilt = HuCredentials.pkcs1ToPkcs8(pkcs1)
        val k2 = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(rebuilt))
        assertArrayEquals(key.encoded, k2.encoded)
    }

    /** Length of the trailing PKCS#1 structure (a DER SEQUENCE) inside a PKCS#8 encoding. */
    private fun pkcs1Length(pkcs8: ByteArray): Int {
        for (i in pkcs8.indices) {
            if (pkcs8[i] == 0x04.toByte() && i + 2 < pkcs8.size && pkcs8[i + 1] == 0x82.toByte()) {
                val len = ((pkcs8[i + 2].toInt() and 0xFF) shl 8) or (pkcs8[i + 3].toInt() and 0xFF)
                if (i + 4 + len == pkcs8.size) return len
            }
        }
        error("no octet string")
    }
}
