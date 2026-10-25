package com.example.remotectl

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.client.ClientActivity
import com.example.remotectl.databinding.ActivityMainBinding
import com.example.remotectl.host.HostActivity
import com.example.remotectl.webrtc.WebRtcClientActivity
import com.example.remotectl.webrtc.WebRtcHostActivity

/** 실행 시 연결 모드(와이파이 / 인터넷)와 역할(조종 / 피조종)을 고른다. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

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
    }
}
