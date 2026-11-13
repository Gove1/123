package com.example.chapter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 14장 컴포넌트: BroadcastReceiver
 * 요구사항: "브로드캐스트 리시버로 설정한 시간이 되면 알림 이벤트 수신"
 * AlarmManager에 의해 예약된 시간에 브로드캐스트를 수신하여 상단 바에 알림(Notification)을 띄우고,
 * 배터리 상태 변경(14장 연계) 이벤트도 처리합니다.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ScheduleReceiver"
        const val ACTION_SCHEDULE_ALARM = "com.example.chapter.ACTION_SCHEDULE_ALARM"
        const val EXTRA_TITLE = "extra_schedule_title"
        const val EXTRA_MEMO = "extra_schedule_memo"
        const val EXTRA_TIME = "extra_schedule_time"
        const val EXTRA_ID = "extra_schedule_id"

        const val CHANNEL_ID = "schedule_alarm_channel"
        const val CHANNEL_NAME = "스마트 일정 알림"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "브로드캐스트 수신됨: $action")

        when (action) {
            ACTION_SCHEDULE_ALARM -> {
                // 1. 설정한 시간에 도달하여 알림 이벤트 수신
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "일정 알림"
                val memo = intent.getStringExtra(EXTRA_MEMO) ?: "등록된 일정을 확인하세요."
                val time = intent.getStringExtra(EXTRA_TIME) ?: ""
                val id = intent.getLongExtra(EXTRA_ID, System.currentTimeMillis()).toInt()

                showNotification(context, id, title, "$time $memo")

                // 15장 음악 재생 연계: 알림 발생 시 음악 재생 서비스 호출 가능
                val serviceIntent = Intent(context, ScheduleService::class.java).apply {
                    this.action = ScheduleService.ACTION_PLAY_ALARM_SOUND
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            // 14장 배터리 상태 연계
            Intent.ACTION_BATTERY_LOW -> {
                Toast.makeText(context, "[14장 배터리 리시버] 배터리가 부족합니다! 절전 모드를 권장합니다.", Toast.LENGTH_LONG).show()
            }
            Intent.ACTION_POWER_CONNECTED -> {
                Toast.makeText(context, "[14장 배터리 리시버] 충전기가 연결되었습니다.", Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Toast.makeText(context, "[14장 배터리 리시버] 충전기가 분리되었습니다.", Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "기기 부팅 완료: 서비스 및 알람 재등록 필요")
            }
        }
    }

    /**
     * 14장 교재 스타일의 NotificationChannel 및 Notification 생성
     */
    private fun showNotification(context: Context, notiId: Int, title: String, contentText: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder: NotificationCompat.Builder

        // 알림 클릭 시 MainActivity를 열도록 PendingIntent 설정
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notiId,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0 (API 26) 이상: 알림 채널 생성 및 사운드/진동 등록
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "설정한 일정 시간에 알림을 발송하는 채널입니다."
                setShowBadge(true)
                val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(soundUri, audioAttributes)
                enableVibration(true)
            }

            manager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, CHANNEL_ID)
        } else {
            // Android 8.0 미만
            builder = NotificationCompat.Builder(context)
        }

        builder.apply {
            setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            setWhen(System.currentTimeMillis())
            setContentTitle("⏰ [스마트 일정] $title")
            setContentText(contentText)
            setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            setContentIntent(pendingIntent)
            setAutoCancel(true)
            setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        manager.notify(notiId, builder.build())
    }
}
