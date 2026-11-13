package com.example.chapter

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.chapter.databinding.ActivityAddBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 13장 컴포넌트: Activity (일정 등록 화면)
 * 16장 갤러리 및 카메라 사진 연동 (FileProvider & MediaStore)
 * 14장 브로드캐스트 리시버와 연동하기 위한 AlarmManager 스케줄 등록
 * 16장 ContentProvider를 통한 일정 DB 삽입
 */
class AddActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddBinding

    // 선택된 날짜 및 시간 저장
    private var selectedCalendar: Calendar = Calendar.getInstance()
    private var selectedDateStr: String = ""
    private var selectedTimeStr: String = ""

    // 16장 사진 연계: 파일 경로 및 URI
    private var attachedFilePath: String? = null
    private var photoUri: Uri? = null

    // 16장 갤러리 실행 런처
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                attachedFilePath = uri.toString()
                try {
                    val inSampleSize = calculateInSampleSize(uri, 150, 150)
                    val options = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                    }
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

    // 16장 카메라 실행 런처
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            attachedFilePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val uri = Uri.fromFile(file)
                    val inSampleSize = calculateInSampleSize(uri, 150, 150)
                    val options = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                    }
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

        // 기본 날짜/시간 초기화
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        selectedDateStr = dateFormat.format(selectedCalendar.time)
        selectedTimeStr = timeFormat.format(selectedCalendar.time)

        binding.tvSelectedDate.text = selectedDateStr
        binding.tvSelectedTime.text = selectedTimeStr

        // 1. 날짜 선택 다이얼로그
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

        // 2. 시간 선택 다이얼로그
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

        // 3. 16장 연계: 갤러리 앱 연동
        binding.galleryButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            galleryLauncher.launch(intent)
        }

        // 4. 16장 연계: 카메라 앱 연동
        binding.cameraButton.setOnClickListener {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
            attachedFilePath = file.absolutePath

            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }
            cameraLauncher.launch(intent)
        }

        // 5. 저장 버튼 리스너
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

    /**
     * 일정 저장 로직:
     * 1) 16장 ContentProvider에 insert
     * 2) 14장 BroadcastReceiver로 알림 이벤트 전달을 위한 AlarmManager 등록
     */
    private fun saveSchedule() {
        val title = binding.addEditView.text.toString().trim()
        val memo = binding.addMemoView.text.toString().trim()
        val hasAlarm = binding.switchAlarm.isChecked

        if (title.isEmpty()) {
            Toast.makeText(this, "일정 제목을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. ContentProvider를 통해 DB에 저장
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

        // 2. 알림 예약 (14장 BroadcastReceiver 연동)
        if (hasAlarm) {
            registerAlarm(scheduleId, title, memo, selectedCalendar.timeInMillis)
        }

        Toast.makeText(this, "일정이 등록되었습니다.", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    /**
     * 14장 리시버로 설정한 시간에 알림을 보내도록 AlarmManager 등록
     */
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

        // 과거 시간이 아닐 때만 등록 (단, 테스트 편의를 위해 과거 시간인 경우 5초 후 알림)
        val alarmTime = if (triggerAtMillis <= System.currentTimeMillis()) {
            System.currentTimeMillis() + 5000 // 5초 뒤 즉시 울림
        } else {
            triggerAtMillis
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent)
        }
        Log.d("AddActivity", "알람 예약 완료: $alarmTime (ID: $id)")
    }

    /**
     * 16장 교재의 이미지 샘플링 비율 계산 함수
     */
    private fun calculateInSampleSize(fileUri: Uri, reqWidth: Int, reqHeight: Int): Int {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
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
