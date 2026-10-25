package com.example.remotectl

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 간단한 길이-접두 프레이밍 프로토콜. (같은 와이파이 안에서만 사용하는 개인용)
 *
 *   [int32 type][int32 payloadLength][payload bytes...]
 *
 * B(피조종) -> A(조종): META / VIDEO_CONFIG / VIDEO
 * A(조종) -> B(피조종): INPUT_*
 *
 * 좌표는 모두 0.0~1.0 로 정규화해서 보낸다. 그래야 양쪽 해상도가 달라도
 * B가 자기 화면 픽셀로 바로 환산할 수 있다.
 */
object Protocol {
    const val PORT = 7001

    // B -> A
    const val TYPE_META = 1          // "width,height" (인코딩된 영상 크기)
    const val TYPE_VIDEO_CONFIG = 2  // H.264 SPS/PPS (csd-0)
    const val TYPE_VIDEO = 3         // H.264 프레임 (앞 8바이트: presentationTimeUs long)

    // 인증 (연결 직후, 다른 모든 것보다 먼저)
    const val TYPE_AUTH = 20         // A -> B : PIN 문자열(UTF-8)
    const val TYPE_AUTH_OK = 21      // B -> A : 인증 성공
    const val TYPE_AUTH_FAIL = 22    // B -> A : 인증 실패(직후 연결 종료)

    // A -> B
    const val TYPE_TAP = 10          // float x, float y
    const val TYPE_SWIPE = 11        // float x1,y1,x2,y2, int durationMs
    const val TYPE_KEY = 12          // int keycode
    const val TYPE_LONG_PRESS = 13   // float x, float y

    const val KEY_BACK = 0
    const val KEY_HOME = 1
    const val KEY_RECENTS = 2

    fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeInt(type)
            out.writeInt(payload.size)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    data class Packet(val type: Int, val payload: ByteArray)

    fun readPacket(input: DataInputStream): Packet {
        val type = input.readInt()
        val len = input.readInt()
        require(len in 0..(32 * 1024 * 1024)) { "bad packet length $len" }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        return Packet(type, payload)
    }
}
