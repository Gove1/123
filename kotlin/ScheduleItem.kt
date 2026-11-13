package com.example.chapter

import java.io.Serializable

/**
 * 스마트 일정 관리 앱의 일정 데이터 모델 클래스
 */
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
