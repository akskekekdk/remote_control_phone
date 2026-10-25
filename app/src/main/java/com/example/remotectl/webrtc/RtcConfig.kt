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

    // 공개 STUN (무료). TURN 은 사용자가 입력하거나 아래에 직접 채운다.
    private val STUN = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    /** TURN 자격증명이 있으면 함께 포함한 ICE 서버 목록 생성 */
    fun iceServers(
        turnUrl: String? = null,
        turnUser: String? = null,
        turnCred: String? = null,
    ): List<PeerConnection.IceServer> {
        val list = ArrayList<PeerConnection.IceServer>()
        STUN.forEach { list.add(PeerConnection.IceServer.builder(it).createIceServer()) }
        if (!turnUrl.isNullOrBlank()) {
            list.add(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(turnUser ?: "")
                    .setPassword(turnCred ?: "")
                    .createIceServer()
            )
        }
        return list
    }
}
