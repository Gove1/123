package com.example.chapter

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

/**
 * 16장 컴포넌트: ContentProvider
 * 요구사항: "컨텐츠 프로바이더로 저장된 일정을 다른 앱이나 기능에서 구현(제공)하게 만들거야"
 * 앱 내부뿐만 아니라 외부 앱에서도 일정 데이터를 CRUD할 수 있도록 표준 URI 인터페이스를 제공합니다.
 */
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
        val db: SQLiteDatabase = dbHelper.readableDatabase
        val cursor: Cursor

        when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> {
                cursor = db.query(
                    DBHelper.TABLE_NAME,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    sortOrder ?: "${DBHelper.COLUMN_ID} DESC"
                )
            }
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                val finalSelection = if (selection.isNullOrEmpty()) {
                    "${DBHelper.COLUMN_ID} = ?"
                } else {
                    "${DBHelper.COLUMN_ID} = ? AND ($selection)"
                }
                val finalArgs = if (selectionArgs == null) {
                    arrayOf(id ?: "")
                } else {
                    arrayOf(id ?: "", *selectionArgs)
                }
                cursor = db.query(
                    DBHelper.TABLE_NAME,
                    projection,
                    finalSelection,
                    finalArgs,
                    null,
                    null,
                    sortOrder
                )
            }
            else -> throw IllegalArgumentException("알 수 없는 URI: $uri")
        }

        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != CODE_SCHEDULES) {
            throw IllegalArgumentException("잘못된 삽입 URI: $uri")
        }
        val db: SQLiteDatabase = dbHelper.writableDatabase
        val id = db.insert(DBHelper.TABLE_NAME, null, values)
        if (id > 0) {
            val itemUri = ContentUris.withAppendedId(CONTENT_URI, id)
            context?.contentResolver?.notifyChange(uri, null)
            return itemUri
        }
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val db: SQLiteDatabase = dbHelper.writableDatabase
        val count: Int
        when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> {
                count = db.update(DBHelper.TABLE_NAME, values, selection, selectionArgs)
            }
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                val finalSelection = "${DBHelper.COLUMN_ID} = ?"
                count = db.update(DBHelper.TABLE_NAME, values, finalSelection, arrayOf(id ?: ""))
            }
            else -> throw IllegalArgumentException("알 수 없는 수정 URI: $uri")
        }
        if (count > 0) {
            context?.contentResolver?.notifyChange(uri, null)
        }
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val db: SQLiteDatabase = dbHelper.writableDatabase
        val count: Int
        when (uriMatcher.match(uri)) {
            CODE_SCHEDULES -> {
                count = db.delete(DBHelper.TABLE_NAME, selection, selectionArgs)
            }
            CODE_SCHEDULE_ID -> {
                val id = uri.lastPathSegment
                count = db.delete(DBHelper.TABLE_NAME, "${DBHelper.COLUMN_ID} = ?", arrayOf(id ?: ""))
            }
            else -> throw IllegalArgumentException("알 수 없는 삭제 URI: $uri")
        }
        if (count > 0) {
            context?.contentResolver?.notifyChange(uri, null)
        }
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
