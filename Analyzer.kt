package com.example.timelog.core

import java.time.LocalDate

/**
 * 分析层——纯逻辑，输入永远是 List<LogEntry> / List<DaySummary>，不碰文件、不碰 UI。
 *
 * 这一版只用 analyzeDay（单日）。combine 已备好：未来的周/月/年统计
 * 只需把那段日期的 DaySummary 收集起来调用它，无需新增分析逻辑。
 */
object Analyzer {

    /** 单日重叠校验：同一天按开始时间排序，前一条结束 > 后一条开始即重叠。 */
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

    /**
     * 把某一天的记录算成 DaySummary（数据原子）。
     * 覆盖率分母 = 1 天 × 24h，睡眠计入。
     */
    fun analyzeDay(all: List<LogEntry>, date: LocalDate): DaySummary {
        val day = all.filter { it.date == date }
        val total = day.sumOf { maxOf(0, it.minutes) }
        val l1 = mutableMapOf<String, Int>()
        val l2 = mutableMapOf<String, Int>()
        day.forEach { e ->
            l1[e.level1] = (l1[e.level1] ?: 0) + maxOf(0, e.minutes)
            val key2 = e.level2.ifEmpty { "（未填二级）" }
            l2[key2] = (l2[key2] ?: 0) + maxOf(0, e.minutes)
        }
        return DaySummary(
            date = date,
            days = 1,
            entries = day.size,
            totalMinutes = total,
            level1Minutes = l1,
            level2Minutes = l2,
            overlaps = overlaps(day).size
        )
    }

    /**
     * 聚合若干 DaySummary 为一个周期的统计（周 / 月 / 年通用）。
     *
     * 这是为下一阶段预留的唯一扩展点：
     *   周分析 = combine(该周 7 天的 DaySummary, days = 7)
     *   月分析 = combine(该月各天的 DaySummary, days = 当月天数)
     * 加总逻辑只有这一处，不随周期种类增加而增长。
     *
     * @param summaries 要聚合的日小计
     * @param days 覆盖天数，用于覆盖率分母；<=0 时按 summaries 数量推算
     */
    fun combine(summaries: List<DaySummary>, days: Int = 0): DaySummary {
        if (summaries.isEmpty()) return DaySummary.EMPTY
        val total = summaries.sumOf { it.totalMinutes }
        val l1 = mutableMapOf<String, Int>()
        val l2 = mutableMapOf<String, Int>()
        summaries.forEach { s ->
            s.level1Minutes.forEach { (k, v) -> l1[k] = (l1[k] ?: 0) + v }
            s.level2Minutes.forEach { (k, v) -> l2[k] = (l2[k] ?: 0) + v }
        }
        return DaySummary(
            date = summaries.first().date,
            days = if (days > 0) days else summaries.size,
            entries = summaries.sumOf { it.entries },
            totalMinutes = total,
            level1Minutes = l1,
            level2Minutes = l2,
            overlaps = summaries.sumOf { it.overlaps }
        )
    }
}
