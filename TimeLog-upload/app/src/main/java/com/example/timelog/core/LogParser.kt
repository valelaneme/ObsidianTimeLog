package com.example.timelog.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object LogParser {
    private val TASK_RE = Regex("""^\s*-\s*\[([ xX])\]\s*(.*)$""")
    private val TIME_RE = Regex("""==\s*(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})\s*==""")
    private val L1_RE = Regex("""\[\[\s*○\s*([^\]]+)\]\]""")
    private val L2_RE = Regex("""\[\[\s*♺\s*([^\]]+)\]\]""")
    private val BOLD_RE = Regex("""\*\*(.+?)\*\*""")
    private val LINK_RE = Regex("""\[\[([^\]]+)\]\]""")
    private val FILE_DATE_RE = Regex("""(\d{4}-\d{2}-\d{2})""")

    fun parseFile(fileName: String, text: String): ParseResult {
        val dateMatch = FILE_DATE_RE.find(fileName)
        if (dateMatch == null) {
            return ParseResult(emptyList(), listOf("跳过 $fileName：文件名里没有 YYYY-MM-DD 日期"))
        }
        val fileDate = try {
            LocalDate.parse(dateMatch.value, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            return ParseResult(emptyList(), listOf("跳过 $fileName：日期格式不对（${dateMatch.value}）"))
        }

        val entries = mutableListOf<LogEntry>()
        val warnings = mutableListOf<String>()
        text.lines().forEachIndexed { index, line ->
            val parsed = parseLine(line, fileDate)
            when {
                parsed.entries.isNotEmpty() -> entries.addAll(parsed.entries)
                parsed.warnings.isNotEmpty() -> warnings.add("$fileName 第${index + 1}行：${parsed.warnings.joinToString("；")}")
            }
        }
        return ParseResult(entries.sortedWith(compareBy({ it.date }, { it.startMin })), warnings)
    }

    fun parseLine(rawLine: String, fallbackDate: LocalDate): ParseResult {
        val warnings = mutableListOf<String>()
        val task = TASK_RE.find(rawLine)
        if (task == null) return ParseResult(emptyList(), warnings)

        val done = task.groupValues[1].equals("x", ignoreCase = true)
        val body = task.groupValues[2]
        val time = TIME_RE.find(body)
        if (time == null) return ParseResult(emptyList(), listOf("没有找到 ==HH:MM - HH:MM== 时间段"))

        val sh = time.groupValues[1].toInt()
        val sm = time.groupValues[2].toInt()
        val eh = time.groupValues[3].toInt()
        val em = time.groupValues[4].toInt()
        if (sh !in 0..24 || eh !in 0..24 || sm !in 0..59 || em !in 0..59 ||
            (sh == 24 && sm != 0) || (eh == 24 && em != 0)) {
            return ParseResult(emptyList(), listOf("时间数字不合法"))
        }

        val start = sh * 60 + sm
        val end = eh * 60 + em
        if (start == end) return ParseResult(emptyList(), listOf("开始和结束时间相同，时长为 0"))

        val l1 = L1_RE.find(body)?.groupValues?.get(1)?.trim()?.ifEmpty { "未分类" } ?: "未分类"
        val l2 = L2_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val bold = BOLD_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val title = if (bold == "-" || bold.isEmpty()) "" else bold

        val links = LINK_RE.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { !it.startsWith("○") && !it.startsWith("♺") }
            .distinct()
            .toList()

        var rest = body
        listOf(TIME_RE, L1_RE, L2_RE, BOLD_RE, LINK_RE).forEach { rest = rest.replace(it, " ") }
        val note = rest.replace(Regex("""\s+"""), " ").trim()

        val segments = mutableListOf<LogEntry>()
        if (end > start) {
            segments.add(LogEntry(fallbackDate, start, end, l1, l2, title, note, links, done, rawLine))
        } else {
            segments.add(LogEntry(fallbackDate, start, 1440, l1, l2, title, note, links, done, rawLine))
            segments.add(LogEntry(fallbackDate.plusDays(1), 0, end, l1, l2, title, note, links, done, rawLine))
            warnings.add("跨午夜，已按 24 点拆成两天")
        }
        return ParseResult(segments, warnings)
    }
}
