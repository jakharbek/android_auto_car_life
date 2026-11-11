package com.jakharbek.monzaauto.carlife

import com.jakharbek.monzaauto.proto.PbIn
import com.jakharbek.monzaauto.proto.PbOut
import java.security.MessageDigest

/** CarLife message type codes. Bit 0x8000 in the low word = sent by the head unit. */
object Msg {
    // CMD channel, head unit -> phone
    const val HU_PROTOCOL_VERSION = 0x00018001
    const val HU_INFO = 0x00018003
    const val VIDEO_ENCODER_INIT = 0x00018007
    const val VIDEO_ENCODER_START = 0x00018009
    const val VIDEO_ENCODER_PAUSE = 0x0001800A
    const val VIDEO_ENCODER_RESET = 0x0001800B
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C
    const val LAUNCH_MODE_NORMAL = 0x0001801D
    const val GO_TO_FOREGROUND = 0x00018025
    const val STATISTIC_INFO = 0x00018027
    const val MODULE_CONTROL = 0x00018028
    const val CARLIFE_DATA_SUBSCRIBE = 0x00018043
    const val CARLIFE_DATA_SUBSCRIBE_START = 0x00018045
    const val CARLIFE_DATA_SUBSCRIBE_STOP = 0x00018046
    const val HU_AUTHEN_REQUEST = 0x00018048
    const val HU_AUTHEN_RESULT = 0x0001804A
    const val HU_FEATURE_CONFIG_RESPONSE = 0x00018052
    const val ERROR_CODE = 0x00018055
    const val VIDEO_ENCODER_JPEG = 0x00018056
    const val REQUEST_THEME = 0x00018079

    // CMD channel, phone -> head unit
    const val PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
    const val MD_INFO = 0x00010004
    const val VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D
    const val SCREEN_ON = 0x00010018
    const val FOREGROUND = 0x0001001B
    const val MODULE_STATUS = 0x00010026
    const val CAR_DATA_SUBSCRIBE = 0x00010031
    const val CARLIFE_DATA_SUBSCRIBE_DONE = 0x00010044
    const val MD_AUTHEN_RESPONSE = 0x00010049
    const val MD_AUTHEN_RESULT = 0x0001004B
    const val MD_FEATURE_CONFIG_REQUEST = 0x00010051
    const val VIDEO_ENCODER_JPEG_ACK = 0x00010057
    const val MD_EXIT = 0x00010059
    const val RESPONSE_THEME = 0x00010080

    // VIDEO channel
    const val VIDEO_DATA = 0x00020001

    // MEDIA channel
    const val MEDIA_INIT = 0x00030001
    const val MEDIA_STOP = 0x00030002
    const val MEDIA_DATA = 0x00030006

    // TTS channel
    const val TTS_INIT = 0x00040001
    const val TTS_STOP = 0x00040002
    const val TTS_DATA = 0x00040003

    // TOUCH channel, head unit -> phone
    const val TOUCH_ACTION = 0x00068001
    const val TOUCH_DOWN = 0x00068002
    const val TOUCH_UP = 0x00068003
    const val TOUCH_MOVE = 0x00068004
    const val TOUCH_SINGLE_CLICK = 0x00068005
    const val TOUCH_HARD_KEY = 0x00068008

    private val names = mapOf(
        HU_PROTOCOL_VERSION to "HU_PROTOCOL_VERSION", HU_INFO to "HU_INFO", VIDEO_ENCODER_INIT to "VIDEO_ENCODER_INIT",
        VIDEO_ENCODER_START to "VIDEO_ENCODER_START", VIDEO_ENCODER_PAUSE to "VIDEO_ENCODER_PAUSE",
        VIDEO_ENCODER_RESET to "VIDEO_ENCODER_RESET", VIDEO_ENCODER_FRAME_RATE_CHANGE to "FRAME_RATE_CHANGE",
        LAUNCH_MODE_NORMAL to "LAUNCH_MODE_NORMAL", GO_TO_FOREGROUND to "GO_TO_FOREGROUND",
        STATISTIC_INFO to "STATISTIC_INFO", MODULE_CONTROL to "MODULE_CONTROL",
        CARLIFE_DATA_SUBSCRIBE to "DATA_SUBSCRIBE", CARLIFE_DATA_SUBSCRIBE_START to "DATA_SUBSCRIBE_START",
        CARLIFE_DATA_SUBSCRIBE_STOP to "DATA_SUBSCRIBE_STOP", HU_AUTHEN_REQUEST to "HU_AUTHEN_REQUEST",
        HU_AUTHEN_RESULT to "HU_AUTHEN_RESULT", HU_FEATURE_CONFIG_RESPONSE to "HU_FEATURE_CONFIG",
        ERROR_CODE to "ERROR_CODE", VIDEO_ENCODER_JPEG to "VIDEO_ENCODER_JPEG", REQUEST_THEME to "REQUEST_THEME",
        PROTOCOL_VERSION_MATCH_STATUS to "MATCH_STATUS", MD_INFO to "MD_INFO", VIDEO_ENCODER_INIT_DONE to "INIT_DONE",
        SCREEN_ON to "SCREEN_ON", FOREGROUND to "FOREGROUND", MODULE_STATUS to "MODULE_STATUS",
        CAR_DATA_SUBSCRIBE to "CAR_DATA_SUBSCRIBE", CARLIFE_DATA_SUBSCRIBE_DONE to "DATA_SUBSCRIBE_DONE",
        MD_AUTHEN_RESPONSE to "MD_AUTHEN_RESPONSE", MD_AUTHEN_RESULT to "MD_AUTHEN_RESULT",
        MD_FEATURE_CONFIG_REQUEST to "FEATURE_CONFIG_REQUEST", MD_EXIT to "MD_EXIT",
    )

    fun name(type: Int) = names[type] ?: "0x" + Integer.toHexString(type)
}

/** Head unit hardware keys (TOUCH_HARD_KEY). */
object HardKey {
    const val HOME = 1
    const val PHONE_CALL = 2
    const val PHONE_END = 3
    const val SELECTOR_NEXT = 6
    const val SELECTOR_PREVIOUS = 7
    const val BACK = 0x0E
    const val SEEK_SUB = 0x0F
    const val SEEK_ADD = 0x10
    const val OK = 0x14
    const val MOVE_LEFT = 0x15
    const val MOVE_RIGHT = 0x16
    const val MOVE_UP = 0x17
    const val MOVE_DOWN = 0x18
    const val MEDIA_START = 0x1F
    const val MEDIA_STOP = 0x20
    const val VR_START = 0x21
}

/** Information about the phone sent in MD_INFO. Field values must stay identical between messages. */
data class PhoneInfo(
    val board: String, val bootloader: String, val brand: String, val cpuAbi: String, val cpuAbi2: String,
    val device: String, val display: String, val fingerprint: String, val hardware: String, val host: String,
    val id: String, val manufacturer: String, val model: String, val product: String, val codename: String,
    val incremental: String, val release: String, val sdkInt: Int,
) {
    /** Head units refuse unknown newer Android versions: report at most API 29, real value otherwise. */
    val reportedSdk: Int get() = if (sdkInt >= 33) 29 else sdkInt
}

/** Builders and parsers for CarLife protobuf bodies. */
object Bodies {
    /** Protocol version of the phone side reported in MD_INFO. */
    const val PHONE_PROTOCOL_VERSION = "8.6.7"

    fun matchStatus(ok: Boolean) = PbOut().int(1, if (ok) 1 else 2).toBytes()

    fun authenResult(ok: Boolean) = PbOut().bool(1, ok).toBytes()

    fun phoneInfo(p: PhoneInfo): ByteArray {
        val w = PbOut()
        fun s(f: Int, v: String) {
            if (v.isNotEmpty()) w.string(f, v)
        }
        s(1, "Android"); s(2, p.board); s(3, p.bootloader); s(4, p.brand); s(5, p.cpuAbi); s(6, p.cpuAbi2)
        s(7, p.device); s(8, p.display); s(9, p.fingerprint); s(10, p.hardware); s(11, p.host); s(12, p.id)
        s(13, p.manufacturer); s(14, p.model); s(15, p.product); s(16, "unknown"); s(17, p.codename)
        s(18, p.incremental); s(19, p.release); s(20, p.reportedSdk.toString())
        w.int(21, p.reportedSdk)
        s(23, "unknown"); s(24, PHONE_PROTOCOL_VERSION)
        w.int(25, 1)
        return w.toBytes()
    }

    /** MODULE_STATUS with every module idle. Head units of protocol 2+ expect modules 8 and 9 as well. */
    fun moduleStatusAllIdle(huMajor: Int): ByteArray {
        val ids = if (huMajor >= 2) intArrayOf(1, 2, 3, 4, 6, 8, 9) else intArrayOf(1, 2, 3, 4, 6)
        val w = PbOut().int(1, ids.size)
        for (id in ids) w.msg(2, PbOut().int(1, id).int(2, 0))
        return w.toBytes()
    }

    fun moduleStatus(moduleId: Int, statusId: Int) =
        PbOut().int(1, 1).msg(2, PbOut().int(1, moduleId).int(2, statusId)).toBytes()

    /** Reply to CARLIFE_DATA_SUBSCRIBE: the same module list, each marked as supported. */
    fun subscribeDone(request: ByteArray): ByteArray {
        val req = PbIn.parse(request)
        val items = req.msgs(2)
        val w = PbOut().int(1, items.size)
        for (it in items) w.msg(2, PbOut().int(1, it.int(1)).int(2, 1))
        return w.toBytes()
    }

    fun videoInfo(width: Int, height: Int, fps: Int) = PbOut().int(1, width).int(2, height).int(3, fps).toBytes()

    fun frameRate(fps: Int) = PbOut().int(1, fps).toBytes()

    /** MEDIA_INIT / TTS_INIT: sample rate, channel count, bits per sample. */
    fun audioInit(rate: Int, channels: Int, bits: Int) = PbOut().int(1, rate).int(2, channels).int(3, bits).toBytes()

    fun authenResponse(value: String) = PbOut().string(1, value).toBytes()

    /** Feature list from HU_FEATURE_CONFIG_RESPONSE: key -> value. */
    fun featureConfig(data: ByteArray): Map<String, Int> =
        PbIn.parse(data).msgs(2).associate { (it.string(1) ?: "") to it.int(2) }

    /**
     * Answer to an authentication challenge of the form "2.0;<random>": lowercase hex md5 of
     * "CARLIFE" + brand + model + sdk (as sent in MD_INFO) + <random>. Other challenge forms are not supported.
     */
    fun challengeAnswer(random: String, p: PhoneInfo): String? {
        val parts = random.split(";")
        if (parts.size < 2 || parts[0] != "2.0") return null
        val text = "CARLIFE" + p.brand + p.model + p.reportedSdk + parts[1]
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
