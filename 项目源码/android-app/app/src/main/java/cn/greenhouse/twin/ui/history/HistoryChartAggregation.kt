package cn.greenhouse.twin.ui.history

import cn.greenhouse.twin.domain.ObservationSource
import cn.greenhouse.twin.domain.ObservationValidity
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.history.HistoryPoint
import cn.greenhouse.twin.domain.history.HistoryWindow

// 汇总保留全部真实采样身份，便于将显示值与历史记录逐条核验。
internal data class HistoryChartAggregate(
    val bucketStartUtcMs: Long,
    val bucketEndUtcMs: Long,
    val firstUtcMs: Long,
    val lastUtcMs: Long,
    val value: Double,
    val samples: List<HistoryPoint>,
    val sources: List<ObservationSource>,
    val validTenSecondBuckets: Long,
    val expectedTenSecondBuckets: Long,
) {
    val recordCount: Int get() = samples.size
    val centerUtcMs: Long get() = bucketStartUtcMs + (bucketEndUtcMs - bucketStartUtcMs) / 2
    val coveragePercent: Double get() = validTenSecondBuckets * 100.0 / expectedTenSecondBuckets
}

internal fun historyChartBucketDurationMs(windowDurationMs: Long): Long = when (windowDurationMs) {
    3_600_000L -> 300_000L
    21_600_000L -> 1_800_000L
    86_400_000L -> 7_200_000L
    604_800_000L -> 43_200_000L
    else -> error("图表汇总仅接受已定义的1小时、6小时、24小时、7天窗口")
}

internal fun historyChartBucketLabel(bucketDurationMs: Long): String = when (bucketDurationMs) {
    300_000L -> "5分钟"
    1_800_000L -> "30分钟"
    7_200_000L -> "2小时"
    43_200_000L -> "12小时"
    else -> error("未定义的图表汇总桶宽")
}

// 时间桶锚定查询起点，采用左闭右开区间；空桶不生成柱，也不补入零值。
internal fun aggregateHistoryChart(window: HistoryWindow): List<HistoryChartAggregate> {
    val bucketMs = historyChartBucketDurationMs(window.endUtcMs - window.startUtcMs)
    val validPoints = window.points.filter {
        it.validity == ObservationValidity.VALID && it.value != null &&
            it.utcMs >= window.startUtcMs && it.utcMs < window.endUtcMs
    }.sortedBy { it.utcMs }
    validPoints.forEach { point ->
        val value = checkNotNull(point.value)
        check(value.isFinite())
        if (window.metric == HistoryMetric.LIGHT) check(value in 0.0..7.0 && value == value.toInt().toDouble())
    }
    return validPoints.groupBy { (it.utcMs - window.startUtcMs) / bucketMs }.map { (bucketIndex, points) ->
        val start = window.startUtcMs + bucketIndex * bucketMs
        val end = minOf(start + bucketMs, window.endUtcMs)
        // 断档不改变时段统计定义；折线是否连续仍完全由原有segments决定。
        val value = if (window.metric == HistoryMetric.LIGHT) checkNotNull(points.last().value)
            else points.map { checkNotNull(it.value) }.average()
        HistoryChartAggregate(
            bucketStartUtcMs = start,
            bucketEndUtcMs = end,
            firstUtcMs = points.first().utcMs,
            lastUtcMs = points.last().utcMs,
            value = value,
            samples = points,
            sources = points.map { it.source }.distinct(),
            validTenSecondBuckets = points.map { Math.floorDiv(it.utcMs, 10_000L) }.distinct().size.toLong(),
            expectedTenSecondBuckets = Math.floorDiv(end - 1, 10_000L) - Math.floorDiv(start, 10_000L) + 1,
        )
    }
}
