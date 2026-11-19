package com.example.timelog.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.LogParser
import com.example.timelog.core.ParseResult
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
