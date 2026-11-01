package cn.greenhouse.twin.data.history

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import cn.greenhouse.twin.data.local.TwinDatabase
import cn.greenhouse.twin.data.local.TwinRecordEntity
import cn.greenhouse.twin.data.local.TwinArchiveRepresentationEntity
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONObject

class HistoryRepository(context: Context) {
    private val app = context.applicationContext
    private val database = TwinDatabase.open(app)
    private val records = database.records()
    private val representations = database.representations()
    val devices: Flow<List<String>> = records.devices()
    val devicesFlow: Flow<List<String>> = devices

    suspend fun latestObservedUtc(deviceId: String): Long? = withContext(Dispatchers.IO) { records.lastUtc(deviceId) }

    suspend fun timeBounds(deviceId: String): Pair<Long?, Long?> = withContext(Dispatchers.IO) {
        records.firstUtc(deviceId) to records.lastUtc(deviceId)
    }

    suspend fun previewCsv(uri: Uri, expectedDeviceId: String? = null): CsvImportPreview = withContext(Dispatchers.IO) {
        val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "无法读取文件名称" }
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: throw IllegalArgumentException("无法读取所选文件信息")
        require(!fileName.contains("DESIGN_DEMO", true) && !fileName.contains("protocol-example-only", true)) { "设计示例不能导入正式历史" }
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = app.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开所选CSV")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val (kind, parsed) = DigestInputStream(stream, digest).use { input ->
            InputStreamReader(input, decoder).use { CsvArchiveReader.read(it, fileName, expectedDeviceId, System.currentTimeMillis()) }
        }
        val candidates = mutableListOf<CsvCandidate>()
        val seen = mutableMapOf<String, TwinRecordEntity>()
        val rowResults = parsed.mapIndexed { index, result ->
            if (result.isFailure) return@mapIndexed CsvRowResult(index + 2, CsvRowStatus.INVALID, null,
                result.exceptionOrNull()?.message ?: "记录校验失败")
            val candidate = result.getOrThrow()
            val value = candidate.record
            val key = identity(value)
            val existing = seen[key] ?: records.findIdentity(value.deviceId, value.bootId, value.kind, value.seq)
            val archiveConflict = representations.identity(value.deviceId, value.bootId, value.kind, value.seq)
                .any { !HistoryRecordCodec.equivalent(representationRecord(it), value) }
            val duplicate = existing != null && HistoryRecordCodec.equivalent(existing, value)
            if (archiveConflict || existing != null && !duplicate) {
                CsvRowResult(candidate.rowNumber, CsvRowStatus.INVALID, key, "相同设备/启动/类型/序号的核心内容冲突，原记录不会覆盖")
            } else {
                candidates += candidate
                seen[key] = value
                CsvRowResult(candidate.rowNumber, if (duplicate) CsvRowStatus.DUPLICATE else CsvRowStatus.READY, key,
                    if (duplicate) "已有相同记录，确认时跳过" else null)
            }
        }
        val valid = candidates.map { it.record }
        val knownTimes = valid.mapNotNull { it.recordUtcMs }
        CsvImportPreview(fileName, digest.digest().joinToString("") { "%02x".format(it) }, kind,
            valid.map { it.deviceId }.distinct().sorted(), knownTimes.minOrNull(), knownTimes.maxOrNull(),
            valid.count { it.kind == "snapshot" }, valid.count { it.kind == "event" },
            rowResults.count { it.status == CsvRowStatus.DUPLICATE }, rowResults.count { it.status == CsvRowStatus.INVALID },
            valid.count { it.recordUtcMs == null }, rowResults, candidates)
    }

    suspend fun confirmImport(preview: CsvImportPreview): CsvImportResult = withContext(Dispatchers.IO) {
        val importedAt = System.currentTimeMillis()
        val outcomes = preview.rows.associateBy { it.rowNumber }.toMutableMap()
        database.withTransaction {
            preview.candidates.forEach { candidate ->
                val value = candidate.record.copy(importedAtUtcMs = importedAt)
                val existing = records.findIdentity(value.deviceId, value.bootId, value.kind, value.seq)
                val archiveConflict = representations.identity(value.deviceId, value.bootId, value.kind, value.seq)
                    .any { !HistoryRecordCodec.equivalent(representationRecord(it), value) }
                val outcome = when {
                    archiveConflict -> CsvRowResult(candidate.rowNumber, CsvRowStatus.CONFLICT, identity(value),
                        "同身份已有完整档案表示的内容冲突，未写入可信档案")
                    existing == null -> {
                        check(records.insertOrIgnore(value) != -1L) { "导入事务身份插入失败" }
                        CsvRowResult(candidate.rowNumber, CsvRowStatus.ADDED, identity(value), null)
                    }
                    HistoryRecordCodec.equivalent(existing, value) ->
                        CsvRowResult(candidate.rowNumber, CsvRowStatus.SKIPPED, identity(value), "同身份同内容，重复跳过")
                    else -> CsvRowResult(candidate.rowNumber, CsvRowStatus.CONFLICT, identity(value),
                        "同身份核心内容冲突，未覆盖已有记录")
                }
                outcomes[candidate.rowNumber] = outcome
                if (outcome.status in setOf(CsvRowStatus.ADDED, CsvRowStatus.SKIPPED)) {
                    representations.insertOrIgnore(TwinArchiveRepresentationEntity(value.deviceId, value.bootId,
                        value.kind, value.seq, preview.sha256, candidate.rowNumber, value.payloadJson,
                        checkNotNull(value.archiveReceivedAtUtcMs), checkNotNull(value.archiveName), importedAt))
                }
            }
        }
        val rows = outcomes.values.sortedBy { it.rowNumber }
        CsvImportResult(preview.fileName, rows.count { it.status == CsvRowStatus.ADDED },
            rows.count { it.status == CsvRowStatus.SKIPPED },
            rows.count { it.status == CsvRowStatus.INVALID || it.status == CsvRowStatus.CONFLICT }, rows, importedAt)
    }

    suspend fun snapshotsForReplay(deviceId: String, startUtcMs: Long, endUtcMs: Long): List<HistorySnapshot> = withContext(Dispatchers.IO) {
        require(endUtcMs > startUtcMs)
        surroundingSnapshots(deviceId, startUtcMs, endUtcMs).map(HistoryRecordCodec::snapshot)
    }

    suspend fun eventsRange(deviceId: String, startUtcMs: Long, endUtcMs: Long): List<HistoryEvent> = withContext(Dispatchers.IO) {
        records.range(deviceId, "event", startUtcMs, endUtcMs).map(HistoryRecordCodec::event)
    }

    suspend fun query(deviceId: String, metric: HistoryMetric, startUtcMs: Long, endUtcMs: Long): HistoryWindow = withContext(Dispatchers.IO) {
        require(deviceId.isNotBlank() && startUtcMs >= 0 && endUtcMs > startUtcMs) { "历史筛选范围无效" }
        val snapshots = surroundingSnapshots(deviceId, startUtcMs, endUtcMs).map(HistoryRecordCodec::snapshot)
        val eventStart = minOf(startUtcMs, snapshots.firstOrNull()?.recordUtcMs ?: startUtcMs)
        val eventEnd = maxOf(endUtcMs, snapshots.lastOrNull()?.recordUtcMs?.plus(1) ?: endUtcMs)
        val surroundingEvents = records.range(deviceId, "event", eventStart, eventEnd).map(HistoryRecordCodec::event)
        val events = surroundingEvents.filter { it.utcMs?.let { utc -> utc in startUtcMs until endUtcMs } == true }
        val selected = snapshots.filter { checkNotNull(it.recordUtcMs) in startUtcMs until endUtcMs }
        val points = selected.mapNotNull { item ->
            val observation = item.observation(metric)
            val utc = observation.sampledAtUtcMs ?: return@mapNotNull null
            if (utc !in startUtcMs until endUtcMs) return@mapNotNull null
            HistoryPoint(utc, observation.value?.toDouble(), observation.validity, observation.source,
                item.identity.bootId, item.identity.seq)
        }
        val segments = mutableListOf<List<HistoryPoint>>()
        var segment = mutableListOf<HistoryPoint>()
        var previous: HistorySnapshot? = null
        selected.forEach { item ->
            val observation = item.observation(metric)
            val utc = observation.sampledAtUtcMs
            val valid = observation.validity == ObservationValidity.VALID && observation.value != null &&
                utc != null && utc in startUtcMs until endUtcMs
            if (!valid || (previous != null && !continuous(checkNotNull(previous), item))) {
                if (segment.isNotEmpty()) segments += segment
                segment = mutableListOf()
            }
            if (valid) segment += HistoryPoint(checkNotNull(utc), checkNotNull(observation.value).toDouble(),
                observation.validity, observation.source, item.identity.bootId, item.identity.seq)
            previous = item
        }
        if (segment.isNotEmpty()) segments += segment
        val metricIntervals = snapshots.zipWithNext().mapNotNull { (a, b) ->
            if (!continuous(a, b) || listOf(a, b).any { it.observation(metric).let { o ->
                    o.validity != ObservationValidity.VALID || o.value == null || o.sampledAtUtcMs == null } }) null
            else clippedInterval(checkNotNull(a.recordUtcMs), checkNotNull(b.recordUtcMs), startUtcMs, endUtcMs)
        }
        val gaps = complement(metricIntervals, startUtcMs, endUtcMs)
        val replayIntervals = snapshots.zipWithNext().mapNotNull { (a, b) ->
            if (HistoryTimeline.actuatorEvents(a, b, surroundingEvents) == null) null
            else clippedInterval(checkNotNull(a.recordUtcMs), checkNotNull(b.recordUtcMs), startUtcMs, endUtcMs)
        }
        val valid = points.filter { it.validity == ObservationValidity.VALID && it.value != null }
        val bucketStarts = valid.map { it.utcMs / 10_000L * 10_000L }.distinct()
        val firstBucket = startUtcMs / 10_000L * 10_000L
        val expected = (endUtcMs - 1) / 10_000L - startUtcMs / 10_000L + 1
        val values = valid.map { checkNotNull(it.value) }
        HistoryWindow(deviceId, metric, startUtcMs, endUtcMs, selected, events, points, segments, gaps,
            HistoryCoverage(startUtcMs, endUtcMs, firstBucket, validBuckets = bucketStarts.size.toLong(), expectedBuckets = expected),
            HistoryStatistics(if (values.isEmpty()) null else values.average(), values.minOrNull(), values.maxOrNull(), values.size),
            durations(snapshots, surroundingEvents, startUtcMs, endUtcMs), records.unknownUtcCount(deviceId),
            complement(replayIntervals, startUtcMs, endUtcMs))
    }

    private suspend fun surroundingSnapshots(deviceId: String, start: Long, end: Long): List<TwinRecordEntity> =
        (listOfNotNull(records.snapshotBefore(deviceId, start)) + records.range(deviceId, "snapshot", start, end) +
            listOfNotNull(records.snapshotAfter(deviceId, end))).distinctBy(::identity).sortedBy { it.recordUtcMs }

    private fun identity(record: TwinRecordEntity) = "${record.deviceId}/${record.bootId}/${record.kind}/${record.seq}"

    private fun representationRecord(value: TwinArchiveRepresentationEntity): TwinRecordEntity {
        val raw = JSONObject(value.payloadJson)
        return TwinRecordEntity(value.deviceId, value.bootId, value.kind, value.seq, raw.getLong("stateVersion"),
            raw.getInt("configVersion"), raw.getInt("modelVersion"), if (raw.isNull("utcMs")) null else raw.getLong("utcMs"),
            raw.getLong("uptimeMs"), null, null, value.payloadJson, "ARCHIVE", HistoryRecordCodec.EXPANDED,
            archiveReceivedAtUtcMs = value.archiveReceivedAtUtcMs, archiveName = value.archiveName,
            importedAtUtcMs = value.importedAtUtcMs)
    }

    private fun continuous(a: HistorySnapshot, b: HistorySnapshot): Boolean = HistoryTimeline.continuous(a, b)

    private fun clippedInterval(a: Long, b: Long, start: Long, end: Long): Pair<Long, Long>? {
        val left = maxOf(a, start); val right = minOf(b, end)
        return if (right > left) left to right else null
    }

    private fun complement(intervals: List<Pair<Long, Long>>, start: Long, end: Long): List<HistoryGap> {
        val result = mutableListOf<HistoryGap>()
        var cursor = start
        intervals.sortedBy { it.first }.forEach { (left, right) ->
            if (left > cursor) result += HistoryGap(cursor, left, "无有效记录或采集间隔超过15秒/启动或序号不连续")
            cursor = maxOf(cursor, right)
        }
        if (cursor < end) result += HistoryGap(cursor, end, "无有效记录或缺少下一条快照，状态未知")
        return result
    }

    // 只统计两条连续同启动快照所证明的区间；即时执行事件可细分该区间，不外推缺口。
    private fun durations(snapshots: List<HistorySnapshot>, events: List<HistoryEvent>, start: Long, end: Long): List<ActuatorHistoryDuration> =
        ActuatorKey.entries.map { target ->
            var running = 0L; var covered = 0L; var count = 0
            fun value(snapshot: HistorySnapshot): Boolean = when (target) {
                ActuatorKey.PUMP -> snapshot.state.pump.reported == ReportedState.ON
                ActuatorKey.LAMP -> snapshot.state.lamp.reported == ReportedState.ON
                ActuatorKey.FAN -> snapshot.state.fan.reported == ReportedState.ON
            }
            snapshots.zipWithNext().forEach { (a, b) ->
                if (!continuous(a, b)) return@forEach
                val aTime = checkNotNull(a.recordUtcMs); val bTime = checkNotNull(b.recordUtcMs)
                val clipped = clippedInterval(aTime, bTime, start, end) ?: return@forEach
                val intervalEvents = HistoryTimeline.actuatorEvents(a, b, events) ?: return@forEach
                var state = value(a)
                var cursor = aTime
                var intervalRunning = 0L
                var intervalCount = 0
                var eventChainValid = true
                val changes = intervalEvents.filter { it.target.equals(target.name, true) && it.newValueJson in setOf("true", "false") &&
                    it.oldValueJson in setOf("true", "false") }
                    .sortedWith(compareBy({ it.utcMs }, { it.seq }))
                changes.forEach { event ->
                    val at = checkNotNull(event.utcMs)
                    if ((event.oldValueJson == "true") != state) eventChainValid = false
                    val length = clippedInterval(cursor, at, start, end)?.let { it.second - it.first } ?: 0
                    if (state) intervalRunning += length
                    val next = event.newValueJson == "true"
                    if (!state && next && at in start until end) intervalCount++
                    state = next; cursor = at
                }
                if (changes.isEmpty() && value(a) != value(b)) {
                    // 没有即时事件时，只能证明两个观测值，转换区间不计运行时长。
                    return@forEach
                }
                if (!eventChainValid || state != value(b)) return@forEach
                if (state) intervalRunning += clippedInterval(cursor, bTime, start, end)?.let { it.second - it.first } ?: 0
                running += intervalRunning
                count += intervalCount
                covered += clipped.second - clipped.first
            }
            ActuatorHistoryDuration(target, count, running, covered, end - start)
        }
}
