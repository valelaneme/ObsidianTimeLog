package com.example.timelog.core

import java.time.LocalDate

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
        fun fmt(minuteOfDay: Int): String {
            val h = minuteOfDay / 60
            val m = minuteOfDay % 60
            return "%02d:%02d".format(h, m)
        }
    }
}

data class Overlap(
    val date: LocalDate,
    val first: LogEntry,
    val second: LogEntry,
    val minutes: Int
)

data class ParseResult(
    val entries: List<LogEntry>,
    val warnings: List<String>
)

enum class Period { DAY, WEEK, MONTH, YEAR }

data class CategoryStat(
    val label: String,
    val minutes: Int,
    val count: Int,
    val percent: Double
)

data class AnalysisResult(
    val periodLabel: String,
    val days: Int,
    val entries: Int,
    val totalMinutes: Int,
    val coveragePercent: Double,
    val byLevel1: List<CategoryStat>,
    val byLevel2: List<CategoryStat>,
    val overlaps: Int
)

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
}
