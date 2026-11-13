package com.example.chapter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 일정 데이터를 저장하고 관리하는 SQLiteOpenHelper
 * ContentProvider에서 데이터베이스를 연동할 때 사용됩니다.
 */
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
        val createTableSql = """
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
        db.execSQL(createTableSql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }
}
