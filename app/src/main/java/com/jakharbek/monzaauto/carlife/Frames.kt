package com.jakharbek.monzaauto.carlife

import java.nio.ByteBuffer

/** CarLife channels on the USB link. */
object Chan {
    const val CMD = 1
    const val VIDEO = 2
    const val MEDIA = 3
    const val TTS = 4
    const val VR = 5
    const val TOUCH = 6

    fun name(c: Int) = when (c) {
        CMD -> "CMD"; VIDEO -> "VIDEO"; MEDIA -> "MEDIA"; TTS -> "TTS"; VR -> "VR"; TOUCH -> "TOUCH"
        else -> "CH$c"
    }

    /** CMD and TOUCH use an 8-byte inner header, the stream channels a 12-byte one. */
    fun innerHeader(c: Int) = if (c == CMD || c == TOUCH) 8 else 12
}

/** One CarLife message: channel, type and payload (protobuf, H.264 or PCM). */
class Packet(val chan: Int, val type: Int, val data: ByteArray, val time: Int = 0) {
    override fun toString() = "${Chan.name(chan)} 0x${Integer.toHexString(type)} len=${data.size}"
}

/**
 * Encoding and decoding of the USB stream.
 *
 * On USB every message goes as `[chan u32 BE][len u32 BE]` + inner message.
 * Inner message of CMD/TOUCH: `[len u16][0 u16][type u32]` + data.
 * Inner message of VIDEO/MEDIA/TTS/VR: `[len u32][time u32][type u32]` + data.
 */
object Frames {
    const val OUTER = 8

    /** Largest inner message we accept; bigger declared lengths mean a broken stream. */
    const val MAX_INNER = 4 * 1024 * 1024

    fun outerHeader(chan: Int, innerLen: Int): ByteArray =
        ByteBuffer.allocate(OUTER).putInt(chan).putInt(innerLen).array()

    fun inner(p: Packet): ByteArray {
        val h = Chan.innerHeader(p.chan)
        val b = ByteBuffer.allocate(h + p.data.size)
        if (h == 8) {
            b.putShort(p.data.size.toShort()).putShort(0).putInt(p.type)
        } else {
            b.putInt(p.data.size).putInt(p.time).putInt(p.type)
        }
        b.put(p.data)
        return b.array()
    }

    /** Whole message as one byte array (tests and logging). */
    fun encode(p: Packet): ByteArray {
        val i = inner(p)
        return outerHeader(p.chan, i.size) + i
    }
}

/**
 * Turns the raw byte stream from the accessory into packets.
 *
 * Bytes are buffered across reads (a read can end anywhere). Outer frames of one channel are joined
 * into a per-channel buffer, and inner messages are cut from there: this way an inner message split
 * across several outer frames (header in one, body in the next) is handled the same as a whole one.
 */
class Deframer(private val onPacket: (Packet) -> Unit) {
    private var buf = ByteArray(64 * 1024)
    private var len = 0
    private val perChan = HashMap<Int, Accum>()

    private class Accum {
        var b = ByteArray(0)
        var n = 0
        fun add(src: ByteArray, off: Int, cnt: Int) {
            if (n + cnt > b.size) b = b.copyOf(maxOf(b.size * 2, n + cnt))
            System.arraycopy(src, off, b, n, cnt)
            n += cnt
        }
        fun drop(cnt: Int) {
            System.arraycopy(b, cnt, b, 0, n - cnt)
            n -= cnt
        }
    }

    /** Number of times the stream had to be resynchronised (for the log). */
    var resyncs = 0
        private set

    fun feed(data: ByteArray, off: Int, cnt: Int) {
        if (len + cnt > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + cnt))
        System.arraycopy(data, off, buf, len, cnt)
        len += cnt
        var p = 0
        while (len - p >= Frames.OUTER) {
            val chan = readInt(buf, p)
            val outerLen = readInt(buf, p + 4)
            if (chan !in 1..6 || outerLen < 0 || outerLen > Frames.MAX_INNER) {
                // lost sync: skip one byte and look for the next plausible header
                resyncs++
                p++
                continue
            }
            if (len - p - Frames.OUTER < outerLen) break
            val a = perChan.getOrPut(chan) { Accum() }
            a.add(buf, p + Frames.OUTER, outerLen)
            p += Frames.OUTER + outerLen
            drain(chan, a)
        }
        if (p > 0) {
            System.arraycopy(buf, p, buf, 0, len - p)
            len -= p
        }
    }

    private fun drain(chan: Int, a: Accum) {
        val h = Chan.innerHeader(chan)
        while (a.n >= h) {
            val dataLen: Int
            val time: Int
            val type: Int
            if (h == 8) {
                dataLen = ((a.b[0].toInt() and 0xFF) shl 8) or (a.b[1].toInt() and 0xFF)
                time = 0
                type = readInt(a.b, 4)
            } else {
                dataLen = readInt(a.b, 0)
                time = readInt(a.b, 4)
                type = readInt(a.b, 8)
            }
            if (dataLen < 0 || dataLen > Frames.MAX_INNER) {
                resyncs++
                a.n = 0
                return
            }
            if (a.n < h + dataLen) return
            val payload = a.b.copyOfRange(h, h + dataLen)
            a.drop(h + dataLen)
            onPacket(Packet(chan, type, payload, time))
        }
    }

    private fun readInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}
