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
        fun onError(message: String)
        fun onClosed()
    }

    private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect() {
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
                        "error" -> listener.onError(o.optString("message", "unknown"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad msg: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws fail: ${t.message}")
                listener.onError(t.message ?: "연결 실패")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    fun sendOffer(sdp: String) =
        send(JSONObject().put("type", "offer").put("sdp", sdp))

    fun sendAnswer(sdp: String) =
        send(JSONObject().put("type", "answer").put("sdp", sdp))

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
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    companion object { private const val TAG = "Signaling" }
}
