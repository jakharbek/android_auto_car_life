package com.jakharbek.monzaauto.media

/** Small helpers for H.264 Annex-B streams. */
object H264 {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** Offsets of NAL unit payloads (first byte after each start code). */
    fun nalOffsets(b: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) {
                    out += i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) {
                    out += i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }

    fun nalTypes(b: ByteArray): List<Int> = nalOffsets(b).filter { it < b.size }.map { b[it].toInt() and 0x1F }

    fun startsWithSps(b: ByteArray): Boolean = nalTypes(b).firstOrNull() == NAL_SPS

    /**
     * Head unit decoders tend to reject high level_idc values that phone encoders write.
     * Lowers level_idc to what the picture size needs and marks baseline streams as constrained baseline.
     */
    fun fixSps(b: ByteArray, width: Int, height: Int): ByteArray {
        var out = b
        for (p in nalOffsets(b)) {
            if (p + 3 >= b.size || (b[p].toInt() and 0x1F) != NAL_SPS) continue
            val profile = b[p + 1].toInt() and 0xFF
            val level = b[p + 3].toInt() and 0xFF
            val wanted = levelFor(width, height)
            val constraints = if (profile == 66) (b[p + 2].toInt() or 0x40) else b[p + 2].toInt()
            if (level <= wanted && constraints == b[p + 2].toInt()) continue
            if (out === b) out = b.copyOf()
            out[p + 2] = constraints.toByte()
            if (level > wanted) out[p + 3] = wanted.toByte()
        }
        return out
    }

    /** Smallest level that fits the picture at 30 fps: 3.1 up to 720p, 4.0 up to 1080p. */
    fun levelFor(width: Int, height: Int): Int {
        val mbs = ((width + 15) / 16) * ((height + 15) / 16)
        return when {
            mbs <= 3600 -> 31 // 1280x720 is exactly 3600 macroblocks
            mbs <= 8192 -> 40
            else -> 51
        }
    }
}
