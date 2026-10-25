package com.example.remotectl

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.client.ClientActivity
import com.example.remotectl.databinding.ActivityMainBinding
import com.example.remotectl.host.HostActivity

/** 실행 시 역할(조종 / 피조종)을 고른다. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnController.setOnClickListener {
            // A 폰: 다른 폰을 본다/조종한다
            startActivity(Intent(this, ClientActivity::class.java))
        }
        b.btnControlled.setOnClickListener {
            // B 폰: 내 화면을 공유하고 조종을 허용한다
            startActivity(Intent(this, HostActivity::class.java))
        }
    }
}
