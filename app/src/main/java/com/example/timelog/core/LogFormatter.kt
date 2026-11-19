package com.example.timelog.core

import java.time.LocalDate

/**
 * 把录入界面的字段拼成你既定格式的 md 行。纯逻辑，不依赖 Android。
 *
 * 格式：
 *   - [ ] ==00:00 - 05:30== [[○ 睡觉]] [[♺ 晚睡]] **事项** 备注 [[地点]] [[任务]]
 *
 * 跨午夜时（结束 < 开始，如 23:50-00:20）拆成两行、分属两天，
 * 与 LogParser 的拆分规则严格对称。
 */
object LogFormatter {

    data class Input(
        val date: LocalDate,
        val startMin: Int,
        val endMin: Int,
        val level1: String,
        val level2: String,
        val title: String,
        val note: String,
        val links: List<String> = emptyList()
    )

    /** 返回 (日期, 要追加的行)。正常一条，跨午夜两条。 */
    fun build(input: Input): List<Pair<LocalDate, String>> {
        val l1 = input.level1.trim().ifEmpty { "未分类" }
        val l2 = input.level2.trim()
        val title = input.title.trim().ifEmpty { "-" }
        val note = input.note.trim().replace(Regex("""\s+"""), " ")
        val cleanLinks = input.links.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun line(start: Int, end: Int): String {
            val sb = StringBuilder()
            sb.append("- [ ] ==${LogEntry.fmt(start)} - ${LogEntry.fmt(end)}==")
            sb.append(" [[○ $l1]]")
            if (l2.isNotEmpty()) sb.append(" [[♺ $l2]]")
            sb.append(" **$title**")
            if (note.isNotEmpty()) sb.append(" $note")
            cleanLinks.forEach { sb.append(" [[$it]]") }
            return sb.toString()
        }

        return when {
            input.startMin == input.endMin -> emptyList() // 无效：时长为 0，UI 层会拦截提示
            input.endMin > input.startMin -> listOf(input.date to line(input.startMin, input.endMin))
            else -> {
                // 跨午夜：当天一段必写；次日那段结束为 00:00 时时长为 0，跳过
                val parts = mutableListOf(input.date to line(input.startMin, 1440))
                if (input.endMin > 0) parts.add(input.date.plusDays(1) to line(0, input.endMin))
                parts
            }
        }
    }
}
