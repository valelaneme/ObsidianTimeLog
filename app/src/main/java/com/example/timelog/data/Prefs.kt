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

    /**
     * 计时状态存"绝对时刻"（毫秒时间戳），不是累加的秒数。
     * 这样锁屏、切到别的 App、甚至进程被杀后重开，用 当前时刻 - 开始时刻 依然准确。
     */
    var stopwatchStart: Long
        get() = sp.getLong(KEY_STOPWATCH, 0L)
        set(value) = sp.edit().putLong(KEY_STOPWATCH, value).apply()

    var countdownEnd: Long
        get() = sp.getLong(KEY_COUNTDOWN_END, 0L)
        set(value) = sp.edit().putLong(KEY_COUNTDOWN_END, value).apply()

    /**
     * 倒计时默认分钟数（整数）。设置页可改，点"倒计时"时用它预填，
     * 改过之后下次还用这个数，不用每次重打。
     */
    var countdownMinutes: Int
        get() = sp.getInt(KEY_COUNTDOWN_MINUTES, DEFAULT_COUNTDOWN_MINUTES)
        set(value) = sp.edit().putInt(KEY_COUNTDOWN_MINUTES, value).apply()

    companion object {
        const val DEFAULT_COUNTDOWN_MINUTES = 25
        private const val KEY_L1 = "level1"
        private const val KEY_L2 = "level2"
        private const val KEY_TITLE = "title"
        private const val KEY_LINKS = "links"
        private const val KEY_STOPWATCH = "stopwatch_start"
        private const val KEY_COUNTDOWN_END = "countdown_end"
        private const val KEY_COUNTDOWN_MINUTES = "countdown_minutes"
    }
}
