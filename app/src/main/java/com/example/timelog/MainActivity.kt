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
import com.example.timelog.core.Analyzer
import com.example.timelog.core.CategoryStat
import com.example.timelog.core.DaySummary
import com.example.timelog.core.Formatters
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.ParseResult
import com.example.timelog.data.Prefs
import com.example.timelog.data.VaultRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * 只做 UI 编排：把控件事件转发给 core / data 层，把结果显示出来。
 * 业务逻辑（解析、分析、生成行）都不在这里，方便以后单独加周/月分析。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: VaultRepository
    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    // 当前分析/校验所选日期（单日版）
    private var viewDate: LocalDate = LocalDate.now()
    private var entries: List<LogEntry> = emptyList()
    private var warnings: List<String> = emptyList()

    private lateinit var tvFolder: TextView
    private lateinit var tvPreview: TextView
    private lateinit var tvCheckDate: TextView
    private lateinit var tvCheck: TextView
    private lateinit var tvStatsDate: TextView
    private lateinit var tvStats: TextView
    private lateinit var etDate: EditText
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etL1: EditText
    private lateinit var etL2: EditText
    private lateinit var etTitle: EditText
    private lateinit var etLinks: EditText
    private lateinit var etNote: EditText

    companion object {
        private const val REQ_PICK = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = VaultRepository(this)
        prefs = Prefs(this)

        tvFolder = findViewById(R.id.tvFolder)
        tvPreview = findViewById(R.id.tvPreview)
        tvCheckDate = findViewById(R.id.tvCheckDate)
        tvCheck = findViewById(R.id.tvCheck)
        tvStatsDate = findViewById(R.id.tvStatsDate)
        tvStats = findViewById(R.id.tvStats)
        etDate = findViewById(R.id.etDate)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)
        etL1 = findViewById(R.id.etL1)
        etL2 = findViewById(R.id.etL2)
        etTitle = findViewById(R.id.etTitle)
        etLinks = findViewById(R.id.etLinks)
        etNote = findViewById(R.id.etNote)

        // 录入提速：日期默认今天，分类/事项/双链沿用上次填过的
        etDate.setText(LocalDate.now().toString())
        etL1.setText(prefs.level1)
        etL2.setText(prefs.level2)
        etTitle.setText(prefs.title)
        etLinks.setText(prefs.links)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(etStart, etEnd, etL1, etL2, etTitle, etLinks, etNote).forEach {
            it.addTextChangedListener(watcher)
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { openFolderPicker() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { reload() }
        findViewById<Button>(R.id.btnNowStart).setOnClickListener { etStart.setText(nowHm()) }
        findViewById<Button>(R.id.btnNowEnd).setOnClickListener { etEnd.setText(nowHm()) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveLine() }

        findViewById<Button>(R.id.tabInput).setOnClickListener { showPanel(R.id.panelInput) }
        findViewById<Button>(R.id.tabCheck).setOnClickListener { showPanel(R.id.panelCheck) }
        findViewById<Button>(R.id.tabStats).setOnClickListener { showPanel(R.id.panelStats) }

        findViewById<Button>(R.id.btnPrevDay).setOnClickListener { shiftDate(-1) }
        findViewById<Button>(R.id.btnNextDay).setOnClickListener { shiftDate(1) }

        refreshFolderLabel()
        updatePreview()
        reload()
    }

    private fun nowHm(): String {
        val now = LocalDateTime.now()
        return "%02d:%02d".format(now.hour, now.minute)
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

    /** 只读所选那一天，秒开。 */
    private fun reload() {
        tvFolder.text = "读取中…"
        Thread {
            val result: ParseResult = repo.loadDay(viewDate)
            mainHandler.post {
                entries = result.entries
                warnings = result.warnings
                refreshFolderLabel()
                renderCheck()
                renderStats()
            }
        }.start()
    }

    private fun showPanel(id: Int) {
        listOf(R.id.panelInput, R.id.panelCheck, R.id.panelStats).forEach { panelId ->
            findViewById<View>(panelId).visibility = if (panelId == id) View.VISIBLE else View.GONE
        }
    }

    private fun shiftDate(delta: Int) {
        viewDate = viewDate.plusDays(delta.toLong())
        reload()
    }

    private fun parseTime(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59 || (h == 24 && m != 0)) return null
        return h * 60 + m
    }

    private fun buildInput(): LogFormatter.Input? {
        val start = parseTime(etStart.text.toString()) ?: return null
        val end = parseTime(etEnd.text.toString()) ?: return null
        if (start == end) return null // 时长为 0，不允许
        val links = etLinks.text.toString().trim()
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return LogFormatter.Input(
            date = viewDate,
            startMin = start,
            endMin = end,
            level1 = etL1.text.toString().trim(),
            level2 = etL2.text.toString().trim(),
            title = etTitle.text.toString().trim(),
            note = etNote.text.toString().trim(),
            links = links
        )
    }

    private fun updatePreview() {
        val input = buildInput()
        if (input == null) {
            tvPreview.text = "时间格式请写成 09:00 这样"
            return
        }
        val lines = LogFormatter.build(input)
        val sb = StringBuilder()
        lines.forEach { (date, line) -> sb.append("$date\n$line\n\n") }
        if (lines.size > 1) sb.append("提示：跨午夜，将按 24 点拆成两天两行。\n")
        tvPreview.text = sb.toString().trimEnd()
    }

    private fun saveLine() {
        val input = buildInput()
        if (input == null) {
            Toast.makeText(this, "时间格式不对，请写成 09:00", Toast.LENGTH_SHORT).show()
            return
        }
        // 记住这次的分类/事项/双链，下次录入自动带出
        prefs.level1 = input.level1
        prefs.level2 = input.level2
        prefs.title = input.title
        prefs.links = etLinks.text.toString().trim()

        val result = repo.append(input)
        if (result.isSuccess) {
            val n = result.getOrNull() ?: 0
            Toast.makeText(this, "已追加 $n 行到 md", Toast.LENGTH_SHORT).show()
            etNote.text.clear()
            etStart.text.clear()
            etEnd.text.clear()
            viewDate = input.date
            reload()
        } else {
            Toast.makeText(this, "写入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderCheck() {
        tvCheckDate.text = "$viewDate 的重叠校验"
        val list = Analyzer.overlaps(entries)
        if (list.isEmpty()) {
            tvCheck.text = "没有发现时间重叠。"
            return
        }
        val sb = StringBuilder().append("发现 ${list.size} 处重叠：\n\n")
        list.forEach { o ->
            sb.append("  ${Formatters.duration(o.minutes)}：")
            sb.append("${o.first.timeRange()} ${o.first.level1} ${o.first.title} ↔ ")
            sb.append("${o.second.timeRange()} ${o.second.level1} ${o.second.title}\n")
        }
        tvCheck.text = sb.toString()
    }

    private fun renderStats() {
        val summary: DaySummary = Analyzer.analyzeDay(entries, viewDate)
        tvStatsDate.text = "$viewDate  单日分析"
        val sb = StringBuilder()
        sb.append("总记录：").append(Formatters.duration(summary.totalMinutes))
            .append("（含睡眠）  ").append(summary.entries).append(" 条\n")
        sb.append("覆盖率：").append(Formatters.percent(summary.coveragePercent))
            .append("（分母 24 小时）\n")
        sb.append("重叠：").append(summary.overlaps).append(" 处\n\n")

        sb.append("一级分类\n")
        appendStats(sb, summary.byLevel1)
        sb.append("\n二级分类\n")
        appendStats(sb, summary.byLevel2)

        if (warnings.isNotEmpty()) {
            sb.append("\n解析提示（前 5 条）\n")
            warnings.take(5).forEach { sb.append("  - $it\n") }
        }
        tvStats.text = sb.toString()
    }

    private fun appendStats(sb: StringBuilder, stats: List<CategoryStat>) {
        if (stats.isEmpty()) {
            sb.append("  （无数据）\n")
            return
        }
        stats.forEach {
            sb.append("  ${it.label}  ${Formatters.duration(it.minutes)}  ")
            sb.append(Formatters.percent(it.percent)).append("\n")
        }
    }
}
