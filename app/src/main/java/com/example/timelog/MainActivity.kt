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

    internal lateinit var repo: VaultRepository
    internal lateinit var prefs: Prefs
    internal val mainHandler = Handler(Looper.getMainLooper())

    internal var viewDate: LocalDate = LocalDate.now()
    internal var entries: List<LogEntry> = emptyList()
    internal var warnings: List<String> = emptyList()
    // 相邻两天的记录，滑动时填进底层预览页；date 为 null 表示那天还没加载
    internal var prevDayEntries: List<LogEntry> = emptyList()
    internal var nextDayEntries: List<LogEntry> = emptyList()
    internal var neighborLoaded = false
    // 最近三天的完整数据缓存（记录+提示+问题行）：滑动到位后直接切换，不再二次读文件刷新
    internal val dayCache = HashMap<LocalDate, ParseResult>()
    // 本次滑动是否正在用预览页；dir = 1 显示后一天，-1 显示前一天
    internal var neighborInUse = false
    internal var neighborDir = 1
    // 解析不出来的行（红字列在记录列表里，点开就能改）
    internal var problems: List<ProblemLine> = emptyList()
    // 记住上次用的文件夹：从设置页换文件夹回来时自动刷新，不用手动点刷新
    internal var lastTreeUri: String? = null
    // onResume 与 onCreate 会连着触发，第一次不重复读
    internal var firstResume = true
    // 上次真正读取的时刻，避免同一瞬间重复读两遍
    internal var lastReloadAt = 0L
    // 翻日期的方向：+1 看后一天（内容从右侧滑进来），-1 看前一天。0 = 不做动画
    internal var pendingSlideDir = 0

    // 正在编辑的那条记录（点记录行进来时非 null）；null = 新录入
    internal var editing: LogEntry? = null
    // 那条记录的原始行所在的文件名日期（跨午夜时可能是前一天）
    internal var editingFileDate: LocalDate? = null
    // 正在修的那一行问题记录（解析失败的行没有 LogEntry，只认原文）
    internal var editingRaw: String? = null
    internal var editingRawDate: LocalDate? = null

    // 历史日志里统计出的常用候选（一级/二级分类、双链）
    internal var suggestions = Suggestions.EMPTY
    internal var suggestionsLoading = false

    // 视图
    internal lateinit var tvStatsDate: TextView
    internal lateinit var contentRoot: LinearLayout
    internal lateinit var contentScroll: android.widget.ScrollView
    // 相邻日期预览页（左右滑动时从侧边露出，避免白边）：结构和真页一致，滑过来就是完整内容
    internal lateinit var neighborPage: View
    internal lateinit var neighborTimeline: TimelineView
    internal lateinit var nbStatRecord: TextView
    internal lateinit var nbStatUnrecorded: TextView
    internal lateinit var nbStatCoverage: TextView
    internal lateinit var nbStatCount: TextView
    internal lateinit var nbLevel1: TextView
    internal lateinit var nbWarnings: TextView
    internal lateinit var nbRecordsContainer: LinearLayout
    internal lateinit var tvStatRecord: TextView
    internal lateinit var tvStatUnrecorded: TextView
    internal lateinit var tvStatCoverage: TextView
    internal lateinit var tvStatCount: TextView
    internal lateinit var tvLevel1: TextView
    internal lateinit var tvWarnings: TextView
    internal lateinit var recordsContainer: LinearLayout
    internal lateinit var tvCheckTitle: TextView
    internal lateinit var tvCheck: TextView
    internal lateinit var tvPreview: TextView
    internal lateinit var btnSave: Button
    internal lateinit var btnSheetDelete: Button
    internal lateinit var tvSheetTitle: TextView
    internal lateinit var timeline: TimelineView
    internal lateinit var etDate: EditText
    internal lateinit var etStart: EditText
    internal lateinit var etEnd: EditText
    internal lateinit var etL1: EditText
    internal lateinit var etL2: EditText
    internal lateinit var etTitle: EditText
    internal lateinit var etLinks: EditText
    internal lateinit var etNote: EditText
    internal lateinit var l1CandidatesRow: LinearLayout
    internal lateinit var l2CandidatesRow: LinearLayout
    internal lateinit var linkCandidatesRow: LinearLayout
    internal lateinit var cbContinue: CheckBox
    internal lateinit var inputSheet: View
    internal lateinit var sheetDim: View
    internal lateinit var sheetHandle: View
    internal lateinit var btnStopwatch: Button
    internal lateinit var btnCountdown: Button

    // 正计时按钮文字刷新
    internal val tick = object : Runnable {
        override fun run() {
            updateStopwatchLabel()
            updateCountdownLabel()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // 跟手翻页：按下位置 + 是否已判定为横向滑动
    internal var swipeDownX = 0f
    internal var swipeDownY = 0f
    internal var swipeDecided = false
    internal var swipeHorizontal = false
    // 今天再往明天的手势：明天不存在，整段手势不跟手、不弹回、完全不出动画
    internal var swipeBlocked = false
    // 自己算帧的滑动任务（不走系统动画，开发者选项关了动画也有效）
    internal var slideTick: Runnable? = null
    // 松手交接动画已滑完、正在切日期读数据：读好后只归位，不再做位移动画
    internal var swipeCommitPending = false

    /**
     * 左右滑动翻日期：手指按住直接拖，页面实时跟手移动；松手划够 40dp 就翻页。
     * 关键：跟手位移和松手后的补间都是"自己算位置"，不经过 View.animate()——
     * 开发者选项里关掉"动画程序时长缩放"后，系统属性动画会被掐成 0 毫秒，看着像没动画。
     * 竖着划仍交给 ScrollView 正常滚动；录入浮层展开时完全不响应。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        if (inputSheet.visibility == View.VISIBLE) {
            swipeDecided = false
            swipeHorizontal = false
            swipeBlocked = false
            return super.dispatchTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 正滑到位、等新数据回来的空窗期：忽略这次按下，别把页面复位闪回旧内容
                if (swipeCommitPending) {
                    swipeDownX = ev.x
                    swipeDownY = ev.y
                    swipeDecided = true
                    swipeHorizontal = false
                    swipeBlocked = false
                    return super.dispatchTouchEvent(ev)
                }
                slideTick?.let { contentRoot.removeCallbacks(it) }
                slideTick = null
                // 上次动画若被打断，先把页面和预览复位，避免残留半截
                if (neighborInUse) {
                    neighborPage.visibility = View.GONE
                    neighborInUse = false
                }
                contentRoot.translationX = 0f
                contentRoot.alpha = 1f
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeDecided = false
                swipeHorizontal = false
                swipeBlocked = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (!swipeDecided) {
                    if (abs(dx) > 6f * density && abs(dx) > abs(dy) * 1.2f) {
                        swipeDecided = true
                        // 往明天滑（左滑 dir=1），而当前已经是今天：明天没有内容，
                        // 从一开始就封锁——不跟手、不显示预览页、松手也不弹回，全程无动画
                        val dir = if (dx < 0f) 1 else -1
                        if (dir > 0 && viewDate >= LocalDate.now()) {
                            swipeBlocked = true
                        } else {
                            swipeHorizontal = true
                        }
                    } else if (abs(dy) > 6f * density) {
                        swipeDecided = true   // 判定为纵向，交给 ScrollView 正常滚
                    }
                }
                if (swipeHorizontal) {
                    // 跟手：直接设位置（0.9 阻尼），纯手动位移，一定看得见
                    val x = dx * 0.9f
                    contentRoot.translationX = x
                    contentRoot.alpha = 1f
                    val w = contentRoot.width.toFloat().coerceAtLeast(1f)
                    if (neighborLoaded) {
                        // 左滑看后一天(dir=1)、右滑看前一天(dir=-1)；预览页贴在露出的那一侧
                        val dir = if (dx < 0f) 1 else -1
                        if (!neighborInUse || dir != neighborDir) {
                            neighborDir = dir
                            renderNeighbor(dir)
                            neighborPage.visibility = View.VISIBLE
                            neighborInUse = true
                        }
                        neighborPage.translationX = x + dir * w
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swipeBlocked) {
                    // 今天往明天滑：页面从没动过，也不需要任何提示，静默忽略
                } else if (swipeHorizontal) {
                    val dx = ev.x - swipeDownX
                    if (abs(dx) >= 40f * density) {
                        val dir = if (dx < 0f) 1 else -1
                        // 兜底：动画期间若刚好跨到今天，这里不再翻、也不播弹回动画、不给提示
                        if (dir > 0 && viewDate >= LocalDate.now()) {
                            contentRoot.translationX = 0f
                            neighborPage.visibility = View.GONE
                            neighborInUse = false
                        } else {
                            // 顺着手指方向把两页滑到位，滑完那一刻才切日期、换内容
                            commitSwipe(dir)
                        }
                    } else {
                        slideContentTo(0f, 180L)                 // 没划够，弹回
                    }
                }
                swipeDecided = false
                swipeHorizontal = false
                swipeBlocked = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipeHorizontal) slideContentTo(0f, 160L)
                swipeDecided = false
                swipeHorizontal = false
                swipeBlocked = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 自己算帧的"滑到位"动画：用 postOnAnimation 每帧插值设 translationX，
     * 不走 View.animate() / ValueAnimator，所以开发者选项关了动画也照样滑。
     * 两页纸：预览页若正在用，就跟当前页保持相对位置一起平移，露出的空隙始终被相邻那天填上。
     */
    private fun slideContentTo(targetX: Float, duration: Long) {
        slideTick?.let { contentRoot.removeCallbacks(it) }
        val fromX = contentRoot.translationX
        val followNeighbor = neighborInUse
        val fromNX = neighborPage.translationX
        // 预览页终点 = 起点 + 当前页的位移量，两页始终同步平移
        val targetNX = fromNX + (targetX - fromX)
        val start = System.currentTimeMillis()
        val interpolator = android.view.animation.DecelerateInterpolator()
        val tick = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / duration).coerceIn(0f, 1f)
                val p = interpolator.getInterpolation(t)
                contentRoot.translationX = fromX + (targetX - fromX) * p
                if (followNeighbor) neighborPage.translationX = fromNX + (targetNX - fromNX) * p
                if (t < 1f && contentRoot.isAttachedToWindow) {
                    contentRoot.postOnAnimation(this)
                } else {
                    contentRoot.translationX = targetX
                    contentRoot.alpha = 1f
                    if (followNeighbor) {
                        neighborPage.visibility = View.GONE
                        neighborInUse = false
                    }
                    slideTick = null
                }
            }
        }
        slideTick = tick
        contentRoot.postOnAnimation(tick)
    }

    /**
     * 松手后顺着手指方向把两页滑到位：当前页往手指方向滑出屏幕，相邻页滑进来占满屏幕。
     * 关键是——滑的过程中两页各自连续移动，没有"瞬移"；等滑到位的那一刻才切日期、读数据，
     * 再由 animateContentIn() 把内容页归位到 0。这样方向和手势一致，也不会有弹跳。
     * dir = 1 左滑看后一天（旧页往左出、新页从右进）；-1 右滑看前一天（反之）。
     */
    private fun commitSwipe(dir: Int) {
        slideTick?.let { contentRoot.removeCallbacks(it) }
        val w = contentRoot.width.toFloat().coerceAtLeast(1f)
        val fromX = contentRoot.translationX
        val fromNX = neighborPage.translationX
        // 当前页沿手指方向滑出一整屏：左滑(dir=1)往左出 -> -w；右滑(dir=-1)往右出 -> +w
        val outX = -dir * w
        // 手指抬起、确定要翻页的这一刻就把日期切过去：顶栏日期和录入框立刻显示目标那天，
        // 不再等滑完动画 + 读完文件才变（那样慢半拍，像没反应）。
        val oldDate = viewDate
        val target = viewDate.plusDays(dir.toLong())
        viewDate = target
        etDate.setText(target.toString())
        tvStatsDate.text = "录入日期：$target ▾"
        // 相邻页从它现在的位置滑到 0（占满屏幕）
        val start = System.currentTimeMillis()
        val duration = 220L
        val interpolator = android.view.animation.DecelerateInterpolator()
        val tick = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / duration).coerceIn(0f, 1f)
                val p = interpolator.getInterpolation(t)
                contentRoot.translationX = fromX + (outX - fromX) * p
                neighborPage.translationX = fromNX + (0f - fromNX) * p
                if (t < 1f && contentRoot.isAttachedToWindow) {
                    contentRoot.postOnAnimation(this)
                } else {
                    slideTick = null
                    // 两页已滑到位：日期在松手时已经切好，这里只管读新数据、归位。
                    // 预览页先留着占住屏幕，等新数据渲染好再撤掉，避免中途露出白底。
                    // 防御极罕见情况——滑动这 220ms 里跨过午夜，目标落到了未来，那就弹回去。
                    if (dir > 0 && target.isAfter(LocalDate.now())) {
                        viewDate = oldDate
                        etDate.setText(oldDate.toString())
                        tvStatsDate.text = "录入日期：$oldDate ▾"
                        slideContentTo(0f, 160L)
                    } else {
                        applyCachedDay(target, dir, oldDate)
                    }
                }
            }
        }
        slideTick = tick
        contentRoot.postOnAnimation(tick)
    }

    /**
     * 滑动到位后直接从缓存切换目标那天，不再二次读文件刷新。
     * 手指抬起时 viewDate 已是 target；这里把缓存里的完整数据填进当前页、渲染、归位，
     * 再后台补读新方向缺的那一天，保证下次滑动仍有完整缓存。
     */
    private fun applyCachedDay(target: LocalDate, dir: Int, oldDate: LocalDate) {
        val cached = dayCache[target]
        if (cached == null) {
            // 极端情况缓存没命中，退回读文件
            swipeCommitPending = true
            pendingSlideDir = 0
            reload()
            return
        }
        entries = cached.entries
        warnings = cached.warnings
        problems = cached.problems
        renderStats()
        renderRecords()
        renderCheck(emptyList())

        // 相邻缓存跟着滚动：旧当前那天仍有数据，新方向缺的一天留给后台补
        val keep = dayCache[oldDate]?.entries?.filter { it.date == oldDate } ?: emptyList()
        if (dir > 0) {
            prevDayEntries = keep
            nextDayEntries = emptyList()
        } else {
            nextDayEntries = keep
            prevDayEntries = emptyList()
        }
        neighborLoaded = true
        pendingSlideDir = 0

        // 两页已滑到位，换新日期后直接归位，不再播入场动画（否则又是弹跳）
        contentRoot.translationX = 0f
        contentRoot.alpha = 1f
        contentScroll.scrollTo(0, 0)
        neighborPage.visibility = View.GONE
        neighborInUse = false

        // 后台补读新方向缺的那一天，只更新缓存和预览数据，不碰当前页
        val missingDate = target.plusDays(dir.toLong())
        Thread {
            val missing = repo.loadDay(missingDate)
            mainHandler.post {
                dayCache[missingDate] = missing
                if (dir > 0) nextDayEntries = missing.entries.filter { it.date == missingDate }
                else prevDayEntries = missing.entries.filter { it.date == missingDate }
            }
        }.start()
    }

    companion object {
        internal const val REQ_SETTINGS = 42
        internal const val REQ_NOTIF = 43
        // 记录行字号（sp）；行高固定是它的 2 倍
        internal const val ROW_TEXT_SP = 13f
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
        neighborPage = findViewById(R.id.neighborPage)
        neighborTimeline = findViewById(R.id.neighborTimeline)
        nbStatRecord = findViewById(R.id.nbStatRecord)
        nbStatUnrecorded = findViewById(R.id.nbStatUnrecorded)
        nbStatCoverage = findViewById(R.id.nbStatCoverage)
        nbStatCount = findViewById(R.id.nbStatCount)
        nbLevel1 = findViewById(R.id.nbLevel1)
        nbWarnings = findViewById(R.id.nbWarnings)
        nbRecordsContainer = findViewById(R.id.nbRecordsContainer)
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
        // 右上角：一键回到今天（已在今天则不动）
        findViewById<View>(R.id.btnBackToday).setOnClickListener {
            val today = LocalDate.now()
            if (today == viewDate) {
                Toast.makeText(this, "已经在今天了", Toast.LENGTH_SHORT).show()
            } else {
                pendingSlideDir = if (today.isAfter(viewDate)) 1 else -1
                goToDate(today)
            }
        }

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

    // 录入浮层的动画状态（方法都在 MainActivityUi.kt）
    internal var sheetAnimToken = 0
    internal var touchStartY = 0f

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
    internal fun reload(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReloadAt < 600) return
        lastReloadAt = now
        Thread {
            val result: ParseResult = repo.loadDay(viewDate)
            // 顺带读相邻两天：既填侧边预览页，也进完整缓存，供滑动到位后直接切换
            val prevDate = viewDate.minusDays(1)
            val nextDate = viewDate.plusDays(1)
            val prevResult = repo.loadDay(prevDate)
            val nextResult = repo.loadDay(nextDate)
            mainHandler.post {
                // 三天完整结果一次入缓存；只留这三天，避免长期翻页越攒越多
                dayCache.clear()
                dayCache[viewDate] = result
                dayCache[prevDate] = prevResult
                dayCache[nextDate] = nextResult
                entries = result.entries
                warnings = result.warnings
                problems = result.problems
                prevDayEntries = prevResult.entries.filter { it.date == prevDate }
                nextDayEntries = nextResult.entries.filter { it.date == nextDate }
                neighborLoaded = true
                renderStats()
                renderRecords()
                renderCheck(emptyList())
                // 若是滑动交接过来的：内容已换成新日期，把内容页归位、撤掉预览页即可，
                // 不能再做位移动画——两页纸已经滑到位了，再动一次就是弹跳。
                if (swipeCommitPending) {
                    swipeCommitPending = false
                    contentRoot.translationX = 0f
                    contentRoot.alpha = 1f
                    contentScroll.scrollTo(0, 0)
                    neighborPage.visibility = View.GONE
                    neighborInUse = false
                } else {
                    renderNeighbor(neighborDir)
                    animateContentIn()
                }
            }
        }.start()
    }

    /** 切日期的统一入口：改日期、同步录入框、重新读那天的 md。 */
    private fun goToDate(target: LocalDate) {
        if (target == viewDate) return
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
     * 翻页动画（v1.6.7）：新的一页从侧边整屏滑进来 + 淡入。
     * 用自己算帧的 slideContentTo，不走系统属性动画——开发者选项关掉动画缩放后照样能看见。
     * dir = 1 表示往后一天（从右边进），-1 表示往前一天（从左边进）。
     * 注意：点日历/手改日期时没有跟手过程，这里给整屏起点；左右滑时手指已经把页面拖到一侧，
     * 那种情况透传标记，起点由手势阶段决定。
     */
    private fun animateContentIn() {
        val dir = pendingSlideDir
        pendingSlideDir = 0
        if (dir == 0 || contentRoot.width == 0) {
            // 普通刷新（保存/回前台）不该有预览页残留，顺手清掉
            if (neighborInUse) {
                neighborPage.visibility = View.GONE
                neighborInUse = false
            }
            return
        }
        contentScroll.scrollTo(0, 0)

        // 日历选日期：没有跟手过程，给一个整屏宽度的起点滑入。
        // （左右滑翻页不走这里——它在两页滑到位后由 reload 直接归位，不再播入场动画。）
        contentRoot.translationX = dir * contentRoot.width.toFloat()
        slideContentTo(0f, 280L)
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
}
