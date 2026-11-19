package com.example.timelog

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.timelog.core.Analyzer
import com.example.timelog.core.CategoryStat
import com.example.timelog.core.DaySummary
import com.example.timelog.core.Formatters
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.LogParser
import com.example.timelog.core.Overlap
import com.example.timelog.core.ParseResult
import com.example.timelog.data.Prefs
import com.example.timelog.data.VaultRepository
import com.example.timelog.core.Suggestions
import com.example.timelog.ui.TimelineView
import android.graphics.Color
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 只做 UI 编排：底层单日分析首页 + 底部三按钮 + 录入半屏浮层。
 * 业务逻辑（解析/分析/生成行/校验）都在 core 层，计时状态在 data 层。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: VaultRepository
    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var viewDate: LocalDate = LocalDate.now()
    private var entries: List<LogEntry> = emptyList()
    private var warnings: List<String> = emptyList()

    // 历史日志里统计出的常用候选（一级/二级分类、双链）
    private var suggestions = Suggestions.EMPTY
    private var suggestionsLoading = false

    // 视图
    private lateinit var tvFolder: TextView
    private lateinit var tvStatsDate: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvCheck: TextView
    private lateinit var tvPreview: TextView
    private lateinit var timeline: TimelineView
    private lateinit var etDate: EditText
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etL1: EditText
    private lateinit var etL2: EditText
    private lateinit var etTitle: EditText
    private lateinit var etLinks: EditText
    private lateinit var etNote: EditText
    private lateinit var l1CandidatesRow: LinearLayout
    private lateinit var l2CandidatesRow: LinearLayout
    private lateinit var linkCandidatesRow: LinearLayout
    private lateinit var cbContinue: CheckBox
    private lateinit var inputSheet: View
    private lateinit var sheetDim: View
    private lateinit var sheetHandle: View
    private lateinit var btnStopwatch: Button
    private lateinit var btnCountdown: Button

    // 正计时按钮文字刷新
    private val tick = object : Runnable {
        override fun run() {
            updateStopwatchLabel()
            updateCountdownLabel()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    companion object {
        private const val REQ_PICK = 42
        private const val REQ_NOTIF = 43
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = VaultRepository(this)
        prefs = Prefs(this)
        bindViews()
        setupListeners()

        etDate.setText(LocalDate.now().toString())
        etL1.setText(prefs.level1)
        etL2.setText(prefs.level2)
        etTitle.setText(prefs.title)
        etLinks.setText(prefs.links)

        limitSheetHeight()      // 录入浮层最多半屏
        requestNotifPermission()
        handleTimerIntent(intent)   // 通知点开时回填时间段
        // 倒计时到点后如果没点通知，过期状态别一直留着
        if (prefs.countdownEnd > 0L && prefs.countdownEnd <= System.currentTimeMillis()) {
            prefs.countdownEnd = 0L
        }
        refreshFolderLabel()
        updatePreview()
        reload()
        loadSuggestionsIfNeeded()
        mainHandler.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTimerIntent(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun bindViews() {
        tvFolder = findViewById(R.id.tvFolder)
        tvStatsDate = findViewById(R.id.tvStatsDate)
        tvStats = findViewById(R.id.tvStats)
        tvCheck = findViewById(R.id.tvCheck)
        tvPreview = findViewById(R.id.tvPreview)
        timeline = findViewById(R.id.timeline)
        etDate = findViewById(R.id.etDate)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)
        etL1 = findViewById(R.id.etL1)
        etL2 = findViewById(R.id.etL2)
        etTitle = findViewById(R.id.etTitle)
        etLinks = findViewById(R.id.etLinks)
        etNote = findViewById(R.id.etNote)
        l1CandidatesRow = findViewById(R.id.l1CandidatesRow)
        l2CandidatesRow = findViewById(R.id.l2CandidatesRow)
        linkCandidatesRow = findViewById(R.id.linkCandidatesRow)
        cbContinue = findViewById(R.id.cbContinue)
        inputSheet = findViewById(R.id.inputSheet)
        sheetDim = findViewById(R.id.sheetDim)
        sheetHandle = findViewById(R.id.sheetHandle)
        btnStopwatch = findViewById(R.id.btnStopwatch)
        btnCountdown = findViewById(R.id.btnCountdown)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { openFolderPicker() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { reload() }
        findViewById<Button>(R.id.btnPrevDay).setOnClickListener { shiftDate(-1) }
        findViewById<Button>(R.id.btnNextDay).setOnClickListener { shiftDate(1) }

        // 底部三按钮
        btnStopwatch.setOnClickListener { onStopwatchClick() }
        btnCountdown.setOnClickListener { onCountdownClick() }
        findViewById<Button>(R.id.btnInput).setOnClickListener { openSheet() }

        // 浮层收起
        findViewById<Button>(R.id.btnHideSheet).setOnClickListener { closeSheet() }
        sheetDim.setOnClickListener { closeSheet() }
        sheetHandle.setOnTouchListener { v, event -> onHandleTouch(v, event) }

        // 日期快捷
        findViewById<Button>(R.id.btnToday).setOnClickListener {
            etDate.setText(LocalDate.now().toString())
        }
        findViewById<Button>(R.id.btnGoDate).setOnClickListener { goToTypedDate() }

        // 录入控件：时间一键填，少打字
        findViewById<Button>(R.id.btnNowStart).setOnClickListener { etStart.setText(nowHm()) }
        findViewById<Button>(R.id.btnNowEnd).setOnClickListener { etEnd.setText(nowHm()) }
        findViewById<Button>(R.id.btnRoundStart).setOnClickListener { etStart.setText(LogEntry.fmt(nowHourStart())) }
        findViewById<Button>(R.id.btnRoundEnd).setOnClickListener { etEnd.setText(LogEntry.fmt(nowHourEnd())) }
        findViewById<Button>(R.id.btnHalfEnd).setOnClickListener { etEnd.setText(LogEntry.fmt(nowHalfEnd())) }
        findViewById<Button>(R.id.btnClearTime).setOnClickListener {
            etStart.text.clear()
            etEnd.text.clear()
            updatePreview()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { onSaveClick() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(etStart, etEnd, etL1, etL2, etTitle, etLinks, etNote).forEach {
            it.addTextChangedListener(watcher)
        }
    }

    // ---------- 录入浮层 ----------

    private fun limitSheetHeight() {
        inputSheet.post {
            // 直接固定成半屏：父容器给确定高度后，子 ScrollView 的 weight=1 才会分配剩余空间
            val half = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            val lp = inputSheet.layoutParams
            lp.height = half
            inputSheet.layoutParams = lp
        }
    }

    private fun openSheet() {
        inputSheet.visibility = View.VISIBLE
        sheetDim.visibility = View.VISIBLE
    }

    private fun closeSheet() {
        inputSheet.visibility = View.GONE
        sheetDim.visibility = View.GONE
    }

    private var touchStartY = 0f
    /** 把手指从把手往下拖超过 60px 就收起浮层。 */
    private fun onHandleTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> touchStartY = event.y
            MotionEvent.ACTION_UP -> {
                if (event.y - touchStartY > 60f) closeSheet()
                else v.performClick()
            }
        }
        return true
    }

    // ---------- 数据读取 ----------

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
        if (requestCode == REQ_NOTIF) return
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
        suggestions = Suggestions.EMPTY
        refreshFolderLabel()
        reload()
        loadSuggestionsIfNeeded()
    }

    private fun refreshFolderLabel() {
        val root: DocumentFile? = repo.root()
        tvFolder.text = if (root == null) "未选择文件夹" else "当前文件夹：${root.name ?: "已授权"}"
    }

    /** 只读当天，秒开。 */
    private fun reload() {
        tvFolder.text = "读取中…"
        Thread {
            val result: ParseResult = repo.loadDay(viewDate)
            mainHandler.post {
                entries = result.entries
                warnings = result.warnings
                refreshFolderLabel()
                renderStats()
                renderCheck(emptyList())
            }
        }.start()
    }

    private fun shiftDate(delta: Int) {
        viewDate = viewDate.plusDays(delta.toLong())
        etDate.setText(viewDate.toString())
        reload()
    }

    /** 手改日期后点"查看"：让首页统计跟着输入的日期走，避免看的是 A 天、存的是 B 天。 */
    private fun goToTypedDate() {
        val date = try {
            LocalDate.parse(etDate.text.toString().trim())
        } catch (e: Exception) {
            null
        }
        if (date == null) {
            Toast.makeText(this, "日期请写成 2026-11-19 这种格式", Toast.LENGTH_SHORT).show()
            return
        }
        viewDate = date
        etDate.setText(date.toString())
        reload()
        Toast.makeText(this, "已切到 $date", Toast.LENGTH_SHORT).show()
    }

    // ---------- 快捷时间 ----------

    private fun nowMinuteOfDay(): Int {
        val now = LocalDateTime.now()
        return now.hour * 60 + now.minute
    }

    private fun nowHourStart(): Int = (nowMinuteOfDay() / 60) * 60

    private fun nowHourEnd(): Int {
        val m = nowMinuteOfDay()
        return if (m % 60 == 0) m else (m / 60 + 1) * 60
    }

    private fun nowHalfEnd(): Int = (nowMinuteOfDay() / 30 + 1) * 30

    // ---------- 常用候选一键选 ----------

    /** 后台扫历史日志；只在第一次打开或换文件夹后扫，之后用内存里的结果。 */
    private fun loadSuggestionsIfNeeded() {
        if (suggestionsLoading || repo.root() == null) return
        if (suggestions != Suggestions.EMPTY) {
            renderSuggestions()
            return
        }
        suggestionsLoading = true
        Thread {
            val result = repo.loadSuggestions()
            mainHandler.post {
                suggestions = result
                suggestionsLoading = false
                renderSuggestions()
            }
        }.start()
    }

    private fun renderSuggestions() {
        renderCandidateRow(l1CandidatesRow, suggestions.level1, etL1, false)
        renderCandidateRow(l2CandidatesRow, suggestions.level2, etL2, false)
        renderCandidateRow(linkCandidatesRow, suggestions.links, etLinks, true)
    }

    private fun renderCandidateRow(
        row: LinearLayout,
        values: List<String>,
        target: EditText,
        isLink: Boolean
    ) {
        row.removeAllViews()
        if (values.isEmpty()) return
        row.addView(TextView(this).apply {
            text = "常用："
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
        })
        values.forEach { value ->
            val chip = Button(this).apply {
                text = value
                textSize = 11f
                isAllCaps = false
                setPadding(10, 2, 10, 2)
                setBackgroundColor(Color.parseColor("#E8F0FE"))
                setTextColor(Color.parseColor("#1967D2"))
            }
            chip.setOnClickListener { applyCandidate(target, value, isLink) }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
            )
        }
    }

    /** 分类=直接填入；双链=点一下添加、再点一下移除。 */
    private fun applyCandidate(target: EditText, value: String, isLink: Boolean) {
        if (!isLink) {
            target.setText(value)
            target.setSelection(value.length)
        } else {
            val current = target.text.toString().trim()
                .split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
            if (current.contains(value)) current.remove(value) else current.add(value)
            val joined = current.joinToString(" ")
            target.setText(joined)
            target.setSelection(joined.length)
        }
        updatePreview()
    }

    // ---------- 录入 + 保存前校验 ----------

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
        if (start == end) return null
        val date = try {
            LocalDate.parse(etDate.text.toString().trim())
        } catch (e: Exception) {
            return null
        }
        val links = etLinks.text.toString().trim()
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return LogFormatter.Input(
            date = date,
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
            tvPreview.text = "时间格式请写成 09:00，日期 2026-11-19"
            return
        }
        val lines = LogFormatter.build(input)
        val sb = StringBuilder()
        lines.forEach { (date, line) -> sb.append("$date\n$line\n\n") }
        if (lines.size > 1) sb.append("提示：跨午夜，将按 24 点拆成两天两行。\n")
        tvPreview.text = sb.toString().trimEnd()
    }

    /** 保存：先校验重叠，有问题弹窗让用户改时间或仍要保存。 */
    private fun onSaveClick() {
        val input = buildInput()
        if (input == null) {
            Toast.makeText(this, "时间或日期格式不对", Toast.LENGTH_SHORT).show()
            return
        }

        // 把这次输入解析成候选记录（含跨午夜拆分），复用正式解析器，保证口径一致
        val candidates = mutableListOf<LogEntry>()
        LogFormatter.build(input).forEach { (date, line) ->
            candidates.addAll(LogParser.parseLine(line, date).entries)
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "这段时间长度为 0，不用记", Toast.LENGTH_SHORT).show()
            return
        }

        // 跨午夜时还要补读"次日"文件，否则次日那段会漏检重叠
        Thread {
            val extra = mutableListOf<LogEntry>()
            candidates.map { it.date }.distinct()
                .filter { it != viewDate }
                .forEach { extra.addAll(repo.loadExactDay(it)) }

            val all = entries + candidates + extra
            val mine = Analyzer.overlaps(all).filter { o ->
                candidates.any { c -> sameEntry(c, o.first) || sameEntry(c, o.second) }
            }
            mainHandler.post {
                if (mine.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("时间重叠，建议修改")
                        .setMessage(describeOverlaps(mine))
                        .setPositiveButton("返回修改时间", null)
                        .setNegativeButton("仍然保存") { _, _ -> doSave(input) }
                        .show()
                } else {
                    doSave(input)
                }
            }
        }.start()
    }

    /** 按内容比对，而不是靠对象引用，避免跨线程/重建列表后漏判。 */
    private fun sameEntry(a: LogEntry, b: LogEntry): Boolean =
        a.date == b.date && a.startMin == b.startMin && a.endMin == b.endMin &&
            a.level1 == b.level1 && a.level2 == b.level2 && a.title == b.title && a.raw == b.raw

    private fun describeOverlaps(list: List<Overlap>): String {
        val sb = StringBuilder("新增的时间段与已有记录重叠 ${list.size} 处：\n\n")
        list.take(5).forEach { o ->
            sb.append("· ${o.date} 重叠 ${Formatters.duration(o.minutes)}\n")
            sb.append("   ${o.first.timeRange()} ${o.first.level1} ${o.first.title}\n")
            sb.append("   ${o.second.timeRange()} ${o.second.level1} ${o.second.title}\n")
        }
        sb.append("\n点\"返回修改时间\"改起止时刻，或\"仍然保存\"照写。")
        return sb.toString()
    }

    private fun doSave(input: LogFormatter.Input) {
        prefs.level1 = input.level1
        prefs.level2 = input.level2
        prefs.title = input.title
        prefs.links = etLinks.text.toString().trim()
        val result = repo.append(input)
        if (result.isSuccess) {
            Toast.makeText(this, "已追加 ${result.getOrNull() ?: 0} 行到 md", Toast.LENGTH_SHORT).show()
            etNote.text.clear()

            val crossed = input.endMin < input.startMin
            val nextDay = when {
                crossed || input.endMin >= 1440 -> input.date.plusDays(1)
                else -> input.date
            }
            val nextStart = when {
                crossed -> input.endMin          // 跨午夜：次日那段从 00:xx 接上
                input.endMin >= 1440 -> 0
                else -> input.endMin
            }

            if (cbContinue.isChecked) {
                etDate.setText(nextDay.toString())
                etStart.setText(LogEntry.fmt(nextStart))
                etEnd.text.clear()
                viewDate = nextDay
                openSheet()
                Toast.makeText(this, "开始时间已接上一段，继续填", Toast.LENGTH_SHORT).show()
            } else {
                etStart.text.clear()
                etEnd.text.clear()
                viewDate = input.date
                closeSheet()
            }
            reload()
            // 新输入可能带来新名字，候选列表后台刷新一次
            suggestions = Suggestions.EMPTY
            loadSuggestionsIfNeeded()
        } else {
            Toast.makeText(this, "写入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 正计时 ----------

    private fun onStopwatchClick() {
        val start = prefs.stopwatchStart
        if (start == 0L) {
            prefs.stopwatchStart = System.currentTimeMillis()
            updateStopwatchLabel()
            Toast.makeText(this, "已开始，忙完再点一下", Toast.LENGTH_SHORT).show()
        } else {
            val endMs = System.currentTimeMillis()
            etStart.setText(hm(start))
            etEnd.setText(hm(endMs))
            prefs.stopwatchStart = 0L
            updateStopwatchLabel()
            openSheet()
            Toast.makeText(this, "时间段已填好，补分类即可", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStopwatchLabel() {
        val s = prefs.stopwatchStart
        btnStopwatch.text = if (s == 0L) "⏱ 正计时" else "⏹ 停止 ${elapsed(s)}"
    }

    private fun elapsed(startMs: Long): String {
        val sec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0)
        return "%02d:%02d".format(sec / 60, sec % 60)
    }

    // ---------- 倒计时（系统闹钟 + 通知） ----------

    private fun onCountdownClick() {
        val end = prefs.countdownEnd
        if (end > System.currentTimeMillis()) {
            AlertDialog.Builder(this)
                .setTitle("倒计时进行中")
                .setMessage("还剩 ${millisToHm(end - System.currentTimeMillis())}，要取消吗？")
                .setPositiveButton("取消倒计时") { _, _ -> cancelCountdown() }
                .setNegativeButton("保留", null)
                .show()
            return
        }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "分钟数，如 25"
        }
        AlertDialog.Builder(this)
            .setTitle("倒计时多少分钟")
            .setMessage("到点弹通知，关掉 App 也会提醒")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val minutes = input.text.toString().toLongOrNull() ?: 0L
                if (minutes <= 0) {
                    Toast.makeText(this, "请输入大于 0 的分钟数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startCountdown(minutes)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCountdown(minutes: Long) {
        val startMs = System.currentTimeMillis()
        val endMs = startMs + minutes * 60_000L
        val startHm = hm(startMs)
        val endHm = hm(endMs)
        prefs.countdownEnd = endMs

        val intent = Intent(this, TimerReceiver::class.java).apply {
            putExtra(TimerReceiver.EXTRA_START, startHm)
            putExtra(TimerReceiver.EXTRA_END, endHm)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Android 12+ 精确闹钟需特殊权限；没有就降级为省电闹钟（可能晚几分钟，但一定响）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMs, pi)
        }
        updateCountdownLabel()
        Toast.makeText(this, "$minutes 分钟倒计时已设，到点通知", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCountdown() {
        prefs.countdownEnd = 0L
        val intent = Intent(this, TimerReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        updateCountdownLabel()
        Toast.makeText(this, "已取消倒计时", Toast.LENGTH_SHORT).show()
    }

    private fun updateCountdownLabel() {
        val end = prefs.countdownEnd
        btnCountdown.text = if (end > System.currentTimeMillis()) {
            "⏳ 剩 ${millisToHm(end - System.currentTimeMillis())}"
        } else {
            "⏳ 倒计时"
        }
    }

    /** 通知被点开：把那次计时的起止时间填进录入面板并展开浮层。 */
    private fun handleTimerIntent(intent: Intent) {
        if (!intent.getBooleanExtra(TimerReceiver.EXTRA_FROM_TIMER, false)) return
        val s = intent.getStringExtra(TimerReceiver.EXTRA_START)
        val e = intent.getStringExtra(TimerReceiver.EXTRA_END)
        if (s != null) etStart.setText(s)
        if (e != null) etEnd.setText(e)
        prefs.countdownEnd = 0L
        updateCountdownLabel()
        openSheet()
        Toast.makeText(this, "时间段已填好，补分类即可", Toast.LENGTH_SHORT).show()
    }

    // ---------- 通知权限 ----------

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    // ---------- 显示 ----------

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
        timeline.setData(entries, viewDate)
    }

    /** 首页内嵌的重叠显示；保存前校验时传入候选重叠做即时提示。 */
    private fun renderCheck(candidateOverlaps: List<Overlap>) {
        val list = Analyzer.overlaps(entries)
        if (list.isEmpty() && candidateOverlaps.isEmpty()) {
            tvCheck.text = "没有发现时间重叠。"
            return
        }
        val sb = StringBuilder()
        candidateOverlaps.forEach {
            sb.append("[待确认] ${Formatters.duration(it.minutes)}：")
            sb.append("${it.first.timeRange()} ↔ ${it.second.timeRange()}\n")
        }
        list.forEach {
            sb.append("${it.date} ${Formatters.duration(it.minutes)}：")
            sb.append("${it.first.timeRange()} ↔ ${it.second.timeRange()}\n")
        }
        tvCheck.text = sb.toString()
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

    // ---------- 时间工具 ----------

    private fun nowHm(): String = hm(System.currentTimeMillis())

    private fun hm(ms: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        return "%02d:%02d".format(dt.hour, dt.minute)
    }

    private fun millisToHm(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return "${min}分"
    }
}
