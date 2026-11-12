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
    @Volatile private var waitSourceKey = false

    /**
     * Newest decoded frame held back by the frame rate limit (output index of [heldCodec]); rendered when the
     * interval ends, so the last picture of a change always reaches the head unit. Codec thread only.
     */
    private var heldIndex = -1
    private var heldCodec: MediaCodec? = null
    private val renderHeld = Runnable {
        val codec = heldCodec
        val i = heldIndex
        heldIndex = -1
        heldCodec = null
        if (codec != null && i >= 0 && codec === decoder) {
            try {
                codec.releaseOutputBuffer(i, true)
                lastRenderNs = System.nanoTime()
            } catch (_: Exception) {
            }
        }
    }

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
                if (codec !== encoder) return // a replaced encoder
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
                Log.e("VIDEO: encoder error (transient=${e.isTransient})", e)
                if (!e.isTransient && codec === encoder) rebuild()
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
        // set before start: callbacks ignore buffers of any codec that is not the current one
        encoder = enc
        enc.start()
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
                    decoded++
                    val interval = minRenderIntervalNs
                    if (info.size <= 0) {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }
                    // a newer frame replaces the held one
                    dropHeld()
                    val now = System.nanoTime()
                    if (interval == 0L || now - lastRenderNs >= interval) {
                        lastRenderNs = now
                        codec.releaseOutputBuffer(index, true)
                    } else {
                        heldIndex = index
                        heldCodec = codec
                        handler.postDelayed(renderHeld, ((lastRenderNs + interval - now) / 1_000_000L).coerceAtLeast(1))
                    }
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
    /**
     * The encoder failed (for example the system took the hardware codec away): build the whole pipeline again,
     * since the decoder draws into the encoder's surface. Codec thread.
     */
    private fun rebuild() {
        if (released) return
        Log.w("VIDEO: rebuilding encoder and decoder")
        synchronized(lock) {
            handler.removeCallbacks(renderHeld)
            heldIndex = -1
            heldCodec = null
            for (p in pending) p.done()
            pending.clear()
            freeInputs.clear()
            val dec = decoder
            decoder = null
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            val enc = encoder
            encoder = null
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            inputSurface?.release()
            inputSurface = null
            try {
                start()
                startDecoder()
                sourceConfig?.let { pending.addLast(Pending(it, 0, true) {}) }
                waitSourceKey = true
            } catch (e: Exception) {
                Log.e("VIDEO: rebuild failed", e)
                return
            }
        }
        afterRestart()
    }

    private fun restartDecoder(failed: MediaCodec) {
        synchronized(lock) {
            if (released || failed !== decoder) return
            handler.removeCallbacks(renderHeld)
            heldIndex = -1
            heldCodec = null
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
        afterRestart()
    }

    /** After the decoder was replaced: get a key frame from both sides; never stay frozen waiting for it. */
    private fun afterRestart() {
        needSourceKeyFrame()
        wantKeyFrame()
        // Ask again if no key frame came; in the end decode whatever arrives rather than stay frozen.
        handler.postDelayed({
            if (waitSourceKey && !released) {
                Log.w("VIDEO: still no key frame from Android Auto, asking again")
                needSourceKeyFrame()
            }
        }, 2000)
        handler.postDelayed({
            if (waitSourceKey && !released) {
                Log.w("VIDEO: no key frame from Android Auto, decoding anyway")
                waitSourceKey = false
            }
        }, 5000)
    }

    /** Releases a held-back frame without showing it. Codec thread only. */
    private fun dropHeld() {
        handler.removeCallbacks(renderHeld)
        val codec = heldCodec
        val i = heldIndex
        heldIndex = -1
        heldCodec = null
        if (codec != null && i >= 0) {
            try {
                codec.releaseOutputBuffer(i, false)
            } catch (_: Exception) {
            }
        }
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
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) {
            // no acknowledgements here: the session is ending, and this may run on the main thread
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
