package com.example.timelog.data

import android.content.Context

/**
 * 录入提速用的轻量偏好：记住上次填过的分类、事项和常用双链。
 * 只存几个字符串，用 SharedPreferences 足够，不引入数据库。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("timelog_input", Context.MODE_PRIVATE)

    var level1: String
        get() = sp.getString(KEY_L1, "").orEmpty()
        set(value) = sp.edit().putString(KEY_L1, value).apply()

    var level2: String
        get() = sp.getString(KEY_L2, "").orEmpty()
        set(value) = sp.edit().putString(KEY_L2, value).apply()

    var title: String
        get() = sp.getString(KEY_TITLE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TITLE, value).apply()

    var links: String
        get() = sp.getString(KEY_LINKS, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LINKS, value).apply()

    companion object {
        private const val KEY_L1 = "level1"
        private const val KEY_L2 = "level2"
        private const val KEY_TITLE = "title"
        private const val KEY_LINKS = "links"
    }
}
