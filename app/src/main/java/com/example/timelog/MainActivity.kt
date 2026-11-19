package com.example.timelog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.AnalysisResult
import com.example.timelog.core.Analyzer
import com.example.timelog.core.Formatters
import com.example.timelog.core.LogEntry
import com.example.timelog.core.ParseResult
import com.example.timelog.core.Period
import com.example.timelog.data.VaultRepository
import java.time.LocalDate
import java.time.format.DateTimeParseException

class MainActivity : AppCompatActivity() {

    private lateinit var repo: VaultRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    private var entries: List<LogEntry> = emptyList()
    private var warnings: List<String> = emptyList()
    private var anchor: LocalDate = LocalDate.now()
    private var period: Period = Period.DAY

    private lateinit var tvFolder: TextView
    private lateinit var tvPreview: TextView
    private lateinit var tvCheck: TextView
    private lateinit var tvStats: TextView
    private lateinit var etDate: EditText
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etL1: EditText
    private lateinit var etL2: EditText
    private lateinit var etTitle: EditText
    private lateinit var etNote: EditText

    companion object {
        private const val REQ_PICK = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = VaultRepository(this)

        tvFolder = findViewById(R.id.tvFolder)
        tvPreview = findViewById(R.id.tvPreview)
        tvCheck = findViewById(R.id.tvCheck)
        tvStats = findViewById(R.id.tvStats)
        etDate = findViewById(R.id.etDate)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)
        etL1 = findViewById(R.id.etL1)
        etL2 = findViewById(R.id.etL2)
        etTitle = findViewById(R.id.etTitle)
        etNote = findViewById(R.id.etNote)
        etDate.setText(LocalDate.now().toString())

        listOf(etStart, etEnd, etL1, etL2, etTitle, etNote).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updatePreview()
            })
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { openFolderPicker() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { reload() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveLine() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { renderOverlaps() }

        findViewById<Button>(R.id.tabInput).setOnClickListener { showPanel(R.id.panelInput) }
        findViewById<Button>(R.id.tabCheck).setOnClickListener { showPanel(R.id.panelCheck) }
        findViewById<Button>(R.id.tabStats).setOnClickListener { showPanel(R.id.panelStats) }

        findViewById<Button>(R.id.pDay).setOnClickListener { setPeriod(Period.DAY) }
        findViewById<Button>(R.id.pWeek).setOnClickListener { setPeriod(Period.WEEK) }
        findViewById<Button>(R.id.pMonth).setOnClickListener { setPeriod(Period.MONTH) }
        findViewById<Button>(R.id.pYear).setOnClickListener { setPeriod(Period.YEAR) }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { shiftAnchor(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { shiftAnchor(1) }

        refreshFolderLabel()
        updatePreview()
        reload()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Toast.makeText(this, "授权未持久化：${e.message}", Toast.LENGTH_SHORT).show()
        }
        repo.treeUri = uri
        refreshFolderLabel()
        reload()
    }

    private fun refreshFolderLabel() {
        val root: DocumentFile? = repo.root()
        tvFolder.text = if (root == null) "未选择文件夹" else "当前文件夹：${root.name ?: "已授权"}"
    }

    private fun reload() {
        tvFolder.text = "读取中…"
        Thread {
            val result: ParseResult = repo.loadAll()
            mainHandler.post {
                entries = result.entries
                warnings = result.warnings
                refreshFolderLabel()
                Toast.makeText(
                    this,
                    "读到 ${entries.size} 条记录${if (warnings.isEmpty()) "" else "，${warnings.size} 条提示"}",
                    Toast.LENGTH_SHORT
                ).show()
                renderOverlaps()
                renderStats()
            }
        }.start()
    }

    private fun showPanel(id: Int) {
        listOf(R.id.panelInput, R.id.panelCheck, R.id.panelStats).forEach { panelId ->
            findViewById<View>(panelId).visibility = if (panelId == id) View.VISIBLE else View.GONE
        }
    }

    private fun setPeriod(next: Period) {
        period = next
        renderStats()
    }

    private fun shiftAnchor(delta: Int) {
        anchor = when (period) {
            Period.DAY -> anchor.plusDays(delta.toLong())
            Period.WEEK -> anchor.plusWeeks(delta.toLong())
            Period.MONTH -> anchor.plusMonths(delta.toLong())
            Period.YEAR -> anchor.plusYears(delta.toLong())
        }
        renderStats()
    }

    private fun parseTime(value: String): Int? {
        val text = value.trim()
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59 || (h == 24 && m != 0)) return null
        return h * 60 + m
    }

    private fun buildLine(): String? {
        val start = parseTime(etStart.text.toString()) ?: return null
        val end = parseTime(etEnd.text.toString()) ?: return null
        val l1 = etL1.text.toString().trim().ifEmpty { "未分类" }
        val l2 = etL2.text.toString().trim()
        val title = etTitle.text.toString().trim().ifEmpty { "-" }
        val note = etNote.text.toString().trim().replace(Regex("""\s+"""), " ")
        val sb = StringBuilder()
        sb.append("- [ ] ==${LogEntry.fmt(start)} - ${LogEntry.fmt(end)}==")
        sb.append(" [[○ $l1]]")
        if (l2.isNotEmpty()) sb.append(" [[♺ $l2]]")
        sb.append(" **$title**")
        if (note.isNotEmpty()) sb.append(" $note")
        return sb.toString()
    }

    private fun updatePreview() {
        val line = buildLine()
        tvPreview.text = if (line == null) "时间格式请写成 09:00 这样" else line
    }

    private fun saveLine() {
        val line = buildLine()
        if (line == null) {
            Toast.makeText(this, "时间格式不对，请写成 09:00", Toast.LENGTH_SHORT).show()
            return
        }
        val date = try {
            LocalDate.parse(etDate.text.toString().trim())
        } catch (e: DateTimeParseException) {
            Toast.makeText(this, "日期格式不对，请写成 2026-11-19", Toast.LENGTH_SHORT).show()
            return
        }
        val result = repo.appendLine(date, line)
        if (result.isSuccess) {
            Toast.makeText(this, "已追加到 ${result.getOrNull()}", Toast.LENGTH_SHORT).show()
            reload()
        } else {
            Toast.makeText(this, "写入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderOverlaps() {
        val list = Analyzer.overlaps(entries)
        if (list.isEmpty()) {
            tvCheck.text = "没有发现时间重叠。"
            return
        }
        val sb = StringBuilder()
        sb.append("发现 ${list.size} 处重叠：\n\n")
        list.forEach { o ->
            sb.append("${o.date}  ${Formatters.duration(o.minutes)}\n")
            sb.append("  A ${o.first.timeRange()} ${o.first.level1} ${o.first.title}\n")
            sb.append("  B ${o.second.timeRange()} ${o.second.level1} ${o.second.title}\n\n")
        }
        tvCheck.text = sb.toString()
    }

    private fun renderStats() {
        val result: AnalysisResult = Analyzer.analyze(entries, period, anchor)
        val sb = StringBuilder()
        sb.append(result.periodLabel).append("\n")
        sb.append("总记录：").append(Formatters.duration(result.totalMinutes))
            .append("（含睡眠）  条目 ").append(result.entries).append(" 条\n")
        sb.append("覆盖率：").append(String.format("%.1f%%", result.coveragePercent))
            .append("（分母按全天 24 小时计）\n")
        sb.append("重叠：").append(result.overlaps).append(" 处\n\n")

        sb.append("一级分类\n")
        appendStats(sb, result.byLevel1)
        sb.append("\n二级分类\n")
        appendStats(sb, result.byLevel2)

        if (warnings.isNotEmpty()) {
            sb.append("\n解析提示（前 10 条）\n")
            warnings.take(10).forEach { sb.append("- $it\n") }
        }
        tvStats.text = sb.toString()
    }

    private fun appendStats(sb: StringBuilder, stats: List<com.example.timelog.core.CategoryStat>) {
        if (stats.isEmpty()) {
            sb.append("  （无数据）\n")
            return
        }
        stats.forEach {
            sb.append("  ${it.label}  ${Formatters.duration(it.minutes)}  ")
            sb.append(String.format("%.1f%%", it.percent)).append("  ${it.count}条\n")
        }
    }
}
