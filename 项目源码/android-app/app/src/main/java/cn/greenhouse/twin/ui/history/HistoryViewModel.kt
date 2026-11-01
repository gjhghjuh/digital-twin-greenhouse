package cn.greenhouse.twin.ui.history

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.GreenhouseApplication
import cn.greenhouse.twin.data.history.HistoryRepository
import cn.greenhouse.twin.domain.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HistoryRepository(application)
    private val live = (application as GreenhouseApplication).homeRepository
    private val mutableHistory = MutableStateFlow(HistoryUiState())
    val history = mutableHistory.asStateFlow()
    private val mutableImport = MutableStateFlow<CsvImportUiState?>(null)
    val csvImport = mutableImport.asStateFlow()
    private val mutableReplay = MutableStateFlow(ReplayUiState())
    val replay = mutableReplay.asStateFlow()
    private var playback = ReplayState()
    private var replayActive = false
    private var queryJob: Job? = null
    private var replayLoadJob: Job? = null
    private var replayLoadGeneration = 0L
    private var fileJob: Job? = null
    private var anchorUtcMs: Long? = null
    private var foreground = true

    init {
        viewModelScope.launch {
            repository.devices.collect { devices ->
                val selected = mutableHistory.value.selectedDeviceId
                mutableHistory.value = mutableHistory.value.copy(devices = devices)
                if (selected == null && devices.isNotEmpty()) selectDevice(devices.first())
            }
        }
        viewModelScope.launch {
            var previous = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                if (playback.playing && foreground) {
                    val window = playback.window
                    val position = playback.positionUtcMs
                    if (window != null && position != null) {
                        val next = (position + ((now - previous) * playback.speed).toLong()).coerceAtMost(window.endUtcMs - 1)
                        playback = playback.copy(positionUtcMs = next, playing = next < window.endUtcMs - 1)
                        publishReplay()
                    }
                }
                previous = now
            }
        }
    }

    fun selectDevice(deviceId: String) {
        anchorUtcMs = null
        mutableHistory.value = mutableHistory.value.copy(selectedDeviceId = deviceId)
        refresh()
    }

    fun selectMetric(metric: HistoryMetric) {
        mutableHistory.value = mutableHistory.value.copy(metric = metric)
        refresh()
    }

    fun selectRange(range: HistoryRange) {
        mutableHistory.value = mutableHistory.value.copy(range = range)
        refresh()
    }

    fun shiftWindow(direction: Int) {
        val current = mutableHistory.value
        val end = anchorUtcMs ?: current.window?.endUtcMs ?: return
        anchorUtcMs = (end + direction * current.range.durationMs).coerceAtLeast(current.range.durationMs)
        refresh()
    }

    fun latest() { anchorUtcMs = null; refresh() }

    fun refresh() {
        queryJob?.cancel()
        cancelReplayLoad()
        val selected = mutableHistory.value
        val device = selected.selectedDeviceId
        if (device == null) {
            mutableHistory.value = selected.copy(window = null, loading = false, error = null)
            if (replayActive) {
                playback = ReplayState(foreground = foreground)
                mutableReplay.value = ReplayUiState()
            }
            return
        }
        if (replayActive) {
            playback = ReplayState(foreground = foreground)
            mutableReplay.value = ReplayUiState(loading = true)
        }
        mutableHistory.value = selected.copy(loading = true, error = null)
        queryJob = viewModelScope.launch {
            try {
                val latest = repository.latestObservedUtc(device)
                val end = anchorUtcMs ?: latest?.let { (it / 10_000 + 1) * 10_000 } ?: System.currentTimeMillis()
                anchorUtcMs = end
                val window = repository.query(device, selected.metric, (end - selected.range.durationMs).coerceAtLeast(0), end)
                ensureActive()
                mutableHistory.value = mutableHistory.value.copy(window = window, loading = false, error = null, endUtcMs = end)
                exportWindow(window)
                if (replayActive) initializePlayback(window)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                mutableHistory.value = mutableHistory.value.copy(loading = false, error = "历史读取失败：${error.message ?: error.javaClass.simpleName}")
                if (replayActive) mutableReplay.value = mutableReplay.value.copy(loading = false, error = mutableHistory.value.error)
            }
        }
    }

    fun openImport() { mutableImport.value = CsvImportUiState() }
    fun closeImport() { if (mutableImport.value?.importing != true) { fileJob?.cancel(); mutableImport.value = null } }
    fun chooseFile(uri: Uri) {
        fileJob?.cancel()
        mutableImport.value = CsvImportUiState(loading = true)
        fileJob = viewModelScope.launch {
            try {
                val preview = repository.previewCsv(uri, mutableHistory.value.selectedDeviceId)
                mutableImport.value = CsvImportUiState(preview = preview, fileName = preview.fileName)
                diagnostic("csv-preview", JSONObject().put("name", preview.fileName).put("sha256", preview.sha256)
                    .put("snapshots", preview.snapshotCount).put("events", preview.eventCount)
                    .put("duplicate", preview.duplicateCount).put("invalid", preview.invalidCount).put("unknownUtc", preview.unknownUtcCount))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableImport.value = CsvImportUiState(error = error.message ?: "CSV读取失败") }
        }
    }

    fun confirmImport() {
        val current = mutableImport.value ?: return
        val preview = current.preview ?: return
        if (current.importing) return
        mutableImport.value = current.copy(importing = true)
        fileJob = viewModelScope.launch {
            try {
                val result = repository.confirmImport(preview)
                mutableImport.value = CsvImportUiState(result = result, fileName = result.fileName)
                diagnostic("csv-result", JSONObject().put("name", result.fileName).put("sha256", preview.sha256)
                    .put("added", result.added).put("duplicate", result.duplicate).put("notImported", result.notImported)
                    .put("rows", JSONArray(result.rows.map { JSONObject().put("row", it.rowNumber).put("status", it.status.name)
                        .put("identity", it.identity ?: JSONObject.NULL).put("reason", it.reason ?: JSONObject.NULL) })))
                refresh()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableImport.value = current.copy(importing = false, error = "导入事务失败，未登记成功：${error.message}") }
        }
    }

    fun startReplay() {
        if (replayActive) return
        replayActive = true
        live.enterReplay()
        val current = mutableHistory.value
        val window = current.window
        // 正在筛选或读取失败时，旧窗口不能作为本次回放的记录来源。
        if (window == null || current.loading || current.error != null) refresh()
        else initializePlayback(window)
    }

    private fun cancelReplayLoad() {
        replayLoadGeneration++
        replayLoadJob?.cancel()
        replayLoadJob = null
    }

    private fun initializePlayback(window: HistoryWindow) {
        cancelReplayLoad()
        val acceptedGeneration = replayLoadGeneration
        playback = ReplayState(foreground = foreground)
        mutableReplay.value = ReplayUiState(loading = true)
        replayLoadJob = viewModelScope.launch {
            try {
                val snapshots = repository.snapshotsForReplay(window.deviceId, window.startUtcMs, window.endUtcMs)
                val events = repository.eventsRange(window.deviceId,
                    minOf(window.startUtcMs, snapshots.firstOrNull()?.recordUtcMs ?: window.startUtcMs),
                    maxOf(window.endUtcMs, snapshots.lastOrNull()?.recordUtcMs?.plus(1) ?: window.endUtcMs))
                ensureActive()
                // 退出、重新进入或切换窗口后，旧任务不得覆盖新的回放会话。
                if (!replayActive || acceptedGeneration != replayLoadGeneration) return@launch
                val start = window.snapshots.firstOrNull()?.recordUtcMs ?: window.startUtcMs
                playback = ReplayState(window.copy(snapshots = snapshots, events = events), start, foreground = foreground)
                publishReplay()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ensureActive()
                if (!replayActive || acceptedGeneration != replayLoadGeneration) return@launch
                mutableReplay.value = ReplayUiState(error = "回放读取失败：${error.message}")
            }
        }
    }

    fun exitReplay() {
        if (!replayActive) return
        replayActive = false
        cancelReplayLoad()
        playback = ReplayState()
        mutableReplay.value = ReplayUiState()
        live.exitReplay()
    }

    fun verifyReplayControlBlock() { if (replayActive) live.verifyReplayControlBlock() }

    fun setPlaying(playing: Boolean) { playback = playback.copy(playing = playing && foreground); publishReplay() }
    fun setSpeed(speed: Float) { require(speed in setOf(0.5f, 1f, 2f, 4f)); playback = playback.copy(speed = speed); publishReplay() }
    fun seek(utcMs: Long) {
        val window = playback.window ?: return
        playback = playback.copy(positionUtcMs = utcMs.coerceIn(window.startUtcMs, window.endUtcMs - 1))
        publishReplay()
    }
    fun previousEvent() = jumpEvent(false)
    fun nextEvent() = jumpEvent(true)
    private fun jumpEvent(next: Boolean) {
        val position = playback.positionUtcMs ?: return
        val window = playback.window ?: return
        val events = window.events.mapNotNull { it.utcMs?.takeIf { time -> time in window.startUtcMs until window.endUtcMs } }.distinct().sorted()
        val destination = if (next) events.firstOrNull { it > position } else events.lastOrNull { it < position }
        destination?.let { playback = playback.copy(playing = false); seek(it) }
    }
    fun setForeground(enabled: Boolean) {
        foreground = enabled
        playback = playback.copy(foreground = enabled, playing = playback.playing && enabled)
        // 加载与读取错误保留原展示状态，前后台切换不能把它们改成空历史。
        if (replayActive && playback.window != null) publishReplay()
    }

    private fun publishReplay() {
        val state = playback.stateAtPosition()
        mutableReplay.value = ReplayUiState(playback.window, playback.positionUtcMs, playback.playing, playback.speed,
            state, gap = state == null, isUnknown = state == null, selectedEvent = playback.eventAtPosition())
        if (BuildConfig.DEBUG && replayActive) {
            File(getApplication<Application>().filesDir, "g3-replay.json").writeText(JSONObject()
                .put("positionUtcMs", playback.positionUtcMs ?: JSONObject.NULL).put("playing", playback.playing)
                .put("speed", playback.speed).put("gap", state == null).put("source", "REPLAY")
                .put("bootId", state?.snapshotIdentity?.bootId ?: JSONObject.NULL).put("seq", state?.snapshotIdentity?.seq ?: JSONObject.NULL)
                .put("pump", state?.pump?.reported?.name ?: "UNKNOWN").put("lamp", state?.lamp?.reported?.name ?: "UNKNOWN")
                .put("fan", state?.fan?.reported?.name ?: "UNKNOWN").put("canSendControl", false)
                .put("eventSeq", playback.eventAtPosition()?.seq ?: JSONObject.NULL).toString(), Charsets.UTF_8)
        }
    }

    private fun diagnostic(event: String, detail: JSONObject) {
        if (BuildConfig.DEBUG) File(getApplication<Application>().filesDir, "g3-import.jsonl").appendText(
            JSONObject().put("event", event).put("appUtcMs", System.currentTimeMillis()).put("detail", detail).toString() + "\n", Charsets.UTF_8)
    }

    private fun exportWindow(window: HistoryWindow) {
        if (!BuildConfig.DEBUG) return
        val data = JSONObject().put("deviceId", window.deviceId).put("metric", window.metric.name)
            .put("startUtcMs", window.startUtcMs).put("endUtcMs", window.endUtcMs)
            .put("snapshots", window.snapshots.size).put("events", window.events.size)
            .put("validBuckets", window.coverage.validBuckets).put("expectedBuckets", window.coverage.expectedBuckets)
            .put("coverage", window.coverage.percent).put("unknownUtcCount", window.unknownUtcCount)
            .put("average", window.statistics.average ?: JSONObject.NULL).put("minimum", window.statistics.minimum ?: JSONObject.NULL)
            .put("maximum", window.statistics.maximum ?: JSONObject.NULL).put("validRecords", window.statistics.validRecords)
            .put("gaps", JSONArray(window.gaps.map { JSONObject().put("startUtcMs", it.startUtcMs).put("endUtcMs", it.endUtcMs).put("reason", it.reason) }))
            .put("actuators", JSONArray(window.actuatorDurations.map { JSONObject().put("target", it.target.name)
                .put("runningMs", it.runningMs).put("coveredMs", it.coveredMs).put("runCount", it.runCount) }))
        File(getApplication<Application>().filesDir, "g3-history.json").writeText(data.toString(), Charsets.UTF_8)
    }
}
