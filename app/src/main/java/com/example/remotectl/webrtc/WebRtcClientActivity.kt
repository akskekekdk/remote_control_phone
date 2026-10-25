package com.example.remotectl.webrtc

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.Prefs
import com.example.remotectl.databinding.ActivityWebrtcClientBinding
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/** A(조종) 인터넷 모드. 상대 화면을 받아 보여주고 터치로 조종. */
class WebRtcClientActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebrtcClientBinding
    private lateinit var eglBase: EglBase
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var signaling: SignalingClient? = null
    private var dataChannel: DataChannel? = null
    private var remoteTrack: VideoTrack? = null
    @Volatile private var offerSent = false
    private var pin = ""
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebrtcClientBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 지난 연결값 자동 채움 (즐겨찾기)
        b.editUrl.setText(prefs.get(Prefs.NET_URL, RtcConfig.DEFAULT_SIGNALING_URL))
        b.editCode.setText(prefs.get(Prefs.NET_CODE))
        b.editPin.setText(prefs.get(Prefs.NET_PIN))
        b.editTurnUrl.setText(prefs.get(Prefs.NET_TURN_URL))
        b.editTurnUser.setText(prefs.get(Prefs.NET_TURN_USER))
        b.editTurnCred.setText(prefs.get(Prefs.NET_TURN_CRED))

        val hasSaved = prefs.has(Prefs.NET_URL) && prefs.has(Prefs.NET_CODE) && prefs.has(Prefs.NET_PIN)
        b.btnQuick.visibility = if (hasSaved) View.VISIBLE else View.GONE
        b.btnQuick.text = "지난 연결로 바로 접속 (코드 ${prefs.get(Prefs.NET_CODE)})"
        b.btnQuick.setOnClickListener { b.btnConnect.performClick() }

        eglBase = EglBase.create()
        b.renderer.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                runOnUiThread { fitRenderer(if (rotation % 180 == 0) w else h,
                                             if (rotation % 180 == 0) h else w) }
            }
        })
        b.renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        b.renderer.setEnableHardwareScaler(true)

        b.btnConnect.setOnClickListener {
            val url = b.editUrl.text.toString().trim()
            val code = b.editCode.text.toString().trim()
            pin = b.editPin.text.toString().trim()
            if (url.isBlank() || code.isBlank()) { toast("서버 주소와 코드를 입력하세요"); return@setOnClickListener }
            if (pin.isBlank()) { toast("연결 PIN을 입력하세요"); return@setOnClickListener }
            // 다음에 바로 쓰도록 저장
            prefs.put(Prefs.NET_URL, url)
            prefs.put(Prefs.NET_CODE, code)
            prefs.put(Prefs.NET_PIN, pin)
            prefs.put(Prefs.NET_TURN_URL, b.editTurnUrl.text.toString().trim())
            prefs.put(Prefs.NET_TURN_USER, b.editTurnUser.text.toString().trim())
            prefs.put(Prefs.NET_TURN_CRED, b.editTurnCred.text.toString().trim())
            start(url, code)
        }

        b.btnBack.setOnClickListener { sendInput(InputCodec.key(0)) }
        b.btnHome.setOnClickListener { sendInput(InputCodec.key(1)) }
        b.btnRecents.setOnClickListener { sendInput(InputCodec.key(2)) }

        setupTouch()
    }

    private fun start(url: String, code: String) {
        b.txtStatus.text = "연결 중…"
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val ice = RtcConfig.iceServers(
            b.editTurnUrl.text.toString().trim().ifBlank { null },
            b.editTurnUser.text.toString().trim().ifBlank { null },
            b.editTurnCred.text.toString().trim().ifBlank { null },
        )
        val rtcConfig = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory!!.createPeerConnection(rtcConfig, object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate) {
                signaling?.sendCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteTrack = track
                    runOnUiThread {
                        track.addSink(b.renderer)
                        b.overlayConnect.visibility = View.GONE
                        b.txtStatus.text = "연결됨"
                    }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack && remoteTrack == null) {
                    remoteTrack = track
                    runOnUiThread { track.addSink(b.renderer); b.overlayConnect.visibility = View.GONE }
                }
            }
        })

        // 입력용 데이터 채널 + 영상 수신 트랜시버
        dataChannel = pc!!.createDataChannel("input", DataChannel.Init())
        pc!!.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        signaling = SignalingClient(url, code, "client", object : SignalingClient.Listener {
            override fun onConnected() { runOnUiThread { b.txtStatus.text = "서버 연결됨, 상대 대기…" } }
            override fun onPeerJoined() { maybeCreateOffer() }
            override fun onPeerLeft() { runOnUiThread { b.txtStatus.text = "상대가 나갔습니다" } }
            override fun onClosed() {}
            override fun onError(message: String) { runOnUiThread { b.txtStatus.text = "오류: $message" } }
            override fun onOffer(sdp: String) {} // client 는 offer 를 보내는 쪽
            override fun onAuth(pin: String) {}  // client 는 PIN 을 보내는 쪽
            override fun onAuthFail() {
                runOnUiThread { b.txtStatus.text = "PIN이 올바르지 않습니다" }
                try { signaling?.close() } catch (_: Exception) {}
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

    @Synchronized
    private fun maybeCreateOffer() {
        if (offerSent) return
        offerSent = true
        // 먼저 PIN 전송(상대가 검증), 그 다음 offer
        signaling?.sendAuth(pin)
        val p = pc ?: return
        p.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                p.setLocalDescription(SdpObserverAdapter(), desc)
                signaling?.sendOffer(desc.description)
            }
        }, MediaConstraints())
    }

    private fun fitRenderer(vw: Int, vh: Int) {
        val container = b.rendererContainer
        container.post {
            val cw = container.width.toFloat(); val ch = container.height.toFloat()
            if (cw == 0f || ch == 0f || vw == 0 || vh == 0) return@post
            val scale = minOf(cw / vw, ch / vh)
            val lp = b.renderer.layoutParams as FrameLayout.LayoutParams
            lp.width = (vw * scale).toInt(); lp.height = (vh * scale).toInt()
            lp.gravity = android.view.Gravity.CENTER
            b.renderer.layoutParams = lp
        }
    }

    // ---- 터치 → 데이터 채널 ----
    private var downX = 0f; private var downY = 0f; private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        b.renderer.setOnTouchListener { v, e ->
            val nx = (e.x / v.width).coerceIn(0f, 1f)
            val ny = (e.y / v.height).coerceIn(0f, 1f)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = nx; downY = ny; downTime = System.currentTimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val dt = (System.currentTimeMillis() - downTime).toInt()
                    val moved = abs(nx - downX) > 0.02f || abs(ny - downY) > 0.02f
                    when {
                        moved -> sendInput(InputCodec.swipe(downX, downY, nx, ny, dt.coerceIn(60, 2000)))
                        dt > 500 -> sendInput(InputCodec.longPress(downX, downY))
                        else -> sendInput(InputCodec.tap(nx, ny))
                    }
                }
            }
            true
        }
    }

    private fun sendInput(json: String) {
        val dc = dataChannel ?: return
        try {
            val buf = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
            dc.send(DataChannel.Buffer(buf, false))
        } catch (e: Exception) { Log.w(TAG, "send: ${e.message}") }
    }

    override fun onDestroy() {
        try { remoteTrack?.removeSink(b.renderer) } catch (_: Exception) {}
        try { signaling?.close() } catch (_: Exception) {}
        try { dataChannel?.dispose() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { b.renderer.release() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object { private const val TAG = "WebRtcClient" }
}
