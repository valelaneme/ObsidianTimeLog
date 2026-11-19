package com.example.timelog.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.LogParser
import com.example.timelog.core.ParseResult
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
        listOf(date.minusDays(1), date).forEach { d ->
            val file = root.findFile("$d.md") ?: return@forEach
            val text = readText(file) ?: return@forEach
            val result = LogParser.parseFile(file.name ?: "$d.md", text)
            // 前一天文件里，可能含有跨午夜落到 date 的那一条；只要 date 当天的条目
            entries.addAll(result.entries.filter { it.date == date })
            warnings.addAll(result.warnings)
        }
        return ParseResult(entries.sortedBy { it.startMin }, warnings)
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
     * 这里逐行追加到各自日期的 md，实现"跨午夜按 24 点切两天"。
     */
    fun append(input: LogFormatter.Input): Result<Int> {
        val root = root() ?: return Result.failure(IllegalStateException("还没有选择日记文件夹"))
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
        val newText = when {
            existing.isEmpty() -> line + "\n"
            existing.endsWith("\n") -> existing + line + "\n"
            else -> existing + "\n" + line + "\n"
        }
        return try {
            val out = context.contentResolver.openOutputStream(file.uri, "wt")
                ?: return Result.failure(IllegalStateException("无法写入 $fileName"))
            out.use { it.write(newText.toByteArray(Charsets.UTF_8)) }
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
