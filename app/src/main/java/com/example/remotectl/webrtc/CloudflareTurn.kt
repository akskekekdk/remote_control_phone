package com.example.remotectl.webrtc

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/**
 * Cloudflare TURN 자격증명을 API로 발급받는다.
 *
 * Cloudflare 대시보드 → Realtime → TURN 에서
 *   - Turn Key ID (keyId)
 *   - API Token (token)
 * 을 받아 앱에 입력하면, 접속할 때마다 이 함수가 ICE 서버 목록을 생성한다.
 *
 * API: POST https://rtc.live.cloudflare.com/v1/turn/keys/{keyId}/credentials/generate
 *      Authorization: Bearer {token}
 *      body: { "ttl": 86400 }
 *      resp: { "iceServers": { "urls": [...], "username": "...", "credential": "..." } }
 */
object CloudflareTurn {
    private const val TAG = "CloudflareTurn"
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 동기 호출(백그라운드 스레드에서 사용). 실패 시 null. */
    fun fetchIceServers(keyId: String, token: String): List<PeerConnection.IceServer>? {
        return try {
            val url = "https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate"
            val body = JSONObject().put("ttl", 86400).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "HTTP ${resp.code}"); return null }
                val json = resp.body?.string() ?: return null
                parse(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: ${e.message}")
            null
        }
    }

    private fun parse(json: String): List<PeerConnection.IceServer>? {
        val root = JSONObject(json)
        val ice = root.optJSONObject("iceServers") ?: return null
        val username = ice.optString("username", "")
        val credential = ice.optString("credential", "")

        // urls 는 배열 또는 단일 문자열
        val urls = ArrayList<String>()
        val arr = ice.optJSONArray("urls")
        if (arr != null) {
            for (i in 0 until arr.length()) urls.add(arr.getString(i))
        } else {
            ice.optString("urls").takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) return null

        val list = ArrayList<PeerConnection.IceServer>()
        // STUN(인증 불필요)과 TURN(인증 필요)을 분리해서 등록
        val stun = urls.filter { it.startsWith("stun:") }
        val turn = urls.filter { it.startsWith("turn:") || it.startsWith("turns:") }
        if (stun.isNotEmpty()) list.add(PeerConnection.IceServer.builder(stun).createIceServer())
        if (turn.isNotEmpty()) {
            list.add(
                PeerConnection.IceServer.builder(turn)
                    .setUsername(username)
                    .setPassword(credential)
                    .createIceServer()
            )
        }
        return list.ifEmpty { null }
    }
}
