package com.jakharbek.monzaauto.proto

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf (proto2 wire format) encoder.
 * Only what the two car protocols need: varints, length-delimited fields and nested messages.
 */
class PbOut {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** int32/int64/uint32/enum. Negative int32 is sign-extended to 64 bits, as protobuf requires. */
    fun int(field: Int, value: Long): PbOut {
        tag(field, 0)
        rawVarint(value)
        return this
    }

    fun int(field: Int, value: Int): PbOut = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): PbOut = int(field, if (value) 1L else 0L)

    /** sint32 (zigzag). */
    fun sint(field: Int, value: Int): PbOut = int(field, ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

    fun bytes(field: Int, value: ByteArray): PbOut {
        tag(field, 2)
        rawVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): PbOut = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: PbOut): PbOut = bytes(field, value.toBytes())

    fun toBytes(): ByteArray = out.toByteArray()
}

/** Parsed protobuf message: field number -> values in arrival order (Long for varints, ByteArray for length-delimited). */
class PbIn private constructor(private val fields: Map<Int, List<Any>>) {

    fun long(field: Int, default: Long = 0): Long = (fields[field]?.firstOrNull() as? Long) ?: default

    fun int(field: Int, default: Int = 0): Int = (fields[field]?.firstOrNull() as? Long)?.toInt() ?: default

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun msg(field: Int): PbIn? = bytes(field)?.let { parse(it) }

    fun msgs(field: Int): List<PbIn> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::parse) }

    fun longs(field: Int): List<Long> = fields[field].orEmpty().mapNotNull { it as? Long }

    override fun toString(): String = fields.entries.joinToString(", ", "{", "}") { (k, v) ->
        "$k=" + v.joinToString("|") { x -> if (x is ByteArray) printable(x) else x.toString() }
    }

    companion object {
        private fun printable(b: ByteArray): String {
            val s = b.toString(Charsets.UTF_8)
            return if (s.all { it.code in 0x20..0x7E || it.code > 0xA0 }) "\"$s\"" else "<${b.size}b>"
        }

        /** Parses a message; malformed input yields whatever was read before the error. */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): PbIn {
            val map = LinkedHashMap<Int, MutableList<Any>>()
            var p = offset
            val end = offset + length

            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (p < end && shift < 64) {
                    val b = data[p++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                throw IllegalArgumentException("bad varint")
            }

            try {
                while (p < end) {
                    val key = varint()
                    val field = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> map.getOrPut(field) { ArrayList() }.add(varint())
                        1 -> p += 8
                        2 -> {
                            val len = varint().toInt()
                            if (len < 0 || p + len > end) throw IllegalArgumentException("bad length")
                            map.getOrPut(field) { ArrayList() }.add(data.copyOfRange(p, p + len))
                            p += len
                        }
                        5 -> p += 4
                        else -> throw IllegalArgumentException("bad wire type")
                    }
                }
            } catch (_: IllegalArgumentException) {
                // keep what was parsed
            }
            return PbIn(map)
        }
    }
}
