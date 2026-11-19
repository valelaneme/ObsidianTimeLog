package com.example.timelog

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.timelog.core.Analyzer
import com.example.timelog.core.DaySummary
import com.example.timelog.core.Formatters
import com.example.timelog.core.LogEntry
import com.example.timelog.core.LogFormatter
import com.example.timelog.core.LogParser
import com.example.timelog.core.Overlap
import com.example.timelog.core.ParseResult
import com.example.timelog.core.ProblemLine
import com.example.timelog.data.Prefs
import com.example.timelog.data.VaultRepository
import com.example.timelog.core.Suggestions
import com.example.timelog.ui.TimelineView
import android.graphics.Color
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

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
    // 解析不出来的行（红字列在记录列表里，点开就能改）
    private var problems: List<ProblemLine> = emptyList()
    // 记住上次用的文件夹：从设置页换文件夹回来时自动刷新，不用手动点刷新
    private var lastTreeUri: String? = null
    // onResume 与 onCreate 会连着触发，第一次不重复读
    private var firstResume = true
    // 上次真正读取的时刻，避免同一瞬间重复读两遍
    private var lastReloadAt = 0L
    // 翻日期的方向：+1 看后一天（内容从右侧滑进来），-1 看前一天。0 = 不做动画
    private var pendingSlideDir = 0

    // 正在编辑的那条记录（点记录行进来时非 null）；null = 新录入
    private var editing: LogEntry? = null
    // 那条记录的原始行所在的文件名日期（跨午夜时可能是前一天）
    private var editingFileDate: LocalDate? = null
    // 正在修的那一行问题记录（解析失败的行没有 LogEntry，只认原文）
    private var editingRaw: String? = null
    private var editingRawDate: LocalDate? = null

    // 历史日志里统计出的常用候选（一级/二级分类、双链）
    private var suggestions = Suggestions.EMPTY
    private var suggestionsLoading = false

    // 视图
    private lateinit var tvStatsDate: TextView
    private lateinit var contentRoot: LinearLayout
    private lateinit var contentScroll: android.widget.ScrollView
    private lateinit var tvStatRecord: TextView
    private lateinit var tvStatUnrecorded: TextView
    private lateinit var tvStatCoverage: TextView
    private lateinit var tvStatCount: TextView
    private lateinit var tvLevel1: TextView
    private lateinit var tvWarnings: TextView
    private lateinit var recordsContainer: LinearLayout
    private lateinit var tvCheckTitle: TextView
    private lateinit var tvCheck: TextView
    private lateinit var tvPreview: TextView
    private lateinit var btnSave: Button
    private lateinit var btnSheetDelete: Button
    private lateinit var tvSheetTitle: TextView
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

    /**
     * 左右滑动翻日期：左滑看后一天、右滑看前一天。
     * 用 GestureDetector 判"横向甩动"，竖向滑动仍然交给 ScrollView 正常滚。
     */
    private val swipeDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                // 录入浮层展开时不翻日期，免得在浮层里滑动把页面甩走
                if (inputSheet.visibility == View.VISIBLE) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDx = 60f * resources.displayMetrics.density
                // 必须是明显的横向动作：够远、横向占优、速度够快
                if (abs(dx) < minDx) return false
                if (abs(dx) < abs(dy) * 1.5f) return false
                if (abs(velocityX) < 300f) return false
                shiftDate(if (dx < 0f) 1 else -1)   // 左滑 → 后一天
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 只"旁听"手势，不管结果都继续走正常分发，不影响按钮点击和滚动
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val REQ_SETTINGS = 42
        private const val REQ_NOTIF = 43
        // 记录行字号（sp）；行高固定是它的 2 倍
        private const val ROW_TEXT_SP = 13f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = VaultRepository(this)
        prefs = Prefs(this)
        lastTreeUri = repo.treeUri?.toString()
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
        updatePreview()
        reload()
        loadSuggestionsIfNeeded()
        mainHandler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // 没有任何「刷新」按钮了，全部自动：
        // 1) 打开 App → onCreate 里读一次；
        // 2) 从设置页换了文件夹 → 这里发现 treeUri 变了，重读并重建常用候选；
        // 3) 从 Obsidian 或别的 App 回来 → 也重读一次，md 里改过的东西立刻能看到。
        val now = repo.treeUri?.toString()
        val folderChanged = now != lastTreeUri
        if (folderChanged) {
            lastTreeUri = now
            suggestions = Suggestions.EMPTY
            reload()
            loadSuggestionsIfNeeded()
            firstResume = false
            return
        }
        if (firstResume) {          // 刚打开，onCreate 已经读过
            firstResume = false
            return
        }
        reload(force = false)       // 同一文件夹，从后台回来也刷新
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
        tvStatsDate = findViewById(R.id.tvStatsDate)
        contentRoot = findViewById(R.id.contentRoot)
        contentScroll = findViewById(R.id.contentScroll)
        tvStatRecord = findViewById(R.id.tvStatRecord)
        tvStatUnrecorded = findViewById(R.id.tvStatUnrecorded)
        tvStatCoverage = findViewById(R.id.tvStatCoverage)
        tvStatCount = findViewById(R.id.tvStatCount)
        tvLevel1 = findViewById(R.id.tvLevel1)
        tvWarnings = findViewById(R.id.tvWarnings)
        tvCheckTitle = findViewById(R.id.tvCheckTitle)
        tvCheck = findViewById(R.id.tvCheck)
        tvPreview = findViewById(R.id.tvPreview)
        btnSave = findViewById(R.id.btnSave)
        btnSheetDelete = findViewById(R.id.btnSheetDelete)
        tvSheetTitle = findViewById(R.id.tvSheetTitle)
        recordsContainer = findViewById(R.id.recordsContainer)
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
        // 顶栏：只有日期（点它选日期）和一个齿轮图标
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }
        tvStatsDate.setOnClickListener { showDatePicker() }

        // 底部三按钮
        btnStopwatch.setOnClickListener { onStopwatchClick() }
        btnCountdown.setOnClickListener { onCountdownClick() }
        findViewById<Button>(R.id.btnInput).setOnClickListener { openNewSheet() }

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
        btnSave.setOnClickListener { onSaveClick() }
        btnSheetDelete.setOnClickListener {
            val entry = editing
            val raw = editingRaw
            val rawDate = editingRawDate
            when {
                entry != null -> confirmDelete(entry)
                raw != null && rawDate != null -> confirmDeleteRawLine(rawDate, raw)
            }
        }

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

    /** 新录入：清掉编辑态，日期回到当前正在看的那天。 */
    private fun openNewSheet() {
        setEditTarget(null, null)
        etDate.setText(viewDate.toString())
        openSheet()
    }

    /**
     * 点某条记录：把它的原文回填到浮层，保存时改写原来那一行（不再追加）。
     * 跨午夜写成的两行会被当成一条整体来编辑。
     */
    private fun openEditSheet(entry: LogEntry) {
        val fileDate = rawFileDate(entry)
        setEditTarget(entry, fileDate)
        etDate.setText(fileDate.toString())
        LogParser.timeOfLine(entry.raw)?.let { (start, end) ->
            etStart.setText(LogEntry.fmt(start))
            etEnd.setText(LogEntry.fmt(end))
        }
        etL1.setText(entry.level1)
        etL2.setText(entry.level2)
        etTitle.setText(entry.title)
        etLinks.setText(entry.links.joinToString(" "))
        etNote.setText(entry.note)
        openSheet()
    }

    /**
     * 点一条"解析不出来"的红字行：尽力把能认出来的字段填进浮层，
     * 时间填不出来的就留空，等用户补——补好保存后替换掉原来那一行。
     */
    private fun openProblemSheet(problem: ProblemLine) {
        clearEditTarget()
        editingRaw = problem.raw
        editingRawDate = problem.date
        tvSheetTitle.text = "修正第${problem.lineNo}行：${problem.reason}"
        btnSave.text = "保存修正"
        btnSheetDelete.visibility = View.VISIBLE

        val fill = LogParser.partial(problem.raw)
        etDate.setText(problem.date.toString())
        etStart.setText(fill.startHm)
        etEnd.setText(fill.endHm)
        etL1.setText(fill.level1)
        etL2.setText(fill.level2)
        etTitle.setText(fill.title)
        etLinks.setText(fill.links.joinToString(" "))
        etNote.setText(fill.note)
        openSheet()
    }

    /** 切换「新录入 / 编辑 / 修问题行」三种模式：标题、按钮文字、删除按钮都跟着变。 */
    private fun setEditTarget(entry: LogEntry?, fileDate: LocalDate?) {
        clearEditTarget()
        editing = entry
        editingFileDate = fileDate
        tvSheetTitle.text = if (entry != null) {
            "编辑记录（点「返回」退出）"
        } else {
            "录入（下滑或点「返回」退出）"
        }
        btnSave.text = if (entry != null) "保存修改" else "校验并追加到当天 md"
        btnSheetDelete.visibility = if (entry != null) View.VISIBLE else View.GONE
    }

    private fun clearEditTarget() {
        editing = null
        editingFileDate = null
        editingRaw = null
        editingRawDate = null
    }

    /** 原始行写在哪个 md 里：跨午夜拆出来的后半段，实际写在前一天的文件里。 */
    private fun rawFileDate(entry: LogEntry): LocalDate {
        val raw = LogParser.timeOfLine(entry.raw) ?: return entry.date
        val cross = raw.second <= raw.first
        if (!cross) return entry.date
        return if (entry.endMin == 1440) entry.date else entry.date.minusDays(1)
    }

    // 浮层滑动动画的序号：快速开关时用，防止上一次收起的收尾把新打开的面板又藏掉
    private var sheetAnimToken = 0

    private fun sheetHeight(): Float {
        val h = inputSheet.height
        return if (h > 0) h.toFloat() else resources.displayMetrics.heightPixels * 0.5f
    }

    /**
     * 打开录入面板：从屏幕下沿滑上来，背景蒙层同时淡入。
     * 只动 translationY / alpha 两个属性，交给 GPU 合成，和翻日期那次动画一个路子。
     */
    private fun openSheet() {
        sheetAnimToken++
        inputSheet.animate().cancel()
        sheetDim.animate().cancel()
        if (inputSheet.visibility != View.VISIBLE) {
            inputSheet.translationY = sheetHeight()
            inputSheet.alpha = 0f
            sheetDim.alpha = 0f
            inputSheet.visibility = View.VISIBLE
            sheetDim.visibility = View.VISIBLE
        }
        updatePreview()
        inputSheet.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        sheetDim.animate().alpha(1f).setDuration(220L).start()
    }

    /** 收起录入面板：往下滑回去，背景蒙层淡出；动画结束才真正 GONE。 */
    private fun closeSheet() {
        if (inputSheet.visibility != View.VISIBLE) return
        sheetAnimToken++
        val token = sheetAnimToken
        setEditTarget(null, null)
        inputSheet.animate().cancel()
        sheetDim.animate().cancel()
        inputSheet.animate()
            .translationY(sheetHeight())
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (token == sheetAnimToken) {
                    inputSheet.visibility = View.GONE
                    sheetDim.visibility = View.GONE
                    inputSheet.translationY = 0f
                    inputSheet.alpha = 1f
                    sheetDim.alpha = 1f
                }
            }
            .start()
        sheetDim.animate().alpha(0f).setDuration(180L).start()
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

    /** 从设置页回来：换文件夹、点"重新读取"或按系统返回键，都重新读一次。 */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_NOTIF) return
        if (requestCode != REQ_SETTINGS || resultCode != Activity.RESULT_OK) return
        lastTreeUri = repo.treeUri?.toString()
        suggestions = Suggestions.EMPTY
        reload()
        loadSuggestionsIfNeeded()
    }

    /**
     * 只读当天，秒开。
     * force = false 时，若刚读过（600ms 内）就跳过，避免 onResume 和设置页回调整出两次重复读。
     */
    private fun reload(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReloadAt < 600) return
        lastReloadAt = now
        Thread {
            val result: ParseResult = repo.loadDay(viewDate)
            mainHandler.post {
                entries = result.entries
                warnings = result.warnings
                problems = result.problems
                renderStats()
                renderRecords()
                renderCheck(emptyList())
                animateContentIn()
            }
        }.start()
    }

    /**
     * 翻一天：-1 前一天，+1 后一天。
     * 到今天为止——未来还没发生，没有可看可录的东西，所以不能往后翻。
     */
    private fun shiftDate(delta: Int) {
        val today = LocalDate.now()
        if (delta > 0 && viewDate >= today) {
            Toast.makeText(this, "今天就是最新的一天了", Toast.LENGTH_SHORT).show()
            return
        }
        pendingSlideDir = if (delta > 0) 1 else -1
        goToDate(viewDate.plusDays(delta.toLong()))
    }

    /** 切日期的统一入口：改日期、同步录入框里的日期、重新读那天的 md。 */
    private fun goToDate(target: LocalDate) {
        viewDate = target
        etDate.setText(target.toString())
        reload()
    }

    /**
     * 点顶栏日期：弹出一个只有日历的窗口（没有"确定/取消"按钮）。
     * 点哪天就立即切到哪天并关掉窗口；点外面或按返回键也能关掉。
     * maxDate = 现在，未来的日子灰掉点不了。
     */
    private fun showDatePicker() {
        val today = LocalDate.now()
        val calendar = android.widget.CalendarView(this).apply {
            maxDate = System.currentTimeMillis()
            // 先设日期再挂监听，避免程序自己设日期时误触发一次"点选"
            date = viewDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择日期（最晚到今天）")
            .setView(calendar)
            .create()
        var picked = false
        calendar.setOnDateChangeListener { _, year, month, day ->
            if (picked) return@setOnDateChangeListener
            val chosen = LocalDate.of(year, month + 1, day)
            if (chosen.isAfter(today)) {
                Toast.makeText(this, "还没到那一天", Toast.LENGTH_SHORT).show()
                return@setOnDateChangeListener
            }
            picked = true
            dialog.dismiss()
            pendingSlideDir = if (chosen.isAfter(viewDate)) 1 else -1
            goToDate(chosen)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * 极简过渡动画：内容容器从翻动方向的 24dp 处滑回原位，同时淡入，160ms 结束。
     * 只动一个 View 的 translationX / alpha，交给 GPU 合成，基本不耗性能。
     */
    private fun animateContentIn() {
        val dir = pendingSlideDir
        pendingSlideDir = 0
        if (dir == 0 || contentRoot.width == 0) return
        contentScroll.scrollTo(0, 0)
        contentRoot.animate().cancel()
        contentRoot.translationX = dp(24) * dir.toFloat()
        contentRoot.alpha = 0.55f
        contentRoot.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(160L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
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
        if (date.isAfter(LocalDate.now())) {
            Toast.makeText(this, "还没到那一天", Toast.LENGTH_SHORT).show()
            return
        }
        pendingSlideDir = if (date.isAfter(viewDate)) 1 else -1
        goToDate(date)
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
        when {
            editing != null -> sb.append("【编辑】保存后替换原来那一行\n")
            editingRaw != null -> sb.append("【修正】保存后替换掉那行有问题的记录\n")
        }
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

            // 编辑时要把"原来那条"排除掉，否则会跟它自己判重叠
            val base = editing?.let { old -> entries.filter { !sameEntry(it, old) } } ?: entries
            val all = base + candidates + extra
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
        list.take(8).forEach { o ->
            sb.append("· ${o.date} 重叠 ${Formatters.duration(o.minutes)}\n")
            sb.append("   ${o.first.timeRange()} ${o.first.level1} ${o.first.title}\n")
            sb.append("   ${o.second.timeRange()} ${o.second.level1} ${o.second.title}\n")
        }
        if (list.size > 8) sb.append("… 还有 ${list.size - 8} 处\n")
        sb.append("\n点\"返回修改时间\"改起止时刻，或\"仍然保存\"照写。")
        return sb.toString()
    }

    private fun doSave(input: LogFormatter.Input) {
        prefs.level1 = input.level1
        prefs.level2 = input.level2
        prefs.title = input.title
        prefs.links = etLinks.text.toString().trim()

        // 先记住编辑目标：closeSheet() 会把 editing / editingRaw 清空
        val editingEntry = editing
        val editingDate = editingFileDate
        val rawLine = editingRaw
        val rawDate = editingRawDate

        val result = when {
            rawLine != null && rawDate != null -> repo.replaceLine(rawDate, rawLine, input)
            editingEntry != null && editingDate != null -> repo.replaceEntry(editingDate, editingEntry, input)
            else -> repo.append(input)
        }
        if (result.isFailure) {
            Toast.makeText(this, "写入失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 编辑已有记录 / 修正问题行：改完就收工，不走"连续录入"
        if (editingEntry != null || rawLine != null) {
            val msg = if (rawLine != null) "已修正这一行" else "已更新这条记录"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            etStart.text.clear()
            etEnd.text.clear()
            etNote.text.clear()
            // 只有真的把日期改到了别的天才跳过去；否则留在当前看的那天（跨午夜那半段不会被甩到前一天）
            val oldDate = editingDate ?: rawDate
            if (oldDate == null || input.date != oldDate) viewDate = input.date
            closeSheet()
            reload()
            suggestions = Suggestions.EMPTY
            loadSuggestionsIfNeeded()
            return
        }

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
            // 计时是真实发生的，日期按今天走，不跟着首页正在看的那天走
            etDate.setText(LocalDate.now().toString())
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
        // 分钟数用设置页里那个值预填，改一次就记住，下次直接点「开始」
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "分钟数，如 25"
            setText(prefs.countdownMinutes.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("倒计时多少分钟")
            .setMessage("默认 ${prefs.countdownMinutes} 分钟（可在设置页改）。到点弹通知，关掉 App 也会提醒。")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val minutes = input.text.toString().trim().toLongOrNull() ?: 0L
                if (minutes <= 0) {
                    Toast.makeText(this, "请输入大于 0 的分钟数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 这次用的数字顺手记成默认值，下次不用再改
                prefs.countdownMinutes = minutes.toInt()
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
        // 倒计时到点是真实发生的时间，日期按今天走，不跟着首页正在看的那天走
        etDate.setText(LocalDate.now().toString())
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
        val day = entries.filter { it.date == viewDate }
        val summary: DaySummary = Analyzer.analyzeDay(entries, viewDate)
        val covered = Analyzer.coveredMinutes(day)
        val unrecorded = (1440 - covered).coerceAtLeast(0)
        tvStatsDate.text = "录入日期：$viewDate ▾"

        // 四项统计排成一行
        tvStatRecord.text = Formatters.duration(covered)
        tvStatUnrecorded.text = Formatters.duration(unrecorded)
        tvStatCoverage.text = Formatters.percent(covered * 100.0 / 1440.0)
        tvStatCount.text = "${summary.entries} 条"

        // 只保留一级分类，一格里按占比从多到少排开
        val level1 = summary.byLevel1
        tvLevel1.text = if (level1.isEmpty()) {
            "一级分类：暂无"
        } else {
            "一级分类：" + level1.joinToString("　") {
                "${it.label} ${Formatters.duration(it.minutes)} ${Formatters.percent(it.percent)}"
            }
        }

        // 只有文件级问题（如没选文件夹、文件名没日期）才留在这一块；
        // 单行的时间问题已经变成下面记录列表里的红字行，这里不再重复列
        if (warnings.isEmpty()) {
            tvWarnings.visibility = View.GONE
            tvWarnings.text = ""
        } else {
            tvWarnings.visibility = View.VISIBLE
            tvWarnings.text = buildString {
                append("提示（共 ${warnings.size} 条）\n")
                warnings.forEach { append("· ").append(it).append("\n") }
            }.trimEnd()
        }

        timeline.setData(day, viewDate)
    }

    // ---------- 当天全部记录 ----------

    /**
     * 逐条列出当天所有记录。两条时间不相连时，中间插一个"半行高"的空档，
     * 高度固定是普通记录行的一半，视觉上一眼就能看出哪段时间漏记了。
     *
     * 解析不出来的行（时间写错等）红字排在最后，点一下就能改或删。
     */
    private fun renderRecords() {
        recordsContainer.removeAllViews()
        // 行高 = 字号 2 倍（跟随系统字体缩放），上下不再另留白
        val rowHeight = rowHeightPx()

        if (repo.root() == null) {
            recordsContainer.addView(hintView("还没选择日记文件夹，请点右上角齿轮选择。"))
            return
        }
        val day = entries.filter { it.date == viewDate }
            .sortedWith(compareBy<LogEntry>({ it.startMin }, { it.endMin }))
        if (day.isEmpty() && problems.isEmpty()) {
            recordsContainer.addView(hintView("$viewDate 还没有时间记录。"))
            return
        }
        day.forEachIndexed { idx, entry ->
            if (idx > 0) {
                val gap = entry.startMin - day[idx - 1].endMin
                if (gap > 0) recordsContainer.addView(gapView(gap, rowHeight / 2))
            }
            recordsContainer.addView(recordRow(entry, rowHeight))
        }

        // 有问题的行：红字列出，点开可改可删
        if (problems.isNotEmpty()) {
            recordsContainer.addView(problemHeader(problems.size))
            problems.forEach { recordsContainer.addView(problemRow(it, rowHeight)) }
        }
    }

    /** 记录行高 = 字号的 2 倍。 */
    private fun rowHeightPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        ROW_TEXT_SP * 2f,
        resources.displayMetrics
    ).toInt()

    private fun problemHeader(count: Int): TextView = TextView(this).apply {
        text = "以下 $count 行时间有问题，点一下就能改"
        textSize = 12f
        setTextColor(Color.parseColor("#C5221F"))
        setPadding(0, dp(6), 0, dp(2))
    }

    /** 一行问题记录：红底红字，点开回填浮层修正；解析失败的行没有 LogEntry，只认原文。 */
    private fun problemRow(problem: ProblemLine, height: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FDECEA"))
            setPadding(dp(3), 0, dp(6), 0)
            minimumHeight = height
            isClickable = true
            isFocusable = true
            setOnClickListener { openProblemSheet(problem) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(1)) }
        }

        row.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#D93025"))
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        row.addView(TextView(this).apply {
            text = "第${problem.lineNo}行 ${problem.reason}：" +
                problem.raw.trim().let { if (it.isEmpty()) "（空行）" else it }
            textSize = ROW_TEXT_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#C5221F"))
            setPadding(dp(7), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun hintView(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 12f
        setTextColor(Color.parseColor("#5F6368"))
        setPadding(0, dp(8), 0, dp(8))
    }

    /** 半行高的空档：留白为主，中间一行淡灰小字说明空了多久。 */
    private fun gapView(minutes: Int, height: Int): TextView = TextView(this).apply {
        text = "· · ·  空 ${Formatters.duration(minutes)}  · · ·"
        textSize = 9f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#9AA0A6"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height
        )
    }

    /**
     * 一条记录压成一行：色条 + 时间 + 内容。
     * 不再带删除按钮 —— 点这一行就会打开录入浮层并填好值，删除按钮在浮层里。
     */
    private fun recordRow(entry: LogEntry, height: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
            setPadding(dp(3), 0, dp(6), 0)
            minimumHeight = height
            isClickable = true
            isFocusable = true
            setOnClickListener { openEditSheet(entry) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(1)) }
        }

        row.addView(View(this).apply {
            setBackgroundColor(TimelineView.colorFor(entry.level1))
            layoutParams = LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
        })

        row.addView(TextView(this).apply {
            text = buildString {
                append(entry.timeRange())
                append(" ").append(Formatters.duration(entry.minutes))
                if (entry.level1.isNotBlank()) append("　").append(entry.level1)
                if (entry.level2.isNotBlank()) append("/").append(entry.level2)
                if (entry.title.isNotBlank()) append(" ").append(entry.title)
                if (entry.links.isNotEmpty()) append(" ").append(entry.links.joinToString(" "))
                if (entry.note.isNotBlank()) append(" ").append(entry.note)
            }
            textSize = ROW_TEXT_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#202124"))
            setPadding(dp(7), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun confirmDelete(entry: LogEntry) {
        val raw = LogParser.timeOfLine(entry.raw)
        val crossed = raw != null && raw.second < raw.first
        val crossMidnightHint = if (crossed) {
            "\n（这一行是跨午夜记录，会连前一天那一段一起删）"
        } else ""
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage(
                "${entry.timeRange()}  ${entry.level1} ${entry.title}\n\n" +
                    "只会从 md 文件里删掉这一行，其它内容不动。$crossMidnightHint"
            )
            .setPositiveButton("删除") { _, _ -> doDelete(entry) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDelete(entry: LogEntry) {
        Thread {
            val result = repo.deleteEntry(viewDate, entry)
            mainHandler.post {
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "已从 md 删除这一行", Toast.LENGTH_SHORT).show()
                    closeSheet()     // 如果是点记录进浮层后删的，顺手把浮层收起
                    reload()
                    suggestions = Suggestions.EMPTY
                    loadSuggestionsIfNeeded()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "删除失败：${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** 删掉一行"解析不出来"的原文（问题行）。 */
    private fun confirmDeleteRawLine(date: LocalDate, rawLine: String) {
        AlertDialog.Builder(this)
            .setTitle("删掉这一行？")
            .setMessage("$rawLine\n\n只会从 $date.md 里删掉这一行，其它内容不动。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val result = repo.deleteLine(date, rawLine)
                    mainHandler.post {
                        if (result.isSuccess) {
                            Toast.makeText(this@MainActivity, "已删除这一行", Toast.LENGTH_SHORT).show()
                            closeSheet()
                            reload()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "删除失败：${result.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 首页内嵌的重叠显示；保存前校验时传入候选重叠做即时提示。没有重叠就整块隐藏。 */
    private fun renderCheck(candidateOverlaps: List<Overlap>) {
        val list = Analyzer.overlaps(entries)
        if (list.isEmpty() && candidateOverlaps.isEmpty()) {
            tvCheckTitle.visibility = View.GONE
            tvCheck.visibility = View.GONE
            tvCheck.text = ""
            return
        }
        tvCheckTitle.visibility = View.VISIBLE
        tvCheck.visibility = View.VISIBLE
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
