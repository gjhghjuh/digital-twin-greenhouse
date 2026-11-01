package cn.greenhouse.twin.domain.history

import cn.greenhouse.twin.domain.*

enum class HistoryMetric(val label: String, val unit: String, val source: ObservationSource) {
    TEMPERATURE("温度", "°C", ObservationSource.SENSOR),
    HUMIDITY("空气湿度", "%", ObservationSource.SENSOR),
    LIGHT("光照档位", "档", ObservationSource.EXPERIMENT),
    SOIL("土壤湿度", "%", ObservationSource.SIMULATION),
}

data class HistorySnapshot(
    val state: GreenhouseTwinState,
    val recordUtcMs: Long?,
    val archiveName: String?,
    val recordSource: String,
) {
    val identity: SnapshotIdentity get() = checkNotNull(state.snapshotIdentity)
    fun observation(metric: HistoryMetric): Observation<out Number> = when (metric) {
        HistoryMetric.TEMPERATURE -> state.temperature
        HistoryMetric.HUMIDITY -> state.humidity
        HistoryMetric.LIGHT -> state.lightLevel
        HistoryMetric.SOIL -> state.soilHumidity
    }
}

data class HistoryEvent(
    val deviceId: String, val bootId: String, val seq: Long, val stateVersion: Long,
    val utcMs: Long?, val uptimeMs: Long, val eventId: String, val eventType: String,
    val target: String, val oldValueJson: String, val newValueJson: String,
    val commandId: String, val reason: String, val operator: String,
)

data class HistoryPoint(
    val utcMs: Long, val value: Double?, val validity: ObservationValidity,
    val source: ObservationSource, val bootId: String, val seq: Long,
)

data class HistoryGap(val startUtcMs: Long, val endUtcMs: Long, val reason: String)

data class HistoryCoverage(
    val startUtcMs: Long, val endUtcMs: Long, val firstBucketStartUtcMs: Long,
    val bucketMs: Long = 10_000, val validBuckets: Long, val expectedBuckets: Long,
) {
    val percent: Double get() = if (expectedBuckets == 0L) 0.0 else validBuckets * 100.0 / expectedBuckets
}

data class HistoryStatistics(val average: Double?, val minimum: Double?, val maximum: Double?, val validRecords: Int)

data class ActuatorHistoryDuration(
    val target: ActuatorKey, val runCount: Int, val runningMs: Long, val coveredMs: Long,
    val selectedMs: Long,
)

data class HistoryWindow(
    val deviceId: String, val metric: HistoryMetric, val startUtcMs: Long, val endUtcMs: Long,
    val snapshots: List<HistorySnapshot>, val events: List<HistoryEvent>,
    val points: List<HistoryPoint>, val segments: List<List<HistoryPoint>>, val gaps: List<HistoryGap>,
    val coverage: HistoryCoverage, val statistics: HistoryStatistics,
    val actuatorDurations: List<ActuatorHistoryDuration>, val unknownUtcCount: Int,
    val replayGaps: List<HistoryGap>,
)

enum class CsvRowStatus { READY, DUPLICATE, INVALID, ADDED, SKIPPED, CONFLICT }

data class CsvRowResult(val rowNumber: Int, val status: CsvRowStatus, val identity: String?, val reason: String?)

data class CsvImportResult(
    val fileName: String, val added: Int, val duplicate: Int, val notImported: Int,
    val rows: List<CsvRowResult>, val importedAtUtcMs: Long,
)
