package cn.greenhouse.twin.domain

import cn.greenhouse.twin.domain.history.HistoryMetric

enum class StateSource { LIVE, DESIGN_DEMO, REPLAY }

enum class ObservationSource { SENSOR, EXPERIMENT, SIMULATION }

enum class ObservationValidity { VALID, NOT_READY, READ_ERROR }

enum class ReportedState { UNKNOWN, OFF, ON }

enum class ActuatorMode { MANUAL }

enum class RequestStatus { IDLE, SENDING, WAITING, EXECUTED, REJECTED, TIMED_OUT, UNCERTAIN, INTERRUPTED }

enum class DeviceConnection { UNKNOWN, ONLINE, OFFLINE }

enum class CloudQueryStatus { UNCONFIGURED, IDLE, QUERYING, SUCCESS, FAILED }

enum class DataFreshness { UNKNOWN, FRESH, DELAYED, STALE }

enum class SynchronizationStatus { INITIAL_SYNC, READY }

enum class AlarmSummaryStatus { NOT_CONNECTED, NONE, WARNING }

enum class AlarmCategory { TEMPERATURE, WATER, EQUIPMENT }

enum class DemoScenario {
    INITIAL_SYNC, NORMAL, SOIL_WARNING, MISSING, DELAYED, STALE, CLOUD_ERROR, OFFLINE,
}

data class Observation<T : Number>(
    val value: T? = null,
    val source: ObservationSource,
    val validity: ObservationValidity = ObservationValidity.NOT_READY,
    val sampledAtUtcMs: Long? = null,
    val sampledUptimeMs: Long? = null,
)

// 请求进度与设备反馈独立，点击或请求成功不能修改 reported。
data class ActuatorState(
    val reported: ReportedState = ReportedState.UNKNOWN,
    val mode: ActuatorMode = ActuatorMode.MANUAL,
    val requestStatus: RequestStatus = RequestStatus.IDLE,
    val requestedState: Boolean? = null,
    val commandId: String? = null,
)

data class SnapshotIdentity(
    val deviceId: String,
    val bootId: String,
    val seq: Long,
    val stateVersion: Long,
    val configVersion: Int,
    val modelVersion: Int,
)

data class AlarmSummary(
    val status: AlarmSummaryStatus = AlarmSummaryStatus.NOT_CONNECTED,
    val title: String = "报警功能待接入",
    val detail: String = "当前尚未启用实时报警规则",
    // 指标警告来自已核实的当前规则，连接类报警不冒充环境越界。
    val metricWarnings: Map<HistoryMetric, String> = emptyMap(),
    val affectedCategories: Set<AlarmCategory> = emptySet(),
    val allAcknowledged: Boolean = false,
)

data class GreenhouseTwinState(
    val stateSource: StateSource = StateSource.LIVE,
    val temperature: Observation<Double> = Observation(source = ObservationSource.SENSOR),
    val humidity: Observation<Double> = Observation(source = ObservationSource.SENSOR),
    val lightLevel: Observation<Int> = Observation(source = ObservationSource.EXPERIMENT),
    val soilHumidity: Observation<Double> = Observation(source = ObservationSource.SIMULATION),
    val pump: ActuatorState = ActuatorState(),
    val lamp: ActuatorState = ActuatorState(),
    val fan: ActuatorState = ActuatorState(),
    val deviceConnection: DeviceConnection = DeviceConnection.UNKNOWN,
    val cloudQueryStatus: CloudQueryStatus = CloudQueryStatus.UNCONFIGURED,
    val freshness: DataFreshness = DataFreshness.UNKNOWN,
    val synchronizationStatus: SynchronizationStatus = SynchronizationStatus.INITIAL_SYNC,
    val snapshotIdentity: SnapshotIdentity? = null,
    val snapshotUtcMs: Long? = null,
    val snapshotUptimeMs: Long? = null,
    val lastSyncedAtUtcMs: Long? = null,
    val lastQueryAtUtcMs: Long? = null,
    val queryErrorMessage: String? = null,
    val alarmSummary: AlarmSummary = AlarmSummary(),
    val selectedDemoScenario: DemoScenario? = null,
    val configuredProductId: String? = null,
    val configuredDeviceName: String? = null,
    val refreshSeconds: Int = 10,
    val storedRecordCount: Int = 0,
    val synchronizationSuspended: Boolean = true,
    val controlRequest: ControlRequestState? = null,
    val recentDeviceResults: List<DeviceCommandFeedback> = emptyList(),
    val controlBlockedReason: String? = "正在读取控制配置",
    val offlinePumpBlockedReason: String? = "正在读取控制配置",
    val resendBlockedReason: String? = "正在读取控制配置",
    val sceneExecution: SceneExecutionState? = null,
    val sceneBlockedReason: String? = "正在读取控制配置",
) {
    val isDesignPreview: Boolean
        get() = stateSource == StateSource.DESIGN_DEMO

    // 陈旧、离线及首次未知时停活动表现，保留最后已知反馈及其文字语义。
    val canPresentCurrentActivity: Boolean
        get() = synchronizationStatus == SynchronizationStatus.READY &&
            (isDesignPreview || !synchronizationSuspended) &&
            deviceConnection == DeviceConnection.ONLINE &&
            freshness == DataFreshness.FRESH &&
            cloudQueryStatus == CloudQueryStatus.SUCCESS

    val canSendControl: Boolean
        get() = stateSource == StateSource.LIVE && canPresentCurrentActivity &&
            snapshotIdentity != null && snapshotUtcMs != null && controlBlockedReason == null

    // 离线预约只保存明确的目标意图，不能点亮实时活动或放宽普通手动控制。
    val canReserveOfflinePump: Boolean
        get() = stateSource == StateSource.LIVE && !synchronizationSuspended &&
            deviceConnection == DeviceConnection.OFFLINE && cloudQueryStatus == CloudQueryStatus.SUCCESS &&
            snapshotIdentity != null && offlinePumpBlockedReason == null

    val canResendControl: Boolean
        get() = controlRequest?.unresolved == true && resendBlockedReason == null

    val canStartScene: Boolean
        get() = stateSource == StateSource.LIVE && canPresentCurrentActivity && snapshotIdentity != null &&
            snapshotUtcMs != null && sceneBlockedReason == null

    // 回放中的活动只表示有效历史片段，不借用实时在线或云端成功标识。
    val canPresentModelActivity: Boolean
        get() = if (stateSource == StateSource.REPLAY)
            synchronizationStatus == SynchronizationStatus.READY && freshness == DataFreshness.FRESH &&
                !synchronizationSuspended else canPresentCurrentActivity
}
