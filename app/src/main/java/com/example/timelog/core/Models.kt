package com.example.timelog.core

import java.time.LocalDate

/**
 * 一条时间日志记录。
 * 由 LogParser 从 md 的一行解析而来；跨午夜时一行会拆成两条（分属两天）。
 */
data class LogEntry(
    val date: LocalDate,
    val startMin: Int,
    val endMin: Int,
    val level1: String,
    val level2: String,
    val title: String,
    val note: String,
    val links: List<String>,
    val done: Boolean,
    val raw: String
) {
    val minutes: Int get() = endMin - startMin

    fun timeRange(): String = "${fmt(startMin)} - ${fmt(endMin)}"

    companion object {
        fun fmt(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}

/** 两段时间重叠的报告。 */
data class Overlap(
    val date: LocalDate,
    val first: LogEntry,
    val second: LogEntry,
    val minutes: Int
)

/** 解析一段文本的结果：记录 + 提示。 */
data class ParseResult(
    val entries: List<LogEntry>,
    val warnings: List<String>
)

/** 分类统计（展示用），percent 由该分类时长 / 总时长得到。 */
data class CategoryStat(
    val label: String,
    val minutes: Int,
    val percent: Double
)

/**
 * 单日小计——整个系统的数据原子。
 *
 * 设计要点：它既能表示"一天"，多个 DaySummary 相加又能得到"一周/一月/一年"。
 * 所以未来的周/月/年统计不需要新的数据结构和新的加总逻辑，只需要 combine()。
 * days 字段用于覆盖率分母（单日为 1，聚合后为跨过的天数）。
 */
data class DaySummary(
    val date: LocalDate,
    val days: Int,
    val entries: Int,
    val totalMinutes: Int,
    val level1Minutes: Map<String, Int>,
    val level2Minutes: Map<String, Int>,
    val overlaps: Int
) {
    /** 覆盖率：记录总时长 /（天数 × 24h），睡眠计入，记满为 100%。 */
    val coveragePercent: Double
        get() = if (days == 0) 0.0 else totalMinutes * 100.0 / (days * 1440.0)

    val byLevel1: List<CategoryStat> get() = toStats(level1Minutes)
    val byLevel2: List<CategoryStat> get() = toStats(level2Minutes)

    private fun toStats(map: Map<String, Int>): List<CategoryStat> {
        if (totalMinutes == 0) return emptyList()
        return map.toList()
            .filter { it.second > 0 }
            .map { (label, minutes) -> CategoryStat(label, minutes, minutes * 100.0 / totalMinutes) }
            .sortedByDescending { it.minutes }
    }

    companion object {
        val EMPTY = DaySummary(
            date = LocalDate.of(1970, 1, 1),
            days = 0,
            entries = 0,
            totalMinutes = 0,
            level1Minutes = emptyMap(),
            level2Minutes = emptyMap(),
            overlaps = 0
        )
    }
}

object Formatters {
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}分"
            m == 0 -> "${h}小时"
            else -> "${h}小时${m}分"
        }
    }

    fun percent(value: Double): String = String.format("%.1f%%", value)
}
