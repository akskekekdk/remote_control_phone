package com.example.remotectl.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 신호 클라이언트. 방(room) 코드로 두 폰을 짝지어 주고
 * offer/answer/candidate 를 상대에게 전달한다.
 *
 * 메시지 형식(JSON):
 *   {type:"join", room:"123456", role:"host"|"client"}
 *   {type:"peer-joined"}            // 상대가 들어왔음
 *   {type:"peer-left"}
 *   {type:"offer",  sdp:"..."}
 *   {type:"answer", sdp:"..."}
 *   {type:"candidate", candidate:"...", sdpMid:"...", sdpMLineIndex:0}
 *   {type:"error", message:"..."}
 */
class SignalingClient(
    private val url: String,
    private val room: String,
    private val role: String, // "host" | "client"
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onAuth(pin: String)   // host 쪽: client 가 보낸 PIN
        fun onAuthFail()          // client 쪽: PIN 불일치
        fun onError(message: String)
        fun onClosed()
    }

    private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var active = true
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** 연결이 끊기면 자동으로 다시 붙는다 (서버가 잠들었다 깨어나도 방으로 복귀). */
    private fun scheduleReconnect() {
        if (!active) return
        try {
            scheduler.schedule({ if (active) connect() }, 3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    fun connect() {
        if (!active) return
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
                send(JSONObject().put("type", "join").put("room", room).put("role", role))
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "peer-joined" -> listener.onPeerJoined()
                        "peer-left" -> listener.onPeerLeft()
                        "offer" -> listener.onOffer(o.getString("sdp"))
                        "answer" -> listener.onAnswer(o.getString("sdp"))
                        "candidate" -> listener.onCandidate(
                            o.getString("candidate"),
                            o.optString("sdpMid", null),
                            o.optInt("sdpMLineIndex", 0)
                        )
                        "auth" -> listener.onAuth(o.optString("pin", ""))
                        "auth-fail" -> listener.onAuthFail()
                        "error" -> listener.onError(o.optString("message", "unknown"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad msg: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws fail: ${t.message}")
                listener.onError(t.message ?: "연결 실패")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
                // 4000 = 새 연결로 교체됨(의도된 종료) → 재접속하지 않음
                if (code != 4000) scheduleReconnect()
            }
        })
    }

    fun sendOffer(sdp: String) =
        send(JSONObject().put("type", "offer").put("sdp", sdp))

    fun sendAnswer(sdp: String) =
        send(JSONObject().put("type", "answer").put("sdp", sdp))

    fun sendAuth(pin: String) =
        send(JSONObject().put("type", "auth").put("pin", pin))

    fun sendAuthFail() =
        send(JSONObject().put("type", "auth-fail"))

    fun sendCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) =
        send(
            JSONObject().put("type", "candidate")
                .put("candidate", candidate)
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", sdpMLineIndex)
        )

    private fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    fun close() {
        active = false
        try { scheduler.shutdownNow() } catch (_: Exception) {}
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    companion object { private const val TAG = "Signaling" }
}
