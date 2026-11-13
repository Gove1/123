package com.example.chapter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chapter.databinding.ActivityMainBinding

/**
 * 스마트 일정 관리 앱 메인 액티비티
 *
 * 13장: 일정 목록 화면(RecyclerView) 및 일정 등록(AddActivity) 이동
 * 14장: 배터리 상태 감지 리시버 및 알림 권한 요청
 * 15장: 앱 종료 후에도 일정 관리하는 포그라운드 서비스(ScheduleService) 실행 및 MP3 배경음 제어
 * 16장: ContentProvider를 통하여 일정 목록을 조회 및 CRUD 수행
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scheduleList = mutableListOf<ScheduleItem>()
    private lateinit var adapter: MyAdapter

    // 14장 배터리 정보 동적 브로드캐스트 리시버
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
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
                            binding.batteryStatusText.text = "AC 전원 충전 중 ($batteryPct%)"
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
    }

    // 13장: AddActivity 실행 및 결과 처리 런처
    private val addScheduleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 새 일정이 등록되었으므로 ContentProvider에서 목록 다시 불러오기
            loadSchedulesFromProvider()
        }
    }

    // 알림 및 포그라운드 서비스 권한 요청 런처 (Android 13+)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "알림 및 백그라운드 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "권한이 거부되어 알림이 정상 동작하지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 권한 확인 (알림 및 카메라/저장소 권한)
        checkAppPermissions()

        // 2. 13장 리사이클러뷰 설정
        setupRecyclerView()

        // 3. 16장 ContentProvider를 통해 저장된 일정 데이터 로드
        loadSchedulesFromProvider()

        // 4. 13장: 일정 등록 화면으로 이동 (+ FAB 버튼)
        binding.mainFab.setOnClickListener {
            val intent = Intent(this, AddActivity::class.java)
            addScheduleLauncher.launch(intent)
        }

        // 5. 15장 서비스 컴포넌트: 앱을 종료해도 일정 관리를 수행하는 포그라운드 서비스 제어
        setupServiceControls()

        // 6. 15장 연계: MP3 배경음 재생/정지 제어
        setupMusicControls()
    }

    override fun onResume() {
        super.onResume()
        // 14장 배터리 상태 감지 브로드캐스트 리시버 등록
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateServiceButtonState()
        loadSchedulesFromProvider()
    }

    override fun onPause() {
        super.onPause()
        // 리시버 등록 해제
        unregisterReceiver(batteryReceiver)
    }

    /**
     * 13장 리사이클러뷰 및 어댑터 초기화
     */
    private fun setupRecyclerView() {
        adapter = MyAdapter(
            scheduleList,
            onItemCheckChanged = { item, isChecked ->
                // 16장 ContentProvider를 통해 DB에 완료 여부 업데이트
                val uri = Uri.withAppendedPath(ScheduleContentProvider.CONTENT_URI, item.id.toString())
                val values = ContentValues().apply {
                    put(DBHelper.COLUMN_IS_DONE, if (isChecked) 1 else 0)
                }
                contentResolver.update(uri, values, null, null)
            },
            onItemDelete = { item ->
                // 16장 ContentProvider를 통해 DB에서 일정 삭제
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

    /**
     * 16장 ContentProvider로부터 일정 목록 조회 (query)
     * 요구사항: "컨텐츠 프로바이더로 저장된 일정을 다른 앱이나 기능에서 구현(조회)하게 만들거야"
     */
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

    /**
     * 15장 서비스 컴포넌트: 앱 종료 후에도 일정 관리하는 백그라운드/포그라운드 서비스 토글
     */
    private fun setupServiceControls() {
        binding.btnToggleService.setOnClickListener {
            val serviceIntent = Intent(this, ScheduleService::class.java)
            if (ScheduleService.isRunning) {
                // 서비스 중지
                serviceIntent.action = ScheduleService.ACTION_STOP_SERVICE
                startService(serviceIntent)
                Toast.makeText(this, "백그라운드 일정 관리 서비스가 중지되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                // 서비스 시작 (Foreground Service)
                serviceIntent.action = ScheduleService.ACTION_START_SERVICE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "백그라운드 일정 관리 서비스가 시작되었습니다.\n(앱을 종료해도 계속 동작합니다)", Toast.LENGTH_LONG).show()
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

    /**
     * 15장 음악 재생 제어: Service에 음악 재생/정지 인텐트 발행
     */
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
            Toast.makeText(this, "[15장 서비스] 알림 배경음악(music.mp3) 재생 시작", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopMusic.setOnClickListener {
            val intent = Intent(this, ScheduleService::class.java).apply {
                action = ScheduleService.ACTION_STOP_MUSIC
            }
            startService(intent)
            Toast.makeText(this, "[15장 서비스] 배경음악 재생 정지", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 필요한 런타임 권한 요청
     */
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
