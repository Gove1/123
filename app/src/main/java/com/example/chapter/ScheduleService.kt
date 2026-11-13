package com.example.chapter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 15장 컴포넌트: Service (Foreground Service)
 * 요구사항: "서비스 컴포넌트로 앱을 종료해도 일정 관리를 하고"
 * 포그라운드 서비스(Foreground Service)로 구동되어 앱이 종료되어도 백그라운드에서
 * 일정을 지속적으로 모니터링하고, 15장의 MP3 음악 재생(music.mp3) 기능도 제공합니다.
 */
class ScheduleService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var scheduler: ScheduledExecutorService? = null

    companion object {
        const val TAG = "ScheduleService"
        const val CHANNEL_ID = "schedule_foreground_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_PLAY_MUSIC = "ACTION_PLAY_MUSIC"
        const val ACTION_STOP_MUSIC = "ACTION_STOP_MUSIC"
        const val ACTION_PLAY_ALARM_SOUND = "ACTION_PLAY_ALARM_SOUND"

        var isRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScheduleService onCreate() 호출됨")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE
        Log.d(TAG, "onStartCommand 액션: $action")

        when (action) {
            ACTION_START_SERVICE -> {
                isRunning = true
                val notification = createForegroundNotification("백그라운드에서 스마트 일정을 관리 중입니다.")
                startForeground(NOTIFICATION_ID, notification)
                startScheduleMonitoring()
            }
            ACTION_STOP_SERVICE -> {
                isRunning = false
                stopMonitoring()
                stopMusic()
                stopForeground(true)
                stopSelf()
            }
            ACTION_PLAY_MUSIC, ACTION_PLAY_ALARM_SOUND -> {
                playMusic()
            }
            ACTION_STOP_MUSIC -> {
                stopMusic()
            }
        }

        // 앱이 비정상 종료되더라도 시스템이 서비스를 자동 재생성하도록 START_STICKY 반환
        return START_STICKY
    }

    /**
     * 앱이 종료되어도 백그라운드에서 ContentProvider를 통해 일정을 주기적으로 모니터링
     */
    private fun startScheduleMonitoring() {
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleAtFixedRate({
                try {
                    // ContentProvider를 조회하여 남은 일정 수 파악
                    val cursor: Cursor? = contentResolver.query(
                        ScheduleContentProvider.CONTENT_URI,
                        arrayOf(DBHelper.COLUMN_ID, DBHelper.COLUMN_TITLE, DBHelper.COLUMN_IS_DONE),
                        "${DBHelper.COLUMN_IS_DONE} = 0",
                        null,
                        null
                    )
                    val uncompletedCount = cursor?.count ?: 0
                    cursor?.close()

                    Log.d(TAG, "[백그라운드 서비스] 실시간 일정 모니터링: 남은 일정 ${uncompletedCount}개")

                    // 포그라운드 알림 내용 갱신
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(
                        NOTIFICATION_ID,
                        createForegroundNotification("남은 미완료 일정: ${uncompletedCount}개 (모니터링 중)")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "일정 모니터링 중 에러", e)
                }
            }, 0, 30, TimeUnit.SECONDS)
        }
    }

    private fun stopMonitoring() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    /**
     * 15장 연계: raw/music.mp3 음악 재생 (알람 또는 배경음)
     */
    private fun playMusic() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, R.raw.music).apply {
                    isLooping = true
                    setOnCompletionListener {
                        Log.d(TAG, "음악 재생 완료")
                    }
                }
            }
            if (!mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
                Log.d(TAG, "MP3 음악 재생 시작")
            }
        } catch (e: Exception) {
            Log.e(TAG, "음악 재생 오류", e)
        }
    }

    private fun stopMusic() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
            mediaPlayer = null
            Log.d(TAG, "MP3 음악 재생 중지")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "스마트 일정 백그라운드 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "앱 종료 후에도 백그라운드에서 일정 관리를 유지하기 위한 서비스 채널"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(contentText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, ScheduleService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("스마트 일정 관리 서비스 실행 중")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "서비스 종료", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopMonitoring()
        stopMusic()
        Log.d(TAG, "ScheduleService onDestroy() 호출됨")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
