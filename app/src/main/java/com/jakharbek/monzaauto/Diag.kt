package com.jakharbek.monzaauto

/**
 * What the links last saw, for the diagnostics screen and the setup wizard.
 * Written by the link threads, read by the UI; every field is a single volatile value.
 */
object Diag {
    enum class CarStage { NONE, USB_OPEN, TALKING, VIDEO_SETUP, STREAMING, PAUSED, CLOSED }

    enum class AaStage { NONE, CONNECTING, HANDSHAKE, CONNECTED, VIDEO, CLOSED }

    // ----- head unit -----
    @Volatile var carStage = CarStage.NONE
    @Volatile var carClosedReason: String? = null
    @Volatile var accessory: String? = null
    @Volatile var usbPermissionMissing = false
    @Volatile var huProtocol: String? = null
    @Volatile var huModel: String? = null
    @Volatile var huScreen: String? = null
    @Volatile var huFeatures: Map<String, Int>? = null
    @Volatile var authUnsupported = false
    @Volatile var carFramesSent = 0L
    @Volatile var carConnectedAt = 0L

    // ----- Android Auto -----
    @Volatile var aaStage = AaStage.NONE
    @Volatile var aaClosedReason: String? = null
    @Volatile var aaRefused = false
    @Volatile var aaTimedOut = false
    @Volatile var aaTlsFailed = false
    @Volatile var aaProtocol: String? = null
    @Volatile var aaFramesReceived = 0L
    @Volatile var aaUsbAudio: Boolean? = null
    @Volatile var aaMic: Boolean? = null

    /** The service will try to connect to Android Auto again in a moment (last try found no server). */
    @Volatile var aaRetryPending = false

    fun carReset() {
        carStage = CarStage.USB_OPEN
        carClosedReason = null
        huProtocol = null
        huModel = null
        huScreen = null
        huFeatures = null
        authUnsupported = false
        carFramesSent = 0
        carConnectedAt = System.currentTimeMillis()
    }

    fun aaReset() {
        aaStage = AaStage.CONNECTING
        aaClosedReason = null
        aaRefused = false
        aaTimedOut = false
        aaTlsFailed = false
        aaProtocol = null
        aaFramesReceived = 0
    }

    /** Immutable copy for evaluation (and unit tests). */
    data class Snapshot(
        val carStage: CarStage, val carClosedReason: String?, val accessory: String?, val usbPermissionMissing: Boolean,
        val huProtocol: String?, val huModel: String?, val huScreen: String?, val huFeatures: Map<String, Int>?,
        val authUnsupported: Boolean, val carFramesSent: Long,
        val aaStage: AaStage, val aaClosedReason: String?, val aaRefused: Boolean, val aaTimedOut: Boolean,
        val aaTlsFailed: Boolean, val aaProtocol: String?, val aaFramesReceived: Long, val aaUsbAudio: Boolean?, val aaMic: Boolean?,
    )

    fun snapshot() = Snapshot(
        carStage, carClosedReason, accessory, usbPermissionMissing, huProtocol, huModel, huScreen, huFeatures,
        authUnsupported, carFramesSent, aaStage, aaClosedReason, aaRefused, aaTimedOut, aaTlsFailed, aaProtocol,
        aaFramesReceived, aaUsbAudio, aaMic,
    )
}
