package com.example.remotectl.webrtc

import org.webrtc.PeerConnection

/**
 * 인터넷 원격 모드 설정값.
 *
 * - 신호 서버(SignalingServer): offer/answer/candidate 를 중계하는 WebSocket.
 *   server/signaling.js 를 공용 주소(VPS, Render, Fly 등)에 올리고 그 주소를 쓴다.
 * - STUN/TURN: 서로 다른 네트워크(LTE↔집) 연결에 필요. STUN 만으로 안 뚫리는
 *   환경(대칭형 NAT, 통신사망)에서는 TURN 이 있어야 연결된다.
 */
object RtcConfig {

    // 기본 신호 서버 주소 (반드시 본인 서버로 교체). 예: wss://your-host.example/ws
    const val DEFAULT_SIGNALING_URL = "wss://example.com/ws"

    // 영상 전송량 상한(약 1Mbps). 24시간 중계해도 월 약 330GB 수준으로 묶는다.
    const val MAX_VIDEO_BITRATE_BPS = 1_000_000

    // 공개 STUN (무료).
    private val STUN = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    // 무료 공개 TURN (Metered OpenRelay). 사용자가 TURN 을 안 넣어도 LTE↔와이파이가
    // 대부분 연결되도록 기본 내장. 공개 서버라 혼잡할 수 있으니, 안정적으로 쓰려면
    // 본인 TURN(coturn) 또는 유료 TURN 자격증명을 앱에 입력해 덮어쓴다.
    private val DEFAULT_TURN_USER = "openrelayproject"
    private val DEFAULT_TURN_CRED = "openrelayproject"
    private val DEFAULT_TURN_URLS = listOf(
        "turn:openrelay.metered.ca:80",
        "turn:openrelay.metered.ca:443",
        "turns:openrelay.metered.ca:443?transport=tcp"
    )

    /**
     * 최종 ICE 서버 목록.
     * 1순위: Cloudflare TURN(자격증명 자동 발급) — 설정돼 있으면 사용
     * 2순위: 사용자가 입력한 TURN
     * 3순위: 무료 공개 TURN 기본값
     * ⚠️ Cloudflare 호출은 네트워크라 반드시 백그라운드 스레드에서 호출할 것.
     */
    fun buildIceServers(
        cfKeyId: String?, cfToken: String?,
        turnUrl: String?, turnUser: String?, turnCred: String?,
    ): List<PeerConnection.IceServer> {
        if (!cfKeyId.isNullOrBlank() && !cfToken.isNullOrBlank()) {
            val cf = CloudflareTurn.fetchIceServers(cfKeyId, cfToken)
            if (cf != null) {
                // Cloudflare 결과 + 백업 STUN
                val list = ArrayList<PeerConnection.IceServer>()
                STUN.forEach { list.add(PeerConnection.IceServer.builder(it).createIceServer()) }
                list.addAll(cf)
                return list
            }
            // 발급 실패 시 아래 폴백
        }
        return iceServers(turnUrl, turnUser, turnCred)
    }

    /**
     * ICE 서버 목록 생성.
     * 사용자가 TURN 을 입력하면 그것을, 없으면 무료 공개 TURN 기본값을 사용한다.
     */
    fun iceServers(
        turnUrl: String? = null,
        turnUser: String? = null,
        turnCred: String? = null,
    ): List<PeerConnection.IceServer> {
        val list = ArrayList<PeerConnection.IceServer>()
        STUN.forEach { list.add(PeerConnection.IceServer.builder(it).createIceServer()) }

        if (!turnUrl.isNullOrBlank()) {
            // 사용자 지정 TURN
            list.add(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(turnUser ?: "")
                    .setPassword(turnCred ?: "")
                    .createIceServer()
            )
        } else {
            // 기본 무료 TURN
            DEFAULT_TURN_URLS.forEach {
                list.add(
                    PeerConnection.IceServer.builder(it)
                        .setUsername(DEFAULT_TURN_USER)
                        .setPassword(DEFAULT_TURN_CRED)
                        .createIceServer()
                )
            }
        }
        return list
    }
}
