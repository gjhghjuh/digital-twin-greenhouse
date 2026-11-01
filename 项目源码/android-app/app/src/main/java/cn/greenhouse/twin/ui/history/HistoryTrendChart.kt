package cn.greenhouse.twin.ui.history

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.domain.ObservationValidity
import cn.greenhouse.twin.domain.history.HistoryGap
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.history.HistoryPoint
import cn.greenhouse.twin.domain.history.HistoryWindow
import cn.greenhouse.twin.ui.theme.GreenhouseColors
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.compose.component.shape.shader.fromBrush
import com.patrykandpatrick.vico.compose.style.ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.DefaultPointConnector
import com.patrykandpatrick.vico.core.chart.decoration.Decoration
import com.patrykandpatrick.vico.core.chart.dimensions.HorizontalDimensions
import com.patrykandpatrick.vico.core.chart.draw.ChartDrawContext
import com.patrykandpatrick.vico.core.chart.layout.HorizontalLayout
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.chart.values.ChartValuesProvider
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.component.shape.ShapeComponent
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.component.shape.shader.DynamicShaders
import com.patrykandpatrick.vico.core.context.DrawContext
import com.patrykandpatrick.vico.core.context.MeasureContext
import com.patrykandpatrick.vico.core.entry.ChartEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.marker.Marker
import com.patrykandpatrick.vico.core.marker.MarkerVisibilityChangeListener
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.log10

private const val HISTORY_ENVIRONMENT_AXIS_ITEMS = 5

internal fun historyMetricColor(metric: HistoryMetric): Color = when (metric) {
    HistoryMetric.TEMPERATURE -> GreenhouseColors.Warning
    HistoryMetric.HUMIDITY -> Color(0xFF62CAD8)
    HistoryMetric.LIGHT -> Color(0xFFE7CC72)
    HistoryMetric.SOIL -> GreenhouseColors.Primary
}

@Composable
fun HistoryTrendChart(window: HistoryWindow, style: HistoryChartStyle, modifier: Modifier = Modifier) {
    val durationSeconds = (window.endUtcMs - window.startUtcMs).toDouble().div(1_000).toFloat()
    require(durationSeconds > 0f)
    val metricColor = historyMetricColor(window.metric)
    // 先相减再转为秒，避免将绝对 UTC 毫秒转成 Float 后丢失采样时刻。
    val sampleEntries = remember(window.segments, window.startUtcMs) {
        window.segments.filter { it.isNotEmpty() }.map { segment ->
            segment.map { point ->
                check(point.validity == ObservationValidity.VALID && point.value != null)
                HistorySampleChartEntry(point, relativeSeconds(point.utcMs, window.startUtcMs), point.value.toFloat())
            }
        }
    }
    val bucketDurationMs = historyChartBucketDurationMs(window.endUtcMs - window.startUtcMs)
    val aggregates = remember(window) { aggregateHistoryChart(window) }
    val entries: List<List<ChartEntry>> = remember(sampleEntries, aggregates, style, window.startUtcMs) {
        val aggregateEntries = aggregates.map {
            HistoryAggregateChartEntry(it, relativeSeconds(it.centerUtcMs, window.startUtcMs), it.value.toFloat())
        }
        when (style) {
            HistoryChartStyle.LINE -> sampleEntries
            HistoryChartStyle.COLUMN -> if (aggregateEntries.isEmpty()) emptyList() else listOf(aggregateEntries)
            HistoryChartStyle.MIXED -> sampleEntries +
                (if (aggregateEntries.isEmpty()) emptyList() else listOf(aggregateEntries))
        }
    }
    var selected by remember(window, style) { mutableStateOf(HistoryChartSelection()) }
    val context = LocalContext.current
    // 沿用既有DEBUG私有归档，实机核验显示汇总时不改VM、数据库和原时段统计。
    LaunchedEffect(window, style, aggregates) {
        if (BuildConfig.DEBUG) withContext(Dispatchers.IO) {
            File(context.filesDir, "g3-chart.json").writeText(
                historyChartDebugJson(window, style, bucketDurationMs, aggregates).toString(), Charsets.UTF_8)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(240.dp).background(GreenhouseColors.Offline.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("所选时段暂无有效记录", color = GreenhouseColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val model = remember(entries) { checkNotNull(ChartEntryModelProducer(entries).getModel()) }
            val pointComponent = remember(metricColor) {
                ShapeComponent(shape = Shapes.pillShape, color = metricColor.toArgb())
            }
            val areaShader = remember(metricColor) {
                DynamicShaders.fromBrush(Brush.verticalGradient(listOf(
                    metricColor.copy(alpha = 0.16f), metricColor.copy(alpha = 0.015f),
                )))
            }
            val connector = remember(window.metric) {
                if (window.metric == HistoryMetric.LIGHT) HistoryStepConnector else DefaultPointConnector(cubicStrength = 0f)
            }
            val decoration = remember(window.gaps, window.startUtcMs, window.metric) {
                HistoryPlotDecoration(window.gaps, window.startUtcMs, window.metric)
            }
            val columnDecoration = remember(aggregates, window.startUtcMs, metricColor, style) {
                HistoryColumnDecoration(aggregates, window.startUtcMs, metricColor,
                    mixed = style == HistoryChartStyle.MIXED)
            }
            val marker = remember(metricColor) { HistoryPointMarker(metricColor) }
            val markerListener = remember(window, style) {
                object : MarkerVisibilityChangeListener {
                    override fun onMarkerShown(marker: Marker, markerEntryModels: List<Marker.EntryModel>) {
                        selected = historyChartSelection(markerEntryModels)
                    }
                    override fun onMarkerMoved(marker: Marker, markerEntryModels: List<Marker.EntryModel>) {
                        selected = historyChartSelection(markerEntryModels)
                    }
                }
            }
            val bottomFormatter = remember(window.startUtcMs, durationSeconds) {
                AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                    historyDateTime(window.startUtcMs + (value.toDouble() * 1_000).toLong(),
                        if (durationSeconds > 86_400f) "MM-dd" else "HH:mm")
                }
            }
            val startFormatter = remember(window.metric) {
                AxisValueFormatter<AxisPosition.Vertical.Start> { value, chartValues ->
                    if (window.metric == HistoryMetric.LIGHT) {
                        check(value in 0f..7f && value == value.toInt().toFloat())
                        value.toInt().toString()
                    } else {
                        // 精度小于最小刻度间距的一半；跨零时分别考虑两侧的较小跨度。
                        val precisionSpan = if (chartValues.minY < 0f && chartValues.maxY > 0f)
                            minOf(-chartValues.minY, chartValues.maxY) else chartValues.lengthY
                        val minimumStep = precisionSpan.toDouble() / (HISTORY_ENVIRONMENT_AXIS_ITEMS - 1)
                        val decimals = ceil(-log10(minimumStep / 2)).toInt().coerceAtLeast(0)
                        historyNumber(value.toDouble(), decimals)
                    }
                }
            }
            val startItemPlacer = remember(window.metric) {
                if (window.metric == HistoryMetric.LIGHT) HistoryLightAxisItemPlacer
                else AxisItemPlacer.Vertical.default(maxItemCount = HISTORY_ENVIRONMENT_AXIS_ITEMS)
            }
            val bottomItemPlacer = remember {
                // 三个时间标签位于区间六分之一、二分之一、六分之五，给两侧保留足够文字空间。
                AxisItemPlacer.Horizontal.default(spacing = 2, offset = 1, addExtremeLabelPadding = false)
            }
            val validValues = remember(sampleEntries, aggregates) {
                sampleEntries.flatten().map { it.y } + aggregates.map { it.value.toFloat() }
            }
            val yMin = if (window.metric == HistoryMetric.LIGHT) 0f else validValues.min()
            val yMax = if (window.metric == HistoryMetric.LIGHT) 7f else validValues.max()
            val containsColumns = style != HistoryChartStyle.LINE
            val rangeMin = if (containsColumns) minOf(0f, yMin) else yMin
            val rangeMax = if (containsColumns) maxOf(0f, yMax) else yMax
            val yPadding = if (rangeMax == rangeMin) 1f else (rangeMax - rangeMin) * 0.08f
            val range = remember(window.metric, style, durationSeconds, rangeMin, rangeMax) {
                // 百分比指标的视觉留白不越过业务范围，采样值和统计值保持原样。
                val percentMetric = window.metric == HistoryMetric.HUMIDITY || window.metric == HistoryMetric.SOIL
                AxisValuesOverrider.fixed(minX = 0f, maxX = durationSeconds,
                    minY = when {
                        window.metric == HistoryMetric.LIGHT -> 0f
                        percentMetric -> if (containsColumns) 0f else (rangeMin - yPadding).coerceIn(0f, 100f)
                        containsColumns -> rangeMin
                        else -> rangeMin - yPadding
                    },
                    maxY = when {
                        window.metric == HistoryMetric.LIGHT -> 7f
                        percentMetric -> (rangeMax + yPadding).coerceIn(0f, 100f)
                        else -> rangeMax + yPadding
                    })
            }
            val chartStyle = remember(metricColor) {
                ChartStyle(
                    ChartStyle.Axis(axisLabelColor = GreenhouseColors.Muted,
                        axisGuidelineColor = GreenhouseColors.Secondary.copy(alpha = 0.06f),
                        axisLineColor = Color.Transparent),
                    ChartStyle.ColumnChart(listOf(LineComponent(metricColor.toArgb(), 2f))),
                    ChartStyle.LineChart(listOf(LineChart.LineSpec(lineColor = metricColor.toArgb()))),
                    ChartStyle.Marker(), GreenhouseColors.Surface,
                )
            }
            // 切换查询或样式时重新建立原生视窗，消除旧缩放和滚动状态影响新时段的场景。
            key(style, window.metric, window.startUtcMs, window.endUtcMs) {
            ProvideChartStyle(chartStyle) {
                // 每个有效片段是独立序列，Vico 不会将缺口两侧的采样点连起来。
                Chart(
                    // 连续片段突出折线，孤立记录保留单点；触摸标记不受影响。
                    chart = lineChart(lines = entries.map { segment ->
                        if (segment.first() is HistoryAggregateChartEntry) {
                            // 透明序列仅注册原生Vico触摸位置；柱由同坐标系Decoration按桶宽绘制。
                            lineSpec(lineColor = Color.Transparent, lineThickness = 0.dp,
                                point = null, pointConnector = DefaultPointConnector(cubicStrength = 0f))
                        } else lineSpec(lineColor = metricColor,
                            lineThickness = 2.25.dp,
                            // 混合图突出真实采样线和时段汇总柱，不再重叠面积填充。
                            lineBackgroundShader = if (style == HistoryChartStyle.LINE && segment.size > 1) areaShader else null,
                            point = if (segment.size == 1) pointComponent else null,
                            pointSize = 3.dp, pointConnector = connector)
                    }, decorations = if (containsColumns) listOf(decoration, columnDecoration) else listOf(decoration),
                        axisValuesOverrider = range, spacing = 36.dp),
                    model = model,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    startAxis = rememberStartAxis(valueFormatter = startFormatter, title = window.metric.unit,
                        itemPlacer = startItemPlacer, axis = null, tick = null, guideline = null),
                    bottomAxis = rememberBottomAxis(valueFormatter = bottomFormatter, guideline = null,
                        itemPlacer = bottomItemPlacer, axis = null, tick = null),
                    marker = marker,
                    markerVisibilityChangeListener = markerListener,
                    chartScrollSpec = rememberChartScrollSpec(),
                    isZoomEnabled = true,
                    horizontalLayout = HorizontalLayout.FullWidth(),
                    getXStep = { durationSeconds / 6f },
                )
            }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (style != HistoryChartStyle.COLUMN) HistoryLegendItem("真实采样折线", metricColor, false)
            if (style != HistoryChartStyle.LINE) HistoryLegendItem(
                historyChartBucketLabel(bucketDurationMs) +
                    if (window.metric == HistoryMetric.LIGHT) "末次实际档位" else "有效值均值",
                metricColor.copy(alpha = 0.45f), true)
            HistoryLegendItem("采集缺口", GreenhouseColors.Offline.copy(alpha = 0.16f), true)
        }
        HistorySelectionCard(selected, window.metric, metricColor, entries.isEmpty(), style)
        Text("横向拖动与双指缩放 · 北京时间", color = GreenhouseColors.Muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HistoryLegendItem(label: String, color: Color, block: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(if (block) 8.dp else 16.dp).height(if (block) 8.dp else 2.dp).background(color))
        Text(label, color = GreenhouseColors.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HistorySelectionCard(selection: HistoryChartSelection, metric: HistoryMetric, color: Color,
    empty: Boolean, style: HistoryChartStyle) {
    val point = selection.point
    val aggregate = selection.aggregate
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = GreenhouseColors.Overview, border = BorderStroke(1.dp, color.copy(alpha = 0.16f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (point == null && aggregate == null) {
                Text(if (empty) "暂无可选择的有效采样" else when (style) {
                    HistoryChartStyle.LINE -> "触摸折线查看采样"
                    HistoryChartStyle.COLUMN -> "触摸柱状查看时段汇总"
                    HistoryChartStyle.MIXED -> "触摸折线或柱状查看记录"
                }, color = GreenhouseColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium)
                Text("真实时间、数值与来源", color = GreenhouseColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (point != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("选中采样", color = GreenhouseColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("有效", color = color, style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(historyNumber(point.value, if (metric == HistoryMetric.LIGHT) 0 else 2), color = color,
                        style = MaterialTheme.typography.displaySmall)
                    Text(metric.unit, Modifier.padding(bottom = 4.dp), color = GreenhouseColors.Secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(historyDateTime(point.utcMs, "yyyy-MM-dd HH:mm:ss.SSS"), color = GreenhouseColors.Text,
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("来源", color = GreenhouseColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(historySourceLabel(point.source), color = GreenhouseColors.Secondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (aggregate != null) {
                Text(if (metric == HistoryMetric.LIGHT) "选中时段 · 末次实际档位" else "选中时段 · 有效值均值",
                    color = GreenhouseColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(historyNumber(aggregate.value, if (metric == HistoryMetric.LIGHT) 0 else 2), color = color,
                        style = MaterialTheme.typography.displaySmall)
                    Text(metric.unit, Modifier.padding(bottom = 4.dp), color = GreenhouseColors.Secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("时段 [${historyDateTime(aggregate.bucketStartUtcMs, "MM-dd HH:mm:ss.SSS")},",
                    color = GreenhouseColors.Text, style = MaterialTheme.typography.bodySmall)
                Text("${historyDateTime(aggregate.bucketEndUtcMs, "MM-dd HH:mm:ss.SSS")})",
                    color = GreenhouseColors.Text, style = MaterialTheme.typography.bodySmall)
                Text("实际首条 ${historyDateTime(aggregate.firstUtcMs, "MM-dd HH:mm:ss.SSS")}",
                    color = GreenhouseColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text("实际末条 ${historyDateTime(aggregate.lastUtcMs, "MM-dd HH:mm:ss.SSS")}",
                    color = GreenhouseColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text("有效记录 ${aggregate.recordCount} 条 · " + aggregate.sources.joinToString(" / ") { historySourceLabel(it) },
                    color = GreenhouseColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text("10秒桶覆盖 ${aggregate.validTenSecondBuckets} / ${aggregate.expectedTenSecondBuckets}" +
                    " · ${historyNumber(aggregate.coveragePercent, 1)}%",
                    color = GreenhouseColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text("柱宽表示汇总时段，不表示连续采集", color = GreenhouseColors.Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class HistorySampleChartEntry(val point: HistoryPoint, override val x: Float, override val y: Float) : ChartEntry {
    override fun withY(y: Float): ChartEntry = copy(y = y)
}

private data class HistoryAggregateChartEntry(val aggregate: HistoryChartAggregate,
    override val x: Float, override val y: Float) : ChartEntry {
    override fun withY(y: Float): ChartEntry = copy(y = y)
}

private data class HistoryChartSelection(val point: HistoryPoint? = null, val aggregate: HistoryChartAggregate? = null)

private fun historyChartSelection(entries: List<Marker.EntryModel>) = HistoryChartSelection(
    point = entries.firstNotNullOfOrNull { (it.entry as? HistorySampleChartEntry)?.point },
    aggregate = entries.firstNotNullOfOrNull { (it.entry as? HistoryAggregateChartEntry)?.aggregate },
)

// 光照固定八个真实整数刻度，测量和绘制共用同一组值，避免小数刻度截断后重复。
private object HistoryLightAxisItemPlacer : AxisItemPlacer.Vertical by AxisItemPlacer.Vertical.default(maxItemCount = 8) {
    private val values = (0..7).map(Int::toFloat)
    override fun getLabelValues(context: ChartDrawContext, axisHeight: Float, maxLabelHeight: Float,
        position: AxisPosition.Vertical): List<Float> = values

    override fun getHeightMeasurementLabelValues(context: MeasureContext,
        position: AxisPosition.Vertical): List<Float> = values

    override fun getWidthMeasurementLabelValues(context: MeasureContext, axisHeight: Float, maxLabelHeight: Float,
        position: AxisPosition.Vertical): List<Float> = values

    override fun getLineValues(context: ChartDrawContext, axisHeight: Float, maxLabelHeight: Float,
        position: AxisPosition.Vertical): List<Float> = values
}

private object HistoryStepConnector : LineChart.LineSpec.PointConnector {
    override fun connect(path: Path, prevX: Float, prevY: Float, x: Float, y: Float,
        horizontalDimensions: HorizontalDimensions, bounds: RectF) {
        path.lineTo(x, prevY)
        path.lineTo(x, y)
    }
}

private class HistoryPlotDecoration(private val gaps: List<HistoryGap>, private val startUtcMs: Long,
    private val metric: HistoryMetric) : Decoration {
    private val paint = Paint().apply { color = GreenhouseColors.Offline.copy(alpha = 0.045f).toArgb() }
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GreenhouseColors.Secondary.copy(alpha = 0.06f).toArgb()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GreenhouseColors.Muted.copy(alpha = 0.8f).toArgb()
        textAlign = Paint.Align.CENTER
    }
    override fun onDrawBehindChart(context: ChartDrawContext, bounds: RectF) {
        val values = context.chartValuesProvider.getChartValues()
        val direction = if (context.isLtr) 1f else -1f
        val drawingStart = (if (context.isLtr) bounds.left else bounds.right) +
            direction * context.horizontalDimensions.startPadding - context.horizontalScroll
        fun x(utcMs: Long) = drawingStart + direction *
            (relativeSeconds(utcMs, startUtcMs) - values.minX) / values.xStep * context.horizontalDimensions.xSpacing
        gaps.forEach { gap ->
            val start = x(gap.startUtcMs)
            val end = x(gap.endUtcMs)
            val left = minOf(start, end).coerceAtLeast(bounds.left)
            val right = maxOf(start, end).coerceAtMost(bounds.right)
            if (right > left) {
                context.canvas.drawRect(left, bounds.top, right, bounds.bottom, paint)
                labelPaint.textSize = context.spToPx(10f)
                // 缺口按真实时间宽度显示，仅在可容纳完整文字时标注。
                if (right - left >= labelPaint.measureText("无数据") + context.dpToPx(16f)) {
                    val baseline = bounds.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
                    context.canvas.drawText("无数据", (left + right) / 2f, baseline, labelPaint)
                }
            }
        }
        // 只保留少量极淡横向参考线，不叠竖网格或完整方格。
        val references = if (metric == HistoryMetric.LIGHT) listOf(0f, 2f, 4f, 6f)
            else (0..4).map { values.minY + it * values.lengthY / 4f }
        referencePaint.strokeWidth = context.dpToPx(0.7f)
        references.forEach { value ->
            val y = bounds.bottom - (value - values.minY) / values.lengthY * bounds.height()
            context.canvas.drawLine(bounds.left, y, bounds.right, y, referencePaint)
        }
    }
}

private class HistoryPointMarker(color: Color) : Marker {
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.copy(alpha = 0.45f).toArgb() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb(); style = Paint.Style.STROKE }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = GreenhouseColors.Surface.toArgb() }
    override fun draw(context: DrawContext, bounds: RectF, markedEntries: List<Marker.EntryModel>,
        chartValuesProvider: ChartValuesProvider) {
        markedEntries.firstOrNull()?.let { marked ->
            val x = marked.location.x
            cursorPaint.strokeWidth = context.dpToPx(0.75f)
            ringPaint.strokeWidth = context.dpToPx(1.75f)
            context.canvas.drawLine(x, bounds.top, x, bounds.bottom, cursorPaint)
            context.canvas.drawCircle(x, marked.location.y, context.dpToPx(4f), centerPaint)
            context.canvas.drawCircle(x, marked.location.y, context.dpToPx(4f), ringPaint)
        }
    }
}

// 桶柱使用原生Vico的轴、滚动、缩放与触摸模型，仅按明确的65%时桶宽度绘制背景柱。
private class HistoryColumnDecoration(private val aggregates: List<HistoryChartAggregate>,
    private val startUtcMs: Long, private val color: Color, private val mixed: Boolean) : Decoration {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDrawBehindChart(context: ChartDrawContext, bounds: RectF) {
        val values = context.chartValuesProvider.getChartValues()
        val direction = if (context.isLtr) 1f else -1f
        val drawingStart = (if (context.isLtr) bounds.left else bounds.right) +
            direction * context.horizontalDimensions.startPadding - context.horizontalScroll
        fun x(utcMs: Long) = drawingStart + direction *
            (relativeSeconds(utcMs, startUtcMs) - values.minX) / values.xStep * context.horizontalDimensions.xSpacing
        fun y(value: Float) = bounds.bottom - (value - values.minY) / values.lengthY * bounds.height()
        val baseline = y(0f)
        val saved = context.canvas.save()
        context.canvas.clipRect(bounds)
        aggregates.forEach { aggregate ->
            val insetMs = ((aggregate.bucketEndUtcMs - aggregate.bucketStartUtcMs) * 0.175).toLong()
            val start = x(aggregate.bucketStartUtcMs + insetMs)
            val end = x(aggregate.bucketEndUtcMs - insetMs)
            val left = minOf(start, end)
            val right = maxOf(start, end)
            if (right >= bounds.left && left <= bounds.right) {
                val valueY = y(aggregate.value.toFloat())
                val top = minOf(baseline, valueY)
                val bottom = maxOf(baseline, valueY)
                if (aggregate.value == 0.0) {
                    // 真实零值仍是有效时桶：只画基线细端记号，不造出非零柱高。
                    paint.shader = null
                    paint.color = color.copy(alpha = if (mixed) 0.5f else 0.7f).toArgb()
                    paint.strokeWidth = context.dpToPx(1.5f)
                    context.canvas.drawLine(left, baseline, right, baseline, paint)
                } else {
                    paint.color = Color.White.toArgb()
                    paint.shader = LinearGradient(0f, top, 0f, bottom,
                        color.copy(alpha = if (mixed) 0.30f else 0.58f).toArgb(),
                        color.copy(alpha = if (mixed) 0.08f else 0.18f).toArgb(), Shader.TileMode.CLAMP)
                    val radius = minOf(context.dpToPx(3f), (right - left) / 2f, (bottom - top) / 2f)
                    context.canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
                }
            }
        }
        context.canvas.restoreToCount(saved)
    }
}

private fun historyChartDebugJson(window: HistoryWindow, style: HistoryChartStyle,
    bucketDurationMs: Long, aggregates: List<HistoryChartAggregate>): JSONObject = JSONObject()
    .put("style", style.name)
    .put("deviceId", window.deviceId)
    .put("metric", window.metric.name)
    .put("startUtcMs", window.startUtcMs)
    .put("endUtcMs", window.endUtcMs)
    .put("bucketDurationMs", bucketDurationMs)
    .put("bucketAnchorUtcMs", window.startUtcMs)
    .put("bucketInterval", "[start,end)")
    .put("aggregation", if (window.metric == HistoryMetric.LIGHT) "LAST_VALID_LEVEL" else "VALID_VALUE_MEAN")
    .put("columnsVisible", style != HistoryChartStyle.LINE)
    .put("columnWidthFraction", 0.65)
    .put("validRecords", window.statistics.validRecords)
    .put("historyValidBuckets", window.coverage.validBuckets)
    .put("historyExpectedBuckets", window.coverage.expectedBuckets)
    .put("buckets", JSONArray(aggregates.map { aggregate ->
        JSONObject()
            .put("bucketStartUtcMs", aggregate.bucketStartUtcMs)
            .put("bucketEndUtcMs", aggregate.bucketEndUtcMs)
            .put("centerUtcMs", aggregate.centerUtcMs)
            .put("firstUtcMs", aggregate.firstUtcMs)
            .put("lastUtcMs", aggregate.lastUtcMs)
            .put("n", aggregate.recordCount)
            .put("value", aggregate.value)
            .put("sources", JSONArray(aggregate.sources.map { it.name }))
            .put("validTenSecondBuckets", aggregate.validTenSecondBuckets)
            .put("expectedTenSecondBuckets", aggregate.expectedTenSecondBuckets)
            .put("coveragePercent", aggregate.coveragePercent)
            .put("samples", JSONArray(aggregate.samples.map { point ->
                JSONObject().put("deviceId", window.deviceId).put("bootId", point.bootId).put("seq", point.seq)
                    .put("utcMs", point.utcMs).put("value", point.value).put("source", point.source.name)
                    .put("validity", point.validity.name)
            }))
    }))

private fun relativeSeconds(utcMs: Long, startUtcMs: Long): Float = ((utcMs - startUtcMs).toDouble() / 1_000).toFloat()

