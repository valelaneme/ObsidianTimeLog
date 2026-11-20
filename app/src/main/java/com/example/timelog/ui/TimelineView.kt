package com.example.timelog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.timelog.core.Analyzer
import com.example.timelog.core.LogEntry
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

/**
 * 24 小时时间轴：一眼看清"哪段记了、哪段空着、哪段重叠"。
 * 不依赖任何第三方图表库，只画一条横向色带。
 *
 * - 灰底 = 没记录的空白时段
 * - 彩色 = 按一级分类上色的已记录时段（同一分类颜色固定）
 * - 顶部红色细条 = 重叠区间
 * - 黑色细竖线 = 6/12/18 点的刻度，最后画在色带之上，任何颜色都盖不住
 * - 蓝色竖线 = 当天的当前时刻（翻到历史日期则不画）
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var entries: List<LogEntry> = emptyList()
    private var date: LocalDate = LocalDate.now()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED")
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D93025")
    }
    // 6/12/18 点的刻度线：黑色，最后画，压在颜色上面，不会被任何色块盖住
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#202124")
        style = Paint.Style.STROKE
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
    }

    init {
        val density = resources.displayMetrics.density
        tickPaint.strokeWidth = 1f * density
        nowPaint.strokeWidth = 2f * density
    }

    fun setData(data: List<LogEntry>, day: LocalDate) {
        entries = data.sortedBy { it.startMin }
        date = day
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val pad = 8f * density
        // 视图里只剩色带本身（原来下方的刻度数字已删掉），上下各留 3dp
        val barTop = 3f * density
        val barBottom = height - 3f * density
        val barWidth = width - pad * 2f
        if (barBottom <= barTop || barWidth <= 0f) return

        // 空白时段（底）
        canvas.drawRect(pad, barTop, pad + barWidth, barBottom, trackPaint)

        // 已记录时段（彩色）
        entries.forEach { e ->
            val x1 = pad + barWidth * (e.startMin.coerceIn(0, 1440) / 1440f)
            val x2 = pad + barWidth * (e.endMin.coerceIn(0, 1440) / 1440f)
            segmentPaint.color = colorFor(e.level1)
            canvas.drawRect(x1, barTop, max(x1 + 2f, x2), barBottom, segmentPaint)
        }

        // 重叠区间（顶部红条）：色带变矮了，红条也跟着收窄
        val stripeBottom = barTop + min(4f * density, (barBottom - barTop) * 0.3f)
        Analyzer.overlaps(entries).forEach { o ->
            val start = maxOf(o.first.startMin, o.second.startMin)
            val end = minOf(o.first.endMin, o.second.endMin)
            if (end > start) {
                val x1 = pad + barWidth * (start / 1440f)
                val x2 = pad + barWidth * (end / 1440f)
                canvas.drawRect(x1, barTop, max(x1 + 2f, x2), stripeBottom, overlapPaint)
            }
        }

        // 当天的当前时刻标线
        if (date == LocalDate.now()) {
            val now = LocalDateTime.now()
            val minute = now.hour * 60 + now.minute
            val x = pad + barWidth * (minute / 1440f)
            canvas.drawLine(x, barTop, x, barBottom, nowPaint)
        }

        // 刻度线只保留 6/12/18 点：最后画，黑色细竖线，压在色块上面当尺子用。
        // 0 点和 24 点在色带两端不画；3/9/15/21 太密，按用户要求去掉，只留三个整段点。
        for (hour in intArrayOf(6, 12, 18)) {
            val x = pad + barWidth * (hour / 24f)
            canvas.drawLine(x, barTop, x, barBottom, tickPaint)
        }
    }

    companion object {
        // 浅色主题下清晰可辨的一组分类色；记录列表左侧色条也复用它
        private val palette = intArrayOf(
            Color.parseColor("#5B8FF9"),
            Color.parseColor("#61DDAA"),
            Color.parseColor("#F6BD16"),
            Color.parseColor("#7262FD"),
            Color.parseColor("#78D3F8"),
            Color.parseColor("#E8684A"),
            Color.parseColor("#9661BC"),
            Color.parseColor("#65789B")
        )

        /** 同一个一级分类永远同一种颜色，时间轴与记录列表口径一致。 */
        fun colorFor(level1: String): Int {
            val hash = level1.hashCode() and Int.MAX_VALUE
            return palette[hash % palette.size]
        }
    }
}
