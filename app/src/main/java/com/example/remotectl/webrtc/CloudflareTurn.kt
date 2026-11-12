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
 * API: POST https://rtc.live.cloudflare.com/v1/turn/keys/{keyId}/credentials/generate-ice-servers
 *      Authorization: Bearer {token}
 *      body: { "ttl": 86400 }
 *      resp: { "iceServers": [ { "urls": [...], "username": "...", "credential": "..." } ] }
 */
object CloudflareTurn {
    private const val TAG = "CloudflareTurn"
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 마지막 발급 결과(진단 표시용): 예 "OK(3)", "HTTP 401", "오류: timeout", "미설정" */
    @Volatile var lastResult: String = "미설정"
        private set

    /** 동기 호출(백그라운드 스레드에서 사용). 실패 시 null. */
    fun fetchIceServers(keyId: String, token: String): List<PeerConnection.IceServer>? {
        var lastErr = "알수없음"
        // 공식 엔드포인트(generate-ice-servers) 우선, 안 되면 구 엔드포인트(generate)
        for (path in listOf("generate-ice-servers", "generate")) {
            try {
                val url = "https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/$path"
                val body = JSONObject().put("ttl", 86400).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                val result = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { lastErr = "HTTP ${resp.code}"; Log.w(TAG, "$path $lastErr"); null }
                    else resp.body?.string()?.let { parse(it) }
                }
                if (result != null) { lastResult = "OK(${result.size})"; return result }
            } catch (e: Exception) {
                lastErr = "오류: ${e.message}"
                Log.w(TAG, "$path failed: ${e.message}")
            }
        }
        lastResult = lastErr
        return null
    }

    /** iceServers 가 배열([{urls,username,credential}, ...])이든 단일 객체든 처리 */
    private fun parse(json: String): List<PeerConnection.IceServer>? {
        val root = JSONObject(json)
        val entries = ArrayList<JSONObject>()
        val arr = root.optJSONArray("iceServers")
        if (arr != null) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
        } else {
            root.optJSONObject("iceServers")?.let { entries.add(it) }
        }

        val list = ArrayList<PeerConnection.IceServer>()
        for (ice in entries) {
            val username = ice.optString("username", "")
            val credential = ice.optString("credential", "")
            val urls = ArrayList<String>()
            val u = ice.optJSONArray("urls")
            if (u != null) {
                for (i in 0 until u.length()) urls.add(u.getString(i))
            } else {
                ice.optString("urls").takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }
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
        }
        return list.ifEmpty { null }
    }
}
