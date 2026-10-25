package com.example.remotectl.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.example.remotectl.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 앱 내 업데이트. latest.json(GitHub raw)에서 최신 버전 정보를 받아
 * 현재 versionCode 와 비교하고, 더 높으면 APK 를 내려받아 설치 화면을 띄운다.
 *
 * latest.json 형식:
 *   { "versionCode": 3, "versionName": "1.2",
 *     "apkUrl": "https://github.com/.../releases/download/v1.2/app-release.apk",
 *     "notes": "변경 내용" }
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 최신 정보를 확인한다. 콜백은 메인 스레드가 아닐 수 있으니 UI 갱신은 호출측에서 처리.
     * @param onResult 업데이트가 있으면 Info, 없으면 null
     * @param onError  네트워크 등 오류 메시지
     */
    fun check(onResult: (Info?) -> Unit, onError: (String) -> Unit) {
        thread(name = "update-check") {
            try {
                val req = Request.Builder().url(BuildConfig.UPDATE_URL).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { onError("서버 응답 ${resp.code}"); return@thread }
                    val body = resp.body?.string() ?: run { onError("빈 응답"); return@thread }
                    val o = JSONObject(body)
                    val info = Info(
                        versionCode = o.getInt("versionCode"),
                        versionName = o.optString("versionName", "?"),
                        apkUrl = o.getString("apkUrl"),
                        notes = o.optString("notes", "")
                    )
                    if (info.versionCode > BuildConfig.VERSION_CODE) onResult(info) else onResult(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                onError(e.message ?: "확인 실패")
            }
        }
    }

    /**
     * APK 다운로드 후 설치 화면 실행. DownloadManager 로 앱 전용 외부 저장소에 받는다.
     */
    fun downloadAndInstall(context: Context, info: Info, onStatus: (String) -> Unit) {
        // "알 수 없는 앱 설치" 권한이 없으면 먼저 설정으로 유도
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            onStatus("'알 수 없는 앱 설치'를 허용한 뒤 다시 눌러주세요")
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (_: Exception) {}
            return
        }

        val fileName = "update-${info.versionCode}.apk"
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(dir, fileName)
        if (outFile.exists()) outFile.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("원격조종 업데이트 ${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        onStatus("다운로드 중…")
        val id = dm.enqueue(req)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val done = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (done != id) return
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                if (outFile.exists()) install(context, outFile, onStatus)
                else onStatus("다운로드 실패")
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun install(context: Context, apk: File, onStatus: (String) -> Unit) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            onStatus("설치 화면을 엽니다")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            onStatus("설치 실행 실패: ${e.message}")
        }
    }
}
