package com.example.remotectl.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.charset.StandardCharsets

/**
 * B(피조종) 인터넷 모드. 화면을 WebRTC 영상 트랙으로 송신하고,
 * 데이터 채널로 받은 입력을 접근성 서비스로 주입한다.
 */
class WebRtcHostService : Service() {

    private lateinit var eglBase: EglBase
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var signaling: SignalingClient? = null
    private var dataChannel: DataChannel? = null

    private var realW = 0f
    private var realH = 0f
    private var encW = 0
    private var encH = 0

    private var pin = ""
    @Volatile private var authorized = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: run { stopSelf(); return START_NOT_STICKY }
        val room = intent.getStringExtra(EXTRA_ROOM) ?: run { stopSelf(); return START_NOT_STICKY }
        val signalUrl = intent.getStringExtra(EXTRA_URL) ?: RtcConfig.DEFAULT_SIGNALING_URL
        val turnUrl = intent.getStringExtra(EXTRA_TURN_URL)
        val turnUser = intent.getStringExtra(EXTRA_TURN_USER)
        val turnCred = intent.getStringExtra(EXTRA_TURN_CRED)
        pin = intent.getStringExtra(EXTRA_PIN) ?: ""

        computeResolution()
        initWebRtc(data, RtcConfig.iceServers(turnUrl, turnUser, turnCred))
        startSignaling(signalUrl, room)
        return START_STICKY
    }

    private fun computeResolution() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        realW = m.widthPixels.toFloat()
        realH = m.heightPixels.toFloat()
        val maxDim = 1280
        val scale = minOf(1f, maxDim.toFloat() / maxOf(m.widthPixels, m.heightPixels))
        encW = (m.widthPixels * scale).toInt() / 2 * 2
        encH = (m.heightPixels * scale).toInt() / 2 * 2
        Log.i(TAG, "enc=${encW}x$encH real=${realW}x$realH")
    }

    private fun initWebRtc(projectionData: Intent, iceServers: List<PeerConnection.IceServer>) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // 화면 캡처 → 영상 트랙
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "projection stopped"); stopEverything(); stopSelf() }
        })
        helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory!!.createVideoSource(true /* isScreencast */)
        capturer!!.initialize(helper, applicationContext, videoSource!!.capturerObserver)
        capturer!!.startCapture(encW, encH, 30)
        videoTrack = factory!!.createVideoTrack("screen0", videoSource)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory!!.createPeerConnection(rtcConfig, object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate) {
                signaling?.sendCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }
        })
        // 화면을 송신하는 쪽이 offer 를 만든다(안정적). 영상 트랙 + 입력 데이터채널을 host가 생성.
        pc!!.addTrack(videoTrack, listOf("stream0"))
        val dc = pc!!.createDataChannel("input", DataChannel.Init())
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val msg = String(bytes, StandardCharsets.UTF_8)
                try { InputCodec.apply(msg, realW, realH) }
                catch (e: Exception) { Log.w(TAG, "input apply: ${e.message}") }
            }
        })
    }

    @Volatile private var offered = false

    private fun createAndSendOffer() {
        if (offered) return
        offered = true
        val p = pc ?: return
        p.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(SdpObserverAdapter(), desc)
                signaling?.sendOffer(desc.description)
            }
        }, MediaConstraints())
    }

    private fun startSignaling(url: String, room: String) {
        signaling = SignalingClient(url, room, "host", object : SignalingClient.Listener {
            override fun onConnected() { Log.i(TAG, "signaling connected (host)") }
            override fun onPeerJoined() { Log.i(TAG, "client joined") }
            override fun onPeerLeft() { Log.i(TAG, "client left") }
            override fun onError(message: String) { Log.w(TAG, "signaling error: $message") }
            override fun onClosed() {}
            override fun onOffer(sdp: String) {}   // host 가 offer 를 만드는 쪽
            override fun onAuthFail() {}            // host 는 PIN 을 검증하는 쪽

            override fun onAuth(clientPin: String) {
                authorized = (clientPin == pin)
                if (!authorized) {
                    Log.w(TAG, "auth failed: wrong PIN")
                    signaling?.sendAuthFail()
                    return
                }
                // PIN 통과 후 offer 전송
                createAndSendOffer()
            }

            override fun onAnswer(sdp: String) {
                pc?.setRemoteDescription(SdpObserverAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            override fun onCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                pc?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
        })
        signaling!!.connect()
    }

    private fun stopEverything() {
        try { signaling?.close() } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { if (::eglBase.isInitialized) eglBase.release() } catch (_: Exception) {}
        signaling = null; capturer = null; videoTrack = null
        videoSource = null; helper = null; pc = null; factory = null
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "원격 화면 공유(인터넷)", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("화면 공유 중 (인터넷)")
            .setContentText("이 폰의 화면이 원격으로 공유되고 있습니다")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WebRtcHost"
        private const val CHANNEL = "rc_webrtc"
        private const val NOTIF_ID = 43
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ROOM = "room"
        const val EXTRA_URL = "url"
        const val EXTRA_TURN_URL = "turn_url"
        const val EXTRA_TURN_USER = "turn_user"
        const val EXTRA_TURN_CRED = "turn_cred"
        const val EXTRA_PIN = "pin"
        const val ACTION_STOP = "stop"
    }
}
