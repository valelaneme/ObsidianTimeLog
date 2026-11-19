package com.example.timelog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 倒计时到点：系统闹钟唤起它，弹一条通知。
 * 通知里带上"这次计时对应的起止时间"，点开 App 后自动回填到录入面板。
 *
 * 靠系统闹钟触发，不依赖 App 保活——红米把 App 杀了照样弹。
 */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val startHm = intent.getStringExtra(EXTRA_START) ?: return
        val endHm = intent.getStringExtra(EXTRA_END) ?: return

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_START, startHm)
            putExtra(EXTRA_END, endHm)
            putExtra(EXTRA_FROM_TIMER, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, 0, open, piFlags)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "倒计时提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "时间日志倒计时到点提醒" }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("时间到 · ${endHm}")
            .setContentText("从 ${startHm} 到 ${endHm}，点这里填进日志")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Android 13+ 通知须运行时授权；没授权时不能 notify，否则抛异常
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }
        nm.notify(NOTIFY_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "timelog_countdown"
        const val EXTRA_START = "extra_start_hm"
        const val EXTRA_END = "extra_end_hm"
        const val EXTRA_FROM_TIMER = "extra_from_timer"
        private const val NOTIFY_ID = 1001
    }
}
