package com.example.timelog.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object LogParser {

    /** 解析不出来的行预填用：字段都是"能认出多少算多少"。 */
    data class PartialLine(
        val startHm: String,
        val endHm: String,
        val level1: String,
        val level2: String,
        val title: String,
        val links: List<String>,
        val note: String
    )

    private val TASK_RE = Regex("""^\s*-\s*\[([ xX])\]\s*(.*)$""")
    private val TIME_RE = Regex("""==\s*(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})\s*==""")
    private val L1_RE = Regex("""\[\[\s*○\s*([^\]]+)\]\]""")
    private val L2_RE = Regex("""\[\[\s*♺\s*([^\]]+)\]\]""")
    private val BOLD_RE = Regex("""\*\*(.+?)\*\*""")
    private val LINK_RE = Regex("""\[\[([^\]]+)\]\]""")
    // 宽松时间：解析失败时用来预填，不管合不合法先把数值抠出来
    private val LOOSE_TIME_RE = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val FILE_DATE_RE = Regex("""(\d{4}-\d{2}-\d{2})""")

    /**
     * 从一行 md 里读出 (开始分钟, 结束分钟)；不是记录行返回 null。
     * 写入时用它判断"哪些行是时间记录"并按时间排序，与解析共用同一套正则和校验口径。
     */
    fun timeOfLine(raw: String): Pair<Int, Int>? {
        if (TASK_RE.find(raw) == null) return null
        val m = TIME_RE.find(raw) ?: return null
        val sh = m.groupValues[1].toIntOrNull() ?: return null
        val sm = m.groupValues[2].toIntOrNull() ?: return null
        val eh = m.groupValues[3].toIntOrNull() ?: return null
        val em = m.groupValues[4].toIntOrNull() ?: return null
        if (sh !in 0..24 || eh !in 0..24 || sm !in 0..59 || em !in 0..59 ||
            (sh == 24 && sm != 0) || (eh == 24 && em != 0)) return null
        return (sh * 60 + sm) to (eh * 60 + em)
    }

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
        val problems = mutableListOf<ProblemLine>()
        text.lines().forEachIndexed { index, line ->
            val parsed = parseLine(line, fileDate)
            when {
                parsed.entries.isNotEmpty() -> entries.addAll(parsed.entries)
                // 是任务行但解析不出来 → 记成"问题行"，交给首页红字列出、点开修
                parsed.warnings.isNotEmpty() -> problems.add(
                    ProblemLine(fileDate, index + 1, line, parsed.warnings.joinToString("；"))
                )
            }
        }
        return ParseResult(
            entries.sortedWith(compareBy({ it.date }, { it.startMin })),
            warnings,
            problems
        )
    }

    /**
     * 解析不出来的一行，也尽量把能认出来的字段抠出来，供用户修改时预填。
     * 时间认不出就返回空串；认出来但是非法（如 25:30）则原样返回，让用户一眼看到错在哪。
     */
    fun partial(rawLine: String): PartialLine {
        val task = TASK_RE.find(rawLine)
        val body = task?.groupValues?.get(2) ?: rawLine
        val strict = TIME_RE.find(body)
        val loose = LOOSE_TIME_RE.findAll(body)
            .map { padHm(it.groupValues[1], it.groupValues[2]) }
            .toList()

        val startHm = when {
            strict != null -> padHm(strict.groupValues[1], strict.groupValues[2])
            loose.isNotEmpty() -> loose[0]
            else -> ""
        }
        val endHm = when {
            strict != null -> padHm(strict.groupValues[3], strict.groupValues[4])
            loose.size > 1 -> loose[1]
            else -> ""
        }

        val l1 = L1_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val l2 = L2_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val bold = BOLD_RE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val links = LINK_RE.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { !it.startsWith("○") && !it.startsWith("♺") }
            .distinct()
            .toList()
        var rest = body
        listOf(TIME_RE, L1_RE, L2_RE, BOLD_RE, LINK_RE).forEach { rest = rest.replace(it, " ") }
        val note = rest.replace(Regex("""\s+"""), " ").trim()

        return PartialLine(
            startHm = startHm,
            endHm = endHm,
            level1 = if (l1 == "未分类") "" else l1,
            level2 = l2,
            title = if (bold == "-") "" else bold,
            links = links,
            note = note
        )
    }

    private fun padHm(h: String, m: String): String {
        val hi = h.toIntOrNull() ?: return "$h:$m"
        val mi = m.toIntOrNull() ?: return "$h:$m"
        return "%02d:%02d".format(hi, mi)
    }

    fun parseLine(rawLine: String, fallbackDate: LocalDate): ParseResult {
        val warnings = mutableListOf<String>()
        val task = TASK_RE.find(rawLine)
        if (task == null) return ParseResult(emptyList(), warnings)

        val done = task.groupValues[1].equals("x", ignoreCase = true)
        val body = task.groupValues[2]
        val time = TIME_RE.find(body)
        if (time == null) {
            // 带 == 或像时间的，说明用户本想写时间段但格式坏了 → 记成问题行，红字提醒
            // 完全没有时间样式的普通待办（如 "- [ ] 买牛奶"）不是时间记录，静默跳过
            val likelyTime = body.contains("==") || LOOSE_TIME_RE.find(body) != null
            return if (likelyTime) {
                ParseResult(emptyList(), listOf("时间段格式不对，需要写成 ==09:00 - 10:00=="))
            } else {
                ParseResult(emptyList(), emptyList())
            }
        }

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
