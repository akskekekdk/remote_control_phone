package com.example.remotectl

import android.content.Context

/** 지난 연결값 저장 (즐겨찾기처럼 다음에 바로 쓰기). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("remotectl", Context.MODE_PRIVATE)

    fun get(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    fun has(key: String): Boolean = !sp.getString(key, null).isNullOrBlank()

    companion object {
        // 마지막으로 조종(A)한 연결 방식: "wifi" | "internet"
        const val LAST_MODE = "last_mode"
        // 와이파이 모드
        const val WIFI_IP = "wifi_ip"
        const val WIFI_PIN = "wifi_pin"
        // 인터넷 모드
        const val NET_URL = "net_url"
        const val NET_CODE = "net_code"
        const val NET_PIN = "net_pin"
        const val NET_TURN_URL = "net_turn_url"
        const val NET_TURN_USER = "net_turn_user"
        const val NET_TURN_CRED = "net_turn_cred"
    }
}
