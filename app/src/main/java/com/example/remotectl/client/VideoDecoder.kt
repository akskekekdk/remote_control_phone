package com.example.remotectl.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** H.264 NAL 스트림을 받아 SurfaceView 로 그린다. */
class VideoDecoder(
    private val surface: Surface,
    width: Int,
    height: Int,
    private val csd0: ByteArray,
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val running = AtomicBoolean(false)
    private var outputThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        codec.configure(format, surface, null, 0)
    }

    fun start() {
        codec.start()
        running.set(true)
        outputThread = Thread({ drainOutput() }, "rc-decoder-out").also { it.start() }
    }

    fun feed(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        if (!running.get()) return
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf: ByteBuffer = codec.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data, offset, length)
                codec.queueInputBuffer(idx, 0, length, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feed failed: ${e.message}")
        }
    }

    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    // true = surface 로 렌더링
                    codec.releaseOutputBuffer(idx, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "drain failed: ${e.message}")
        }
    }

    fun release() {
        running.set(false)
        try { outputThread?.join(300) } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "VideoDecoder" }
}
