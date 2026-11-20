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

/**
 * 一行"看着像时间记录、但解析不出来"的原文。
 *
 * 以前只当文字提示列出来，用户还得自己去 md 里找；现在带上日期和行号，
 * 首页会把它红字列进当天列表，点一下就能在录入浮层里改。
 */
data class ProblemLine(
    val date: LocalDate,   // 这行所在的 md 日期
    val lineNo: Int,       // 文件里的行号（从 1 开始，方便自己去核对）
    val raw: String,       // 原始行内容
    val reason: String     // 为什么不行，如"时间数字不合法"
)

/** 解析一段文本的结果：记录 + 文件级提示 + 行级问题。 */
data class ParseResult(
    val entries: List<LogEntry>,
    val warnings: List<String>,
    val problems: List<ProblemLine> = emptyList()
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

/**
 * 从历史日志里统计出来的"常用候选"，用于录入时一键点选。
 * 三个列表都已按出现次数从多到少排好，并只取前若干名。
 */
data class Suggestions(
    val level1: List<String>,
    val level2: List<String>,
    val links: List<String>
) {
    companion object {
        val EMPTY = Suggestions(emptyList(), emptyList(), emptyList())
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

    private val WEEK_NAMES = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 顶栏日期显示：如「2026-11-20 周五」 */
    fun dateWithWeek(d: LocalDate): String = "$d ${WEEK_NAMES[d.dayOfWeek.value]}"
}
