package com.example.remotectl.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * B(피조종) 폰에서 실제 터치/제스처를 주입하는 서비스.
 * A에서 들어온 입력 메시지를 ScreenCaptureService 가 여기로 넘긴다.
 */
class ControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 화면 픽셀 좌표로 탭 */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 스와이프 / 드래그 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val dur = durationMs.coerceIn(20, 10_000)
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun globalKey(keycode: Int) {
        when (keycode) {
            3 -> { adjustVolume(raise = true); return }
            4 -> { adjustVolume(raise = false); return }
        }
        val action = when (keycode) {
            0 -> GLOBAL_ACTION_BACK
            1 -> GLOBAL_ACTION_HOME
            2 -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(action)
    }

    /** B 미디어(게임) 볼륨 조절 */
    private fun adjustVolume(raise: Boolean) {
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (raise) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.w(TAG, "volume: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ControlA11y"
        @Volatile
        var instance: ControlAccessibilityService? = null
    }
}
