package com.example.remotectl.webrtc

import com.example.remotectl.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 이번 달 TURN 중계로 받은 데이터양을 누적한다(조종하는 A 폰 기준).
 * 상한을 넘으면 그 달은 Cloudflare 중계 접속을 막아 무료 한도(1,000GB) 초과 과금을 피한다.
 * 앱이 직접 재는 값이라 Cloudflare 청구량과 약간 다를 수 있어 여유를 두고 900GB 로 잡는다.
 */
object UsageMeter {
    const val LIMIT_BYTES = 900L * 1024 * 1024 * 1024

    private fun month(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun current(prefs: Prefs): Long {
        if (prefs.get(Prefs.USAGE_MONTH) != month()) return 0L
        return prefs.get(Prefs.USAGE_BYTES).toLongOrNull() ?: 0L
    }

    fun add(prefs: Prefs, bytes: Long) {
        if (bytes <= 0) return
        val total = current(prefs) + bytes // 달이 바뀌었으면 current 가 0 이라 자동 초기화
        prefs.put(Prefs.USAGE_MONTH, month())
        prefs.put(Prefs.USAGE_BYTES, total.toString())
    }

    fun isOver(prefs: Prefs): Boolean = current(prefs) >= LIMIT_BYTES

    fun label(prefs: Prefs): String {
        val gb = current(prefs) / (1024.0 * 1024 * 1024)
        return "이번 달 중계 사용량: %.1f GB / 900 GB".format(gb)
    }
}
