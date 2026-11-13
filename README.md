# 스마트 일정 관리 앱 (Smart Schedule Manager) 통합 가이드

본 프로젝트는 안드로이드 4대 컴포넌트(Activity, BroadcastReceiver, Service, ContentProvider)와 관련된 13장, 14장, 15장, 16장의 실습 기능을 하나의 완성된 **스마트 일정 관리 앱**으로 통합 구현한 프로젝트입니다.

---

## 📌 4대 컴포넌트 통합 구조

| 컴포넌트 | 해당 장 | 역할 및 연계 내용 | 주요 클래스 / 파일 |
| :--- | :---: | :--- | :--- |
| **Activity** | **13장** | • 일정 목록 표시 화면 (`RecyclerView`)<br>• 새 일정 등록 화면 (`AddActivity`)<br>• 일정 완료 체크 및 삭제 액션 | `MainActivity.kt`<br>`AddActivity.kt`<br>`MyAdapter.kt`<br>`activity_main.xml`<br>`activity_add.xml` |
| **BroadcastReceiver** | **14장** | • `AlarmManager`와 연동하여 사용자가 설정한 시간에 도달 시 알림 수신<br>• 상단 바 푸시 알림(`NotificationChannel` & `NotificationCompat`) 발송<br>• 배터리 충전/잔량 상태 실시간 수신 및 UI 반영 | `ScheduleReceiver.kt`<br>`drawable/ac.jpg`<br>`drawable/usb.jpg` |
| **Service** | **15장** | • 사용자가 앱을 종료해도 백그라운드에서 일정 모니터링 유지 (`Foreground Service`)<br>• 알람 발생 시 또는 제어 바를 통해 MP3 배경음(`music.mp3`) 재생/정지 | `ScheduleService.kt`<br>`raw/music.mp3`<br>`drawable/ic_play.png`<br>`drawable/ic_stop.png` |
| **ContentProvider** | **16장** | • SQLite DB(`schedules` 테이블)를 구축하고 외부 앱 및 자체 앱에 데이터 제공 (CRUD)<br>• 갤러리 및 카메라 사진 촬영(`FileProvider`)을 통해 일정에 이미지 첨부 | `ScheduleContentProvider.kt`<br>`DBHelper.kt`<br>`res/xml/file_paths.xml` |

---

## 📂 파일 및 디렉토리 구조

```
Chapter/
├── AndroidManifest.xml          # 4대 컴포넌트 및 필요한 권한 선언
├── Chapter.kt                   # 전체 코틀린 코드를 하나로 모아둔 통합 파일
├── kotlin/
│   ├── ScheduleItem.kt          # 일정 데이터 모델
│   ├── DBHelper.kt              # SQLiteOpenHelper 데이터베이스 정의
│   ├── ScheduleContentProvider.kt # 16장: 컨텐츠 프로바이더 (CRUD 제공)
│   ├── ScheduleReceiver.kt      # 14장: 브로드캐스트 리시버 (시간 도달 알림 & 배터리)
│   ├── ScheduleService.kt       # 15장: 포그라운드 서비스 (앱 종료 후에도 백그라운드 관리 & 음악)
│   ├── MyAdapter.kt             # 13장: 리사이클러뷰 어댑터 (체크박스, 사진, 삭제)
│   ├── AddActivity.kt           # 13장 & 16장: 일정 등록 액티비티 (날짜/시간, 사진 첨부)
│   └── MainActivity.kt          # 13장: 메인 일정 목록 액티비티 & 통합 제어
├── res/
│   ├── layout/
│   │   ├── activity_main.xml    # 메인 화면 레이아웃 (목록, 배터리바, 서비스토글)
│   │   ├── activity_add.xml     # 등록 화면 레이아웃 (날짜/시간, 알림설정, 카메라/갤러리)
│   │   └── item_recyclerview.xml# 리스트 아이템 레이아웃 (체크박스, 이미지, 타이틀, 시간, 삭제)
│   ├── menu/
│   │   └── menu_add.xml         # 일정 등록 저장 액션 메뉴
│   ├── raw/
│   │   └── music.mp3            # 15장: 알람/배경 음악 파일
│   ├── drawable/                # 13~16장 이미지 (todo.jpg, ac.jpg, usb.jpg, ic_play, ic_stop 등)
│   ├── values/                  # colors.xml, strings.xml, themes.xml, dimens.xml
│   └── xml/
│       └── file_paths.xml       # 16장: 카메라 촬영 사진 공유용 FileProvider 경로
└── README.md                    # 본 설명 문서
```

---

## 💡 핵심 동작 및 연동 상세

### 1. 일정 등록 및 목록 (13장 Activity + RecyclerView)
- **일정 등록 (`AddActivity`)**:
  - `DatePickerDialog`와 `TimePickerDialog`를 통해 알림 받을 날짜와 시간을 직관적으로 선택합니다.
  - 16장의 갤러리(`Intent.ACTION_PICK`) 및 카메라(`MediaStore.ACTION_IMAGE_CAPTURE`)를 연동하여 일정에 관련된 사진을 첨부할 수 있습니다.
  - 저장 시 `ContentResolver.insert()`를 통해 **ContentProvider**에 데이터를 저장하고, `AlarmManager`를 통해 **BroadcastReceiver**에 알람을 예약합니다.
- **일정 목록 (`MainActivity`)**:
  - `ContentResolver.query()`로 ContentProvider에서 저장된 일정 목록을 불러와 `RecyclerView`로 표시합니다.
  - 항목의 체크박스를 클릭하면 완료 표시 및 취소선 효과가 적용되며, DB의 `is_done` 컬럼이 업데이트됩니다.
  - 휴지통 아이콘 클릭 시 DB에서 일정이 삭제됩니다.

### 2. 설정한 시간에 알림 이벤트 수신 (14장 BroadcastReceiver)
- `AddActivity`에서 알림 스위치를 켠 상태로 일정을 저장하면 `AlarmManager.setExactAndAllowWhileIdle()`을 호출하여 해당 시간에 브로드캐스트 인텐트를 발송합니다.
- `ScheduleReceiver`의 `onReceive()`에서 이벤트를 수신하여:
  1. `NotificationChannel` (API 26+)을 생성하고 소리 및 진동을 활성화합니다.
  2. `NotificationCompat.Builder`로 상단 바 푸시 알림을 띄웁니다.
  3. 알림 클릭 시 메인 화면으로 이동합니다.
  4. 알림 수신 시 15장의 `ScheduleService`에 신호를 보내 `music.mp3` 음악을 울릴 수도 있습니다.
- 추가로 14장의 배터리 상태 리시버(`ACTION_BATTERY_CHANGED`)가 결합되어 상단 바에 현재 배터리 잔량 및 충전 상태(USB/AC)를 실시간 아이콘으로 표시합니다.

### 3. 앱 종료 후에도 일정 관리 유지 (15장 Service)
- 상단의 **[백그라운드 서비스 ON]** 버튼을 누르면 포그라운드 서비스(`ScheduleService`)가 구동됩니다.
- 포그라운드 서비스는 지속적인 노티피케이션(`startForeground`)을 상단에 띄우기 때문에 **사용자가 앱 화면을 닫거나 홈 화면으로 나가더라도 프로세스가 종료되지 않고 백그라운드에서 살아있습니다.**
- 서비스 내부의 주기적 백그라운드 태스크(30초 단위)가 ContentProvider를 통해 남은 미완료 일정을 감시하고 상단 알림에 "남은 미완료 일정: N개"를 실시간 갱신합니다.
- 15장 음악 재생 기능(`music.mp3`)을 서비스에서 직접 제어할 수 있는 플레이어 기능도 탑재되어 있습니다.

### 4. 다른 앱이나 기능에서 일정 데이터 사용 (16장 ContentProvider)
- `ScheduleContentProvider`는 `AndroidManifest.xml`에 `android:exported="true"`로 등록되어 다른 서드파티 앱이나 모듈에서도 시스템의 `ContentResolver`를 통해 이 앱의 일정을 조회/추가/수정/삭제할 수 있습니다.
- **Content URI**: `content://com.example.chapter.provider/schedules`

#### [다른 앱에서 이 앱의 일정 데이터를 조회하는 예제 코드]
```kotlin
val uri = Uri.parse("content://com.example.chapter.provider/schedules")
val cursor = contentResolver.query(uri, null, null, null, null)

cursor?.use {
    val titleIndex = it.getColumnIndexOrThrow("title")
    val dateIndex = it.getColumnIndexOrThrow("date")
    val timeIndex = it.getColumnIndexOrThrow("time")
    
    while (it.moveToNext()) {
        val title = it.getString(titleIndex)
        val date = it.getString(dateIndex)
        val time = it.getString(timeIndex)
        Log.d("ExternalApp", "일정 불러오기 성공: $title ($date $time)")
    }
}
```

#### [다른 앱에서 이 앱에 새 일정을 등록하는 예제 코드]
```kotlin
val uri = Uri.parse("content://com.example.chapter.provider/schedules")
val values = ContentValues().apply {
    put("title", "외부 앱에서 추가한 회의")
    put("date", "2026-11-20")
    put("time", "14:00")
    put("memo", "ContentProvider 연동 테스트")
    put("has_alarm", 1)
}
val newUri = contentResolver.insert(uri, values)
Log.d("ExternalApp", "새 일정 삽입 완료: $newUri")
```
