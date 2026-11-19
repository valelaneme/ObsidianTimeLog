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

/**
 * 24 小时时间轴：一眼看清"哪段记了、哪段空着、哪段重叠"。
 * 不依赖任何第三方图表库，只画一条横向色带。
 *
 * - 灰底 = 没记录的空白时段
 * - 彩色 = 按一级分类上色的已记录时段（同一分类颜色固定）
 * - 顶部红色细条 = 重叠区间
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5F6368")
        textAlign = Paint.Align.CENTER
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
    }

    // 浅色主题下清晰可辨的一组分类色
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

    init {
        val density = resources.displayMetrics.density
        labelPaint.textSize = 11f * density
        nowPaint.strokeWidth = 2f * density
    }

    fun setData(data: List<LogEntry>, day: LocalDate) {
        entries = data.sortedBy { it.startMin }
        date = day
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f * resources.displayMetrics.density
        val barTop = 30f * resources.displayMetrics.density
        val barBottom = height - 14f * resources.displayMetrics.density
        val barWidth = width - pad * 2f
        if (barBottom <= barTop || barWidth <= 0f) return

        // 刻度：0/3/6/.../24
        for (hour in 0..24 step 3) {
            val x = pad + barWidth * (hour / 24f)
            canvas.drawText(hour.toString(), x, barTop - 8f * resources.displayMetrics.density, labelPaint)
        }

        // 空白时段（底）
        canvas.drawRect(pad, barTop, pad + barWidth, barBottom, trackPaint)

        // 已记录时段（彩色）
        entries.forEach { e ->
            val x1 = pad + barWidth * (e.startMin.coerceIn(0, 1440) / 1440f)
            val x2 = pad + barWidth * (e.endMin.coerceIn(0, 1440) / 1440f)
            segmentPaint.color = colorFor(e.level1)
            canvas.drawRect(x1, barTop, max(x1 + 2f, x2), barBottom, segmentPaint)
        }

        // 重叠区间（顶部红条）
        val stripeBottom = barTop + 6f * resources.displayMetrics.density
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
            canvas.drawLine(x, barTop - 4f, x, barBottom + 4f, nowPaint)
        }
    }

    /** 同一个一级分类永远同一种颜色。 */
    private fun colorFor(level1: String): Int {
        val hash = level1.hashCode() and Int.MAX_VALUE
        return palette[hash % palette.size]
    }
}
