package com.example.timelog.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.LogParser
import com.example.timelog.core.ParseResult
import com.example.timelog.core.ProblemLine
import com.example.timelog.core.Suggestions
import java.time.LocalDate

class VaultRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("timelog_prefs", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }
        set(value) {
            prefs.edit().putString("tree_uri", value?.toString()).apply()
        }

    fun root(): DocumentFile? {
        val uri = treeUri ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /**
     * 只读指定那一天（以及前一天，为接住跨午夜落在今天的部分）的 md。
     * 这是"单日统计秒开"的关键：不扫整个目录，最多开两个文件。
     */
    fun loadDay(date: LocalDate): ParseResult {
        val root = root() ?: return ParseResult(emptyList(), listOf("还没有选择日记文件夹"))
        val entries = mutableListOf<LogEntry>()
        val warnings = mutableListOf<String>()
        val problems = mutableListOf<ProblemLine>()
        listOf(date.minusDays(1), date).forEach { d ->
            val file = root.findFile("$d.md") ?: return@forEach
            val text = readText(file) ?: return@forEach
            val result = LogParser.parseFile(file.name ?: "$d.md", text)
            // 前一天文件里，可能含有跨午夜落到 date 的那一条；只要 date 当天的条目
            entries.addAll(result.entries.filter { it.date == date })
            warnings.addAll(result.warnings)
            // 问题行只取当天的：看哪一天的页面就修哪一天的文件，免得串天
            problems.addAll(result.problems.filter { it.date == date })
        }
        return ParseResult(entries.sortedBy { it.startMin }, warnings, problems)
    }

    /** 只读指定日期这同一个文件，用于跨午夜录入时补查"次日"是否已有重叠记录。 */
    fun loadExactDay(date: LocalDate): List<LogEntry> {
        val root = root() ?: return emptyList()
        val file = root.findFile("$date.md") ?: return emptyList()
        val text = readText(file) ?: return emptyList()
        return LogParser.parseFile(file.name ?: "$date.md", text).entries.filter { it.date == date }
    }

    /**
     * 统计常用候选：扫日记根目录第一层的 md（最多最近 365 个，按文件名倒序）。
     * 一级/二级分类取解析结果，双链取 [[...]] 里除分类外的标签。
     * 只读不写，给录入浮层做"一键点选"，避免每次手打名字。
     */
    fun loadSuggestions(): Suggestions {
        val root = root() ?: return Suggestions.EMPTY
        val l1 = mutableMapOf<String, Int>()
        val l2 = mutableMapOf<String, Int>()
        val links = mutableMapOf<String, Int>()

        root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".md") == true }
            .sortedByDescending { it.name.orEmpty() }
            .take(365)
            .forEach { file ->
                val text = readText(file) ?: return@forEach
                LogParser.parseFile(file.name.orEmpty(), text).entries.forEach { e ->
                    if (e.level1.isNotBlank() && e.level1 != "未分类") {
                        l1[e.level1] = (l1[e.level1] ?: 0) + 1
                    }
                    if (e.level2.isNotBlank()) {
                        l2[e.level2] = (l2[e.level2] ?: 0) + 1
                    }
                    e.links.forEach { link ->
                        if (link.isNotBlank()) links[link] = (links[link] ?: 0) + 1
                    }
                }
            }

        fun top(map: Map<String, Int>): List<String> = map.toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(10)
            .map { it.first }

        return Suggestions(top(l1), top(l2), top(links))
    }

    /**
     * 保存一条录入。跨午夜时 LogFormatter 会给出两行（分属两天），
     * 逐行写进各自日期的 md；每行按开始时间插到正确位置，其它内容保持原样。
     */
    fun append(input: LogFormatter.Input): Result<Int> {
        if (root() == null) return Result.failure(IllegalStateException("还没有选择日记文件夹"))
        val lines = LogFormatter.build(input)
        lines.forEach { (date, line) ->
            val r = appendLine(date, line)
            if (r.isFailure) return Result.failure(r.exceptionOrNull() ?: IllegalStateException("写入失败"))
        }
        return Result.success(lines.size)
    }

    private fun appendLine(date: LocalDate, line: String): Result<String> {
        val root = root() ?: return Result.failure(IllegalStateException("还没有选择日记文件夹"))
        val fileName = "$date.md"
        var file = root.findFile(fileName)
        if (file == null) {
            file = root.createFile("text/markdown", fileName)
                ?: return Result.failure(IllegalStateException("创建 $fileName 失败"))
        }
        val existing = readText(file).orEmpty()
        return writeText(file, insertSorted(existing, line), fileName)
    }

    /**
     * 把新记录行按开始时间插进已有记录序列，而不是一律追加到文件末尾。
     * 只重排"记录行"（含 ==HH:MM - HH:MM==），标题、正文、空行都留在原位。
     */
    private fun insertSorted(existing: String, newLine: String): String {
        val lines = splitLines(existing)
        val recordIdx = lines.indices.filter { LogParser.timeOfLine(lines[it]) != null }
        if (recordIdx.isEmpty()) {
            lines.add(newLine)
        } else {
            val ordered = recordIdx.map { lines[it] }.toMutableList()
            ordered.add(newLine)
            ordered.sortWith(
                compareBy<String>(
                    { LogParser.timeOfLine(it)!!.first },
                    { LogParser.timeOfLine(it)!!.second }
                )
            )
            val idxSet = recordIdx.toSet()
            val lastSlot = recordIdx.last()
            val out = mutableListOf<String>()
            var cursor = 0
            lines.forEachIndexed { i, text ->
                if (i in idxSet) {
                    if (cursor < ordered.size) { out.add(ordered[cursor]); cursor++ }
                } else {
                    out.add(text)
                }
                // 新多出来的那一条接在最后一条记录行之后，整体仍然按时间有序
                if (i == lastSlot) {
                    while (cursor < ordered.size) { out.add(ordered[cursor]); cursor++ }
                }
            }
            lines.clear()
            lines.addAll(out)
        }
        return joinLines(lines)
    }

    /**
     * 删除一条记录：按解析时保存的原始行精确匹配，只删那一行。
     * 先在当天文件找；跨午夜记录的那一行写在"前一天"文件里，再回退找前一天。
     */
    fun deleteEntry(date: LocalDate, entry: LogEntry): Result<String> = deleteLine(date, entry.raw)

    /** 删除"某一天文件里的某一整行"（解析失败的问题行也走这里）。 */
    fun deleteLine(date: LocalDate, rawLine: String): Result<String> {
        val root = root() ?: return Result.failure(IllegalStateException("还没有选择日记文件夹"))
        for (d in listOf(date, date.minusDays(1))) {
            val fileName = "$d.md"
            val file = root.findFile(fileName) ?: continue
            val text = readText(file) ?: continue
            val lines = splitLines(text)
            val idx = lines.indexOfFirst { it == rawLine }
            if (idx < 0) continue
            lines.removeAt(idx)
            return writeText(file, joinLines(lines), fileName)
        }
        return Result.failure(IllegalStateException("没在 md 里找到这条记录对应的行"))
    }

    fun replaceEntry(oldDate: LocalDate, old: LogEntry, input: LogFormatter.Input): Result<Int> =
        replaceLine(oldDate, old.raw, input)

    /**
     * 把"某一天文件里的某一行"换成新内容（问题行修正也走这里）。
     *
     * 步骤：先在原文件按原文精确删掉那一行，再把新内容按开始时间插到正确位置；
     * 同一天只写一次文件，换日期或跨午夜就分文件写。
     */
    fun replaceLine(oldDate: LocalDate, rawLine: String, input: LogFormatter.Input): Result<Int> {
        val root = root() ?: return Result.failure(IllegalStateException("还没有选择日记文件夹"))
        val newLines = LogFormatter.build(input)
        if (newLines.isEmpty()) return Result.failure(IllegalStateException("这段时间长度为 0"))

        // 1) 找到旧行所在文件并删掉它（跨午夜那一行可能在前一天）
        var oldFile: DocumentFile? = null
        var oldFileDate: LocalDate? = null
        var oldText: String? = null
        for (d in listOf(oldDate, oldDate.minusDays(1))) {
            val f = root.findFile("$d.md") ?: continue
            val text = readText(f) ?: continue
            val lines = splitLines(text)
            val idx = lines.indexOfFirst { it == rawLine }
            if (idx < 0) continue
            lines.removeAt(idx)
            oldFile = f
            oldFileDate = d
            oldText = joinLines(lines)
            break
        }
        val file = oldFile ?: return Result.failure(IllegalStateException("没在 md 里找到这条记录对应的行"))
        val fileDate = oldFileDate ?: oldDate
        val baseText = oldText.orEmpty()

        // 2) 新内容还在同一天：直接在这份文本上插入，只写一次文件
        if (newLines.size == 1 && newLines[0].first == fileDate) {
            return writeText(file, insertSorted(baseText, newLines[0].second), "$fileDate.md")
                .map { 1 }
        }

        // 3) 换了日期 / 跨午夜：先把删好的原文件写回，再往目标日期插新行
        val w = writeText(file, baseText, "$fileDate.md")
        if (w.isFailure) return w.map { 0 }
        newLines.forEach { (date, line) ->
            val r = appendLine(date, line)
            if (r.isFailure) return Result.failure(r.exceptionOrNull() ?: IllegalStateException("写入失败"))
        }
        return Result.success(newLines.size)
    }

    /** 统一换行符、去掉末尾一个换行后按行拆；写回时补一个换行，避免行尾反复增生。 */
    private fun splitLines(text: String): MutableList<String> {
        val normalized = text.replace("\r\n", "\n")
        val body = if (normalized.endsWith("\n")) normalized.dropLast(1) else normalized
        return if (body.isEmpty()) mutableListOf() else body.split("\n").toMutableList()
    }

    private fun joinLines(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    private fun writeText(file: DocumentFile, text: String, fileName: String): Result<String> {
        return try {
            val out = context.contentResolver.openOutputStream(file.uri, "wt")
                ?: return Result.failure(IllegalStateException("无法写入 $fileName"))
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Result.success(fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readText(file: DocumentFile): String? = try {
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }
}
