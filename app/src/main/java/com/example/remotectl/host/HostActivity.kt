package com.example.remotectl.host

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.Protocol
import com.example.remotectl.databinding.ActivityHostBinding
import java.net.Inet4Address
import java.net.NetworkInterface

/** B(피조종) 화면: 권한 받고 공유 시작, 접속용 IP 표시. */
class HostActivity : AppCompatActivity() {

    private lateinit var b: ActivityHostBinding
    private lateinit var mpm: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_PIN, b.editPin.text.toString().trim())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            b.txtStatus.text = "공유 중입니다. A 폰에서 아래 IP로 접속하세요."
            b.btnStart.text = "공유 중지"
            sharing = true
        } else {
            b.txtStatus.text = "화면 공유 권한이 거부되었습니다."
        }
    }

    private var sharing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHostBinding.inflate(layoutInflater)
        setContentView(b.root)
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        b.txtIp.text = "내 IP: ${localIpAddress() ?: "와이파이 확인"}  (포트 ${Protocol.PORT})"
        if (b.editPin.text.isNullOrBlank()) {
            b.editPin.setText((1000..9999).random().toString())
        }

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.btnStart.setOnClickListener {
            if (!sharing) {
                if (!isAccessibilityEnabled()) {
                    b.txtStatus.text = "먼저 접근성 서비스를 켜주세요 (원격 조종 허용)."
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                if (b.editPin.text.toString().trim().isEmpty()) {
                    b.txtStatus.text = "연결 PIN을 설정하세요."
                    return@setOnClickListener
                }
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } else {
                stopService(Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
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
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun localIpAddress(): String? {
        // 우선 와이파이 IP
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                return "%d.%d.%d.%d".format(
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}
        // 백업: 네트워크 인터페이스 훑기
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
