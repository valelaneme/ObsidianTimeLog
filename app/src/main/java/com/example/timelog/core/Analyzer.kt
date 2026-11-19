package com.example.timelog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

object Analyzer {

    fun overlaps(entries: List<LogEntry>): List<Overlap> {
        val result = mutableListOf<Overlap>()
        entries.groupBy { it.date }.forEach { (_, dayEntries) ->
            val sorted = dayEntries.sortedBy { it.startMin }
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    val a = sorted[i]
                    val b = sorted[j]
                    val minutes = minOf(a.endMin, b.endMin) - b.startMin
                    if (minutes > 0) result.add(Overlap(a.date, a, b, minutes))
                }
            }
        }
        return result.sortedBy { it.date }
    }

    fun analyze(all: List<LogEntry>, period: Period, anchor: LocalDate): AnalysisResult {
        val (from, to, days, label) = when (period) {
            Period.DAY -> {
                val d = anchor
                Quad(d, d, 1, "日分析 ${d}")
            }
            Period.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val sunday = monday.plusDays(6)
                Quad(monday, sunday, 7, "周分析 $monday ~ $sunday")
            }
            Period.MONTH -> {
                val ym = YearMonth.from(anchor)
                Quad(ym.atDay(1), ym.atEndOfMonth(), ym.lengthOfMonth(), "月分析 $ym")
            }
            Period.YEAR -> {
                Quad(
                    LocalDate.of(anchor.year, 1, 1),
                    LocalDate.of(anchor.year, 12, 31),
                    if (anchor.isLeapYear) 366 else 365,
                    "年分析 ${anchor.year}"
                )
            }
        }

        val inRange = all.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val total = inRange.sumOf { maxOf(0, it.minutes) }
        val coverage = if (days == 0) 0.0 else (total.toDouble() / (days * 1440.0) * 100.0)

        fun statBy(key: (LogEntry) -> String): List<CategoryStat> {
            if (total == 0) return emptyList()
            return inRange.groupBy(key).map { (name, list) ->
                val m = list.sumOf { maxOf(0, it.minutes) }
                CategoryStat(name, m, list.size, m * 100.0 / total)
            }.sortedByDescending { it.minutes }
        }

        return AnalysisResult(
            periodLabel = label,
            days = days,
            entries = inRange.size,
            totalMinutes = total,
            coveragePercent = coverage,
            byLevel1 = statBy { it.level1 },
            byLevel2 = statBy { if (it.level2.isEmpty()) "（未填二级）" else it.level2 },
            overlaps = overlaps(inRange).size
        )
    }

    private data class Quad(
        val from: LocalDate,
        val to: LocalDate,
        val days: Int,
        val label: String
    )
}
