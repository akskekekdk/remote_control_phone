package com.example.remotectl

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.client.ClientActivity
import com.example.remotectl.databinding.ActivityMainBinding
import com.example.remotectl.host.HostActivity
import com.example.remotectl.update.UpdateManager
import com.example.remotectl.webrtc.WebRtcClientActivity
import com.example.remotectl.webrtc.WebRtcHostActivity

/** 실행 시 연결 모드(와이파이 / 인터넷)와 역할(조종 / 피조종)을 고른다. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.txtVersion.text = "v${BuildConfig.VERSION_NAME}"

        b.btnController.setOnClickListener {
            val internet = b.radioInternet.isChecked
            startActivity(Intent(this,
                if (internet) WebRtcClientActivity::class.java else ClientActivity::class.java))
        }
        b.btnControlled.setOnClickListener {
            val internet = b.radioInternet.isChecked
            startActivity(Intent(this,
                if (internet) WebRtcHostActivity::class.java else HostActivity::class.java))
        }

        b.btnQuickConnect.setOnClickListener { quickConnect() }
        b.btnUpdate.setOnClickListener { checkUpdate(manual = true) }

        // 위젯에서 진입하면 바로 연결
        if (intent?.getBooleanExtra(EXTRA_QUICK, false) == true) quickConnect()

        checkUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        refreshQuickButton()
    }

    private fun refreshQuickButton() {
        val info = lastConnectionLabel()
        if (info == null) {
            b.btnQuickConnect.visibility = View.GONE
        } else {
            b.btnQuickConnect.visibility = View.VISIBLE
            b.btnQuickConnect.text = "⚡ 최근 연결로 바로 연결\n$info"
        }
    }

    /** 최근 연결이 있으면 "모드 · 코드/IP" 라벨, 없으면 null */
    private fun lastConnectionLabel(): String? {
        return when (prefs.get(Prefs.LAST_MODE)) {
            "internet" ->
                if (prefs.has(Prefs.NET_CODE) && prefs.has(Prefs.NET_PIN))
                    "인터넷 · 코드 ${prefs.get(Prefs.NET_CODE)}" else null
            "wifi" ->
                if (prefs.has(Prefs.WIFI_IP) && prefs.has(Prefs.WIFI_PIN))
                    "와이파이 · ${prefs.get(Prefs.WIFI_IP)}" else null
            else -> null
        }
    }

    private fun quickConnect() {
        val mode = prefs.get(Prefs.LAST_MODE)
        val target = when (mode) {
            "internet" -> WebRtcClientActivity::class.java
            "wifi" -> ClientActivity::class.java
            else -> { return }
        }
        val extraKey = if (mode == "internet")
            WebRtcClientActivity.EXTRA_AUTOCONNECT else ClientActivity.EXTRA_AUTOCONNECT
        startActivity(Intent(this, target).putExtra(extraKey, true))
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) b.txtVersion.text = "업데이트 확인 중…"
        UpdateManager.check(
            onResult = { info ->
                runOnUiThread {
                    b.txtVersion.text = "v${BuildConfig.VERSION_NAME}"
                    if (info == null) { if (manual) toast("최신 버전입니다") }
                    else showUpdateDialog(info)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    b.txtVersion.text = "v${BuildConfig.VERSION_NAME}"
                    if (manual) toast("확인 실패: $msg")
                }
            }
        )
    }

    private fun showUpdateDialog(info: UpdateManager.Info) {
        AlertDialog.Builder(this)
            .setTitle("새 버전 ${info.versionName}")
            .setMessage(if (info.notes.isBlank()) "업데이트가 있습니다." else info.notes)
            .setPositiveButton("업데이트") { _, _ ->
                UpdateManager.downloadAndInstall(this, info) { s -> runOnUiThread { toast(s) } }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_QUICK = "quick"
    }
}
