package com.example.remotectl.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.Prefs
import com.example.remotectl.databinding.ActivityWebrtcHostBinding
import com.example.remotectl.host.ControlAccessibilityService
import kotlin.random.Random

/** B(피조종) 인터넷 모드 화면. */
class WebRtcHostActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebrtcHostBinding
    private lateinit var mpm: MediaProjectionManager
    private var sharing = false
    private val prefs by lazy { Prefs(this) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, WebRtcHostService::class.java).apply {
                putExtra(WebRtcHostService.EXTRA_CODE, result.resultCode)
                putExtra(WebRtcHostService.EXTRA_DATA, result.data)
                putExtra(WebRtcHostService.EXTRA_ROOM, b.editCode.text.toString().trim())
                putExtra(WebRtcHostService.EXTRA_URL, b.editUrl.text.toString().trim())
                putExtra(WebRtcHostService.EXTRA_TURN_URL, b.editTurnUrl.text.toString().trim())
                putExtra(WebRtcHostService.EXTRA_TURN_USER, b.editTurnUser.text.toString().trim())
                putExtra(WebRtcHostService.EXTRA_TURN_CRED, b.editTurnCred.text.toString().trim())
                putExtra(WebRtcHostService.EXTRA_PIN, b.editPin.text.toString().trim())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            sharing = true
            b.btnStart.text = "공유 중지"
            b.txtStatus.text = "공유 중입니다. A 폰에서 같은 코드로 접속하세요."
        } else {
            b.txtStatus.text = "화면 공유 권한이 거부되었습니다."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebrtcHostBinding.inflate(layoutInflater)
        setContentView(b.root)
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 지난 값 재사용 (A의 저장된 코드/PIN이 계속 맞도록). 처음이면 랜덤 생성.
        b.editUrl.setText(prefs.get(Prefs.NET_URL, RtcConfig.DEFAULT_SIGNALING_URL))
        b.editCode.setText(prefs.get(Prefs.NET_CODE, Random.nextInt(100000, 999999).toString()))
        b.editPin.setText(prefs.get(Prefs.NET_PIN, Random.nextInt(1000, 9999).toString()))
        b.editTurnUrl.setText(prefs.get(Prefs.NET_TURN_URL))
        b.editTurnUser.setText(prefs.get(Prefs.NET_TURN_USER))
        b.editTurnCred.setText(prefs.get(Prefs.NET_TURN_CRED))

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.btnStart.setOnClickListener {
            if (!sharing) {
                if (!isAccessibilityEnabled()) {
                    b.txtStatus.text = "먼저 접근성 서비스를 켜주세요."
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                if (b.editCode.text.toString().isBlank() || b.editUrl.text.toString().isBlank()
                    || b.editPin.text.toString().isBlank()) {
                    b.txtStatus.text = "방 코드·신호 서버 주소·PIN을 입력하세요."
                    return@setOnClickListener
                }
                // 다음에도 같은 코드/PIN 쓰도록 저장
                prefs.put(Prefs.NET_URL, b.editUrl.text.toString().trim())
                prefs.put(Prefs.NET_CODE, b.editCode.text.toString().trim())
                prefs.put(Prefs.NET_PIN, b.editPin.text.toString().trim())
                prefs.put(Prefs.NET_TURN_URL, b.editTurnUrl.text.toString().trim())
                prefs.put(Prefs.NET_TURN_USER, b.editTurnUser.text.toString().trim())
                prefs.put(Prefs.NET_TURN_CRED, b.editTurnCred.text.toString().trim())
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } else {
                stopService(Intent(this, WebRtcHostService::class.java).apply {
                    action = WebRtcHostService.ACTION_STOP
                })
                sharing = false
                b.btnStart.text = "화면 공유 시작"
                b.txtStatus.text = "중지되었습니다."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.txtAccessibility.text = if (isAccessibilityEnabled())
            "접근성 서비스: 켜짐 ✓" else "접근성 서비스: 꺼짐 (조종하려면 필요)"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${ControlAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) if (splitter.next().equals(expected, true)) return true
        return false
    }
}
