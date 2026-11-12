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
    private var reconnectOnResume = false
    private var qualityIndex = RtcConfig.DEFAULT_QUALITY_INDEX
    private var lastRecovery = 0L
    private var cachedIce: List<PeerConnection.IceServer>? = null
    private var debugOn = false
    @Volatile private var cfStatus = "미설정"
    @Volatile private var relayAvailable = false
    private var dbgLastBytes = 0L
    private var dbgLastTime = 0L
    private val debugRunnable = object : Runnable {
        override fun run() { updateDebug(); mainHandler.postDelayed(this, 1500) }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoveryRunnable = Runnable { recoverConnection() }

    // 중계 사용량 측정 (5초마다 통계 확인)
    private var lastTransportBytes = 0L
    private val statsRunnable = object : Runnable {
        override fun run() {
            pollUsage()
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebrtcClientBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 시스템 바(상태바/네비게이션바)만큼 여백 → 영상은 상태바 아래, 버튼은 네비바 위
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.contentRoot) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 지난 연결값 자동 채움 (즐겨찾기)
        b.editUrl.setText(prefs.get(Prefs.NET_URL, RtcConfig.DEFAULT_SIGNALING_URL))
        b.editCode.setText(prefs.get(Prefs.NET_CODE))
        b.editPin.setText(prefs.get(Prefs.NET_PIN))
        b.editTurnUrl.setText(prefs.get(Prefs.NET_TURN_URL))
        b.editTurnUser.setText(prefs.get(Prefs.NET_TURN_USER))
        b.editTurnCred.setText(prefs.get(Prefs.NET_TURN_CRED))
        b.editCfKey.setText(prefs.get(Prefs.CF_KEY_ID))
        b.editCfToken.setText(prefs.get(Prefs.CF_TOKEN))
        val forceDefault = if (prefs.has(Prefs.FORCE_RELAY)) prefs.get(Prefs.FORCE_RELAY) == "1"
                           else prefs.has(Prefs.CF_KEY_ID)  // Cloudflare 설정돼 있으면 기본 켜짐
        b.chkForceRelay.isChecked = forceDefault
        b.txtUsage.text = UsageMeter.label(prefs)

        val hasSaved = prefs.has(Prefs.NET_URL) && prefs.has(Prefs.NET_CODE) && prefs.has(Prefs.NET_PIN)
        b.btnQuick.visibility = if (hasSaved) View.VISIBLE else View.GONE
        b.btnQuick.text = "지난 연결로 바로 접속 (코드 ${prefs.get(Prefs.NET_CODE)})"
        b.btnQuick.setOnClickListener { b.btnConnect.performClick() }

        eglBase = EglBase.create()
        b.renderer.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                runOnUiThread { b.txtStatus.text = "영상 수신 중" }
                // 데이터채널이 열렸으니 현재 화질 설정을 B에 반영
                mainHandler.postDelayed({ sendQuality() }, 500)
            }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                runOnUiThread { fitRenderer(if (rotation % 180 == 0) w else h,
                                             if (rotation % 180 == 0) h else w) }
            }
        })
        b.renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        b.renderer.setEnableHardwareScaler(true)

        // PeerConnectionFactory 는 한 번만 생성 (재접속 때 재사용)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        b.btnConnect.setOnClickListener {
            val url = b.editUrl.text.toString().trim()
            val code = b.editCode.text.toString().trim()
            pin = b.editPin.text.toString().trim()
            if (url.isBlank() || code.isBlank()) { toast("서버 주소와 코드를 입력하세요"); return@setOnClickListener }
            if (pin.isBlank()) { toast("연결 PIN을 입력하세요"); return@setOnClickListener }
            // 다음에 바로 쓰도록 저장
            prefs.put(Prefs.LAST_MODE, "internet")
            prefs.put(Prefs.NET_URL, url)
            prefs.put(Prefs.NET_CODE, code)
            prefs.put(Prefs.NET_PIN, pin)
            prefs.put(Prefs.NET_TURN_URL, b.editTurnUrl.text.toString().trim())
            prefs.put(Prefs.NET_TURN_USER, b.editTurnUser.text.toString().trim())
            prefs.put(Prefs.NET_TURN_CRED, b.editTurnCred.text.toString().trim())
            prefs.put(Prefs.CF_KEY_ID, b.editCfKey.text.toString().trim())
            prefs.put(Prefs.CF_TOKEN, b.editCfToken.text.toString().trim())
            prefs.put(Prefs.FORCE_RELAY, if (b.chkForceRelay.isChecked) "1" else "0")
            com.example.remotectl.widget.RemoteWidgetProvider.updateAll(this)
            cachedIce = null // 새로 입력한 값(TURN 등)으로 다시 받도록
            start(url, code)
        }

        b.btnBack.setOnClickListener { sendInput(InputCodec.key(0)) }
        b.btnHome.setOnClickListener { sendInput(InputCodec.key(1)) }
        b.btnRecents.setOnClickListener { sendInput(InputCodec.key(2)) }

        qualityIndex = (prefs.get(Prefs.QUALITY_INDEX).toIntOrNull()
            ?: RtcConfig.DEFAULT_QUALITY_INDEX).coerceIn(0, RtcConfig.QUALITIES.size - 1)
        updateQualityButton()
        b.btnQuality.setOnLongClickListener {
            debugOn = !debugOn
            b.txtDebug.visibility = if (debugOn) View.VISIBLE else View.GONE
            if (debugOn) mainHandler.post(debugRunnable) else mainHandler.removeCallbacks(debugRunnable)
            toast(if (debugOn) "진단 표시 켜짐" else "진단 표시 꺼짐")
            true
        }
        b.btnQuality.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % RtcConfig.QUALITIES.size
            prefs.put(Prefs.QUALITY_INDEX, qualityIndex.toString())
            updateQualityButton()
            sendQuality()
            toast("화질: ${RtcConfig.QUALITIES[qualityIndex].name}")
        }

        setupTouch()

        // 탭하면 입력창 보기(바로연결 화면에서 설정으로)
        b.txtConnecting.setOnClickListener { showForm() }

        // 바로 연결(메인 버튼/위젯에서 진입) — 저장값이 있으면 입력창 없이 바로 연결
        if (intent?.getBooleanExtra(EXTRA_AUTOCONNECT, false) == true && hasSaved) {
            showConnecting()
            b.root.post { b.btnConnect.performClick() }
        }
    }

    /** 입력창 숨기고 "연결 중…" 화면 표시 (바로연결) */
    private fun showConnecting() {
        b.overlayConnect.visibility = View.GONE
        b.txtConnecting.visibility = View.VISIBLE
        b.txtConnecting.text = "연결 중…"
    }

    /** 연결중 화면 숨김 (영상 도착) */
    private fun hideConnecting() {
        b.txtConnecting.visibility = View.GONE
    }

    /** 입력창 보이기 (실패/설정 필요) */
    private fun showForm() {
        b.txtConnecting.visibility = View.GONE
        b.overlayConnect.visibility = View.VISIBLE
    }

    /** ICE 가 끊기면 delay 후 자동 복구(재연결). 그 전에 다시 연결되면 취소됨. */
    private fun scheduleRecovery(delayMs: Long) {
        mainHandler.removeCallbacks(recoveryRunnable)
        mainHandler.postDelayed(recoveryRunnable, delayMs)
    }

    /** 저장된 값으로 연결을 다시 맺는다(끊김 자동 복구). 너무 잦은 재연결은 막는다. */
    private fun recoverConnection() {
        if (isFinishing || isDestroyed) return
        // 이미 다시 연결돼 있으면(스스로 복구됨) 아무것도 하지 않음
        if (pc?.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
            pc?.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecovery < 12_000) { scheduleRecovery(12_000 - (now - lastRecovery)); return }
        lastRecovery = now
        val url = b.editUrl.text.toString().trim()
        val code = b.editCode.text.toString().trim()
        pin = b.editPin.text.toString().trim()
        if (url.isBlank() || code.isBlank() || pin.isBlank()) return
        b.txtStatus.text = "재연결 중…"
        disconnect()
        start(url, code)
    }

    /** 진단: 연결 경로/전송량/fps/끊김을 화면에 표시 */
    private fun updateDebug() {
        val p = pc
        val ice = p?.iceConnectionState()?.name ?: "-"
        if (p == null) { b.txtDebug.text = "ICE:$ice"; return }
        p.getStats { report ->
            try {
                val stats = report.statsMap
                var path = "?"; var relaySrv = ""
                var kbps = 0L; var fps = 0.0; var res = ""; var loss = 0L; var freeze = 0L
                for (s in stats.values) {
                    when (s.type) {
                        "transport" -> {
                            val pair = stats[s.members["selectedCandidatePairId"] as? String ?: ""]
                            if (pair != null) {
                                val lc = stats[pair.members["localCandidateId"] as? String ?: ""]
                                val rc = stats[pair.members["remoteCandidateId"] as? String ?: ""]
                                val lt = lc?.members?.get("candidateType") ?: "?"
                                val rt = rc?.members?.get("candidateType") ?: "?"
                                path = "$lt/$rt"
                                val addr = (rc?.members?.get("address") ?: rc?.members?.get("ip") ?: "") as? String ?: ""
                                relaySrv = addr
                            }
                            val tb = (s.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (dbgLastTime > 0 && now > dbgLastTime)
                                kbps = (tb - dbgLastBytes) * 8 / (now - dbgLastTime)
                            dbgLastBytes = tb; dbgLastTime = now
                        }
                        "inbound-rtp" -> if (s.members["kind"] == "video" || s.members["mediaType"] == "video") {
                            fps = (s.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                            val w = (s.members["frameWidth"] as? Number)?.toInt() ?: 0
                            val h = (s.members["frameHeight"] as? Number)?.toInt() ?: 0
                            res = "${w}x${h}"
                            loss = (s.members["packetsLost"] as? Number)?.toLong() ?: 0L
                            freeze = (s.members["freezeCount"] as? Number)?.toLong() ?: 0L
                        }
                    }
                }
                val srv = if (relaySrv.isNotBlank()) " @${relaySrv}" else ""
                val line = "ICE:$ice  경로:$path$srv\nCF:$cfStatus  ${kbps}kbps ${"%.0f".format(fps)}fps $res\n손실:$loss 끊김:$freeze ${if (path.contains("relay")) "(중계)" else "(직접)"}"
                runOnUiThread { if (debugOn) b.txtDebug.text = line }
            } catch (e: Exception) {
                runOnUiThread { if (debugOn) b.txtDebug.text = "stats err: ${e.message}" }
            }
        }
    }

    /** 연결 통계에서 받은 바이트를 읽어, TURN 중계 중일 때만 월 사용량에 더한다. */
    private fun pollUsage() {
        val p = pc ?: return
        p.getStats { report ->
            try {
                val stats = report.statsMap
                var total = 0L
                var relayed = false
                var pairKnown = false
                for (s in stats.values) {
                    if (s.type != "transport") continue
                    total += (s.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    val pair = stats[s.members["selectedCandidatePairId"] as? String ?: ""] ?: continue
                    pairKnown = true
                    for (key in listOf("localCandidateId", "remoteCandidateId")) {
                        val cand = stats[pair.members[key] as? String ?: ""] ?: continue
                        if (cand.members["candidateType"] == "relay") relayed = true
                    }
                }
                val delta = total - lastTransportBytes
                lastTransportBytes = total
                // 경로를 알 수 없으면 보수적으로 중계로 간주
                if (delta > 0 && (relayed || !pairKnown)) {
                    UsageMeter.add(prefs, delta)
                    if (UsageMeter.isOver(prefs)) runOnUiThread { stopForUsageLimit() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stats: ${e.message}")
            }
        }
    }

    private fun stopForUsageLimit() {
        if (isFinishing || isDestroyed) return
        mainHandler.removeCallbacks(recoveryRunnable)
        reconnectOnResume = false
        disconnect()
        showForm()
        b.txtUsage.text = UsageMeter.label(prefs)
        b.txtStatus.text = "이번 달 중계 사용량 상한(900GB)에 도달해 접속을 멈췄습니다. 다음 달에 다시 쓸 수 있습니다."
    }

    private fun start(url: String, code: String) {
        if (factory == null) return
        mainHandler.removeCallbacks(recoveryRunnable)
        if (prefs.has(Prefs.CF_KEY_ID) && UsageMeter.isOver(prefs)) {
            showForm()
            b.txtUsage.text = UsageMeter.label(prefs)
            b.txtStatus.text = "이번 달 중계 사용량 상한(900GB)에 도달해 접속할 수 없습니다. 다음 달에 다시 쓸 수 있습니다."
            return
        }
        offerSent = false
        b.txtStatus.text = "연결 중…"

        val cfKey = prefs.get(Prefs.CF_KEY_ID).ifBlank { null }
        val cfToken = prefs.get(Prefs.CF_TOKEN).ifBlank { null }
        val turnUrl = b.editTurnUrl.text.toString().trim().ifBlank { null }
        val turnUser = b.editTurnUser.text.toString().trim().ifBlank { null }
        val turnCred = b.editTurnCred.text.toString().trim().ifBlank { null }
        // 캐시된 ICE(이미 받아둔 Cloudflare 자격증명)가 있으면 재사용 → 재연결이 빠름
        val reuse = cachedIce
        if (reuse != null && reuse.isNotEmpty()) {
            continueConnect(url, code, reuse)
            return
        }
        if (cfKey != null) b.txtStatus.text = "TURN 자격증명 받는 중…"
        kotlin.concurrent.thread {
            val ice = RtcConfig.buildIceServers(cfKey, cfToken, turnUrl, turnUser, turnCred)
            cfStatus = if (cfKey != null) CloudflareTurn.lastResult else "미설정"
            // 중계(relay) 후보를 쓸 수 있는가: Cloudflare 발급 성공 또는 직접 TURN 입력
            relayAvailable = cfStatus.startsWith("OK") || turnUrl != null
            if (ice.isNotEmpty()) cachedIce = ice
            runOnUiThread { if (!isFinishing && !isDestroyed) continueConnect(url, code, ice) }
        }
    }

    private fun continueConnect(url: String, code: String, ice: List<PeerConnection.IceServer>) {
        if (factory == null) return
        lastTransportBytes = 0L
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 5000)
        val rtcConfig = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // 직접 연결이 불안정한 모바일에선 중계(TURN) 강제가 안정적.
            // 단, 쓸 수 있는 중계가 있을 때만 강제(없는데 강제하면 영원히 checking).
            if (prefs.get(Prefs.FORCE_RELAY) == "1" && relayAvailable) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            } else if (prefs.get(Prefs.FORCE_RELAY) == "1" && !relayAvailable) {
                runOnUiThread {
                    b.txtStatus.text = "중계(Cloudflare) 사용 불가: $cfStatus — 직접 연결로 시도"
                    toast("Cloudflare TURN 실패: $cfStatus")
                }
            }
        }
        pc = factory!!.createPeerConnection(rtcConfig, object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate) {
                signaling?.sendCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        mainHandler.removeCallbacks(recoveryRunnable) // 복구 예약 취소
                        runOnUiThread { if (remoteTrack != null) b.txtStatus.text = "영상 수신 중" }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // 모바일에선 잠깐 불안정했다가 스스로 복구되는 일이 흔하다.
                        // 끊지 말고 기다리되, 15초 넘게 안 돌아오면 그때 재연결.
                        runOnUiThread { b.txtStatus.text = "연결 불안정…" }
                        scheduleRecovery(15000)
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED ->
                        scheduleRecovery(2500) // 실패 — 재연결
                    else -> {}
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteTrack = track
                    runOnUiThread {
                        track.addSink(b.renderer)
                        b.overlayConnect.visibility = View.GONE
                        hideConnecting()
                        b.txtStatus.text = "연결됨"
                    }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack && remoteTrack == null) {
                    remoteTrack = track
                    runOnUiThread { track.addSink(b.renderer); b.overlayConnect.visibility = View.GONE; hideConnecting() }
                }
            }
            // host 가 만든 입력용 데이터채널 수신 → 이 채널로 입력을 보낸다
            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
            }
        })

        signaling = SignalingClient(url, code, "client", object : SignalingClient.Listener {
            override fun onConnected() { runOnUiThread { b.txtStatus.text = "서버 연결됨, 상대 대기…" } }
            override fun onPeerJoined() {
                // 상대(host)가 있으면 PIN 전송 → host 가 검증 후 offer 를 보냄
                if (!offerSent) { offerSent = true; signaling?.sendAuth(pin) }
            }
            override fun onPeerLeft() {
                offerSent = false // 상대(B)가 돌아오면 다시 인증하도록
                runOnUiThread { b.txtStatus.text = "상대 대기…" }
            }
            override fun onClosed() {}
            override fun onError(message: String) { runOnUiThread { b.txtStatus.text = "오류: $message" } }
            override fun onAuth(pin: String) {}  // client 는 PIN 을 보내는 쪽
            override fun onAuthFail() {
                runOnUiThread { showForm(); b.txtStatus.text = "PIN이 올바르지 않습니다" }
                try { signaling?.close() } catch (_: Exception) {}
            }

            // host 가 보낸 offer 를 받아 answer 생성
            override fun onOffer(sdp: String) {
                val p = pc ?: return
                p.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        p.createAnswer(object : SdpObserverAdapter() {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                p.setLocalDescription(SdpObserverAdapter(), desc)
                                signaling?.sendAnswer(desc.description)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            override fun onAnswer(sdp: String) {} // client 는 answer 를 보내는 쪽
            override fun onCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                pc?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
        })
        signaling!!.connect()
    }

    private fun fitRenderer(vw: Int, vh: Int) {
        val container = b.rendererContainer
        container.post {
            val cw = container.width.toFloat(); val ch = container.height.toFloat()
            if (cw == 0f || ch == 0f || vw == 0 || vh == 0) return@post
            // 세로(높이)에 꽉 차게: 비율 유지, 상하 여백 없음. 좌우는 여백 또는 넘침(크롭) 허용.
            val scale = ch / vh
            val lp = b.renderer.layoutParams as FrameLayout.LayoutParams
            lp.width = (vw * scale).toInt(); lp.height = ch.toInt()
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

    private fun updateQualityButton() {
        b.txtQualityLabel.text = RtcConfig.QUALITIES[qualityIndex].name
    }

    /** 현재 화질 단계를 B에게 전달 → B가 전송량/프레임레이트 조절 */
    private fun sendQuality() {
        val q = RtcConfig.QUALITIES[qualityIndex]
        sendInput(InputCodec.quality(q.bitrate, q.fps))
    }

    private fun sendInput(json: String) {
        val dc = dataChannel ?: return
        try {
            val buf = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
            dc.send(DataChannel.Buffer(buf, false))
        } catch (e: Exception) { Log.w(TAG, "send: ${e.message}") }
    }

    /** 연결만 끊는다(팩토리/렌더러는 유지). 돌아오면 '바로 접속'으로 재연결. */
    private fun disconnect() {
        mainHandler.removeCallbacks(statsRunnable)
        try { remoteTrack?.removeSink(b.renderer) } catch (_: Exception) {}
        remoteTrack = null
        try { signaling?.close() } catch (_: Exception) {}
        signaling = null
        try { dataChannel?.dispose() } catch (_: Exception) {}
        dataChannel = null
        try { pc?.close() } catch (_: Exception) {}
        pc = null
        offerSent = false
    }

    override fun onStop() {
        // 앱을 벗어나면 연결을 정리. 조종 중이었다면 돌아왔을 때 자동 재접속.
        mainHandler.removeCallbacks(recoveryRunnable)
        reconnectOnResume = (b.overlayConnect.visibility == View.GONE)
        disconnect()
        b.overlayConnect.visibility = View.VISIBLE
        hideConnecting()
        b.txtStatus.text = ""
        b.txtUsage.text = UsageMeter.label(prefs)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 조종 중(영상 화면)이었는데 연결이 살아있지 않으면 재접속.
        // (홈 눌렀다 복귀 시 onStop 이 안 오고 백그라운드에서 끊겨 화면이 얼어붙는 경우 포함)
        val shouldBeConnected = b.overlayConnect.visibility == View.GONE
        val ice = pc?.iceConnectionState()
        val healthy = ice == PeerConnection.IceConnectionState.CONNECTED ||
            ice == PeerConnection.IceConnectionState.COMPLETED
        if (factory != null && (reconnectOnResume || (shouldBeConnected && !healthy))) {
            reconnectOnResume = false
            showConnecting() // 입력창 없이 바로 재접속
            b.btnConnect.performClick()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recoveryRunnable)
        mainHandler.removeCallbacks(debugRunnable)
        disconnect()
        try { b.renderer.release() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { eglBase.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "WebRtcClient"
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }
}
