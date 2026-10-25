package com.example.remotectl.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.remotectl.Protocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B(피조종) 폰의 심장부.
 *  - MediaProjection 으로 화면을 캡처
 *  - MediaCodec(H.264)으로 인코딩
 *  - ServerSocket 으로 A의 접속을 받아 영상 전송 + 입력 수신
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    @Volatile private var out: DataOutputStream? = null

    // 인코딩 해상도 (긴 변을 maxDim 으로 축소)
    private var encWidth = 0
    private var encHeight = 0
    private var screenDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped")
                    stopEverything()
                    stopSelf()
                }
            }, null)
        }

        computeResolution()
        running.set(true)
        Thread({ serverLoop() }, "rc-server").start()
        return START_STICKY
    }

    private fun computeResolution() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        screenDpi = metrics.densityDpi

        val maxDim = 1280
        val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
        // H.264 는 16의 배수가 안전
        encWidth = (w * scale).toInt() / 2 * 2
        encHeight = (h * scale).toInt() / 2 * 2
        Log.i(TAG, "screen=${w}x$h enc=${encWidth}x$encHeight dpi=$screenDpi")
    }

    private fun serverLoop() {
        try {
            serverSocket = ServerSocket(Protocol.PORT)
            Log.i(TAG, "listening on ${Protocol.PORT}")
            while (running.get()) {
                val socket = try {
                    serverSocket!!.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                handleClient(socket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        Log.i(TAG, "client connected: ${socket.inetAddress.hostAddress}")
        socket.tcpNoDelay = true
        val dout = DataOutputStream(socket.getOutputStream().buffered())
        val din = DataInputStream(socket.getInputStream().buffered())
        out = dout

        try {
            // 영상 크기 먼저 알림
            Protocol.writePacket(dout, Protocol.TYPE_META, "$encWidth,$encHeight".toByteArray())
            startEncoder()
            // 인코더 출력 송신 루프를 별도 스레드로
            val sender = Thread({ drainEncoder(dout) }, "rc-encoder")
            sender.start()
            // 입력 수신 루프 (현재 스레드)
            inputLoop(din)
            sender.join(500)
        } catch (e: Exception) {
            Log.w(TAG, "client loop ended: ${e.message}")
        } finally {
            stopEncoder()
            out = null
            try { socket.close() } catch (_: Exception) {}
            Log.i(TAG, "client disconnected")
        }
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = projection!!.createVirtualDisplay(
            "rc-display",
            encWidth, encHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }

    private fun drainEncoder(dout: DataOutputStream) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get() && out === dout) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf: ByteBuffer = codec.getOutputBuffer(idx) ?: continue
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        Protocol.writePacket(dout, Protocol.TYPE_VIDEO_CONFIG, bytes)
                    } else if (bytes.isNotEmpty()) {
                        // 앞 8바이트에 타임스탬프 붙이기
                        val packet = ByteArray(8 + bytes.size)
                        var ts = info.presentationTimeUs
                        for (i in 7 downTo 0) { packet[i] = (ts and 0xff).toByte(); ts = ts shr 8 }
                        System.arraycopy(bytes, 0, packet, 8, bytes.size)
                        Protocol.writePacket(dout, Protocol.TYPE_VIDEO, packet)
                    }
                    codec.releaseOutputBuffer(idx, false)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // surface 입력에서는 보통 여기서 csd 안 옴 (codec-config 플래그로 옴)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "drain ended: ${e.message}")
        }
    }

    private fun inputLoop(din: DataInputStream) {
        while (running.get()) {
            val p = Protocol.readPacket(din)
            val a11y = ControlAccessibilityService.instance
            val bb = ByteBuffer.wrap(p.payload)
            when (p.type) {
                Protocol.TYPE_TAP -> {
                    val x = bb.float; val y = bb.float
                    a11y?.tap(x * encWidthToScreenX(), y * encHeightToScreenY())
                }
                Protocol.TYPE_LONG_PRESS -> {
                    val x = bb.float; val y = bb.float
                    a11y?.longPress(x * encWidthToScreenX(), y * encHeightToScreenY())
                }
                Protocol.TYPE_SWIPE -> {
                    val x1 = bb.float; val y1 = bb.float
                    val x2 = bb.float; val y2 = bb.float
                    val dur = bb.int
                    a11y?.swipe(
                        x1 * encWidthToScreenX(), y1 * encHeightToScreenY(),
                        x2 * encWidthToScreenX(), y2 * encHeightToScreenY(),
                        dur.toLong()
                    )
                }
                Protocol.TYPE_KEY -> {
                    val k = bb.int
                    a11y?.globalKey(k)
                }
            }
        }
    }

    // 정규화(0..1) → B 실제 화면 픽셀
    private var realW = 0f
    private var realH = 0f
    private fun ensureReal() {
        if (realW == 0f) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            realW = m.widthPixels.toFloat()
            realH = m.heightPixels.toFloat()
        }
    }
    private fun encWidthToScreenX(): Float { ensureReal(); return realW }
    private fun encHeightToScreenY(): Float { ensureReal(); return realH }

    private fun stopEncoder() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        virtualDisplay = null
        encoder = null
        inputSurface = null
    }

    private fun stopEverything() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        stopEncoder()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "원격 화면 공유", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("화면 공유 중")
            .setContentText("이 폰의 화면이 원격으로 공유되고 있습니다")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL = "rc_capture"
        private const val NOTIF_ID = 42
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }
}
