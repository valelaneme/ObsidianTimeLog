package com.example.timelog.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.LogEntry
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

    fun loadAll(): ParseResult {
        val root = root() ?: return ParseResult(emptyList(), listOf("还没有选择日记文件夹"))
        val entries = mutableListOf<LogEntry>()
        val warnings = mutableListOf<String>()
        root.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (!name.endsWith(".md", ignoreCase = true)) return@forEach
            val text = readText(file) ?: return@forEach
            val result = LogParser.parseFile(name, text)
            entries.addAll(result.entries)
            warnings.addAll(result.warnings)
        }
        return ParseResult(
            entries.sortedWith(compareBy({ it.date }, { it.startMin })),
            warnings
        )
    }

    fun appendLine(date: LocalDate, line: String): Result<String> {
        val root = root() ?: return Result.failure(IllegalStateException("还没有选择日记文件夹"))
        val fileName = "$date.md"
        var file = root.findFile(fileName)
        if (file == null) {
            file = root.createFile("text/markdown", fileName)
                ?: return Result.failure(IllegalStateException("创建 $fileName 失败"))
        }
        val existing = readText(file).orEmpty()
        val newText = if (existing.isEmpty()) {
            line
        } else if (existing.endsWith("\n")) {
            existing + line + "\n"
        } else {
            existing + "\n" + line + "\n"
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
