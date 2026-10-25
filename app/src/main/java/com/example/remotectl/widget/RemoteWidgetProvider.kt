package com.example.remotectl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.remotectl.MainActivity
import com.example.remotectl.Prefs
import com.example.remotectl.R

/** 홈 화면 위젯: 탭하면 최근 연결로 바로 접속. */
class RemoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, mgr, id)
    }

    companion object {
        /** 모든 위젯 인스턴스를 갱신 (연결값이 바뀌었을 때 호출) */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, RemoteWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, mgr, it) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = Prefs(context)
            val label = when (prefs.get(Prefs.LAST_MODE)) {
                "internet" -> if (prefs.has(Prefs.NET_CODE)) "코드 ${prefs.get(Prefs.NET_CODE)}" else "설정 필요"
                "wifi" -> if (prefs.has(Prefs.WIFI_IP)) prefs.get(Prefs.WIFI_IP) else "설정 필요"
                else -> "설정 필요"
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_QUICK, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_remote).apply {
                setTextViewText(R.id.widgetSub, label)
                setOnClickPendingIntent(R.id.widgetRoot, pi)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}
