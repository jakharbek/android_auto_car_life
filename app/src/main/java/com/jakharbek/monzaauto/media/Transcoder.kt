package com.jakharbek.monzaauto.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.jakharbek.monzaauto.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Re-encodes the Android Auto picture for the head unit.
 *
 * Decoder output is rendered straight into the encoder's input surface (no copies, no GL).
 * The encoder repeats the last picture every 100 ms, so the head unit keeps receiving frames on a
 * still screen, makes a key frame every second and on request, and its SPS is patched for head unit decoders.
 */
class Transcoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int,
    /** Encoded access unit (Annex-B) and whether it is a key frame; SPS/PPS are prepended when [wantKeyFrame] was called. */
    private val output: (ByteArray, Boolean) -> Unit,
    /** The decoder had to be restarted: it needs a key frame from Android Auto. */
    private val needSourceKeyFrame: () -> Unit = {},
) {
    private val thread = HandlerThread("video-codec").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Object()

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private class Pending(val data: ByteArray, val ptsUs: Long, val config: Boolean, val done: () -> Unit)

    private val pending = ArrayDeque<Pending>()
    private val freeInputs = ArrayDeque<Int>()

    @Volatile private var csd: ByteArray? = null

    /** Pending key frame requests; a request arriving while a frame is being handled is never lost. */
    private val keyRequests = AtomicInteger(1)
    @Volatile private var released = false

    /** Frames rendered into the encoder at most this often (head unit asked for a lower rate). */
    @Volatile private var minRenderIntervalNs = 0L
    private var lastRenderNs = 0L

    /** Decoder input timestamps: local and strictly increasing, whatever clock Android Auto uses. */
    private var lastPtsUs = 0L

    /** Parameter sets from Android Auto, kept to restart the decoder. */
    private var sourceConfig: ByteArray? = null

    /** After a decoder restart, Android Auto frames are skipped until its next key frame. */
    private var waitSourceKey = false

    private var decoded = 0L
    private var encoded = 0L

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buf.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            csd = H264.fixSps(bytes, width, height)
                            Log.i("VIDEO: encoder config ${Log.hex(csd!!, 24)}")
                        } else {
                            onEncoded(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    if (!released) Log.e("VIDEO: encoder output", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("VIDEO: encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i("VIDEO: encoder format $format")
            }
        }, handler)
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders refuse the extra keys: retry with the basics only.
            Log.w("VIDEO: encoder rejected the format, retrying with defaults", e)
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_PRIORITY)
            format.removeKey(MediaFormat.KEY_MAX_B_FRAMES)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
        Log.i("VIDEO: encoder ${enc.name} ${width}x$height ${bitrate / 1000} kbit/s")
    }

    private fun onEncoded(frame: ByteArray, key: Boolean) {
        encoded++
        val requests = keyRequests.get()
        if (requests > 0) {
            if (!key) return // the head unit must start with SPS + PPS + IDR
            val c = csd
            val data = if (c != null && !H264.startsWithSps(frame)) c + frame else H264.fixSps(frame, width, height)
            // only clear the requests this frame answered; newer ones get the next key frame
            keyRequests.addAndGet(-requests)
            output(data, true)
            return
        }
        output(frame, key)
    }

    /** Head unit wants at most [fps] frames per second. */
    fun setMaxFps(fps: Int) {
        minRenderIntervalNs = if (fps in 1 until this.fps) 1_000_000_000L / fps else 0L
        Log.i("VIDEO: frame rate limit ${if (minRenderIntervalNs == 0L) "off" else "$fps fps"}")
    }

    /** Next frame for the head unit must be a key frame with SPS/PPS. */
    fun wantKeyFrame() {
        keyRequests.incrementAndGet()
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w("VIDEO: key frame request failed", e)
        }
    }

    /** H.264 parameter sets from Android Auto. Creates the decoder on first use. */
    fun feedConfig(data: ByteArray) {
        synchronized(lock) {
            sourceConfig = data
            if (decoder == null) startDecoder()
            pending.addLast(Pending(data, 0, true) {})
            drain()
        }
    }

    fun feedFrame(data: ByteArray, ptsUs: Long, done: () -> Unit) {
        synchronized(lock) {
            if (decoder == null) {
                done()
                return
            }
            if (waitSourceKey) {
                if (H264.NAL_IDR !in H264.nalTypes(data)) {
                    done()
                    return
                }
                waitSourceKey = false
                Log.i("VIDEO: decoding again from a key frame")
            }
            val pts = maxOf(lastPtsUs + 1, android.os.SystemClock.elapsedRealtimeNanos() / 1000)
            lastPtsUs = pts
            pending.addLast(Pending(data, pts, false, done))
            drain()
        }
    }

    private fun startDecoder() {
        val surface = inputSurface ?: return
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        dec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(lock) {
                    if (codec !== decoder) return
                    freeInputs.addLast(index)
                    drain()
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val now = System.nanoTime()
                    val render = info.size > 0 && (minRenderIntervalNs == 0L || now - lastRenderNs >= minRenderIntervalNs)
                    if (render) lastRenderNs = now
                    codec.releaseOutputBuffer(index, render)
                    decoded++
                } catch (e: Exception) {
                    if (!released) Log.e("VIDEO: decoder output", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("VIDEO: decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
                if (!e.isTransient) restartDecoder(codec)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i("VIDEO: decoder format $format")
            }
        }, handler)
        try {
            dec.configure(format, surface, null, 0)
        } catch (e: Exception) {
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            dec.configure(format, surface, null, 0)
        }
        dec.start()
        decoder = dec
        Log.i("VIDEO: decoder ${dec.name}")
    }

    /**
     * A failed decoder stops asking for input, which would leave Android Auto's frames unacknowledged and freeze
     * the picture: replace it, acknowledge everything waiting, and resume from Android Auto's next key frame.
     */
    private fun restartDecoder(failed: MediaCodec) {
        synchronized(lock) {
            if (released || failed !== decoder) return
            for (p in pending) p.done()
            pending.clear()
            freeInputs.clear()
            try { failed.stop() } catch (_: Exception) {}
            try { failed.release() } catch (_: Exception) {}
            decoder = null
            try {
                startDecoder()
                sourceConfig?.let { pending.addLast(Pending(it, 0, true) {}) }
                waitSourceKey = true
            } catch (e: Exception) {
                Log.e("VIDEO: decoder restart failed", e)
            }
        }
        needSourceKeyFrame()
        wantKeyFrame()
    }

    /** Moves pending frames into free decoder input buffers. Caller holds [lock]. */
    private fun drain() {
        val dec = decoder ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val p = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = dec.getInputBuffer(index)
                if (buf == null || buf.capacity() < p.data.size) {
                    Log.w("VIDEO: frame of ${p.data.size} bytes does not fit the decoder buffer")
                    dec.queueInputBuffer(index, 0, 0, p.ptsUs, 0)
                } else {
                    buf.clear()
                    buf.put(p.data)
                    val flags = if (p.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    dec.queueInputBuffer(index, 0, p.data.size, p.ptsUs, flags)
                }
            } catch (e: Exception) {
                if (!released) Log.e("VIDEO: decoder input", e)
            }
            p.done()
        }
    }

    fun stats() = "decoded $decoded, encoded $encoded"

    fun release() {
        released = true
        synchronized(lock) {
            for (p in pending) p.done()
            pending.clear()
            freeInputs.clear()
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
        }
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        inputSurface?.release()
        inputSurface = null
        thread.quitSafely()
    }
}
