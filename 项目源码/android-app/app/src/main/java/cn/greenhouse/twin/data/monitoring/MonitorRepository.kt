package cn.greenhouse.twin.data.monitoring

import android.content.Context
import cn.greenhouse.twin.BuildConfig
import cn.greenhouse.twin.data.local.ConnectionStore
import cn.greenhouse.twin.data.local.OneNetSettings
import cn.greenhouse.twin.data.local.TwinDatabase
import cn.greenhouse.twin.domain.*
import cn.greenhouse.twin.domain.history.HistoryMetric
import cn.greenhouse.twin.domain.monitoring.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

// 应用只创建一个消费者。它观察真实状态，不拥有网络查询、控制提交或后台轮询入口。
class MonitorRepository(
    context: Context,
    private val liveState: StateFlow<GreenhouseTwinState>,
    private val connectionStore: ConnectionStore,
    private val trustedNowUtcMs: () -> Long?,
) {
    private val applicationContext = context.applicationContext
    private val dao = TwinDatabase.open(applicationContext).alarms()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MonitoringUiState())
    val state: StateFlow<MonitoringUiState> = mutableState.asStateFlow()
    private val episodes = linkedMapOf<String, AlarmEpisodeEntity>()
    private data class SampleIdentity(val deviceId: String, val bootId: String, val seq: Long, val utcMs: Long)
    private val processedSamples = mutableMapOf<AlarmKind, SampleIdentity>()
    private val observedQueries = mutableMapOf<AlarmKind, Long>()
    private var previousRequest: ControlRequestState? = null
    private var monitoringSettings: MonitoringSettings? = null
    private var freshnessTicker: Job? = null
    private var lastDebugSignature: String? = null

    init {
        scope.launch {
            try {
                dao.all().forEach { episodes[it.id] = it }
                markPending("应用重新启动，最后观察状态待核验")
                combine(liveState, connectionStore.monitoringSettings) { live, settings -> live to settings }
                    .collect { (_, settings) -> mutex.withLock {
                        val currentLive = liveState.value
                        updateTicker(currentLive, settings)
                        observe(currentLive, settings)
                    } }
            } catch (failure: Exception) {
                freshnessTicker?.cancel()
                mutableState.value = mutableState.value.copy(status = MonitoringStatus.UNVERIFIED,
                    statusReason = "监测读取失败", error = failure.message ?: failure.javaClass.simpleName,
                    summary = AlarmSummary(AlarmSummaryStatus.NOT_CONNECTED, "监测读取失败", failure.message ?: "无法读取本地监测记录"))
            }
        }
    }

    suspend fun acknowledge(id: String) = mutex.withLock {
        val value = checkNotNull(episodes[id]) { "报警记录不存在" }
        if (value.acknowledgedAtUtcMs == null) {
            put(value.copy(acknowledgedAtUtcMs = System.currentTimeMillis()))
            publish(liveState.value, checkNotNull(monitoringSettings))
        }
    }

    suspend fun saveSettings(settings: MonitoringSettings) {
        settings.validate()
        connectionStore.saveMonitoringSettings(settings)
        // 保存返回前等待规则消费者处理本次配置，避免成功提示之后仍使用旧规则。
        val applied = state.first { it.settings == settings || it.error != null }
        check(applied.error == null) { "本地阈值已保存，但监测处理失败：${applied.error}" }
    }

    private fun updateTicker(live: GreenhouseTwinState, settings: MonitoringSettings) {
        if (!settings.monitorEnabled || live.stateSource != StateSource.LIVE || live.synchronizationSuspended) {
            freshnessTicker?.cancel()
            freshnessTicker = null
        } else if (freshnessTicker?.isActive != true) {
            freshnessTicker = scope.launch {
                while (isActive) {
                    delay(1_000)
                    mutex.withLock {
                        val currentLive = liveState.value
                        val currentSettings = monitoringSettings ?: return@launch
                        if (!currentSettings.monitorEnabled || currentLive.stateSource != StateSource.LIVE ||
                            currentLive.synchronizationSuspended) return@launch
                        observe(currentLive, currentSettings)
                    }
                }
            }
        }
    }

    // 打开控制面板前再次核对当前可信采样，不能把曾经有效的建议当成永久有效。
    fun currentAdvice(id: String, expectedSettings: MonitoringSettings): AdviceItem? {
        val monitored = state.value
        val settings = monitored.settings
        val live = liveState.value
        if (monitored.status != MonitoringStatus.ACTIVE || monitored.error != null ||
            settings != expectedSettings || !settings.monitorEnabled || !environmentReady(live)) return null
        return advice(live, settings).firstOrNull { it.id == id }
    }

    private suspend fun observe(live: GreenhouseTwinState, settings: MonitoringSettings) {
        settings.validate()
        val priorSettings = monitoringSettings
        monitoringSettings = settings
        if (priorSettings != null) {
            environmentKinds.filter { signature(it, priorSettings) != signature(it, settings) }
                .forEach { processedSamples.remove(it) }
        }
        for (episode in episodes.values.toList().filter { it.endedAtUtcMs == null }) {
            val kind = AlarmKind.valueOf(episode.kind)
            if (episode.ruleSignature != signature(kind, settings)) {
                finish(episode, AlarmEndReason.RULE_CHANGED, false, "规则修改，非数值恢复")
                processedSamples.remove(kind)
            }
        }
        val frontLive = live.stateSource == StateSource.LIVE && !live.synchronizationSuspended
        if (!settings.monitorEnabled || !frontLive) {
            markPending(if (!settings.monitorEnabled) "本地监测未启用，最后观察状态待核验" else "监测已暂停，最后观察状态待核验")
            processedSamples.clear()
            previousRequest = null
            publish(live, settings)
            return
        }

        observeStatus(live, settings)
        observeCommand(live, settings)
        // 同一快照会先发布 UNKNOWN 再确认 FRESH，仅在有效阶段按指标采样身份消费。
        if (environmentReady(live)) {
            for (kind in environmentKinds) {
                val threshold = threshold(kind, settings) ?: continue
                val observation = observation(kind, live)
                if (!eligible(observation, live)) {
                    pending(kind, "该指标没有新的可信有效采样，最后观察状态待核验")
                    continue
                }
                val identity = checkNotNull(live.snapshotIdentity)
                val sampleTime = checkNotNull(observation.sampledAtUtcMs)
                val sampleKey = SampleIdentity(identity.deviceId, identity.bootId, identity.seq, sampleTime)
                if (processedSamples[kind] == sampleKey) continue
                processedSamples[kind] = sampleKey
                val value = checkNotNull(observation.value).toDouble()
                val violates = if (kind in highKinds) value > threshold else value < threshold
                val existing = open(kind)
                if (violates) {
                    if (existing == null) {
                        start(kind, settings, live, observation.source, value, threshold,
                            sampleTime, detail = "首个可信有效采样越界；边界值属于正常范围")
                    } else {
                        val delta = if (!existing.verificationPending && existing.lastBootId == identity.bootId &&
                            existing.lastSeq?.plus(1) == identity.seq && existing.lastSampleUtcMs != null) {
                            (sampleTime - existing.lastSampleUtcMs).takeIf { it in 1..15_000 } ?: 0
                        } else 0
                        put(existing.copy(latestValue = value, lastObservedAtUtcMs = System.currentTimeMillis(),
                            lastSampleUtcMs = sampleTime, lastBootId = identity.bootId, lastSeq = identity.seq,
                            observedDurationMs = existing.observedDurationMs + delta, verificationPending = false,
                            detail = "当前有效采样仍越界；已证实观察时长不跨暂停、缺口或重启"))
                    }
                } else if (existing != null) {
                    put(existing.copy(latestValue = value, lastSampleUtcMs = sampleTime,
                        lastBootId = identity.bootId, lastSeq = identity.seq,
                        lastObservedAtUtcMs = System.currentTimeMillis()))
                    finish(checkNotNull(episodes[existing.id]), AlarmEndReason.VALUE_RECOVERED, true,
                        "新的可信有效采样已回到包含边界的安全范围")
                }
            }
        } else if (live.cloudQueryStatus != CloudQueryStatus.QUERYING && !awaitingFreshnessResolution(live)) {
            for (kind in environmentKinds) pending(kind, "实时数据当前不可信，最后观察状态待核验")
        }
        publish(live, settings)
    }

    private suspend fun observeStatus(live: GreenhouseTwinState, settings: MonitoringSettings) {
        if (live.cloudQueryStatus == CloudQueryStatus.SUCCESS) {
            when (live.deviceConnection) {
                DeviceConnection.OFFLINE -> statusAlarm(AlarmKind.DEVICE_OFFLINE, true, live, settings, "云端查询成功，设备报告离线", AlarmEndReason.CONNECTION_RECOVERED)
                DeviceConnection.ONLINE -> statusAlarm(AlarmKind.DEVICE_OFFLINE, false, live, settings, "云端查询成功，设备报告在线", AlarmEndReason.CONNECTION_RECOVERED)
                DeviceConnection.UNKNOWN -> pending(AlarmKind.DEVICE_OFFLINE, "设备状态未知，最后观察状态待核验")
            }
            statusAlarm(AlarmKind.QUERY_FAILURE, false, live, settings, "新的云端查询成功", AlarmEndReason.QUERY_RECOVERED)
        } else if (live.cloudQueryStatus == CloudQueryStatus.FAILED) {
            statusAlarm(AlarmKind.QUERY_FAILURE, true, live, settings,
                live.queryErrorMessage ?: "云端查询失败", AlarmEndReason.QUERY_RECOVERED)
            pending(AlarmKind.DEVICE_OFFLINE, "查询失败，无法确认设备在线状态")
        }
        observeDataStale(live, settings)
    }

    private suspend fun observeDataStale(live: GreenhouseTwinState, settings: MonitoringSettings) {
        val sample = live.snapshotUtcMs
        val now = trustedNowUtcMs()
        val identity = live.snapshotIdentity
        if (sample == null || now == null || identity == null || now - sample < -1_000) {
            pending(AlarmKind.DATA_STALE, "设备采样时间未知，最后观察状态待核验")
            return
        }
        val age = now - sample
        val existing = open(AlarmKind.DATA_STALE)
        if (age > settings.staleAfterMs) {
            if (existing == null) {
                start(AlarmKind.DATA_STALE, settings, live, null, null, null, sample,
                    detail = "真实快照采样时间超过当前本地陈旧阈值 ${settings.staleAfterMs / 1_000} 秒")
            } else if (existing.verificationPending || existing.lastBootId != identity.bootId ||
                existing.lastSeq != identity.seq || existing.lastSampleUtcMs != sample) {
                put(existing.copy(lastObservedAtUtcMs = System.currentTimeMillis(), lastBootId = identity.bootId,
                    lastSeq = identity.seq, lastSampleUtcMs = sample, verificationPending = false,
                    detail = "真实快照采样时间仍超过当前本地陈旧阈值"))
            }
        } else if (existing != null) {
            val newSample = existing.lastBootId != identity.bootId || existing.lastSeq != identity.seq ||
                existing.lastSampleUtcMs != sample
            if (age <= 25_000 && environmentReady(live) && newSample) {
                put(existing.copy(lastObservedAtUtcMs = System.currentTimeMillis(), lastBootId = identity.bootId,
                    lastSeq = identity.seq, lastSampleUtcMs = sample))
                finish(checkNotNull(episodes[existing.id]), AlarmEndReason.FRESH_DATA, true, "新的可信实时快照已恢复")
            } else pending(AlarmKind.DATA_STALE, "等待不同身份的新可信实时采样；时钟变化不代表数值恢复")
        }
    }

    private suspend fun observeCommand(live: GreenhouseTwinState, settings: MonitoringSettings) {
        val request = live.controlRequest
        val prior = previousRequest
        // 首次读到日志中的旧超时不创建报警，必须观察到本次请求的实际状态迁移。
        if (request != null && prior != null && request.commandId == prior.commandId &&
            request.status == RequestStatus.TIMED_OUT && prior.status in setOf(RequestStatus.SENDING,
                RequestStatus.WAITING, RequestStatus.UNCERTAIN)) {
            if (open(AlarmKind.COMMAND_TIMEOUT, request.commandId) == null) {
                start(AlarmKind.COMMAND_TIMEOUT, settings, live, null, null, null, null,
                    request.commandId, "当前观察到的命令等待超时；超时不代表设备未执行", request.target)
            }
        }
        if (request != null && request.status in setOf(RequestStatus.EXECUTED, RequestStatus.REJECTED, RequestStatus.INTERRUPTED)) {
            open(AlarmKind.COMMAND_TIMEOUT, request.commandId)?.let {
                finish(it, AlarmEndReason.COMMAND_RESOLVED, true,
                    "命令最终结果 ${request.status.name}${if (request.late) "，迟到回执" else ""}：${request.detail}")
            }
        }
        // 设备回执列表只代表已收到原始结果；最终核验由原控制链完成，不能据此提前恢复超时。
        previousRequest = request
    }

    private suspend fun statusAlarm(kind: AlarmKind, active: Boolean, live: GreenhouseTwinState,
        settings: MonitoringSettings, detail: String, recovery: AlarmEndReason) {
        val queryTime = live.lastQueryAtUtcMs ?: return
        if (observedQueries[kind] == queryTime) return
        observedQueries[kind] = queryTime
        val existing = open(kind)
        if (active) {
            if (existing == null) start(kind, settings, live, null, null, null, null, detail = detail)
            else {
                // 只按实际新查询累计观察，不让每秒时效检查伪造连接证据。
                val delta = if (!existing.verificationPending)
                    (queryTime - existing.lastObservedAtUtcMs).takeIf {
                        it in 1..(live.refreshSeconds * 1_000L + 10_000)
                    } ?: 0 else 0
                put(existing.copy(lastObservedAtUtcMs = queryTime, verificationPending = false,
                    observedDurationMs = existing.observedDurationMs + delta, detail = detail))
            }
        } else if (existing != null) finish(existing, recovery, true, detail)
    }

    private suspend fun start(kind: AlarmKind, settings: MonitoringSettings, live: GreenhouseTwinState,
        source: ObservationSource?, value: Double?, threshold: Double?, sampleUtcMs: Long?,
        commandId: String? = null, detail: String, commandTarget: ActuatorKey? = null) {
        val now = System.currentTimeMillis()
        val identity = live.snapshotIdentity
        val episode = AlarmEpisodeEntity(UUID.randomUUID().toString(),
            identity?.deviceId ?: OneNetSettings.PROJECT_DEVICE_NAME, kind.name, AlarmSeverity.WARNING.name,
            source?.name, value, value, threshold, kind.metric?.unit ?: "", now, now,
            sampleUtcMs, sampleUtcMs, identity?.bootId, identity?.seq, identity?.bootId, identity?.seq,
            0, null, null, null, null, false, commandId, detail, signature(kind, settings), commandTarget?.name)
        dao.insert(episode)
        episodes[episode.id] = episode
    }

    private suspend fun finish(value: AlarmEpisodeEntity, reason: AlarmEndReason, recovered: Boolean, detail: String) {
        val now = System.currentTimeMillis()
        put(value.copy(endedAtUtcMs = now, recoveredAtUtcMs = if (recovered) now else null,
            endedReason = reason.name, verificationPending = false, detail = detail))
    }

    private suspend fun pending(kind: AlarmKind, detail: String) {
        for (episode in episodes.values.toList().filter { it.kind == kind.name && it.endedAtUtcMs == null }) {
            if (!episode.verificationPending || episode.detail != detail) put(episode.copy(verificationPending = true, detail = detail))
        }
    }

    private suspend fun markPending(detail: String) {
        for (episode in episodes.values.toList().filter { it.endedAtUtcMs == null }) {
            if (!episode.verificationPending || episode.detail != detail) put(episode.copy(verificationPending = true, detail = detail))
        }
    }

    private suspend fun put(value: AlarmEpisodeEntity) {
        dao.update(value)
        episodes[value.id] = value
    }

    private fun open(kind: AlarmKind, commandId: String? = null): AlarmEpisodeEntity? = episodes.values.firstOrNull {
        it.kind == kind.name && it.commandId == commandId && it.endedAtUtcMs == null
    }

    private fun environmentReady(live: GreenhouseTwinState) = live.stateSource == StateSource.LIVE &&
        !live.synchronizationSuspended && live.synchronizationStatus == SynchronizationStatus.READY &&
        live.cloudQueryStatus == CloudQueryStatus.SUCCESS && live.deviceConnection == DeviceConnection.ONLINE &&
        live.freshness == DataFreshness.FRESH && live.snapshotIdentity != null && trustedNowUtcMs() != null

    // 解析后的瞬时 UNKNOWN 不等于采样断档；同轮可信时钟确认完成后才判断新样本。
    private fun awaitingFreshnessResolution(live: GreenhouseTwinState): Boolean {
        if (live.cloudQueryStatus != CloudQueryStatus.SUCCESS || live.freshness != DataFreshness.UNKNOWN ||
            live.deviceConnection != DeviceConnection.ONLINE || live.synchronizationStatus != SynchronizationStatus.READY) return false
        val snapshot = live.snapshotUtcMs ?: return false
        val now = trustedNowUtcMs() ?: return false
        return now - snapshot in -1_000..25_000
    }

    private fun eligible(observation: Observation<out Number>, live: GreenhouseTwinState): Boolean {
        if (observation.validity != ObservationValidity.VALID || observation.value?.toDouble()?.isFinite() != true) return false
        val sample = observation.sampledAtUtcMs ?: return false
        val sampleUptime = observation.sampledUptimeMs ?: return false
        val snapshot = live.snapshotUtcMs ?: return false
        val snapshotUptime = live.snapshotUptimeMs ?: return false
        val now = trustedNowUtcMs() ?: return false
        return sample > 0 && sampleUptime >= 0 && sampleUptime <= snapshotUptime &&
            sample <= snapshot + 1_000 && now - sample in -1_000..25_000
    }

    private fun observation(kind: AlarmKind, live: GreenhouseTwinState): Observation<out Number> = when (kind.metric) {
        HistoryMetric.TEMPERATURE -> live.temperature
        HistoryMetric.HUMIDITY -> live.humidity
        HistoryMetric.LIGHT -> live.lightLevel
        HistoryMetric.SOIL -> live.soilHumidity
        null -> error("状态报警没有环境指标")
    }

    private fun threshold(kind: AlarmKind, settings: MonitoringSettings): Double? = when (kind) {
        AlarmKind.LOW_TEMPERATURE -> settings.temperatureLowC
        AlarmKind.HIGH_TEMPERATURE -> settings.temperatureHighC
        AlarmKind.LOW_HUMIDITY -> settings.humidityLowPercent
        AlarmKind.HIGH_HUMIDITY -> settings.humidityHighPercent
        AlarmKind.LOW_SOIL_MOISTURE -> settings.soilLowPercent
        AlarmKind.LOW_LIGHT -> settings.lightLowLevel?.toDouble()
        else -> null
    }

    private fun signature(kind: AlarmKind, settings: MonitoringSettings): String =
        "${settings.monitorEnabled}:${kind.name}:${if (kind == AlarmKind.DATA_STALE) settings.staleAfterMs else threshold(kind, settings)}"

    private fun publish(live: GreenhouseTwinState, settings: MonitoringSettings) {
        val alarms = episodes.values.map { it.episode() }.sortedByDescending { it.startedAtUtcMs }
        val current = alarms.filter { it.isOpen }
        val frontLive = live.stateSource == StateSource.LIVE && !live.synchronizationSuspended
        val configured = environmentKinds.filter { threshold(it, settings) != null }
        val allMetricsKnown = configured.isNotEmpty() && configured.all { eligible(observation(it, live), live) }
        val status = when {
            !settings.monitorEnabled -> MonitoringStatus.DISABLED
            !frontLive -> MonitoringStatus.PAUSED
            !environmentReady(live) || !allMetricsKnown -> MonitoringStatus.UNVERIFIED
            else -> MonitoringStatus.ACTIVE
        }
        val reason = when (status) {
            MonitoringStatus.DISABLED -> "本地规则尚未启用"
            MonitoringStatus.PAUSED -> "后台、设计预览或回放期间暂停实时监测"
            MonitoringStatus.UNVERIFIED -> "等待新的可信有效实时数据；未知状态不等于正常"
            MonitoringStatus.ACTIVE -> "前台实时监测；确认仅表示已读，数值恢复需要新采样"
        }
        val advice = if (settings.monitorEnabled && environmentReady(live)) advice(live, settings) else emptyList()
        val summary = when {
            current.isNotEmpty() -> AlarmSummary(AlarmSummaryStatus.WARNING, "${current.size} 项未结束报警",
                if (status != MonitoringStatus.ACTIVE || current.any { it.verificationPending }) "保留最后观察，当前状态待核验" else current.first().title,
                metricWarnings = if (status == MonitoringStatus.ACTIVE) current.filter {
                    it.metric != null && !it.verificationPending
                }.associate { checkNotNull(it.metric) to it.kind.label } else emptyMap(),
                affectedCategories = current.map { when (it.metric) {
                    HistoryMetric.TEMPERATURE -> AlarmCategory.TEMPERATURE
                    HistoryMetric.HUMIDITY, HistoryMetric.SOIL -> AlarmCategory.WATER
                    HistoryMetric.LIGHT, null -> AlarmCategory.EQUIPMENT
                } }.toSet(), allAcknowledged = current.all { it.isAcknowledged })
            status == MonitoringStatus.ACTIVE -> AlarmSummary(AlarmSummaryStatus.NONE, "已配置规则无报警", "仅代表当前可信采样的已配置本地规则")
            else -> AlarmSummary(AlarmSummaryStatus.NOT_CONNECTED, if (status == MonitoringStatus.PAUSED) "监测已暂停" else "监测尚未核实", reason)
        }
        val unavailableReason = if (status == MonitoringStatus.ACTIVE) null else when {
            !settings.monitorEnabled -> "本地监测未启用，暂不生成操作建议"
            !frontLive -> "后台、设计预览与回放暂停实时建议"
            live.cloudQueryStatus == CloudQueryStatus.FAILED -> "云端查询失败，不能核实实时建议依据"
            live.cloudQueryStatus == CloudQueryStatus.QUERYING -> "正在查询，等待可信实时建议依据"
            live.deviceConnection == DeviceConnection.OFFLINE -> "设备离线，暂不生成操作建议"
            live.freshness != DataFreshness.FRESH -> "数据尚未恢复可信实时状态，暂不生成操作建议"
            else -> "已配置指标存在无效或未知采样，暂不开放建议操作"
        }
        val uiState = MonitoringUiState(settings, status, reason, current, alarms.filterNot { it.isOpen },
            advice, unavailableReason, summary = summary)
        mutableState.value = uiState
        writeDebug(live, uiState)
    }

    private fun advice(live: GreenhouseTwinState, settings: MonitoringSettings): List<AdviceItem> {
        return listOf(AlarmKind.LOW_SOIL_MOISTURE, AlarmKind.HIGH_TEMPERATURE, AlarmKind.LOW_LIGHT).mapNotNull { kind ->
            val limit = threshold(kind, settings) ?: return@mapNotNull null
            val observation = observation(kind, live)
            if (!eligible(observation, live)) return@mapNotNull null
            val target = checkNotNull(kind.actuator)
            if (target.reported(live).reported != ReportedState.OFF) return@mapNotNull null
            val value = checkNotNull(observation.value).toDouble()
            if (if (kind in highKinds) value <= limit else value >= limit) return@mapNotNull null
            val identity = checkNotNull(live.snapshotIdentity)
            val impact = when (target) {
                ActuatorKey.PUMP -> "开启水泵实验 LED，泵状态会影响软件土壤模型的湿度变化；不能代表真实灌溉效果"
                ActuatorKey.FAN -> "开启风扇实验 LED；本阶段不承诺实际降温效果"
                ActuatorKey.LAMP -> "开启补光实验 LED；光照档位来自 ADC 实验输入"
            }
            AdviceItem("${kind.name}:${identity.bootId}:$limit", "建议开启${target.label}",
                "${kind.metric?.label} $value ${kind.metric?.unit}，${if (kind in highKinds) "高于" else "低于"}本地阈值 $limit；设备反馈${target.label}关闭",
                impact, checkNotNull(kind.metric), observation.source, value, limit,
                checkNotNull(observation.sampledAtUtcMs), identity.bootId, identity.seq, target,
                canExecute = live.canSendControl && live.controlRequest?.unresolved != true,
                blockedReason = if (!live.canSendControl) live.controlBlockedReason ?: "当前实时状态不允许控制" else
                    if (live.controlRequest?.unresolved == true) "已有未解决控制请求" else null)
        }
    }

    private fun writeDebug(live: GreenhouseTwinState, monitoring: MonitoringUiState) {
        if (!BuildConfig.DEBUG) return
        val document = JSONObject().put("stateSource", live.stateSource.name)
            .put("synchronizationSuspended", live.synchronizationSuspended).put("status", monitoring.status.name)
            .put("statusReason", monitoring.statusReason).put("monitorEnabled", monitoring.settings.monitorEnabled)
            .put("snapshotBootId", live.snapshotIdentity?.bootId ?: JSONObject.NULL)
            .put("snapshotSeq", live.snapshotIdentity?.seq ?: JSONObject.NULL)
            .put("snapshotUtcMs", live.snapshotUtcMs ?: JSONObject.NULL)
            .put("cloudQueryStatus", live.cloudQueryStatus.name).put("freshness", live.freshness.name)
            .put("settings", JSONObject().put("temperatureLowC", monitoring.settings.temperatureLowC ?: JSONObject.NULL)
                .put("temperatureHighC", monitoring.settings.temperatureHighC ?: JSONObject.NULL)
                .put("humidityLowPercent", monitoring.settings.humidityLowPercent ?: JSONObject.NULL)
                .put("humidityHighPercent", monitoring.settings.humidityHighPercent ?: JSONObject.NULL)
                .put("soilLowPercent", monitoring.settings.soilLowPercent ?: JSONObject.NULL)
                .put("lightLowLevel", monitoring.settings.lightLowLevel ?: JSONObject.NULL)
                .put("staleAfterMs", monitoring.settings.staleAfterMs)
                .put("animationsEnabled", monitoring.settings.animationsEnabled))
            .put("episodes", JSONArray(episodes.values.map { value ->
                JSONObject().put("id", value.id).put("kind", value.kind).put("severity", value.severity)
                    .put("source", value.source ?: JSONObject.NULL).put("triggerValue", value.triggerValue ?: JSONObject.NULL)
                    .put("latestValue", value.latestValue ?: JSONObject.NULL).put("thresholdValue", value.thresholdValue ?: JSONObject.NULL)
                    .put("startedAtUtcMs", value.startedAtUtcMs).put("lastObservedAtUtcMs", value.lastObservedAtUtcMs)
                    .put("triggerSampleUtcMs", value.triggerSampleUtcMs ?: JSONObject.NULL)
                    .put("lastSampleUtcMs", value.lastSampleUtcMs ?: JSONObject.NULL)
                    .put("triggerBootId", value.triggerBootId ?: JSONObject.NULL).put("triggerSeq", value.triggerSeq ?: JSONObject.NULL)
                    .put("lastBootId", value.lastBootId ?: JSONObject.NULL).put("lastSeq", value.lastSeq ?: JSONObject.NULL)
                    .put("observedDurationMs", value.observedDurationMs).put("acknowledgedAtUtcMs", value.acknowledgedAtUtcMs ?: JSONObject.NULL)
                    .put("recoveredAtUtcMs", value.recoveredAtUtcMs ?: JSONObject.NULL).put("endedAtUtcMs", value.endedAtUtcMs ?: JSONObject.NULL)
                    .put("endedReason", value.endedReason ?: JSONObject.NULL).put("verificationPending", value.verificationPending)
                    .put("commandId", value.commandId ?: JSONObject.NULL).put("commandTarget", value.commandTarget ?: JSONObject.NULL)
                    .put("detail", value.detail).put("ruleSignature", value.ruleSignature)
            })).put("advice", JSONArray(monitoring.adviceItems.map { value -> JSONObject()
                .put("id", value.id).put("target", value.target.name).put("source", value.source.name)
                .put("sampledAtUtcMs", value.sampledAtUtcMs).put("bootId", value.bootId).put("seq", value.seq)
                .put("value", value.value).put("threshold", value.thresholdValue).put("canExecute", value.canExecute)
                .put("blockedReason", value.blockedReason ?: JSONObject.NULL) }))
        val signature = document.toString()
        if (signature != lastDebugSignature) {
            document.put("observedAtUtcMs", System.currentTimeMillis())
            val temporary = File(applicationContext.filesDir, "g4-monitoring.tmp")
            temporary.writeText(document.toString(2), Charsets.UTF_8)
            check(temporary.renameTo(File(applicationContext.filesDir, "g4-monitoring.json"))) { "监测验收记录保存失败" }
            lastDebugSignature = signature
        }
    }

    private companion object {
        val environmentKinds = listOf(AlarmKind.LOW_TEMPERATURE, AlarmKind.HIGH_TEMPERATURE,
            AlarmKind.LOW_HUMIDITY, AlarmKind.HIGH_HUMIDITY, AlarmKind.LOW_SOIL_MOISTURE, AlarmKind.LOW_LIGHT)
        val highKinds = setOf(AlarmKind.HIGH_TEMPERATURE, AlarmKind.HIGH_HUMIDITY)
    }
}
