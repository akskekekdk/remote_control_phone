package com.example.remotectl.client

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.remotectl.Protocol
import com.example.remotectl.Prefs
import com.example.remotectl.databinding.ActivityClientBinding
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.abs

/** A(조종) 화면: B의 화면을 보고 터치로 조종. */
class ClientActivity : AppCompatActivity() {

    private lateinit var b: ActivityClientBinding

    @Volatile private var surface: Surface? = null
    private val surfaceLatch = CountDownLatch(1)

    @Volatile private var socket: Socket? = null
    @Volatile private var dout: DataOutputStream? = null
    @Volatile private var connected = false

    private var videoW = 0
    private var videoH = 0
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityClientBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 지난 연결값 자동 채움
        b.editIp.setText(prefs.get(Prefs.WIFI_IP))
        b.editPin.setText(prefs.get(Prefs.WIFI_PIN))
        val hasSaved = prefs.has(Prefs.WIFI_IP) && prefs.has(Prefs.WIFI_PIN)
        b.btnQuick.visibility = if (hasSaved) android.view.View.VISIBLE else android.view.View.GONE
        b.btnQuick.text = "지난 연결로 바로 접속 (${prefs.get(Prefs.WIFI_IP)})"
        b.btnQuick.setOnClickListener { b.btnConnect.performClick() }

        b.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                surfaceLatch.countDown()
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
        })

        b.btnConnect.setOnClickListener {
            val ip = b.editIp.text.toString().trim()
            val pin = b.editPin.text.toString().trim()
            if (ip.isEmpty()) { toast("B 폰의 IP를 입력하세요"); return@setOnClickListener }
            if (pin.isEmpty()) { toast("연결 PIN을 입력하세요"); return@setOnClickListener }
            prefs.put(Prefs.LAST_MODE, "wifi")
            prefs.put(Prefs.WIFI_IP, ip)
            prefs.put(Prefs.WIFI_PIN, pin)
            com.example.remotectl.widget.RemoteWidgetProvider.updateAll(this)
            connect(ip, pin)
        }

        b.btnBack.setOnClickListener { sendKey(Protocol.KEY_BACK) }
        b.btnHome.setOnClickListener { sendKey(Protocol.KEY_HOME) }
        b.btnRecents.setOnClickListener { sendKey(Protocol.KEY_RECENTS) }

        setupTouch()

        if (intent?.getBooleanExtra(EXTRA_AUTOCONNECT, false) == true && hasSaved) {
            b.root.post { b.btnConnect.performClick() }
        }
    }

    private fun connect(ip: String, pin: String) {
        if (connected) return
        b.txtStatus.text = "연결 중…"
        thread(name = "rc-client") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, Protocol.PORT), 5000)
                s.tcpNoDelay = true
                socket = s
                val o = DataOutputStream(s.getOutputStream().buffered())
                val din = DataInputStream(s.getInputStream().buffered())

                // 인증: PIN 전송 후 응답 확인
                Protocol.writePacket(o, Protocol.TYPE_AUTH, pin.toByteArray())
                val resp = Protocol.readPacket(din)
                if (resp.type != Protocol.TYPE_AUTH_OK) {
                    runOnUiThread { b.txtStatus.text = "PIN이 올바르지 않습니다" }
                    return@thread
                }

                dout = o
                connected = true
                runOnUiThread {
                    b.txtStatus.text = "연결됨"
                    b.overlayConnect.visibility = View.GONE
                }
                readLoop(din)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                runOnUiThread { b.txtStatus.text = "연결 실패: ${e.message}" }
            } finally {
                cleanup()
            }
        }
    }

    private var decoder: VideoDecoder? = null

    private fun readLoop(din: DataInputStream) {
        while (connected) {
            val p = Protocol.readPacket(din)
            when (p.type) {
                Protocol.TYPE_META -> {
                    val parts = String(p.payload).split(",")
                    videoW = parts[0].toInt()
                    videoH = parts[1].toInt()
                    runOnUiThread { fitSurface(videoW, videoH) }
                }
                Protocol.TYPE_VIDEO_CONFIG -> {
                    surfaceLatch.await()
                    val s = surface ?: continue
                    decoder?.release()
                    decoder = VideoDecoder(s, videoW, videoH, p.payload).also { it.start() }
                }
                Protocol.TYPE_VIDEO -> {
                    // 앞 8바이트 = pts
                    val d = decoder ?: continue
                    var pts = 0L
                    for (i in 0 until 8) pts = (pts shl 8) or (p.payload[i].toLong() and 0xff)
                    d.feed(p.payload, 8, p.payload.size - 8, pts)
                }
            }
        }
    }

    /** 영상 종횡비에 맞춰 SurfaceView 크기 조정 (레터박스) */
    private fun fitSurface(vw: Int, vh: Int) {
        val container = b.surfaceContainer
        container.post {
            val cw = container.width.toFloat()
            val ch = container.height.toFloat()
            if (cw == 0f || ch == 0f) return@post
            val scale = minOf(cw / vw, ch / vh)
            val lp = b.surface.layoutParams as FrameLayout.LayoutParams
            lp.width = (vw * scale).toInt()
            lp.height = (vh * scale).toInt()
            lp.gravity = android.view.Gravity.CENTER
            b.surface.layoutParams = lp
        }
    }

    // ---- 터치 → 입력 전송 ----
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        b.surface.setOnTouchListener { v, e ->
            val nx = (e.x / v.width).coerceIn(0f, 1f)
            val ny = (e.y / v.height).coerceIn(0f, 1f)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = nx; downY = ny; downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - downTime
                    val moved = abs(nx - downX) > 0.02f || abs(ny - downY) > 0.02f
                    if (moved) {
                        sendSwipe(downX, downY, nx, ny, dt.toInt().coerceIn(60, 2000))
                    } else if (dt > 500) {
                        sendLongPress(downX, downY)
                    } else {
                        sendTap(nx, ny)
                    }
                }
            }
            true
        }
    }

    private fun sendTap(x: Float, y: Float) = sendInput {
        it.writeInt(Protocol.TYPE_TAP); it.writeInt(8); it.writeFloat(x); it.writeFloat(y)
    }
    private fun sendLongPress(x: Float, y: Float) = sendInput {
        it.writeInt(Protocol.TYPE_LONG_PRESS); it.writeInt(8); it.writeFloat(x); it.writeFloat(y)
    }
    private fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Int) = sendInput {
        it.writeInt(Protocol.TYPE_SWIPE); it.writeInt(20)
        it.writeFloat(x1); it.writeFloat(y1); it.writeFloat(x2); it.writeFloat(y2); it.writeInt(dur)
    }
    private fun sendKey(code: Int) = sendInput {
        it.writeInt(Protocol.TYPE_KEY); it.writeInt(4); it.writeInt(code)
    }

    private inline fun sendInput(crossinline body: (DataOutputStream) -> Unit) {
        val o = dout ?: return
        thread {
            try { synchronized(o) { body(o); o.flush() } }
            catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
        }
    }

    private fun cleanup() {
        connected = false
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; dout = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "RcClient"
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }
}
