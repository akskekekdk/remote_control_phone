package com.example.remotectl.webrtc

import com.example.remotectl.host.ControlAccessibilityService
import org.json.JSONObject

/**
 * 데이터 채널로 주고받는 입력 메시지(JSON).
 * 좌표는 0.0~1.0 정규화. 수신 측(B)이 자기 화면 픽셀로 환산한다.
 */
object InputCodec {

    fun tap(x: Float, y: Float) =
        JSONObject().put("t", "tap").put("x", x).put("y", y).toString()

    fun longPress(x: Float, y: Float) =
        JSONObject().put("t", "long").put("x", x).put("y", y).toString()

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Int) =
        JSONObject().put("t", "swipe")
            .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("d", durMs)
            .toString()

    fun key(k: Int) = JSONObject().put("t", "key").put("k", k).toString()

    /** B 쪽: 수신 메시지를 실제 화면 픽셀 터치로 실행 */
    fun apply(json: String, screenW: Float, screenH: Float) {
        val a11y = ControlAccessibilityService.instance ?: return
        val o = JSONObject(json)
        when (o.getString("t")) {
            "tap" -> a11y.tap(o.getDouble("x").toFloat() * screenW, o.getDouble("y").toFloat() * screenH)
            "long" -> a11y.longPress(o.getDouble("x").toFloat() * screenW, o.getDouble("y").toFloat() * screenH)
            "swipe" -> a11y.swipe(
                o.getDouble("x1").toFloat() * screenW, o.getDouble("y1").toFloat() * screenH,
                o.getDouble("x2").toFloat() * screenW, o.getDouble("y2").toFloat() * screenH,
                o.getInt("d").toLong()
            )
            "key" -> a11y.globalKey(o.getInt("k"))
        }
    }
}
