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
        val cfKey = intent.getStringExtra(EXTRA_CF_KEY)
        val cfToken = intent.getStringExtra(EXTRA_CF_TOKEN)
        forceRelay = intent.getBooleanExtra(EXTRA_FORCE_RELAY, false)
        pin = intent.getStringExtra(EXTRA_PIN) ?: ""

        computeResolution()
        // ICE 서버는 네트워크(Cloudflare 발급)일 수 있어 백그라운드에서 준비 후 시작
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            // 이 폰이 LTE/5G 면 TURN 을 TCP/TLS 로 (UDP 중계가 모바일에서 끊김)
            val ice = RtcConfig.buildIceServers(cfKey, cfToken, turnUrl, turnUser, turnCred, isCellular())
            // 중계 가능 여부: Cloudflare 발급 성공 또는 직접 TURN 입력
            relayAvailable = (!cfKey.isNullOrBlank() && CloudflareTurn.lastResult.startsWith("OK")) ||
                !turnUrl.isNullOrBlank()
            mainHandler.post {
                initWebRtc(data, ice)
                startSignaling(signalUrl, room)
            }
        }, "rc-ice").start()
        return START_STICKY
    }

    /** 이 폰이 셀룰러(LTE/5G)에 있나? → TURN 을 TCP 로 쓰면 중계가 안정적. */
    private fun isCellular(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) { false }
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

    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    private fun initWebRtc(projectionData: Intent, servers: List<PeerConnection.IceServer>) {
        // 이미 초기화돼 있는데 다시 시작되면(액티비티 재생성/재시작) 옛 자원을 먼저 해제.
        // (안 하면 factory·eglBase·capturer 가 중복 생성돼 누수+이중 캡처로 B 가 무거워짐)
        if (factory != null) stopEverything()
        iceServers = servers
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // 화면 캡처 → 영상 트랙 (연결과 무관하게 한 번만 생성, 계속 유지)
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "projection stopped"); stopEverything(); stopSelf() }
        })
        helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory!!.createVideoSource(true /* isScreencast */)
        capturer!!.initialize(helper, applicationContext, videoSource!!.capturerObserver)
        capturer!!.startCapture(encW, encH, 40)
        videoTrack = factory!!.createVideoTrack("screen0", videoSource)
    }

    @Volatile private var offered = false
    @Volatile private var forceRelay = false
    @Volatile private var relayAvailable = false

    /**
     * 이전 연결 자원을 완전히 해제한다.
     * close() 만 하면 네이티브(ICE 스레드·소켓·메모리)가 안 풀려서,
     * 재접속할 때마다 B 프로세스에 쌓여 시간이 갈수록 B가 느려진다 → dispose() 까지 한다.
     */
    private fun disposePeerConnection() {
        try { dataChannel?.unregisterObserver() } catch (_: Exception) {}
        try { dataChannel?.close() } catch (_: Exception) {}
        try { dataChannel?.dispose() } catch (_: Exception) {}
        dataChannel = null
        videoSender = null
        try { pc?.close() } catch (_: Exception) {}
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
    }

    /** 접속(재접속)마다 새 PeerConnection 을 만든다 → A가 나갔다 다시 와도 연결됨. */
    private fun createPeerConnection() {
        disposePeerConnection()
        offered = false

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // A·B 양쪽 중계(relay/relay)가 모바일에서 가장 안정적. 중계 쓸 수 있을 때만.
            if (forceRelay && relayAvailable)
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val newPc = factory!!.createPeerConnection(rtcConfig, object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate) {
                signaling?.sendCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }
        }) ?: return
        // 재접속마다 영상 트랙을 새로 만들어 붙인다.
        // (닫힌 PeerConnection 에 붙었던 트랙을 재사용하면 새 연결에서 프레임이 안 나감 → 0x0)
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = factory!!.createVideoTrack("screen${System.nanoTime()}", videoSource)
        videoSender = newPc.addTrack(videoTrack, listOf("stream0"))
        applyBitrateCap()
        val dc = newPc.createDataChannel("input", DataChannel.Init())
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val msg = String(bytes, StandardCharsets.UTF_8)
                try {
                    if (msg.contains("\"quality\"")) {
                        val o = org.json.JSONObject(msg)
                        if (o.optString("t") == "quality") {
                            curBitrate = o.optInt("bitrate", curBitrate)
                            curFps = o.optInt("fps", curFps)
                            applyBitrateCap()
                            return
                        }
                    }
                    InputCodec.apply(msg, realW, realH)
                } catch (e: Exception) { Log.w(TAG, "input apply: ${e.message}") }
            }
        })
        pc = newPc
    }

    private var videoSender: org.webrtc.RtpSender? = null
    @Volatile private var curBitrate = RtcConfig.QUALITIES[RtcConfig.DEFAULT_QUALITY_INDEX].bitrate
    @Volatile private var curFps = RtcConfig.QUALITIES[RtcConfig.DEFAULT_QUALITY_INDEX].fps

    /** 영상 전송량/프레임레이트 적용. 협상 전에는 encodings 가 비어 있을 수 있어 answer 뒤에도 다시 호출한다. */
    private fun applyBitrateCap() {
        val s = videoSender ?: return
        try {
            val p = s.parameters
            if (p.encodings.isEmpty()) return
            // 끊길 때 프레임을 유지(해상도만 낮춤) → 조작·게임에서 반응이 가장 부드럽다.
            try { p.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE } catch (_: Exception) {}
            p.encodings.forEach {
                it.maxBitrateBps = curBitrate
                it.maxFramerate = curFps
                it.minBitrateBps = curBitrate / 2   // 바닥을 올려 번지는 화면 방지
            }
            s.parameters = p
        } catch (e: Exception) {
            Log.w(TAG, "bitrate cap: ${e.message}")
        }
    }

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
            override fun onPeerJoined() {
                // 주의: 여기서 PeerConnection 을 재생성하지 않는다.
                // 신호 WebSocket 이 잠깐 끊겼다 붙어도 peer-joined 가 다시 오는데,
                // 그때마다 재생성하면 멀쩡히 흐르던 영상이 끊긴다(몇초마다 끊김 원인).
                // 영상은 신호와 무관하게 relay 로 계속 흐르므로 기존 연결을 유지한다.
                // 실제 새 연결은 A가 보내는 AUTH 로만 시작한다.
                Log.i(TAG, "client joined (keep existing media)")
            }
            override fun onPeerLeft() { Log.i(TAG, "client left") }
            override fun onError(message: String) { Log.w(TAG, "signaling error: $message") }
            override fun onClosed() {}
            override fun onOffer(sdp: String) {}   // host 가 offer 를 만드는 쪽
            override fun onAuthFail() {}            // host 는 PIN 을 검증하는 쪽

            override fun onAuth(clientPin: String, relay: Boolean) {
                authorized = (clientPin == pin)
                if (!authorized) {
                    Log.w(TAG, "auth failed: wrong PIN")
                    signaling?.sendAuthFail()
                    return
                }
                // A가 정한 중계 사용 여부에 맞춘다(같은 망이면 직접, 떨어지면 중계)
                forceRelay = relay
                // 실제 새 핸드셰이크 → 이때만 새 PeerConnection 생성 후 offer
                createPeerConnection()
                createAndSendOffer()
            }

            override fun onAnswer(sdp: String) {
                pc?.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { applyBitrateCap() }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
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
        disposePeerConnection()   // DataChannel + PeerConnection 네이티브 자원까지 해제
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { if (::eglBase.isInitialized) eglBase.release() } catch (_: Exception) {}
        signaling = null; capturer = null; videoTrack = null
        videoSource = null; helper = null; factory = null
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
        const val EXTRA_CF_KEY = "cf_key"
        const val EXTRA_CF_TOKEN = "cf_token"
        const val EXTRA_FORCE_RELAY = "force_relay"
        const val EXTRA_PIN = "pin"
        const val ACTION_STOP = "stop"
    }
}
