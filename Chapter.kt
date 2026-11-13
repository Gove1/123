package com.example.chapter

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chapter.databinding.ActivityAddBinding
import com.example.chapter.databinding.ActivityMainBinding
import com.example.chapter.databinding.ItemRecyclerviewBinding
import java.io.File
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * =========================================================================================
 * [스마트 일정 관리 통합 앱 (Smart Schedule Manager)]
 *
 * 13장: 액티비티 컴포넌트 (MainActivity - 일정 목록 & AddActivity - 일정 등록 화면, RecyclerView 연동)
 * 14장: 브로드캐스트 리시버 (ScheduleReceiver - 설정한 시간 도달 시 푸시 알림 수신, 배터리 상태 감지)
 * 15장: 서비스 컴포넌트 (ScheduleService - Foreground Service로 앱 종료 후에도 일정 관리, music.mp3 재생)
 * 16장: 콘텐츠 프로바이더 (ScheduleContentProvider - 일정을 DB에 저장하고 외부/내부 앱에서 데이터 제공)
 * =========================================================================================
 */

// =========================================================================================
// [1] 데이터 모델 클래스
// =========================================================================================
data class ScheduleItem(
    var id: Long = 0,
    var title: String = "",
    var date: String = "",
    var time: String = "",
    var memo: String = "",
    var imagePath: String? = null,
    var isDone: Boolean = false,
    var hasAlarm: Boolean = true
) : Serializable


// =========================================================================================
// [2] SQLite 데이터베이스 헬퍼
// =========================================================================================
class DBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "ScheduleDB.db"
        const val DATABASE_VERSION = 1

        const val TABLE_NAME = "schedules"
        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DATE = "date"
        const val COLUMN_TIME = "time"
        const val COLUMN_MEMO = "memo"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_IS_DONE = "is_done"
        const val COLUMN_HAS_ALARM = "has_alarm"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_DATE TEXT,
                $COLUMN_TIME TEXT,
                $COLUMN_MEMO TEXT,
                $COLUMN_IMAGE_PATH TEXT,
                $COLUMN_IS_DONE INTEGER DEFAULT 0,
                $COLUMN_HAS_ALARM INTEGER DEFAULT 1
            );
        """.trimIndent()
        db.execSQL(createSql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }
}


// =========================================================================================
// [3] 16장 컴포넌트: ContentProvider
// 요구사항: "컨텐츠 프로바이더로 저장된 일정을 다른 앱이나 기능에서 구현하게 만들거야"
// =========================================================================================
class ScheduleContentProvider : ContentProvider() {

    private lateinit var dbHelper: DBHelper

    companion object {
        const val AUTHORITY = "com.example.chapter.provider"
        const val BASE_PATH = "schedules"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$BASE_PATH")

        private const val CODE_SCHEDULES = 1
        private const val CODE_SCHEDULE_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, BASE_PATH, CODE_SCHEDULES)
            addURI(AUTHORITY, "$BASE_PATH/#", CODE_SCHEDULE_ID)
        }
    }

    override fun onCreate(): Boolean {
        context?.let {
            dbHelper = DBHelper(it)
            return true
        }
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val db = dbHelper.readableDatabase
        val cursor = when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> db.query(
                DBHelper.TABLE_NAME, projection, selection, selectionArgs, null, null,
                sortOrder ?: "${DBHelper.COLUMN_ID} DESC"
            )
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                val finalSelection = if (selection.isNullOrEmpty()) "${DBHelper.COLUMN_ID} = ?" else "${DBHelper.COLUMN_ID} = ? AND ($selection)"
                val finalArgs = if (selectionArgs == null) arrayOf(id ?: "") else arrayOf(id ?: "", *selectionArgs)
                db.query(DBHelper.TABLE_NAME, projection, finalSelection, finalArgs, null, null, sortOrder)
            }
            else -> throw IllegalArgumentException("알 수 없는 URI: $uri")
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != CODE_SCHEDULES) throw IllegalArgumentException("잘못된 URI: $uri")
        val db = dbHelper.writableDatabase
        val id = db.insert(DBHelper.TABLE_NAME, null, values)
        if (id > 0) {
            val itemUri = ContentUris.withAppendedId(CONTENT_URI, id)
            context?.contentResolver?.notifyChange(uri, null)
            return itemUri
        }
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val db = dbHelper.writableDatabase
        val count = when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> db.update(DBHelper.TABLE_NAME, values, selection, selectionArgs)
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                db.update(DBHelper.TABLE_NAME, values, "${DBHelper.COLUMN_ID} = ?", arrayOf(id ?: ""))
            }
            else -> throw IllegalArgumentException("잘못된 URI: $uri")
        }
        if (count > 0) context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val db = dbHelper.writableDatabase
        val count = when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> db.delete(DBHelper.TABLE_NAME, selection, selectionArgs)
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                db.delete(DBHelper.TABLE_NAME, "${DBHelper.COLUMN_ID} = ?", arrayOf(id ?: ""))
            }
            else -> throw IllegalArgumentException("잘못된 URI: $uri")
        }
        if (count > 0) context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$BASE_PATH"
            CODE_SCHEDULE_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.$BASE_PATH"
            else -> null
        }
    }
}


// =========================================================================================
// [4] 14장 컴포넌트: BroadcastReceiver
// 요구사항: "브로드캐스트 리시버로 설정한 시간이 되면 알림 이벤트 수신"
// =========================================================================================
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
        Log.d(TAG, "브로드캐스트 리시버 수신됨: $action")

        when (action) {
            ACTION_SCHEDULE_ALARM -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "일정 알림"
                val memo = intent.getStringExtra(EXTRA_MEMO) ?: "등록된 일정을 확인하세요."
                val time = intent.getStringExtra(EXTRA_TIME) ?: ""
                val id = intent.getLongExtra(EXTRA_ID, System.currentTimeMillis()).toInt()

                // 상단 바 노티피케이션 알림 발송
                showNotification(context, id, title, "$time $memo")

                // 15장 음악 재생 연계: 알림 발생 시 음악 재생 서비스 호출
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
                Toast.makeText(context, "[배터리 리시버] 배터리가 부족합니다! 절전 모드를 권장합니다.", Toast.LENGTH_LONG).show()
            }
            Intent.ACTION_POWER_CONNECTED -> {
                Toast.makeText(context, "[배터리 리시버] 충전기가 연결되었습니다.", Toast.LENGTH_SHORT).show()
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Toast.makeText(context, "[배터리 리시버] 충전기가 분리되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNotification(context: Context, notiId: Int, title: String, contentText: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder: NotificationCompat.Builder

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


// =========================================================================================
// [5] 15장 컴포넌트: Service (Foreground Service)
// 요구사항: "서비스 컴포넌트로 앱을 종료해도 일정 관리를 하고"
// =========================================================================================
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE

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
            ACTION_PLAY_MUSIC, ACTION_PLAY_ALARM_SOUND -> playMusic()
            ACTION_STOP_MUSIC -> stopMusic()
        }

        return START_STICKY
    }

    private fun startScheduleMonitoring() {
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleAtFixedRate({
                try {
                    val cursor: Cursor? = contentResolver.query(
                        ScheduleContentProvider.CONTENT_URI,
                        arrayOf(DBHelper.COLUMN_ID, DBHelper.COLUMN_TITLE, DBHelper.COLUMN_IS_DONE),
                        "${DBHelper.COLUMN_IS_DONE} = 0",
                        null,
                        null
                    )
                    val uncompletedCount = cursor?.count ?: 0
                    cursor?.close()

                    Log.d(TAG, "[백그라운드 서비스] 실시간 일정 모니터링: 미완료 일정 ${uncompletedCount}개")

                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(
                        NOTIFICATION_ID,
                        createForegroundNotification("남은 미완료 일정: ${uncompletedCount}개 (모니터링 중)")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "일정 모니터링 에러", e)
                }
            }, 0, 30, TimeUnit.SECONDS)
        }
    }

    private fun stopMonitoring() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun playMusic() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, R.raw.music).apply {
                    isLooping = true
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
            if (it.isPlaying) it.stop()
            it.release()
            mediaPlayer = null
            Log.d(TAG, "MP3 음악 재생 정지")
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


// =========================================================================================
// [6] 13장 어댑터: RecyclerView Adapter
// =========================================================================================
class MyViewHolder(val binding: ItemRecyclerviewBinding) : RecyclerView.ViewHolder(binding.root)

class MyAdapter(
    private val datas: MutableList<ScheduleItem>,
    private val onItemCheckChanged: (ScheduleItem, Boolean) -> Unit,
    private val onItemDelete: (ScheduleItem) -> Unit
) : RecyclerView.Adapter<MyViewHolder>() {

    override fun getItemCount(): Int = datas.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemRecyclerviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val item = datas[position]
        val binding = holder.binding

        binding.itemTitle.text = item.title
        if (item.isDone) {
            binding.itemTitle.paintFlags = binding.itemTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.itemTitle.alpha = 0.5f
        } else {
            binding.itemTitle.paintFlags = binding.itemTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.itemTitle.alpha = 1.0f
        }

        binding.itemDatetime.text = "${item.date} ${item.time}"
        binding.itemAlarmBadge.visibility = if (item.hasAlarm) View.VISIBLE else View.GONE

        binding.itemCheckbox.setOnCheckedChangeListener(null)
        binding.itemCheckbox.isChecked = item.isDone
        binding.itemCheckbox.setOnCheckedChangeListener { _, isChecked ->
            item.isDone = isChecked
            onItemCheckChanged(item, isChecked)
            notifyItemChanged(position)
        }

        if (!item.imagePath.isNullOrEmpty()) {
            val file = File(item.imagePath!!)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.itemImage.setImageBitmap(bitmap)
            } else {
                try {
                    val uri = Uri.parse(item.imagePath)
                    binding.itemImage.setImageURI(uri)
                } catch (e: Exception) {
                    binding.itemImage.setImageResource(R.drawable.todo)
                }
            }
        } else {
            binding.itemImage.setImageResource(R.drawable.todo)
        }

        binding.itemDeleteBtn.setOnClickListener {
            onItemDelete(item)
        }
    }
}


// =========================================================================================
// [7] 13장 컴포넌트: AddActivity (일정 등록 액티비티)
// 16장 갤러리/카메라 연동 + 14장 AlarmManager 알람 등록 + 16장 ContentProvider 저장
// =========================================================================================
class AddActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddBinding

    private var selectedCalendar: Calendar = Calendar.getInstance()
    private var selectedDateStr: String = ""
    private var selectedTimeStr: String = ""

    private var attachedFilePath: String? = null
    private var photoUri: Uri? = null

    // 16장 갤러리 런처
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                attachedFilePath = uri.toString()
                try {
                    val inSampleSize = calculateInSampleSize(uri, 150, 150)
                    val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()
                    binding.userImageView.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    binding.userImageView.setImageURI(uri)
                }
            }
        }
    }

    // 16장 카메라 런처
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            attachedFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val uri = Uri.fromFile(file)
                    val inSampleSize = calculateInSampleSize(uri, 150, 150)
                    val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    val bitmap = BitmapFactory.decodeFile(path, options)
                    binding.userImageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        selectedDateStr = dateFormat.format(selectedCalendar.time)
        selectedTimeStr = timeFormat.format(selectedCalendar.time)

        binding.tvSelectedDate.text = selectedDateStr
        binding.tvSelectedTime.text = selectedTimeStr

        binding.btnSelectDate.setOnClickListener {
            val c = selectedCalendar
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                c.set(Calendar.YEAR, year)
                c.set(Calendar.MONTH, month)
                c.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                selectedDateStr = dateFormat.format(c.time)
                binding.tvSelectedDate.text = selectedDateStr
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnSelectTime.setOnClickListener {
            val c = selectedCalendar
            TimePickerDialog(this, { _, hourOfDay, minute ->
                c.set(Calendar.HOUR_OF_DAY, hourOfDay)
                c.set(Calendar.MINUTE, minute)
                c.set(Calendar.SECOND, 0)
                selectedTimeStr = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                binding.tvSelectedTime.text = selectedTimeStr
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }

        binding.galleryButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            galleryLauncher.launch(intent)
        }

        binding.cameraButton.setOnClickListener {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
            attachedFilePath = file.absolutePath

            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }
            cameraLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            saveSchedule()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_add, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add_save -> {
                saveSchedule()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveSchedule() {
        val title = binding.addEditView.text.toString().trim()
        val memo = binding.addMemoView.text.toString().trim()
        val hasAlarm = binding.switchAlarm.isChecked

        if (title.isEmpty()) {
            Toast.makeText(this, "일정 제목을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. ContentProvider에 일정 등록
        val values = ContentValues().apply {
            put(DBHelper.COLUMN_TITLE, title)
            put(DBHelper.COLUMN_DATE, selectedDateStr)
            put(DBHelper.COLUMN_TIME, selectedTimeStr)
            put(DBHelper.COLUMN_MEMO, memo)
            put(DBHelper.COLUMN_IMAGE_PATH, attachedFilePath)
            put(DBHelper.COLUMN_IS_DONE, 0)
            put(DBHelper.COLUMN_HAS_ALARM, if (hasAlarm) 1 else 0)
        }

        val newUri = contentResolver.insert(ScheduleContentProvider.CONTENT_URI, values)
        val scheduleId = newUri?.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis()

        // 2. AlarmManager를 통해 14장 브로드캐스트 리시버에 알림 등록
        if (hasAlarm) {
            registerAlarm(scheduleId, title, memo, selectedCalendar.timeInMillis)
        }

        Toast.makeText(this, "일정이 등록되었습니다.", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun registerAlarm(id: Long, title: String, memo: String, triggerAtMillis: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_SCHEDULE_ALARM
            putExtra(ScheduleReceiver.EXTRA_ID, id)
            putExtra(ScheduleReceiver.EXTRA_TITLE, title)
            putExtra(ScheduleReceiver.EXTRA_MEMO, memo)
            putExtra(ScheduleReceiver.EXTRA_TIME, "$selectedDateStr $selectedTimeStr")
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val alarmTime = if (triggerAtMillis <= System.currentTimeMillis()) System.currentTimeMillis() + 5000 else triggerAtMillis

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        }
    }

    private fun calculateInSampleSize(fileUri: Uri, reqWidth: Int, reqHeight: Int): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}


// =========================================================================================
// [8] 13장 컴포넌트: MainActivity (일정 목록 및 통합 제어 액티비티)
// =========================================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scheduleList = mutableListOf<ScheduleItem>()
    private lateinit var adapter: MyAdapter

    // 14장 배터리 상태 감지 리시버
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

                val chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val isUsbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
                val isAcCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC

                if (isCharging) {
                    if (isUsbCharge) {
                        binding.batteryIcon.setImageResource(R.drawable.usb)
                        binding.batteryStatusText.text = "USB 충전 중 ($batteryPct%)"
                    } else if (isAcCharge) {
                        binding.batteryIcon.setImageResource(R.drawable.ac)
                        binding.batteryStatusText.text = "AC 충전 중 ($batteryPct%)"
                    } else {
                        binding.batteryIcon.setImageResource(R.drawable.ac)
                        binding.batteryStatusText.text = "충전 중 ($batteryPct%)"
                    }
                } else {
                    binding.batteryIcon.setImageResource(R.drawable.todo)
                    binding.batteryStatusText.text = "배터리 잔량: $batteryPct%"
                }
            }
        }
    }

    private val addScheduleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            loadSchedulesFromProvider()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            Toast.makeText(this, "알림 권한을 허용해야 알림을 받을 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAppPermissions()
        setupRecyclerView()
        loadSchedulesFromProvider()

        binding.mainFab.setOnClickListener {
            val intent = Intent(this, AddActivity::class.java)
            addScheduleLauncher.launch(intent)
        }

        setupServiceControls()
        setupMusicControls()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateServiceButtonState()
        loadSchedulesFromProvider()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
    }

    private fun setupRecyclerView() {
        adapter = MyAdapter(
            scheduleList,
            onItemCheckChanged = { item, isChecked ->
                val uri = Uri.withAppendedPath(ScheduleContentProvider.CONTENT_URI, item.id.toString())
                val values = ContentValues().apply {
                    put(DBHelper.COLUMN_IS_DONE, if (isChecked) 1 else 0)
                }
                contentResolver.update(uri, values, null, null)
            },
            onItemDelete = { item ->
                val uri = Uri.withAppendedPath(ScheduleContentProvider.CONTENT_URI, item.id.toString())
                contentResolver.delete(uri, null, null)
                loadSchedulesFromProvider()
                Toast.makeText(this, "일정이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
        )
        binding.mainRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.mainRecyclerView.adapter = adapter
        binding.mainRecyclerView.addItemDecoration(
            DividerItemDecoration(this, LinearLayoutManager.VERTICAL)
        )
    }

    private fun loadSchedulesFromProvider() {
        scheduleList.clear()
        val cursor: Cursor? = contentResolver.query(
            ScheduleContentProvider.CONTENT_URI,
            null,
            null,
            null,
            "${DBHelper.COLUMN_DATE} ASC, ${DBHelper.COLUMN_TIME} ASC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_ID)
            val titleIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_TITLE)
            val dateIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_DATE)
            val timeIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_TIME)
            val memoIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_MEMO)
            val imageIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_IMAGE_PATH)
            val isDoneIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_IS_DONE)
            val hasAlarmIndex = it.getColumnIndexOrThrow(DBHelper.COLUMN_HAS_ALARM)

            while (it.moveToNext()) {
                val item = ScheduleItem(
                    id = it.getLong(idIndex),
                    title = it.getString(titleIndex),
                    date = it.getString(dateIndex) ?: "",
                    time = it.getString(timeIndex) ?: "",
                    memo = it.getString(memoIndex) ?: "",
                    imagePath = it.getString(imageIndex),
                    isDone = it.getInt(isDoneIndex) == 1,
                    hasAlarm = it.getInt(hasAlarmIndex) == 1
                )
                scheduleList.add(item)
            }
        }

        adapter.notifyDataSetChanged()
        binding.emptyTextView.visibility = if (scheduleList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupServiceControls() {
        binding.btnToggleService.setOnClickListener {
            val serviceIntent = Intent(this, ScheduleService::class.java)
            if (ScheduleService.isRunning) {
                serviceIntent.action = ScheduleService.ACTION_STOP_SERVICE
                startService(serviceIntent)
                Toast.makeText(this, "백그라운드 일정을 관리 서비스가 중지되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                serviceIntent.action = ScheduleService.ACTION_START_SERVICE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "백그라운드 일정을 관리 서비스가 시작되었습니다.\n(앱을 종료해도 유지됩니다)", Toast.LENGTH_LONG).show()
            }
            binding.root.postDelayed({ updateServiceButtonState() }, 300)
        }
    }

    private fun updateServiceButtonState() {
        if (ScheduleService.isRunning) {
            binding.btnToggleService.text = "백그라운드 서비스 ON (동작 중)"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            binding.btnToggleService.text = "백그라운드 서비스 OFF (시작)"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    private fun setupMusicControls() {
        binding.btnPlayMusic.setOnClickListener {
            val intent = Intent(this, ScheduleService::class.java).apply {
                action = ScheduleService.ACTION_PLAY_MUSIC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "[15장 서비스] music.mp3 음악 재생 시작", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopMusic.setOnClickListener {
            val intent = Intent(this, ScheduleService::class.java).apply {
                action = ScheduleService.ACTION_STOP_MUSIC
            }
            startService(intent)
            Toast.makeText(this, "[15장 서비스] 음악 재생 정지", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAppPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
