package com.jakharbek.monzaauto.aa

import com.jakharbek.monzaauto.proto.PbOut

/** Channel numbers this head unit assigns to its services. */
object Ch {
    const val CONTROL = 0
    const val SENSOR = 1
    const val VIDEO = 2
    const val INPUT = 3
    const val SPEECH = 4
    const val SYSTEM = 5
    const val MEDIA = 6
    const val MIC = 7
    const val PLAYBACK = 9
    const val NAVIGATION = 10

    fun name(c: Int) = when (c) {
        CONTROL -> "CONTROL"; SENSOR -> "SENSOR"; VIDEO -> "VIDEO"; INPUT -> "INPUT"; SPEECH -> "SPEECH"
        SYSTEM -> "SYSTEM"; MEDIA -> "MEDIA"; MIC -> "MIC"; PLAYBACK -> "PLAYBACK"; NAVIGATION -> "NAVIGATION"
        else -> "CH$c"
    }
}

/** Frame flags. */
object Flag {
    const val FIRST = 0x01
    const val LAST = 0x02
    const val CONTROL = 0x04
    const val ENCRYPTED = 0x08

    const val PLAIN = FIRST or LAST
    const val ENC = FIRST or LAST or ENCRYPTED
}

/** Message types. */
object T {
    // control
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val SSL = 3
    const val AUTH_COMPLETE = 4
    const val DISCOVERY_REQUEST = 5
    const val DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYEBYE_REQUEST = 15
    const val BYEBYE_RESPONSE = 16
    const val VOICE_SESSION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19

    // media (video, audio, microphone)
    const val MEDIA_DATA = 0
    const val MEDIA_CODEC_CONFIG = 1
    const val MEDIA_SETUP = 0x8000
    const val MEDIA_START = 0x8001
    const val MEDIA_STOP = 0x8002
    const val MEDIA_CONFIG = 0x8003
    const val MEDIA_ACK = 0x8004
    const val MIC_REQUEST = 0x8005
    const val MIC_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_NOTIFICATION = 0x8008

    // sensors
    const val SENSOR_REQUEST = 0x8001
    const val SENSOR_RESPONSE = 0x8002
    const val SENSOR_EVENT = 0x8003

    // input
    const val INPUT_EVENT = 0x8001
    const val KEY_BINDING_REQUEST = 0x8002
    const val KEY_BINDING_RESPONSE = 0x8003
}

/** What this head unit declares to Android Auto. */
data class HeadUnitConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val dpi: Int = 160,
    /** Media and guidance audio go to the car over USB (otherwise they stay on the phone, e.g. via Bluetooth). */
    val usbAudio: Boolean,
    /** Phone microphone is available for Android Auto; without it the head unit declares itself a motorcycle. */
    val mic: Boolean,
    val keycodes: List<Int> = listOf(3, 4, 5, 6, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127),
)

object AaMessages {
    private const val SENSOR_NIGHT = 10
    private const val SENSOR_DRIVING_STATUS = 13
    private const val CODEC_PCM = 1
    private const val CODEC_H264 = 3
    private const val AUDIO_SPEECH = 1
    private const val AUDIO_SYSTEM = 2
    private const val AUDIO_MEDIA = 3
    private const val VEHICLE_CAR = 1
    private const val VEHICLE_MOTORCYCLE = 3

    /** Protocol version 1.2: major and minor as big-endian u16. */
    fun versionRequest(): ByteArray = byteArrayOf(0, 1, 0, 2)

    fun authComplete(): ByteArray = PbOut().int(1, 0).toBytes()

    private fun audioConfig(rate: Int, channels: Int) = PbOut().int(1, rate).int(2, 16).int(3, channels)

    private fun audioSink(id: Int, type: Int, rate: Int, channels: Int) = PbOut().int(1, id).msg(
        3, PbOut().int(1, CODEC_PCM).int(2, type).msg(3, audioConfig(rate, channels)),
    )

    fun discoveryResponse(c: HeadUnitConfig): ByteArray {
        val resolution = when {
            c.width >= 1920 -> 3
            c.width >= 1280 -> 2
            else -> 1
        }
        val sensor = PbOut().int(1, Ch.SENSOR).msg(
            2, PbOut().msg(1, PbOut().int(1, SENSOR_DRIVING_STATUS)).msg(1, PbOut().int(1, SENSOR_NIGHT)),
        )
        val video = PbOut().int(1, Ch.VIDEO).msg(
            3,
            PbOut().int(1, CODEC_H264).int(2, 0).msg(
                4,
                PbOut().int(1, resolution).int(2, 2).int(3, 0).int(4, 0).int(5, c.dpi).int(8, 10000).int(10, CODEC_H264),
            ).bool(5, true),
        )
        val input = PbOut().int(1, Ch.INPUT).msg(
            4,
            PbOut().apply { c.keycodes.forEach { int(1, it) } }.msg(2, PbOut().int(1, c.width).int(2, c.height)),
        )
        val w = PbOut()
        w.msg(1, sensor)
        w.msg(1, video)
        w.msg(1, input)
        w.msg(1, audioSink(Ch.SYSTEM, AUDIO_SYSTEM, 16000, 1))
        if (c.usbAudio) {
            w.msg(1, audioSink(Ch.SPEECH, AUDIO_SPEECH, 16000, 1))
            w.msg(1, audioSink(Ch.MEDIA, AUDIO_MEDIA, 48000, 2))
        }
        if (c.mic) {
            w.msg(1, PbOut().int(1, Ch.MIC).msg(5, PbOut().int(1, CODEC_PCM).msg(2, audioConfig(16000, 1))))
        }
        w.msg(1, PbOut().int(1, Ch.PLAYBACK).msg(9, PbOut()))
        w.msg(1, PbOut().int(1, Ch.NAVIGATION).msg(8, PbOut().int(1, 1000).int(2, 2)))

        val vehicleType = if (c.mic) VEHICLE_CAR else VEHICLE_MOTORCYCLE
        val vehicleId = if (c.mic) "monza-auto" else "monza-auto-moto"
        val make = "Chevrolet"
        val model = "Monza"
        val year = "2024"
        // Android Auto's head unit server works with a desktop head unit identity.
        val huMake = "Google"
        val huModel = "Desktop Head Unit"
        val build = "1"
        val version = "1.0"
        w.string(2, make).string(3, model).string(4, year).string(5, vehicleId).int(6, 0)
            .string(7, huMake).string(8, huModel).string(9, build).string(10, version)
            .bool(11, false).bool(12, false).string(14, "Monza Auto")
            .msg(
                17,
                PbOut().string(1, make).string(2, model).string(3, year).string(4, vehicleId)
                    .string(5, huMake).string(6, huModel).string(7, build).string(8, version).int(9, vehicleType),
            )
        return w.toBytes()
    }

    fun channelOpenOk(): ByteArray = PbOut().int(1, 0).toBytes()

    fun pingResponse(ts: Long): ByteArray = PbOut().int(1, ts).toBytes()

    fun navFocus(): ByteArray = PbOut().int(1, 2).toBytes()

    fun byeByeResponse(): ByteArray = ByteArray(0)

    fun byeByeRequest(): ByteArray = PbOut().int(1, 1).toBytes()

    /** Grants whatever focus was asked for. */
    fun audioFocus(request: Int): ByteArray {
        val state = when (request) {
            1 -> 1   // GAIN
            2 -> 2   // GAIN_TRANSIENT
            3 -> 7   // GAIN_TRANSIENT_MAY_DUCK -> guidance only
            4 -> 3   // RELEASE -> LOSS
            else -> 1
        }
        return PbOut().int(1, state).bool(2, false).toBytes()
    }

    fun mediaConfig(maxUnacked: Int): ByteArray = PbOut().int(1, 2).int(2, maxUnacked).int(3, 0).toBytes()

    fun videoFocus(projected: Boolean, unsolicited: Boolean): ByteArray =
        PbOut().int(1, if (projected) 1 else 2).bool(2, unsolicited).toBytes()

    fun mediaAck(session: Int): ByteArray = PbOut().int(1, session).int(2, 1).toBytes()

    fun sensorOk(): ByteArray = PbOut().int(1, 0).toBytes()

    fun drivingStatusUnrestricted(): ByteArray = PbOut().msg(13, PbOut().int(1, 0)).toBytes()

    fun nightMode(night: Boolean): ByteArray = PbOut().msg(10, PbOut().bool(1, night)).toBytes()

    fun keyBindingOk(): ByteArray = PbOut().int(1, 0).toBytes()

    fun micResponse(ok: Boolean, session: Int): ByteArray = PbOut().int(1, if (ok) 0 else -7).int(2, session).toBytes()

    fun touch(timestampNs: Long, action: Int, index: Int, pointers: List<Triple<Int, Int, Int>>): ByteArray {
        val te = PbOut()
        for ((id, x, y) in pointers) te.msg(1, PbOut().int(1, x).int(2, y).int(3, id))
        te.int(2, index).int(3, action)
        return PbOut().int(1, timestampNs).msg(3, te).toBytes()
    }

    fun key(timestampNs: Long, keycode: Int, down: Boolean): ByteArray =
        PbOut().int(1, timestampNs).msg(4, PbOut().msg(1, PbOut().int(1, keycode).bool(2, down).int(3, 0).bool(4, false)))
            .toBytes()
}
