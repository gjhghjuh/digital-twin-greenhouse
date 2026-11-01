package cn.greenhouse.twin.data.repository

import android.content.Context
import android.os.SystemClock
import cn.greenhouse.twin.data.local.ConnectionStore
import cn.greenhouse.twin.data.local.ControlJournal
import cn.greenhouse.twin.data.model.packetJson
import cn.greenhouse.twin.data.remote.OneNetReading
import cn.greenhouse.twin.data.local.OneNetSettings
import cn.greenhouse.twin.data.local.TwinDatabase
import cn.greenhouse.twin.data.local.TwinRecordEntity
import cn.greenhouse.twin.data.model.DemoStateFactory
import cn.greenhouse.twin.data.history.HistoryRecordCodec
import cn.greenhouse.twin.data.remote.OneNetClient
import cn.greenhouse.twin.data.remote.OneNetQueryException
import cn.greenhouse.twin.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

// 应用范围只有一个同步入口；页面重建不会另开轮询，设计源不写入正式历史。
class HomeStateRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ConnectionStore(context)
    val connectionStore: ConnectionStore get() = store
    private val records = TwinDatabase.open(context).records()
    private val client = OneNetClient()
    private val diagnostics = SyncDiagnostics(context)
    private val queryMutex = Mutex()
    private val controlMutex = Mutex()
    private val controlJournal = ControlJournal(context)
    private val mutableState = MutableStateFlow(GreenhouseTwinState())
    val state: StateFlow<GreenhouseTwinState> = mutableState.asStateFlow()
    @Volatile private var settings: OneNetSettings? = null
    @Volatile private var foreground = false
    @Volatile private var replayActive = false
    private var pollingJob: Job? = null
    private var submissionJob: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var successfulQueryGeneration: Long? = null
    private var recordCount = 0
    private var serverAnchorUtcMs: Long? = null
    private var serverAnchorElapsedMs = 0L
    private var controlRequest: ControlRequestState? = null
    private var sceneExecution: SceneExecutionState? = null
    @Volatile private var sceneStopReason: String? = null
    @Volatile private var controlLoaded = false
    @Volatile private var controlStorageError: String? = null
    @Volatile private var desiredChecked = false
    @Volatile private var foreignDesired = false
    private var desiredOccupied = false
    private var recentDeviceResults = emptyList<DeviceCommandFeedback>()

    init {
        scope.launch {
            try {
                val journal = controlJournal.loadState()
                controlRequest = journal.request?.let {
                    if (it.status == RequestStatus.SENDING) it.copy(status = RequestStatus.UNCERTAIN,
                        detail = "应用恢复，提交结果尚待核实；保留原请求") else it
                }
                sceneExecution = journal.scene?.let { scene ->
                    val restored = scene.copy(steps = scene.steps.map { step ->
                        if (step.request != null && step.request.commandId == controlRequest?.commandId)
                            step.copy(request = controlRequest) else step
                    })
                    if (restored.isRunning) stoppedScene(restored, "应用重新启动，未发送步骤已停止；原请求只查询结果，不自动续发") else restored
                }
                controlJournal.save(controlRequest, sceneExecution)
                controlLoaded = true
                publish(mutableState.value)
            } catch (_: Exception) {
                controlStorageError = "控制记录读取失败，禁止创建新请求"
                controlLoaded = true
                publish(mutableState.value)
            }
        }
        scope.launch {
            records.count().collect { count ->
                recordCount = count
                publish(mutableState.value.copy(storedRecordCount = count))
            }
        }
        scope.launch {
            try {
                if (store.importPendingConfiguration()) diagnostics.event("configuration-imported")
                store.settings.collect { configured ->
                    if (configured != settings) {
                        stopRunningScene("连接配置已改变，未发送步骤已停止")
                        settings = configured
                        stopPolling()
                        if (mutableState.value.isDesignPreview) {
                            publish(withConfiguration(mutableState.value))
                        } else {
                            publish(initialLiveState())
                            startPolling()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(mutableState.value.copy(cloudQueryStatus = CloudQueryStatus.FAILED,
                    queryErrorMessage = "连接配置读取失败：${error.javaClass.simpleName}"))
                diagnostics.event("configuration-failed")
            }
        }
    }

    fun setForeground(enabled: Boolean) {
        if (foreground == enabled) return
        foreground = enabled
        diagnostics.event(if (enabled) "foreground-resume" else "foreground-pause")
        if (replayActive) {
            publish(mutableState.value.copy(synchronizationSuspended = true))
            return
        }
        if (mutableState.value.isDesignPreview) return
        if (!enabled) {
            stopRunningScene("应用进入后台，未发送步骤已停止；当前请求仍需核实")
            submissionJob?.cancel()
            stopPolling()
            publish(mutableState.value.copy(synchronizationSuspended = true))
        } else {
            publish(mutableState.value.copy(synchronizationSuspended = false,
                deviceConnection = DeviceConnection.UNKNOWN,
                cloudQueryStatus = if (settings == null) CloudQueryStatus.UNCONFIGURED else CloudQueryStatus.QUERYING))
            updateFreshness()
            startPolling()
        }
    }

    fun enterDesignPreview() {
        check(!replayActive) { "请先退出历史回放" }
        stopRunningScene("进入设计预览，未发送步骤已停止")
        submissionJob?.cancel()
        stopPolling()
        serverAnchorUtcMs = null
        publish(withConfiguration(DemoStateFactory.create(DemoScenario.NORMAL)))
        diagnostics.event("design-enter")
    }

    fun selectDemoScenario(scenario: DemoScenario) {
        check(mutableState.value.isDesignPreview) { "必须先明确进入设计预览，才能切换设计场景" }
        publish(withConfiguration(DemoStateFactory.create(scenario)))
    }

    fun exitDesignPreview() {
        stopPolling()
        serverAnchorUtcMs = null
        // 新建未知状态，禁止把设计值或设计设备反馈带进真实模式。
        publish(initialLiveState())
        diagnostics.event("design-exit")
        startPolling()
    }

    fun updateRefreshSeconds(seconds: Int) {
        require(seconds >= 5) { "刷新间隔不得小于五秒" }
        scope.launch {
            store.updateRefreshSeconds(seconds)
            diagnostics.event("refresh-setting", JSONObject().put("seconds", seconds))
        }
    }

    // 历史状态由独立回放状态持有；实时仓库只暂停活动，不接收历史观测值。
    fun enterReplay() {
        if (replayActive) return
        stopRunningScene("进入历史回放，未发送步骤已停止")
        replayActive = true
        submissionJob?.cancel()
        stopPolling()
        publish(mutableState.value.copy(synchronizationSuspended = true))
        diagnostics.event("replay-enter")
    }

    fun exitReplay() {
        if (!replayActive) return
        replayActive = false
        stopPolling()
        serverAnchorUtcMs = null
        desiredChecked = false
        foreignDesired = false
        desiredOccupied = false
        // 退出时清除旧反馈，等待新查询完成及新鲜度核实后才允许实时控制。
        publish(initialLiveState())
        diagnostics.event("replay-exit-resync")
        startPolling()
    }

    // 调试验收复用真实业务入口；只有已进入回放时才允许执行这个禁控核验。
    fun verifyReplayControlBlock() {
        check(replayActive) { "禁控核验必须在历史回放中执行" }
        requestControl(ActuatorKey.PUMP, true)
        resendControl()
        mutableState.value.snapshotIdentity?.let { requestScene(ManualScenePreset.IRRIGATION, it) }
            ?: diagnostics.event("scene-replay-probe-skipped", JSONObject().put("reason", "当前没有快照身份"))
        diagnostics.event("replay-control-probe", JSONObject().put("replayActive", true))
    }

    fun refreshNow() {
        if (replayActive || !foreground || mutableState.value.stateSource != StateSource.LIVE) return
        stopPolling()
        startPolling()
    }

    fun requestControl(target: ActuatorKey, enabled: Boolean) {
        createControlRequest(target, enabled, ControlPurpose.REALTIME, expectedIdentity = null)
    }

    fun requestOfflinePump(enabled: Boolean, expectedIdentity: SnapshotIdentity) {
        createControlRequest(ActuatorKey.PUMP, enabled, ControlPurpose.OFFLINE_RESERVATION, expectedIdentity)
    }

    fun requestScene(preset: ManualScenePreset, expectedIdentity: SnapshotIdentity) {
        if (replayActive || !mutableState.value.canStartScene ||
            !confirmedIdentityMatches(mutableState.value.snapshotIdentity, expectedIdentity)) {
            diagnostics.event("scene-blocked", JSONObject().put("preset", preset.name)
                .put("reason", if (replayActive) "历史回放禁止真实场景控制" else "场景条件或确认身份已改变"))
            return
        }
        launchControlAction(submission = true) {
            controlMutex.withLock {
                val current = mutableState.value
                if (!current.canStartScene || !confirmedIdentityMatches(current.snapshotIdentity, expectedIdentity)) return@withLock
                sceneStopReason = null
                val scene = SceneExecutionState("SCENE-${UUID.randomUUID().toString().replace("-", "")}", preset,
                    checkNotNull(current.snapshotIdentity), checkNotNull(settings).productId, checkNotNull(serverNow()),
                    preset.steps.map { SceneStepState(it.target, it.requestedState) })
                saveScene(scene)
                diagnostics.event("scene-created", JSONObject().put("runId", scene.runId).put("preset", preset.name)
                    .put("bootId", scene.identity.bootId))
                advanceSceneLocked()
            }
            refreshNow()
        }
    }

    fun stopSceneRemaining() = stopRunningScene("用户停止后续步骤；已发请求不撤回，已完成步骤不回滚")

    private fun stoppedScene(scene: SceneExecutionState, reason: String): SceneExecutionState = scene.copy(
        status = stoppedSceneStatus(scene.confirmedStepCount, scene.steps.size),
        detail = reason)

    // 结果按已确认步骤计算，停止事实仍保留；结果补齐不重新启动编排。
    private fun stoppedSceneStatus(confirmed: Int, total: Int): SceneExecutionStatus = when {
        confirmed == total -> SceneExecutionStatus.SUCCESS
        confirmed > 0 -> SceneExecutionStatus.PARTIAL
        else -> SceneExecutionStatus.STOPPED
    }

    // 立即阻止在途写入及下一步，停止事实随后和原包在同一持久提交中公开。
    private fun stopRunningScene(reason: String) {
        if (sceneExecution?.isRunning != true) return
        sceneStopReason = reason
        launchControlAction {
            controlMutex.withLock {
                sceneExecution?.takeIf { it.isRunning }?.let { saveScene(stoppedScene(it, reason)) }
            }
        }
    }

    private fun continueSceneAfterQuery() {
        if (sceneExecution?.isRunning != true || controlStorageError != null || submissionJob?.isActive == true) return
        launchControlAction(submission = true) {
            controlMutex.withLock { advanceSceneLocked() }
        }
    }

    // 只有既有轮询提供完整确认且清空 Desired 才推进，不另开场景查询或跳过已达目标的步骤。
    private suspend fun advanceSceneLocked() {
        val scene = sceneExecution?.takeIf { it.isRunning } ?: return
        val current = mutableState.value
        val problem = sceneStopReason ?: when {
            !confirmedIdentityMatches(current.snapshotIdentity, scene.identity) || settings?.productId != scene.productId -> "场景目标启动或连接配置已改变，未发送步骤已停止"
            else -> purposeContextBlockedReason(current, ControlPurpose.REALTIME, retry = false)
        }
        if (problem != null) {
            saveScene(stoppedScene(scene, problem))
            return
        }
        // 正常提交后的刷新会暂时清除查询资格，这个复核窗口只等待，不误判为设备故障。
        if (!desiredChecked) return
        if (foreignDesired) {
            saveScene(stoppedScene(scene, "云端出现另一控制包，未发送步骤已停止"))
            return
        }
        val pending = scene.steps.firstOrNull { it.request != null && it.request.status != RequestStatus.EXECUTED }
        if (pending != null) {
            if (pending.request?.status !in setOf(RequestStatus.SENDING, RequestStatus.WAITING))
                saveScene(stoppedScene(scene, "当前步骤结果未能完整确认，未发送步骤已停止"))
            return
        }
        if (desiredOccupied) return
        if (scene.confirmedStepCount == scene.steps.size) {
            saveScene(scene.copy(status = SceneExecutionStatus.SUCCESS, detail = "全部步骤已关联确认，云端待处理包已清空"))
            diagnostics.event("scene-confirmed", JSONObject().put("runId", scene.runId))
            return
        }
        val index = scene.steps.indexOfFirst { it.request == null }
        val step = scene.steps[index]
        issueControlLocked(step.target, step.requestedState, ControlPurpose.REALTIME, sceneStepIndex = index)
    }

    // 确认面板冻结设备和启动身份；采样序号可正常推进，意图不能悄悄改绑新启动或新配置。
    private fun confirmedIdentityMatches(current: SnapshotIdentity?, expected: SnapshotIdentity): Boolean =
        current != null && current.deviceId == expected.deviceId && current.bootId == expected.bootId &&
            current.configVersion == expected.configVersion

    private fun createControlRequest(target: ActuatorKey, enabled: Boolean, purpose: ControlPurpose,
        expectedIdentity: SnapshotIdentity?) {
        if (replayActive) {
            diagnostics.event("control-blocked", JSONObject().put("reason", "历史回放禁止真实控制"))
            return
        }
        val initial = mutableState.value
        if (purpose == ControlPurpose.OFFLINE_RESERVATION &&
            !confirmedIdentityMatches(initial.snapshotIdentity, checkNotNull(expectedIdentity))) {
            diagnostics.event("control-blocked", JSONObject().put("reason", "预约确认后的设备启动或配置已改变，请重新查询后确认")
                .put("purpose", purpose.name))
            return
        }
        val allowed = if (purpose == ControlPurpose.REALTIME) initial.canSendControl else initial.canReserveOfflinePump
        if (!allowed) {
            diagnostics.event("control-blocked", JSONObject().put("reason",
                if (purpose == ControlPurpose.REALTIME) initial.controlBlockedReason
                else initial.offlinePumpBlockedReason).put("purpose", purpose.name))
            return
        }
        launchControlAction(submission = true) {
            controlMutex.withLock {
                val current = mutableState.value
                if (purpose == ControlPurpose.OFFLINE_RESERVATION &&
                    !confirmedIdentityMatches(current.snapshotIdentity, checkNotNull(expectedIdentity))) {
                    diagnostics.event("control-blocked", JSONObject().put("reason", "等待提交期间设备启动或配置已改变，原预约确认不适用")
                        .put("purpose", purpose.name))
                    return@withLock
                }
                if (purpose == ControlPurpose.REALTIME && !current.canSendControl ||
                    purpose == ControlPurpose.OFFLINE_RESERVATION && !current.canReserveOfflinePump) return@withLock
                issueControlLocked(target, enabled, purpose)
            }
            refreshNow()
        }
    }

    private suspend fun issueControlLocked(target: ActuatorKey, enabled: Boolean, purpose: ControlPurpose,
        sceneStepIndex: Int? = null) {
        val acceptedGeneration = generation
        val acceptedSettings = settings
        val identity = checkNotNull(mutableState.value.snapshotIdentity)
        val issued = checkNotNull(serverNow()) - 1_000
        if (issued <= controlJournal.highWater()) {
            diagnostics.event("control-blocked", JSONObject().put("reason", "请求时钟尚未递增"))
            sceneExecution?.takeIf { sceneStepIndex != null && it.isRunning }?.let {
                saveScene(stoppedScene(it, "请求时钟尚未递增，未发送步骤已停止"))
            }
            return
        }
        val request = ControlRequestState("APP-${UUID.randomUUID().toString().replace("-", "")}",
            target, enabled, identity.bootId, identity.configVersion, issued, issued + 60_000,
            identity.stateVersion, purpose = purpose)
        if (!controlContextValid(acceptedGeneration, acceptedSettings, request, retry = false)) return
        val nextScene = if (sceneStepIndex == null) sceneExecution else checkNotNull(sceneExecution).let { scene ->
            if (!scene.isRunning || sceneStopReason != null || !confirmedIdentityMatches(identity, scene.identity)) return
            scene.copy(steps = scene.steps.mapIndexed { index, step ->
                if (index == sceneStepIndex) step.copy(request = request) else step
            }, detail = "正在提交第${sceneStepIndex + 1}项手动控制")
        }
        persistControlState(request, nextScene)
        diagnostics.event("control-created", JSONObject(request.packetJson()).put("purpose", purpose.name)
            .put("sceneRunId", if (sceneStepIndex == null) JSONObject.NULL else nextScene?.runId))
        submitOriginal(request, retry = false, acceptedGeneration, acceptedSettings)
    }

    fun resendControl() {
        if (replayActive) {
            diagnostics.event("control-blocked", JSONObject().put("reason", "历史回放禁止重发真实控制"))
            return
        }
        launchControlAction(submission = true) {
            controlMutex.withLock {
                val request = controlRequest ?: return@withLock
                val current = mutableState.value
                if (!current.canResendControl) {
                    diagnostics.event("control-blocked", JSONObject().put("reason", current.resendBlockedReason)
                        .put("purpose", request.purpose.name))
                    return@withLock
                }
                // 原标识与原八字段包全部保留，不延长有效期；到期重发只用于核实结果。
                submitOriginal(request, retry = true, generation, settings)
            }
            refreshNow()
        }
    }

    fun dismissControlWaiting() {
        launchControlAction {
            controlMutex.withLock {
                controlRequest?.let { saveControl(it.copy(waitingDismissed = true)) }
                diagnostics.event("control-waiting-dismissed")
            }
        }
    }

    private fun launchControlAction(submission: Boolean = false, block: suspend () -> Unit) {
        if (submission && submissionJob?.isActive == true) {
            diagnostics.event("control-blocked", JSONObject().put("reason", "已有提交正在处理"))
            return
        }
        val job = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                controlStorageError = "控制操作未能安全持久化，已停止发送"
                if (sceneExecution?.isRunning == true) sceneStopReason = controlStorageError
                publish(mutableState.value)
                diagnostics.event("control-storage-failed", JSONObject().put("reason", error.javaClass.simpleName))
            }
        }
        if (submission) submissionJob = job
    }

    private fun purposeContextBlockedReason(value: GreenhouseTwinState, purpose: ControlPurpose, retry: Boolean): String? = when {
        replayActive || value.stateSource != StateSource.LIVE -> "设计预览或回放禁止真实控制"
        !foreground || value.synchronizationSuspended -> "需要前台实时模式"
        value.cloudQueryStatus != CloudQueryStatus.SUCCESS -> "需要本次云端查询成功，查询失败不等于设备离线"
        value.snapshotIdentity == null -> "需要云端提供该设备的最后快照身份"
        value.snapshotIdentity.deviceId != settings?.deviceName -> "快照不属于当前配置设备"
        purpose == ControlPurpose.REALTIME && !value.canPresentCurrentActivity -> "需要在线且新鲜的设备反馈"
        purpose == ControlPurpose.OFFLINE_RESERVATION && successfulQueryGeneration != generation -> "正在重新核对云端设备状态"
        purpose == ControlPurpose.OFFLINE_RESERVATION && value.deviceConnection == DeviceConnection.UNKNOWN -> "设备状态未知，不能保存离线预约"
        purpose == ControlPurpose.OFFLINE_RESERVATION && value.deviceConnection != DeviceConnection.OFFLINE &&
            !(retry && value.canPresentCurrentActivity) -> if (retry) "需要设备明确离线，或在线且新鲜的反馈" else "只有设备明确离线时才能保存预约"
        serverNow() == null -> "需要云端可信时间"
        else -> null
    }

    // 写入前再次核对同一前台、配置、启动及目的；预约不能整体放宽实时请求。
    private fun controlContextValid(acceptedGeneration: Long, acceptedSettings: OneNetSettings?,
        request: ControlRequestState, retry: Boolean): Boolean {
        val current = mutableState.value
        val stoppedBeforeWrite = !retry && sceneStopReason != null &&
            sceneExecution?.steps?.any { it.request?.commandId == request.commandId } == true
        return acceptedGeneration == generation && acceptedSettings == settings && controlLoaded &&
            controlStorageError == null && !stoppedBeforeWrite && desiredChecked && !foreignDesired &&
            purposeContextBlockedReason(current, request.purpose, retry) == null &&
            current.snapshotIdentity?.bootId == request.targetBootId &&
            current.snapshotIdentity?.configVersion == request.configVersion
    }

    private suspend fun submitOriginal(request: ControlRequestState, retry: Boolean,
        acceptedGeneration: Long, acceptedSettings: OneNetSettings?) {
        val configured = settings ?: return
        var posting = false
        try {
            val key = withContext(Dispatchers.IO) { store.accessKey() }
                ?: throw OneNetQueryException("产品访问密钥尚未配置")
            // 这是查询后提交的排他复核，并非云端原子 CAS；不覆盖已看到的另一包。
            val desired = client.queryDesired(configured, key)
            desiredChecked = true
            desiredOccupied = desired != null
            foreignDesired = desired != null
            if (desired != null) foreignDesired = !samePacket(desired.packet, request.packetJson())
            if (foreignDesired) {
                saveControl(request.copy(status = if (retry) RequestStatus.UNCERTAIN else RequestStatus.REJECTED,
                    detail = if (retry) "云端已有另一控制包，未重发；原请求结果仍待核实"
                    else "云端已有另一控制包，未覆盖、未提交本次请求"))
                diagnostics.event("control-conflict-blocked", JSONObject().put("commandId", request.commandId))
                return
            }
            saveControl(request.copy(status = RequestStatus.SENDING, detail = "正在提交原控制包"))
            if (!controlContextValid(acceptedGeneration, acceptedSettings, request, retry)) {
                saveControl(request.copy(status = if (retry) RequestStatus.UNCERTAIN else RequestStatus.REJECTED,
                    detail = if (retry) "提交条件已改变，未重发；原结果仍待核实"
                    else "提交条件已改变，本次控制尚未发送"))
                return
            }
            posting = true
            client.submit(configured, key, request.packetJson()) {
                controlContextValid(acceptedGeneration, acceptedSettings, request, retry)
            }
            saveControl(request.copy(status = RequestStatus.WAITING, cloudAccepted = true,
                detail = if (request.purpose == ControlPurpose.OFFLINE_RESERVATION)
                    "离线意图已保存云端，等待原启动设备在有效期内重连处理；尚未确认接收或执行"
                else "云端已接受，等待同一启动和命令的设备结果；未推断设备已接收", waitingDismissed = false))
            diagnostics.event("control-cloud-accepted", JSONObject().put("commandId", request.commandId))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                saveControl(request.copy(status = if (!retry && !posting) RequestStatus.REJECTED else RequestStatus.UNCERTAIN,
                    detail = if (!retry && !posting) "已暂停实时模式，本次请求未开始提交"
                    else "发送过程已暂停，原请求可能已提交；保留原标识查询实际结果"))
                diagnostics.event("control-submission-paused", JSONObject().put("commandId", request.commandId)
                    .put("postingStarted", posting))
            }
            throw error
        } catch (error: Exception) {
            val definitive = error is OneNetQueryException && error.message?.startsWith("OneNET 返回错误码") == true
            saveControl(request.copy(status = if (!retry && (!posting || definitive)) RequestStatus.REJECTED else RequestStatus.UNCERTAIN,
                detail = if (retry) "原请求核对或重发未完成：${safeControlError(error)}；之前的执行结果仍待核实"
                else if (!posting) "发送前核对失败，未提交：${safeControlError(error)}"
                else if (definitive) "云端拒绝提交：${safeControlError(error)}"
                else "提交结果未确认：${safeControlError(error)}；请查询或重发原请求"))
            diagnostics.event("control-submit-unconfirmed", JSONObject().put("commandId", request.commandId)
                .put("postingStarted", posting).put("reason", safeControlError(error)))
        }
    }

    private fun safeControlError(error: Exception): String = when (error) {
        is OneNetQueryException -> error.message ?: "云端操作失败"
        is java.net.SocketTimeoutException -> "云端请求超时"
        is javax.net.ssl.SSLException -> "TLS安全连接失败"
        else -> error.javaClass.simpleName
    }

    private fun samePacket(first: String, second: String): Boolean {
        val a = JSONObject(first)
        val b = JSONObject(second)
        return a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
            a.keys().asSequence().all { a.get(it) == b.get(it) }
    }

    private suspend fun saveControl(value: ControlRequestState, observedState: GreenhouseTwinState? = null) {
        val scene = sceneExecution?.let { existing ->
            if (existing.steps.none { it.request?.commandId == value.commandId }) existing else {
                val steps = existing.steps.map { step ->
                    if (step.request?.commandId == value.commandId) step.copy(request = value) else step
                }
                val confirmed = steps.count { it.request?.status == RequestStatus.EXECUTED }
                val stop = sceneStopReason ?: when {
                    value.status in setOf(RequestStatus.REJECTED, RequestStatus.INTERRUPTED, RequestStatus.TIMED_OUT,
                        RequestStatus.UNCERTAIN) -> "当前步骤${value.detail}；未发送步骤已停止"
                    value.late -> "迟到结果只补记原步骤，未发送步骤已停止"
                    else -> null
                }
                when {
                    existing.isRunning && stop != null -> existing.copy(steps = steps,
                        status = stoppedSceneStatus(confirmed, steps.size),
                        detail = stop)
                    existing.isRunning -> existing.copy(steps = steps,
                        detail = if (value.status == RequestStatus.EXECUTED) "原步骤已关联确认，等待云端待处理包清空和当前在线反馈"
                        else existing.detail)
                    existing.status != SceneExecutionStatus.SUCCESS -> existing.copy(steps = steps,
                        status = stoppedSceneStatus(confirmed, steps.size),
                        detail = if (confirmed == steps.size)
                            "${existing.detail}；${if (value.late) "迟到" else ""}原步骤结果已补齐，未恢复编排" else existing.detail)
                    else -> existing.copy(steps = steps)
                }
            }
        }
        persistControlState(value, scene, observedState)
    }

    private suspend fun saveScene(value: SceneExecutionState) {
        persistControlState(controlRequest, value)
        if (!value.isRunning) diagnostics.event("scene-stopped-or-finished", JSONObject().put("runId", value.runId)
            .put("status", value.status.name).put("confirmedSteps", value.confirmedStepCount).put("reason", value.detail))
    }

    private suspend fun persistControlState(request: ControlRequestState?, scene: SceneExecutionState?,
        observedState: GreenhouseTwinState? = null) {
        // 先完成持久化再公开或发送，失败即保留阻塞，不悄悄换新ID继续。
        try {
            withContext(NonCancellable) {
                controlJournal.save(request, scene)
                controlRequest = request
                sceneExecution = scene
                // 核实设备结果时同时公开该批快照，禁止成功状态搭配上一批 Reported。
                publish(observedState?.let(::withConfiguration) ?: mutableState.value)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            controlStorageError = "控制记录保存失败，禁止继续发送"
            if (sceneExecution?.isRunning == true) sceneStopReason = controlStorageError
            publish(mutableState.value)
            throw error
        }
    }

    private suspend fun reconcileControl(reading: OneNetReading, observedState: GreenhouseTwinState) {
        controlMutex.withLock {
            reading.commandReceipt?.let { receipt ->
                val feedback = DeviceCommandFeedback(receipt.commandId, receipt.bootId, receipt.result,
                    receipt.duplicate, receipt.stateVersion, receipt.utcMs,
                    controlRequest?.takeIf { it.commandId == receipt.commandId }?.target)
                if (recentDeviceResults.none { it.commandId == feedback.commandId && it.bootId == feedback.bootId &&
                        it.result == feedback.result && it.duplicate == feedback.duplicate && it.utcMs == feedback.utcMs })
                    recentDeviceResults = (listOf(feedback) + recentDeviceResults).take(6)
            }
            desiredChecked = true
            desiredOccupied = reading.desiredPacket != null
            foreignDesired = reading.desiredPacket != null
            if (reading.desiredPacket != null && controlRequest != null)
                foreignDesired = !samePacket(reading.desiredPacket.packet, checkNotNull(controlRequest).packetJson())
            val request = controlRequest ?: return@withLock
            val snapshot = reading.snapshot ?: return@withLock
            if (request.unresolved && snapshot.identity.bootId != request.targetBootId) {
                saveControl(request.copy(status = RequestStatus.INTERRUPTED,
                    detail = "目标启动已结束；新启动不执行旧意图，当前反馈另行同步"), observedState)
                diagnostics.event("control-boot-changed", JSONObject().put("commandId", request.commandId))
                return@withLock
            }
            val receipt = reading.commandReceipt ?: return@withLock
            if (receipt.commandId != request.commandId || receipt.bootId != request.targetBootId) return@withLock
            if (receipt.result != "EXECUTED") {
                saveControl(request.copy(status = if (receipt.result == "INTERRUPTED") RequestStatus.INTERRUPTED else RequestStatus.REJECTED,
                    detail = "设备结果：${receipt.result}", resultUtcMs = receipt.utcMs), observedState)
                diagnostics.event("control-device-rejected", JSONObject().put("commandId", request.commandId).put("result", receipt.result))
                return@withLock
            }
            val actual = when (request.target) {
                ActuatorKey.PUMP -> snapshot.pump
                ActuatorKey.LAMP -> snapshot.lamp
                ActuatorKey.FAN -> snapshot.fan
            }
            // 关联快照可以证明此前执行；当前在线活动仍由在线与新鲜度门禁单独决定。
            val confirmed = snapshot.identity.bootId == request.targetBootId &&
                snapshot.identity.configVersion == request.configVersion &&
                receipt.stateVersion > request.baseStateVersion && snapshot.identity.stateVersion >= receipt.stateVersion &&
                JSONObject(snapshot.payloadJson).getString("cmd") == request.commandId &&
                receipt.targetState(request.target) == request.requestedState && actual == request.requestedState &&
                receipt.utcMs != null && receipt.utcMs >= request.issuedAtUtcMs
            if (confirmed && request.status != RequestStatus.EXECUTED) {
                val late = request.status in setOf(RequestStatus.TIMED_OUT, RequestStatus.UNCERTAIN) ||
                    (serverNow() ?: request.issuedAtUtcMs) >= request.expiresAtUtcMs
                saveControl(request.copy(status = RequestStatus.EXECUTED, late = late,
                    detail = if (late) "迟到反馈已核实，本次命令与设备状态匹配" else "同一命令、启动与实际反馈已匹配",
                    resultUtcMs = receipt.utcMs), observedState)
                diagnostics.event("control-confirmed", JSONObject().put("commandId", request.commandId)
                    .put("bootId", receipt.bootId).put("stateVersion", receipt.stateVersion)
                    .put("duplicate", receipt.duplicate).put("late", late))
            }
        }
    }

    private fun serverNow(): Long? = serverAnchorUtcMs?.plus(SystemClock.elapsedRealtime() - serverAnchorElapsedMs)

    // 本地规则沿用云端授时和单调时钟，不将手机墙上时间伪装成设备采样时间。
    fun trustedNowUtcMs(): Long? = serverNow()

    private suspend fun updateControlDeadline() {
        controlMutex.withLock {
            val request = controlRequest ?: return@withLock
            val now = serverNow() ?: return@withLock
            if (request.unresolved && request.status != RequestStatus.TIMED_OUT && request.status != RequestStatus.SENDING &&
                now >= request.expiresAtUtcMs) {
                saveControl(request.copy(status = RequestStatus.TIMED_OUT,
                    detail = "有效期已过，暂未匹配设备确认；实际结果仍待核实，取消等待不会撤回请求"))
                diagnostics.event("control-timed-out", JSONObject().put("commandId", request.commandId))
            }
        }
    }

    private fun stopPolling() {
        generation++
        successfulQueryGeneration = null
        desiredChecked = false
        pollingJob?.cancel()
        pollingJob = null
        publish(mutableState.value)
    }

    private fun startPolling() {
        val configured = settings ?: return
        if (replayActive || !foreground || mutableState.value.isDesignPreview || pollingJob?.isActive == true) return
        val acceptedGeneration = generation
        pollingJob = scope.launch {
            val ticker = launch {
                while (isActive) {
                    delay(1_000)
                    updateFreshness()
                    try { updateControlDeadline() }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        controlStorageError = "控制记录保存失败，禁止继续发送"
                        publish(mutableState.value)
                        diagnostics.event("control-storage-failed", JSONObject().put("reason", error.javaClass.simpleName))
                    }
                }
            }
            try {
                while (isActive && acceptedGeneration == generation && foreground && !mutableState.value.isDesignPreview) {
                    val started = SystemClock.elapsedRealtime()
                    diagnostics.event("query-start")
                    try {
                        queryMutex.withLock {
                            val key = withContext(Dispatchers.IO) { store.accessKey() }
                                ?: throw OneNetQueryException("产品访问密钥尚未配置")
                            val reading = client.query(configured, key)
                            ensureActive()
                            if (acceptedGeneration != generation || !foreground || mutableState.value.isDesignPreview) return@withLock
                            serverAnchorUtcMs = reading.serverUtcMs
                            serverAnchorElapsedMs = reading.serverReceivedElapsedMs
                            successfulQueryGeneration = acceptedGeneration
                            val now = System.currentTimeMillis()
                            val snapshot = reading.snapshot
                            if (snapshot == null) {
                                val observedState = withConfiguration(GreenhouseTwinState(deviceConnection = reading.connection,
                                    cloudQueryStatus = CloudQueryStatus.SUCCESS, lastQueryAtUtcMs = now,
                                    lastSyncedAtUtcMs = mutableState.value.lastSyncedAtUtcMs, synchronizationSuspended = false))
                                reconcileControl(reading, observedState)
                                publish(observedState)
                            } else {
                                val identity = snapshot.identity
                                val inserted = records.insertOrIgnore(TwinRecordEntity(identity.deviceId, identity.bootId,
                                    "snapshot", identity.seq, identity.stateVersion, identity.configVersion, identity.modelVersion,
                                    snapshot.utcMs, snapshot.uptimeMs, checkNotNull(reading.cloudReceivedAtUtcMs), now, snapshot.payloadJson))
                                if (inserted == -1L) {
                                    val stored = records.findIdentity(identity.deviceId, identity.bootId, "snapshot", identity.seq)
                                    val currentRecord = TwinRecordEntity(identity.deviceId, identity.bootId,
                                        "snapshot", identity.seq, identity.stateVersion, identity.configVersion, identity.modelVersion,
                                        snapshot.utcMs, snapshot.uptimeMs, checkNotNull(reading.cloudReceivedAtUtcMs), now, snapshot.payloadJson)
                                    check(stored != null && HistoryRecordCodec.equivalent(stored, currentRecord)) { "IDENTITY_CONFLICT" }
                                }
                                ensureActive()
                                if (acceptedGeneration != generation || !foreground || mutableState.value.isDesignPreview) return@withLock
                                val observedState = withConfiguration(snapshot.asState(reading.connection).copy(lastQueryAtUtcMs = now,
                                    lastSyncedAtUtcMs = now, synchronizationSuspended = false))
                                reconcileControl(reading, observedState)
                                publish(observedState)
                                diagnostics.event(if (inserted == -1L) "snapshot-duplicate" else "snapshot-inserted",
                                    JSONObject().put("bootId", identity.bootId).put("seq", identity.seq)
                                        .put("cloudUtcMs", reading.cloudReceivedAtUtcMs)
                                        .put("deviceUtcMs", snapshot.utcMs ?: JSONObject.NULL))
                            }
                            updateFreshness()
                            continueSceneAfterQuery()
                        }
                    } catch (error: CancellationException) {
                        diagnostics.event("query-cancelled")
                        throw error
                    } catch (error: Exception) {
                        ensureActive()
                        if (acceptedGeneration != generation || !foreground || mutableState.value.isDesignPreview) break
                        val message = when (error) {
                            is OneNetQueryException -> error.message ?: "云端查询失败"
                            is javax.net.ssl.SSLException -> "TLS 安全查询失败"
                            is java.net.SocketTimeoutException -> "云端查询超时"
                            is java.net.UnknownHostException -> "无法解析云端地址"
                            else -> "查询或记录校验失败：${error.javaClass.simpleName}"
                        }
                        publish(mutableState.value.copy(cloudQueryStatus = CloudQueryStatus.FAILED,
                            deviceConnection = DeviceConnection.UNKNOWN, lastQueryAtUtcMs = System.currentTimeMillis(),
                            queryErrorMessage = message))
                        stopRunningScene("云端查询未成功，未发送步骤已停止：$message")
                        diagnostics.event("query-failed", JSONObject().put("reason", message))
                    }
                    diagnostics.event("query-end", JSONObject().put("durationMs", SystemClock.elapsedRealtime() - started))
                    delay((configured.refreshSeconds * 1_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                }
            } finally { ticker.cancel() }
        }
    }

    private fun updateFreshness() {
        val current = mutableState.value
        if (replayActive || current.isDesignPreview) return
        val configured = settings ?: return
        val snapshotUtc = current.snapshotUtcMs
        val serverUtc = serverAnchorUtcMs
        val freshness = if (snapshotUtc == null || serverUtc == null) DataFreshness.UNKNOWN else {
            val age = serverUtc + (SystemClock.elapsedRealtime() - serverAnchorElapsedMs) - snapshotUtc
            when {
                age < -1_000 -> DataFreshness.UNKNOWN
                age <= configured.delayedAfterMs -> DataFreshness.FRESH
                age <= configured.staleAfterMs -> DataFreshness.DELAYED
                else -> DataFreshness.STALE
            }
        }
        if (freshness != current.freshness) publish(current.copy(freshness = freshness))
        if (freshness != DataFreshness.FRESH && sceneExecution?.isRunning == true)
            stopRunningScene("设备反馈已不新鲜，未发送步骤已停止")
    }

    private fun initialLiveState() = withConfiguration(GreenhouseTwinState(
        cloudQueryStatus = if (settings == null) CloudQueryStatus.UNCONFIGURED else CloudQueryStatus.QUERYING,
        synchronizationSuspended = !foreground))

    private fun withConfiguration(value: GreenhouseTwinState) = value.copy(
        configuredProductId = settings?.productId, configuredDeviceName = settings?.deviceName,
        refreshSeconds = settings?.refreshSeconds ?: 10, storedRecordCount = recordCount,
        synchronizationSuspended = replayActive || value.isDesignPreview || !foreground)

    private fun publish(value: GreenhouseTwinState) {
        val request = controlRequest.takeIf { value.stateSource == StateSource.LIVE }
        fun actuator(key: ActuatorKey, reported: ActuatorState): ActuatorState =
            if (request?.target == key) reported.copy(requestStatus = request.status,
                requestedState = request.requestedState, commandId = request.commandId) else reported
        val sharedBlocked = when {
            replayActive -> "历史回放禁止真实控制"
            value.stateSource != StateSource.LIVE -> "设计预览或回放禁止真实控制"
            !controlLoaded -> "正在读取控制记录"
            controlStorageError != null -> controlStorageError
            sceneExecution?.isRunning == true -> "手动场景正在逐项执行，请先停止后续步骤"
            !desiredChecked -> "正在核对云端待处理控制包"
            foreignDesired -> "云端已有另一控制包，禁止覆盖"
            request?.unresolved == true -> "本设备尚有一包待核实请求，请先查询原结果"
            desiredOccupied -> "设备正在处理或清理已有控制包，请重新查询"
            else -> null
        }
        val blocked = sharedBlocked ?: if (!value.canPresentCurrentActivity)
            "需要前台、在线、查询成功且新鲜的设备反馈" else null
        val offlineBlocked = sharedBlocked ?: purposeContextBlockedReason(value, ControlPurpose.OFFLINE_RESERVATION, retry = false)
        val resendBlocked = when {
            replayActive || value.stateSource != StateSource.LIVE -> "设计预览或回放禁止重发真实控制"
            !controlLoaded -> "正在读取控制记录"
            controlStorageError != null -> controlStorageError
            request == null || !request.unresolved -> "没有待核实的原请求"
            request.status == RequestStatus.SENDING -> "原请求正在提交"
            !desiredChecked -> "正在核对云端待处理控制包"
            foreignDesired -> "云端已有另一控制包，禁止覆盖"
            value.snapshotIdentity?.bootId != request.targetBootId -> "原目标启动尚未核实或已结束，不能改绑重发"
            value.snapshotIdentity?.configVersion != request.configVersion -> "设备配置版本已改变，不能重发原请求"
            else -> purposeContextBlockedReason(value, request.purpose, retry = true)
        }
        val next = value.copy(controlRequest = request, controlBlockedReason = blocked,
            offlinePumpBlockedReason = offlineBlocked, resendBlockedReason = resendBlocked,
            sceneExecution = sceneExecution.takeIf { value.stateSource == StateSource.LIVE }, sceneBlockedReason = blocked,
            recentDeviceResults = if (value.stateSource == StateSource.LIVE) recentDeviceResults else emptyList(),
            pump = actuator(ActuatorKey.PUMP, value.pump), lamp = actuator(ActuatorKey.LAMP, value.lamp),
            fan = actuator(ActuatorKey.FAN, value.fan))
        mutableState.value = next
        diagnostics.state(next)
    }
}
